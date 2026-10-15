/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager.payment;

import alfio.manager.NotificationManager;
import alfio.manager.support.PaymentResult;
import alfio.manager.support.PaymentWebhookResult;
import alfio.manager.system.ConfigurationLevel;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.system.ConfigurationManager.MaybeConfiguration;
import alfio.model.PaymentInformation;
import alfio.model.PurchaseContext;
import alfio.model.TicketReservation;
import alfio.model.TicketReservation.TicketReservationStatus;
import alfio.model.TicketReservationStatusAndValidation;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.*;
import alfio.model.transaction.banchile.BanchileWebhookPayload;
import alfio.model.transaction.banchile.CreateSessionRequest;
import alfio.model.transaction.banchile.CreateSessionRequest.Amount;
import alfio.model.transaction.banchile.CreateSessionRequest.Auth;
import alfio.model.transaction.banchile.CreateSessionRequest.Buyer;
import alfio.model.transaction.banchile.CreateSessionRequest.Payment;
import alfio.model.transaction.banchile.SessionResponse;
import alfio.model.transaction.capabilities.PaymentInfo;
import alfio.model.transaction.capabilities.RefundRequest;
import alfio.model.transaction.capabilities.WebhookHandler;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.util.ClockProvider;
import alfio.util.Json;
import alfio.util.MonetaryUtil;
import alfio.util.RenderedTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;

import static alfio.manager.payment.PaymentManagerUtils.invalidateExistingTransactions;
import static alfio.model.TicketReservation.TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT;
import static alfio.model.TicketReservation.TicketReservationStatus.STUCK;
import static alfio.model.TicketReservation.TicketReservationStatus.WAITING_EXTERNAL_CONFIRMATION;
import static alfio.model.system.ConfigurationKeys.*;

/**
 * Implementación de PaymentProvider para Banchile Pagos (PlacetoPay gateway).
 *
 * <p>Flujo de pago:
 * <ol>
 *   <li>initTransaction: crea una CheckoutSession en Banchile, persiste la transacción y
 *       redirige al usuario a la URL de pago.</li>
 *   <li>processWebhook: recibe notificación de Banchile, valida firma, consulta estado
 *       remoto y actualiza el estado de la reserva en Alfio.</li>
 * </ol>
 *
 * <p>Mapeo de estados Banchile → Alfio:
 * <ul>
 *   <li>PENDING → Transaction.Status.PENDING (sin acción)</li>
 *   <li>APPROVED → Transaction.Status.COMPLETE (confirma reserva)</li>
 *   <li>REJECTED → Transaction.Status.FAILED</li>
 *   <li>APPROVED_PARTIAL → Transaction.Status.PENDING (no soportado en MVP)</li>
 *   <li>PARTIAL_EXPIRED → Transaction.Status.FAILED</li>
 * </ul>
 */
@Component
@AllArgsConstructor
public class BanchilePagosWebhookManager implements PaymentProvider, WebhookHandler, RefundRequest, PaymentInfo {

    private static final Logger log = LoggerFactory.getLogger(BanchilePagosWebhookManager.class);

    public static final String WEBHOOK_URL_TEMPLATE = "/api/payment/webhook/banchile";

    protected static final Set<ConfigurationKeys> ALL_OPTIONS = EnumSet.of(
        BANCHILE_ENABLED,
        BANCHILE_LOGIN,
        BANCHILE_TRANKEY,
        BANCHILE_BASE_URL
    );

    /**
     * Estados en que la reserva sigue esperando el resultado del pago en Banchile. Solo en estos
     * estados {@link #forceTransactionCheck} aplica el resultado a la reserva.
     */
    private static final Set<TicketReservationStatus> RESERVATION_AWAITING_PAYMENT =
        EnumSet.of(EXTERNAL_PROCESSING_PAYMENT, WAITING_EXTERNAL_CONFIRMATION, STUCK);

    /**
     * Reserva confirmada en alf.io, o con la confirmación en curso: FINALIZING y OFFLINE_FINALIZING
     * son etapas intermedias que el finalizador lleva a COMPLETE (o reintenta si falla). En las
     * alertas cuentan igual que COMPLETE.
     */
    private static final Set<TicketReservationStatus> RESERVATION_CONFIRMED = EnumSet.of(
        TicketReservationStatus.COMPLETE, TicketReservationStatus.FINALIZING, TicketReservationStatus.OFFLINE_FINALIZING);

    /**
     * Reservas que la limpieza automática de alf.io borra, junto con todas sus transacciones, cuando
     * vence su validez: las PENDING ({@code TicketReservationManager#cleanupExpiredReservations}) y las
     * OFFLINE_PAYMENT, si la anulación automática del evento está activa
     * ({@code TicketReservationManager#cleanupExpiredOfflineReservations}).
     */
    private static final Set<TicketReservationStatus> RESERVATION_REMOVED_BY_CLEANUP = EnumSet.of(
        TicketReservationStatus.PENDING, TicketReservationStatus.OFFLINE_PAYMENT);

    private static final String REFUNDED = "REFUNDED";

    private static final String APPROVED_PAYMENT_ALERT = "pago aprobado en Banchile para una reserva que no está activa: "
        + "verificar y reembolsar desde el portal de Banchile si corresponde";

    /**
     * Reserva no confirmada con el pago que Banchile informa como reembolsado: ya se devolvió, así
     * que no hay nada que reembolsar. La detección del reembolso no está verificada contra una
     * respuesta real de Banchile (ver {@link #isRefunded}): por eso se pide confirmarlo en el portal.
     */
    private static final String REFUNDED_PAYMENT_ALERT = "pago reembolsado en Banchile para una reserva que no está confirmada "
        + "en alf.io: alf.io no aplicó el pago a la reserva y Banchile informa que ya se devolvió, así que no hay que "
        + "reembolsarlo. Verificar en el portal de Banchile que la devolución esté hecha";

    /**
     * Reserva confirmada en alf.io con el pago rechazado en Banchile: el incidente BDD21987 (29-ago),
     * una reserva confirmada a mano sin pago por la que se emitió la boleta SII 16104.
     */
    private static final String REJECTED_PAYMENT_ALERT = "pago rechazado en Banchile para una reserva confirmada en alf.io: "
        + "puede haber entradas y boleta emitidas sin pago. Revisar la reserva y, si corresponde, anular las entradas "
        + "y emitir la nota de crédito";

    /** Reserva confirmada en alf.io con el pago que Banchile informa como reembolsado. */
    private static final String REFUNDED_ON_CONFIRMED_RESERVATION_ALERT = "pago reembolsado en Banchile para una reserva "
        + "confirmada en alf.io: la reserva sigue confirmada aunque el pago se devolvió, así que puede haber entradas y "
        + "boleta emitidas sin pago. Revisar la reserva y, si corresponde, anular las entradas y emitir la nota de crédito";

    /**
     * Una alerta sobre una reserva confirmada puede venir de una sola sesión: el comprador pudo haber
     * pagado en otra (apretó "Pagar" dos veces) o por otro medio, y anular sus entradas sería un error.
     */
    private static final String VERIFY_BEFORE_ANNULLING = "Importante: verificar que no se haya pagado en otra sesión o por "
        + "otro medio antes de anular.";

    /**
     * Sesión que alf.io dejó INVALID al crear otra para la misma reserva (el comprador apretó "Pagar"
     * de nuevo) y que se pagó igual: Banchile no la cancela y se puede pagar hasta que vence.
     */
    private static final String INVALIDATED_SESSION_ALERT = "pago aprobado en una sesión Banchile invalidada: posible doble "
        + "cobro; verificar y reembolsar desde el portal si corresponde";

    private static final String CLEANUP_NOTE = " Si la reserva estaba vencida, alf.io puede eliminarla, junto con su "
        + "transacción, en su limpieza automática.";

    /**
     * Clave de la metadata con que se marca una sesión invalidada ya revisada en Banchile, para no
     * volver a consultarla ni a alertar: {@code alerted} (pago aprobado o reembolsado) o
     * {@code rejected}.
     */
    public static final String INVALIDATED_SESSION_REVIEW = "banchile_invalidated_session_review";

    /**
     * Cuánto tiempo después de su creación se sigue revisando una sesión invalidada: una sesión de
     * Banchile se puede pagar durante 30 minutos ({@link #doPayment} fija ese vencimiento) y el cron
     * corre cada 12. Con 90 minutos se ve un pago hecho en el último minuto de la sesión aunque se
     * pierdan varios runs del cron (un reinicio, un run atrasado).
     */
    public static final Duration INVALIDATED_SESSION_WINDOW = Duration.ofMinutes(90);

    private static final String NOT_REPORTED = "no informado";

    /**
     * Duración de una sesión de Banchile: el comprador puede pagar hasta 30 minutos después de apretar
     * "Pagar". De ella sale, una sola vez, el vencimiento que se manda a Banchile y la nueva validez de
     * la reserva.
     */
    static final Duration SESSION_DURATION = Duration.ofMinutes(30);

    /**
     * Cuánto más que la sesión queda vigente la reserva: un ciclo completo del cron de conciliación
     * ({@code BanchileReconciliationJob}, cada 12 minutos) más 3 de margen. Cuando Banchile informa la
     * sesión vencida o rechazada la reserva sigue vigente, y el flujo de pago fallido de alf.io la
     * devuelve a PENDING para que el comprador reintente (con al menos
     * RESERVATION_MIN_TIMEOUT_AFTER_FAILED_PAYMENT minutos, 10 por defecto) en vez de anularla.
     */
    static final Duration RESERVATION_VALIDITY_AFTER_SESSION = Duration.ofMinutes(15);

    /** Respuesta de Banchile a una request inválida ("request_not_valid"): no crea la sesión. */
    private static final int HTTP_BAD_REQUEST = 400;

    /** IP que se manda a Banchile cuando no hay una IP válida del comprador (el valor de siempre). */
    static final String UNKNOWN_IP_ADDRESS = "0.0.0.0";

    /** User-agent que se manda a Banchile cuando no hay uno del comprador (el valor de siempre). */
    static final String DEFAULT_USER_AGENT = "alfio/banchile";

    // Largos máximos de createSession según la referencia de la API de PlacetoPay, el motor de Banchile
    // (docs.placetopay.dev/checkout/api/reference/session).
    /** Person.name y Person.surname: 60. */
    private static final int MAX_BUYER_NAME_LENGTH = 60;
    /** Person.email: 80. Un correo más largo no se corta: sería otro correo. */
    private static final int MAX_BUYER_EMAIL_LENGTH = 80;
    /** userAgent: 255. */
    private static final int MAX_USER_AGENT_LENGTH = 255;
    /** payment.description: el esquema dice 255 y el texto "hasta 250 caracteres"; se usa el menor. */
    private static final int MAX_DESCRIPTION_LENGTH = 250;
    /** ipAddress admite 46; el texto de una IPv6 ocupa como máximo 45. */
    private static final int MAX_IP_ADDRESS_LENGTH = 45;

    /**
     * Nombre o apellido del comprador: letras (con tildes, ñ, diéresis), espacios, punto, guion y
     * apóstrofo. Con cualquier otro carácter no se manda el comprador, para que Banchile no rechace
     * la sesión.
     */
    private static final Pattern BUYER_NAME = Pattern.compile("^\\p{L}[\\p{L}\\p{M} .'’-]*$");

    /**
     * Correo del comprador, con un formato conservador: ASCII, sin comillas, puntos seguidos ni en los
     * extremos de la parte local, y un dominio con etiquetas válidas y un TLD alfabético.
     */
    private static final Pattern BUYER_EMAIL = Pattern.compile(
        "^[A-Za-z0-9_%+-]+(?:\\.[A-Za-z0-9_%+-]+)*@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");

    /** IPv4 en notación decimal con puntos, sin ceros a la izquierda. */
    private static final Pattern IPV4 = Pattern.compile(
        "^(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    /** Caracteres de un literal IPv6 (sin zona: el "%eth0" de una dirección de enlace no va). */
    private static final Pattern IPV6_CHARACTERS = Pattern.compile("^[0-9A-Fa-f:.]+$");

    /** Caracteres de formato (invisibles, como el espacio de ancho cero): se eliminan. */
    private static final Pattern FORMAT_CHARACTERS = Pattern.compile("\\p{Cf}");

    /** Caracteres de control y separadores seguidos: se convierten en un espacio. */
    private static final Pattern CONTROL_OR_SEPARATOR_RUNS = Pattern.compile("[\\p{Cc}\\p{Z}]+");

    /**
     * Caracteres que no van en la descripción: Banchile admite alfanuméricos, acentuados y símbolos
     * comunes, así que se descarta todo lo que no sea letra, dígito, espacio o uno de estos símbolos.
     */
    private static final Pattern DESCRIPTION_DISCARDED_CHARACTERS = Pattern.compile("[^\\p{L}\\p{M}0-9 .,;:!?¡¿'\"()&/#%+*=@$_-]");

    private final ConfigurationManager configurationManager;
    private final BanchilePagosClient banchilePagosClient;
    private final TransactionRepository transactionRepository;
    private final TicketReservationRepository ticketReservationRepository;
    private final ClockProvider clockProvider;
    private final NotificationManager notificationManager;
    private final OrganizationRepository organizationRepository;

    // -------------------------------------------------------------------------
    // PaymentProvider — metadata del proxy
    // -------------------------------------------------------------------------

    @Override
    public PaymentProxy getPaymentProxy() {
        return PaymentProxy.BANCHILE;
    }

    @Override
    public Set<PaymentMethod> getSupportedPaymentMethods(PaymentContext paymentContext,
                                                         TransactionRequest transactionRequest) {
        if (checkIfActive(getConfiguration(paymentContext.getConfigurationLevel()))) {
            return EnumSet.of(PaymentMethod.CREDIT_CARD);
        }
        return Set.of();
    }

    @Override
    public boolean accept(PaymentMethod paymentMethod, PaymentContext context, TransactionRequest transactionRequest) {
        if (paymentMethod != PaymentMethod.CREDIT_CARD) {
            return false;
        }
        return checkIfActive(getConfiguration(context.getConfigurationLevel()));
    }

    @Override
    public boolean accept(Transaction transaction) {
        return PaymentProxy.BANCHILE == transaction.getPaymentProxy();
    }

    @Override
    public PaymentMethod getPaymentMethodForTransaction(Transaction transaction) {
        return PaymentMethod.CREDIT_CARD;
    }

    @Override
    public boolean isActive(PaymentContext paymentContext) {
        return checkIfActive(getConfiguration(paymentContext.getConfigurationLevel()));
    }

    // -------------------------------------------------------------------------
    // PaymentProvider — flujo de pago
    // -------------------------------------------------------------------------

    @Override
    public PaymentResult getToken(PaymentSpecification spec) {
        return doPayment(spec);
    }

    /**
     * Crea una sesión de pago en Banchile y redirige al usuario al checkout.
     *
     * <p>Persiste una transacción con status PENDING cuyo paymentId = requestId (int)
     * devuelto por Banchile.
     *
     * <p>La sesión lleva el comprador de la reserva (Banchile lo precarga en su checkout), la IP y el
     * navegador del comprador, {@code skipResult} (al terminar, Banchile vuelve directo al
     * {@code returnUrl}) y la descripción {@code <evento> - reserva <ID corto>}. doPayment corre en el
     * hilo del request que confirma la reserva ({@code ReservationApiV2Controller#confirmOverview} →
     * {@code TicketReservationManager#performPayment}), así que la IP y el navegador salen de ese
     * request. Ningún dato del comprador hace fallar la sesión: si falta o no es válido, va lo de
     * antes (sin comprador, IP {@value #UNKNOWN_IP_ADDRESS}, user-agent {@value #DEFAULT_USER_AGENT}),
     * y si Banchile igual rechaza la request por inválida (HTTP 400), se reintenta una vez como se
     * mandaba antes: sin esos datos, sin {@code skipResult} y con la descripción
     * {@code Reserva <referencia>}.
     *
     * <p>Con la sesión creada, la reserva queda vigente hasta {@link #RESERVATION_VALIDITY_AFTER_SESSION}
     * después del vencimiento de la sesión (ver {@link #extendValidity}).
     *
     * @param spec Especificación del pago
     * @return PaymentResult.redirect(processUrl) si tuvo éxito, PaymentResult.failed si no
     */
    @Override
    public PaymentResult doPayment(PaymentSpecification spec) {
        try {
            var purchaseContext = spec.getPurchaseContext();
            var configuration = getConfiguration(purchaseContext.getConfigurationLevel());
            var reservationId = spec.getReservationId();
            var reservation = ticketReservationRepository.findReservationById(reservationId);

            String login    = configuration.get(BANCHILE_LOGIN).getRequiredValue();
            String tranKey  = configuration.get(BANCHILE_TRANKEY).getRequiredValue();
            String baseUrl  = configuration.get(BANCHILE_BASE_URL).getRequiredValue();

            // Construir el cuerpo de la request
            Auth auth = BanchilePagosClient.buildAuth(login, tranKey);
            String reference = merchantReference(reservationId);
            String description = paymentDescription(purchaseContext, reservation, reference);
            String currency = purchaseContext.getCurrency();
            long totalCents = spec.getPriceWithVAT();
            // Banchile espera el total en unidades enteras (CLP no usa decimales)
            Amount amount = new Amount(currency, totalCents);
            Payment payment = new Payment(reference, description, amount);

            // URL de retorno: la misma "book" que usa Mollie como patrón
            String alfioBaseUrl = configurationManager.getFor(BASE_URL, purchaseContext.getConfigurationLevel()).getRequiredValue();
            String reservationBaseUrl = alfioBaseUrl + "/" + purchaseContext.getType().getUrlComponent()
                + "/" + purchaseContext.getPublicIdentifier()
                + "/reservation/" + reservationId;
            String returnUrl = reservationBaseUrl + "/book";
            // cancelUrl: vuelve al overview cuando el usuario cancela en Banchile
            String cancelUrl = reservationBaseUrl + "/overview";
            // notificationUrl: webhook S2S fijo (sin reservationId en el path). El controller
            // extrae el reservation del campo `reference` del body. Banchile WebCheckout
            // descarta este campo y usa la URL registrada en el panel de comercios, así que
            // este valor se envía por completitud pero Banchile no lo respeta.
            String notificationUrl = alfioBaseUrl + WEBHOOK_URL_TEMPLATE;

            // Vencimiento de la sesión: el mismo para Banchile y para la nueva validez de la reserva
            ZonedDateTime sessionExpiration = purchaseContext.now(clockProvider).plus(SESSION_DURATION);
            String expiration = sessionExpiration.toInstant().toString();

            Buyer buyer = buyerOf(reservation);
            HttpServletRequest buyerRequest = currentRequest();
            String ipAddress = clientIpAddress(buyerRequest);
            String userAgent = clientUserAgent(buyerRequest);

            CreateSessionRequest req = new CreateSessionRequest(
                auth,
                "es_CL",
                buyer,
                payment,
                returnUrl,
                cancelUrl,
                notificationUrl,
                ipAddress,
                userAgent,
                expiration,
                Boolean.TRUE // skipResult: al terminar, Banchile vuelve directo al returnUrl
            );

            SessionResponse sessionResponse;
            try {
                sessionResponse = banchilePagosClient.createSession(req, baseUrl);
            } catch (BanchilePagosClient.BanchilePagosException e) {
                if (e.getHttpStatus() != HTTP_BAD_REQUEST) {
                    throw e;
                }
                // un dato del comprador que Banchile no acepte no puede impedir el pago: la sesión de siempre.
                // Un 400 no crea la sesión, así que el reintento no la duplica
                log.warn("Banchile rechazó la sesión de la reserva {} (HTTP 400): se reintenta sin comprador, IP, "
                    + "user-agent ni skipResult y con la descripción de antes", reservationId);
                req = new CreateSessionRequest(BanchilePagosClient.buildAuth(login, tranKey), req.locale(), null,
                    new Payment(reference, "Reserva " + reference, amount), returnUrl, cancelUrl, notificationUrl,
                    UNKNOWN_IP_ADDRESS, DEFAULT_USER_AGENT, expiration);
                sessionResponse = banchilePagosClient.createSession(req, baseUrl);
            }

            if (sessionResponse == null || sessionResponse.requestId() == null || sessionResponse.processUrl() == null) {
                log.warn("createSession devolvió respuesta incompleta para reserva {}", reservationId);
                return PaymentResult.failed("banchile_session_creation_failed");
            }

            String requestIdStr = String.valueOf(sessionResponse.requestId());
            String processUrl = sessionResponse.processUrl();

            // Actualizar estado y validez de la reserva
            ticketReservationRepository.updateReservationStatus(reservationId, EXTERNAL_PROCESSING_PAYMENT.toString());
            Date validity = extendValidity(reservationId, reservation, sessionExpiration);

            // Invalidar transacciones previas e insertar la nueva
            invalidateExistingTransactions(reservationId, transactionRepository);
            transactionRepository.insert(
                requestIdStr,                           // gtw_tx_id
                requestIdStr,                           // gtw_payment_id
                reservationId,
                purchaseContext.now(clockProvider),
                spec.getPriceWithVAT(),
                currency,
                "Banchile Pagos",
                PaymentProxy.BANCHILE.name(),
                0L, 0L,
                Transaction.Status.PENDING,
                Map.of()
            );

            // sin datos personales en el log: solo si se mandaron
            log.info("Sesión Banchile creada: requestId={} para reserva={}, vigente hasta {} (comprador={}, IP del comprador={}, "
                    + "user-agent del comprador={}, skipResult={})", requestIdStr, reservationId, validity.toInstant(), req.buyer() != null,
                !UNKNOWN_IP_ADDRESS.equals(req.ipAddress()), !DEFAULT_USER_AGENT.equals(req.userAgent()), req.skipResult() != null);
            return PaymentResult.redirect(processUrl);

        } catch (BanchilePagosClient.BanchilePagosException e) {
            log.warn("Error al crear sesión Banchile para reserva {}: HTTP {} — {}",
                spec.getReservationId(), e.getHttpStatus(), e.getMessage());
            return PaymentResult.failed("banchile_session_creation_failed");
        } catch (Exception e) {
            log.error("Excepción inesperada al crear sesión Banchile para reserva {}", spec.getReservationId(), e);
            return PaymentResult.failed("banchile_session_creation_failed");
        }
    }

    // -------------------------------------------------------------------------
    // WebhookHandler
    // -------------------------------------------------------------------------

    /**
     * Banchile firma webhooks con el mismo secretKey de la integración. La validación
     * real se hace dentro de processWebhook, así que retornamos el secretKey del
     * configurationLevel adecuado para que el framework lo tenga disponible.
     */
    @Override
    public String getWebhookSignatureKey(ConfigurationLevel configurationLevel) {
        return configurationManager.getFor(BANCHILE_TRANKEY, configurationLevel).getValue().orElse(null);
    }

    /**
     * Parsea el body JSON del webhook de Banchile en un {@link BanchileWebhookPayload}.
     *
     * <p>El {@code additionalInfo} debe contener la clave "reservationId" mapeada
     * desde la URL del webhook.
     */
    @Override
    public Optional<TransactionWebhookPayload> parseTransactionPayload(String body,
                                                                       String signature,
                                                                       Map<String, String> additionalInfo,
                                                                       PaymentContext paymentContext) {
        try {
            BanchileWebhookPayload payload = Json.GSON.fromJson(body, BanchileWebhookPayload.class);
            if (payload == null || payload.requestId() == null) {
                log.warn("Webhook Banchile con requestId nulo, body={}", body);
                return Optional.empty();
            }
            return Optional.of(new BanchileTransactionWebhookPayload(
                payload,
                additionalInfo.getOrDefault("reservationId", "")
            ));
        } catch (Exception e) {
            log.warn("No se pudo parsear el webhook de Banchile", e);
            return Optional.empty();
        }
    }

    /**
     * Procesa el webhook entrante de Banchile.
     *
     * <p>Flujo:
     * <ol>
     *   <li>Valida la firma SHA-256 contra el secretKey del comercio</li>
     *   <li>Verifica idempotencia (si ya está COMPLETE, no confirma de nuevo)</li>
     *   <li>Consulta el estado remoto vía querySession para evitar spoofing</li>
     *   <li>Mapea el estado Banchile → Transaction.Status y actualiza Alfio</li>
     * </ol>
     */
    @Override
    public PaymentWebhookResult processWebhook(TransactionWebhookPayload payload,
                                               Transaction transaction,
                                               PaymentContext paymentContext) {
        if (!(payload instanceof BanchileTransactionWebhookPayload banchilePayload)) {
            return PaymentWebhookResult.error("payload tipo incorrecto");
        }

        BanchileWebhookPayload whPayload = banchilePayload.getWebhookPayload();

        try {
            // --- Idempotencia first: si ya está COMPLETE, devolver NOT_RELEVANT sin validar firma ---
            if (transaction.getStatus() == Transaction.Status.COMPLETE) {
                log.info("Webhook Banchile recibido para transacción ya COMPLETE requestId={}, ignorando",
                    whPayload.requestId());
                return PaymentWebhookResult.notRelevant("already_complete");
            }

            // --- Obtener configuración (secretKey usado para auth y firma del webhook) ---
            var purchaseContext = paymentContext.getPurchaseContext();
            var configuration = getConfiguration(purchaseContext.getConfigurationLevel());
            String login     = configuration.get(BANCHILE_LOGIN).getRequiredValue();
            String secretKey = configuration.get(BANCHILE_TRANKEY).getRequiredValue();
            String baseUrl   = configuration.get(BANCHILE_BASE_URL).getRequiredValue();

            // --- Validación de firma (SHA-256(requestId + status.status + status.date + secretKey)) ---
            if (whPayload.signature() != null) {
                if (!validateWebhookSignature(whPayload, secretKey)) {
                    log.warn("Firma de webhook Banchile inválida para requestId={}", whPayload.requestId());
                    return PaymentWebhookResult.error("firma inválida");
                }
            } else {
                log.debug("Webhook Banchile sin signature — confiando solo en querySession para requestId={}",
                    whPayload.requestId());
            }

            // --- Consultar estado remoto para evitar spoofing ---
            Auth freshAuth = BanchilePagosClient.buildAuth(login, secretKey);
            SessionResponse remoteSession = banchilePagosClient.querySession(
                whPayload.requestId(), freshAuth, baseUrl
            );

            if (remoteSession == null || remoteSession.status() == null) {
                log.warn("querySession devolvió respuesta nula para requestId={}", whPayload.requestId());
                return PaymentWebhookResult.error("respuesta remota inválida");
            }

            String banchileStatus = remoteSession.status().status();
            Transaction.Status alfioStatus = mapStatus(banchileStatus);

            String requestIdStr = String.valueOf(whPayload.requestId());
            ZonedDateTime now = purchaseContext.now(clockProvider);

            switch (alfioStatus) {
                case COMPLETE -> {
                    // Verificar que la reserva sigue en estado procesable
                    var optionalReservation = ticketReservationRepository
                        .findOptionalStatusAndValidationById(transaction.getReservationId())
                        .filter(r -> r.getStatus() == EXTERNAL_PROCESSING_PAYMENT
                            || r.getStatus() == WAITING_EXTERNAL_CONFIRMATION);

                    if (optionalReservation.isEmpty()) {
                        log.warn("Reserva {} no encontrada o en estado no procesable para APPROVED de Banchile",
                            transaction.getReservationId());
                        return PaymentWebhookResult.error("reserva no procesable");
                    }

                    Map<String, String> metadata = buildBanchileMetadata(transaction, whPayload, remoteSession, banchileStatus);
                    transactionRepository.update(
                        transaction.getId(),
                        requestIdStr, requestIdStr,
                        now,
                        0L, 0L,
                        Transaction.Status.COMPLETE,
                        metadata
                    );

                    log.info("Pago Banchile APPROVED — reserva={} requestId={}",
                        transaction.getReservationId(), requestIdStr);
                    return PaymentWebhookResult.successful(new BanchilePaymentToken(requestIdStr));
                }

                case FAILED -> {
                    Map<String, String> metadata = buildBanchileMetadata(transaction, whPayload, remoteSession, banchileStatus);
                    transactionRepository.update(
                        transaction.getId(),
                        requestIdStr, requestIdStr,
                        now,
                        transaction.getPlatformFee(), transaction.getGatewayFee(),
                        Transaction.Status.FAILED,
                        metadata
                    );
                    log.info("Pago Banchile FAILED (status={}) — reserva={} requestId={}",
                        banchileStatus, transaction.getReservationId(), requestIdStr);
                    return PaymentWebhookResult.failed(banchileStatus);
                }

                case PENDING -> {
                    // PENDING puro o APPROVED_PARTIAL (no soportado en MVP)
                    if ("APPROVED_PARTIAL".equals(banchileStatus)) {
                        log.warn("Banchile APPROVED_PARTIAL no soportado en MVP — reserva={} requestId={}. " +
                                 "Tratando como PENDING hasta resolución manual.",
                            transaction.getReservationId(), requestIdStr);
                    } else {
                        log.debug("Banchile PENDING — reserva={} requestId={}", transaction.getReservationId(), requestIdStr);
                    }
                    return PaymentWebhookResult.pending();
                }

                default -> {
                    log.warn("Estado Banchile desconocido: {} para requestId={}", banchileStatus, requestIdStr);
                    return PaymentWebhookResult.notRelevant(banchileStatus);
                }
            }

        } catch (BanchilePagosClient.BanchilePagosException e) {
            log.warn("Error HTTP al consultar Banchile durante processWebhook requestId={}: {}",
                whPayload.requestId(), e.getMessage());
            return PaymentWebhookResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("Excepción procesando webhook Banchile requestId={}", whPayload.requestId(), e);
            return PaymentWebhookResult.error(e.getMessage());
        }
    }

    /**
     * Fuerza la re-verificación del estado del pago consultando directamente a Banchile.
     *
     * <p>Todo lo que consulta el estado de un pago Banchile pasa por acá, vía
     * {@code TicketReservationManager#forceTransactionCheck}: el cron de conciliación de 12
     * minutos, el polling del navegador, el botón "verificar pago" y los jobs de alf.io que
     * revisan pagos pendientes. Por eso la regla de qué hacer con la reserva vive en este método:
     * <ul>
     *   <li>Banchile sin estado final (PENDING, APPROVED_PARTIAL o desconocido): no cambia nada.</li>
     *   <li>Estado final y reserva esperando el pago (EXTERNAL_PROCESSING_PAYMENT,
     *       WAITING_EXTERNAL_CONFIRMATION o STUCK): persiste el estado en la transacción y devuelve
     *       SUCCESSFUL o FAILED, para que alf.io complete la reserva o corra el flujo de pago fallido.
     *       Antes de devolver FAILED revisa en Banchile las sesiones invalidadas de la reserva, que
     *       ese flujo puede borrar con la reserva (ver
     *       {@link #reviewInvalidatedSessionsBeforeTheFailedPaymentFlow}).</li>
     *   <li>Estado final y reserva que no espera el pago (CANCELLED, COMPLETE, PENDING u otro), o
     *       pago aprobado que Banchile informa como reembolsado: no toca la reserva. Cierra la
     *       transacción (APPROVED → COMPLETE, REJECTED → FAILED) para que deje de consultarse y
     *       manda una alerta a la organización del evento si el pago vino aprobado y la reserva no
     *       está confirmada (o el pago fue reembolsado), o si el pago vino rechazado y la reserva está
     *       confirmada (se confirmó sin cobro). Confirmada es COMPLETE o una confirmación en curso
     *       (FINALIZING, OFFLINE_FINALIZING). Devuelve NOT_RELEVANT.</li>
     * </ul>
     *
     * <p>En estados finales persiste el metadata banchile_* con todos los detalles de la
     * transacción (auth, internalReference, etc.) — mismo comportamiento que processWebhook.
     *
     * @param reservation    reserva de la transacción; su estado se relee de la BD porque el
     *                       objeto recibido puede estar desactualizado
     * @param transaction    transacción BANCHILE en PENDING, ya bloqueada por el llamador
     * @param paymentContext contexto de compra de la reserva
     * @return SUCCESSFUL o FAILED si el resultado se aplica a la reserva, NOT_RELEVANT si no hay
     *         que tocarla, ERROR si no se pudo obtener el estado de Banchile
     */
    @Override
    public PaymentWebhookResult forceTransactionCheck(TicketReservation reservation,
                                                      Transaction transaction,
                                                      PaymentContext paymentContext) {
        try {
            var purchaseContext = paymentContext.getPurchaseContext();
            var configuration = getConfiguration(purchaseContext.getConfigurationLevel());
            String login   = configuration.get(BANCHILE_LOGIN).getRequiredValue();
            String tranKey = configuration.get(BANCHILE_TRANKEY).getRequiredValue();
            String baseUrl = configuration.get(BANCHILE_BASE_URL).getRequiredValue();

            int requestId = Integer.parseInt(transaction.getPaymentId());
            Auth freshAuth = BanchilePagosClient.buildAuth(login, tranKey);
            SessionResponse remoteSession = banchilePagosClient.querySession(requestId, freshAuth, baseUrl);

            if (remoteSession == null || remoteSession.status() == null) {
                return PaymentWebhookResult.error("respuesta remota inválida");
            }

            String banchileStatus = remoteSession.status().status();
            boolean refunded = isRefunded(remoteSession);
            // un pago reembolsado fue aprobado antes: la transacción se cierra como COMPLETE, igual
            // que en alf.io, donde un reembolso no cambia el estado de la transacción
            Transaction.Status alfioStatus = refunded ? Transaction.Status.COMPLETE : mapStatus(banchileStatus);
            if (alfioStatus != Transaction.Status.COMPLETE && alfioStatus != Transaction.Status.FAILED) {
                // sin estado final: se preserva la transacción tal cual hasta la próxima consulta
                return PaymentWebhookResult.notRelevant(banchileStatus);
            }

            String reservationId = transaction.getReservationId();
            String requestIdStr = String.valueOf(requestId);
            TicketReservationStatus reservationStatus = ticketReservationRepository.findOptionalStatusAndValidationById(reservationId)
                .map(TicketReservationStatusAndValidation::getStatus)
                .orElse(null);
            boolean awaitingPayment = !refunded && RESERVATION_AWAITING_PAYMENT.contains(reservationStatus);
            boolean confirmed = RESERVATION_CONFIRMED.contains(reservationStatus);

            if (awaitingPayment && alfioStatus == Transaction.Status.FAILED) {
                // el flujo de pago fallido puede borrar la reserva con todas sus transacciones: antes se
                // revisan sus sesiones invalidadas. Va antes de cerrar la transacción, como las alertas:
                // si Banchile no responde por alguna, la transacción sigue PENDING y el rechazo se
                // vuelve a procesar en la próxima consulta
                reviewInvalidatedSessionsBeforeTheFailedPaymentFlow(transaction, banchileStatus, purchaseContext);
            }

            // las alertas van antes de cerrar la transacción: si el correo falla, la transacción
            // sigue PENDING y la alerta se reintenta en la próxima consulta
            if (!awaitingPayment && alfioStatus == Transaction.Status.COMPLETE && (refunded || !confirmed)) {
                sendApprovedPaymentAlert(purchaseContext, transaction, remoteSession, banchileStatus, reservationStatus, refunded);
            } else if (alfioStatus == Transaction.Status.FAILED && confirmed) {
                // REJECTED (o PARTIAL_EXPIRED, que mapStatus trata igual) con la reserva confirmada:
                // se confirmó sin que Banchile cobrara
                sendRejectedPaymentAlert(purchaseContext, transaction, remoteSession, banchileStatus, reservationStatus);
            }
            closeTransaction(transaction, requestId, remoteSession, banchileStatus, alfioStatus, purchaseContext);

            if (awaitingPayment) {
                log.info("forceTransactionCheck Banchile {} — reserva={} requestId={}",
                    alfioStatus, reservationId, requestIdStr);
                return alfioStatus == Transaction.Status.COMPLETE
                    ? PaymentWebhookResult.successful(new BanchilePaymentToken(transaction.getPaymentId()))
                    : PaymentWebhookResult.failed(banchileStatus);
            }

            log.warn("forceTransactionCheck Banchile {} (reembolsado={}) para la reserva {} en estado {}: "
                    + "se cierra la transacción como {} sin modificar la reserva — requestId={}",
                banchileStatus, refunded, reservationId, reservationStatus, alfioStatus, requestIdStr);
            return PaymentWebhookResult.notRelevant(refunded ? "refunded" : "reservation_not_awaiting_payment");
        } catch (Exception e) {
            log.warn("Error en forceTransactionCheck para reserva {}", reservation.getId(), e);
            return PaymentWebhookResult.error(e.getMessage());
        }
    }

    /**
     * Persiste en la transacción el estado final informado por Banchile, con el metadata banchile_*.
     *
     * @param transaction     transacción a cerrar
     * @param requestId       requestId de la sesión Banchile
     * @param remoteSession   respuesta de querySession (ground truth remoto)
     * @param banchileStatus  estado de la sesión en Banchile
     * @param alfioStatus     estado final para alf.io (COMPLETE o FAILED)
     * @param purchaseContext contexto de compra, para la fecha en su zona horaria
     */
    private void closeTransaction(Transaction transaction,
                                  int requestId,
                                  SessionResponse remoteSession,
                                  String banchileStatus,
                                  Transaction.Status alfioStatus,
                                  PurchaseContext purchaseContext) {
        Map<String, String> metadata = buildBanchileMetadata(transaction, syntheticPayload(requestId, remoteSession),
            remoteSession, banchileStatus);
        String requestIdStr = String.valueOf(requestId);
        transactionRepository.update(
            transaction.getId(),
            requestIdStr, requestIdStr,
            purchaseContext.now(clockProvider),
            transaction.getPlatformFee(), transaction.getGatewayFee(),
            alfioStatus,
            metadata
        );
    }

    /**
     * Resultado de {@link #reviewInvalidatedSession}.
     */
    public enum InvalidatedSessionReview {
        /** Banchile informa el pago aprobado o reembolsado: se alertó a la organización y la sesión quedó marcada. */
        ALERTED,
        /** Banchile informa la sesión rechazada: quedó marcada como revisada, sin alerta. */
        REJECTED,
        /**
         * Banchile no informa un estado final: no cambia nada y se vuelve a consultar en el próximo
         * run, salvo que sea su última revisión (entonces queda un WARN en el log).
         */
        NOT_FINAL,
        /** La sesión ya estaba marcada como revisada: no se consultó. */
        ALREADY_REVIEWED
    }

    /**
     * Revisa en Banchile una sesión cuya transacción alf.io dejó INVALID al crear otra sesión para la
     * misma reserva (el comprador apretó "Pagar" de nuevo, o eligió otro medio de pago). alf.io no
     * cancela esa sesión en Banchile: se puede pagar hasta que vence, y nada más la consulta.
     * <ul>
     *   <li>APPROVED o reembolsado: alerta de posible doble cobro a la organización del evento y
     *       marca la sesión como alertada.</li>
     *   <li>REJECTED (o PARTIAL_EXPIRED): la marca como revisada, sin alerta.</li>
     *   <li>Sin estado final (PENDING, APPROVED_PARTIAL o desconocido): no cambia nada. Si es la
     *       última revisión dentro de la ventana, deja un WARN en el log: nadie la volverá a consultar.</li>
     * </ul>
     *
     * <p>No toca la reserva ni el estado INVALID de la transacción: solo escribe en su metadata la
     * marca de revisión y los detalles banchile_* del pago. La alerta se encola en la transacción de
     * base de datos del llamador, antes que la marca: quedan las dos o ninguna, y si algo falla la
     * sesión sigue sin marcar y se reintenta en el próximo run.
     *
     * @param transaction           transacción BANCHILE en INVALID, ya bloqueada ({@code FOR UPDATE}) por el llamador
     * @param purchaseContext       contexto de compra de la reserva
     * @param lastReviewInTheWindow true si el próximo run ya no la revisa porque sale de
     *                              {@link #INVALIDATED_SESSION_WINDOW}
     * @return el resultado de la revisión
     * @throws BanchilePagosClient.BanchilePagosException si Banchile no responde a tiempo o responde con error
     */
    public InvalidatedSessionReview reviewInvalidatedSession(Transaction transaction, PurchaseContext purchaseContext,
                                                             boolean lastReviewInTheWindow) {
        return reviewInvalidatedSession(transaction, purchaseContext, null, lastReviewInTheWindow
            ? "sale de la ventana de revisión de " + INVALIDATED_SESSION_WINDOW.toMinutes() + " minutos: alf.io no la volverá a consultar"
            : null);
    }

    /**
     * Sesión vigente de la reserva que Banchile informó rechazada: la revisión de las sesiones
     * invalidadas que se hace antes del flujo de pago fallido la menciona en la alerta.
     *
     * @param requestId      requestId de la sesión vigente
     * @param banchileStatus estado de la sesión vigente en Banchile (REJECTED o PARTIAL_EXPIRED)
     */
    private record RejectedSession(String requestId, String banchileStatus) {}

    /**
     * Antes de devolver FAILED con la reserva esperando el pago: revisa en Banchile, como
     * {@link #reviewInvalidatedSession}, las sesiones de la misma reserva que alf.io dejó INVALID y
     * todavía no tienen un resultado final. El flujo de pago fallido de alf.io anula la reserva si
     * está vencida, y la limpieza la borra si vuelve a PENDING y vence: en los dos casos se borran
     * todas sus transacciones, también las de esas sesiones, y el cron ya no las ve.
     *
     * <p>Cada sesión se bloquea con {@code FOR UPDATE} después de la transacción PENDING, que el
     * llamador ya tiene bloqueada: el orden de siempre, PENDING y después INVALID. Si Banchile no
     * responde por alguna, la excepción se propaga y {@link #forceTransactionCheck} devuelve ERROR
     * sin cerrar la transacción vigente: el rechazo se vuelve a procesar en la próxima consulta.
     *
     * @param rejected        transacción PENDING de la sesión vigente, que Banchile informa rechazada
     * @param rejectedStatus  estado de la sesión vigente en Banchile
     * @param purchaseContext contexto de compra de la reserva
     */
    private void reviewInvalidatedSessionsBeforeTheFailedPaymentFlow(Transaction rejected,
                                                                     String rejectedStatus,
                                                                     PurchaseContext purchaseContext) {
        // la misma ventana que el cron (BanchileReconciliationJob), medida desde la creación de la sesión
        var createdSince = ZonedDateTime.now(clockProvider.getClock()).minus(INVALIDATED_SESSION_WINDOW);
        var sessions = transactionRepository.findAllByReservationIdAndPaymentProxy(rejected.getReservationId(), PaymentProxy.BANCHILE).stream()
            .filter(tx -> tx.getStatus() == Transaction.Status.INVALID)
            .filter(tx -> !tx.getTimestamp().isBefore(createdSince))
            .filter(tx -> !isInvalidatedSessionReviewed(tx))
            .toList();
        var rejectedSession = new RejectedSession(rejected.getPaymentId(), rejectedStatus);
        // si sigue sin estado final, puede ser su última revisión: con la reserva vencida, el flujo de
        // pago fallido borra su transacción
        String lastReviewReason = "se revisó al procesar el rechazo de la sesión vigente requestId=" + rejected.getPaymentId()
            + ": si la reserva está vencida, el flujo de pago fallido la anula y borra esta transacción, y alf.io no la volverá a consultar";
        for (var session : sessions) {
            transactionRepository.loadOptionalByIdAndStatusForUpdate(session.getId(), Transaction.Status.INVALID)
                .ifPresent(locked -> reviewInvalidatedSession(locked, purchaseContext, rejectedSession, lastReviewReason));
        }
    }

    /**
     * {@link #reviewInvalidatedSession(Transaction, PurchaseContext, boolean)}, con la sesión vigente
     * rechazada cuando la revisión se hace antes del flujo de pago fallido.
     *
     * @param rejectedSession  sesión vigente que Banchile informó rechazada, o null si revisa el cron
     * @param lastReviewReason por qué puede ser la última revisión de la sesión, para el WARN si
     *                         Banchile no informa un estado final; null si habrá otras
     */
    private InvalidatedSessionReview reviewInvalidatedSession(Transaction transaction, PurchaseContext purchaseContext,
                                                              RejectedSession rejectedSession, String lastReviewReason) {
        if (isInvalidatedSessionReviewed(transaction)) {
            return InvalidatedSessionReview.ALREADY_REVIEWED;
        }
        var configuration = getConfiguration(purchaseContext.getConfigurationLevel());
        String login   = configuration.get(BANCHILE_LOGIN).getRequiredValue();
        String tranKey = configuration.get(BANCHILE_TRANKEY).getRequiredValue();
        String baseUrl = configuration.get(BANCHILE_BASE_URL).getRequiredValue();

        int requestId = Integer.parseInt(transaction.getPaymentId());
        Auth freshAuth = BanchilePagosClient.buildAuth(login, tranKey);
        SessionResponse remoteSession = banchilePagosClient.querySession(requestId, freshAuth, baseUrl);
        if (remoteSession == null || remoteSession.status() == null) {
            log.warn("querySession devolvió una respuesta inválida para la sesión invalidada requestId={}", requestId);
            return InvalidatedSessionReview.NOT_FINAL;
        }
        String banchileStatus = remoteSession.status().status();
        boolean refunded = isRefunded(remoteSession);
        Transaction.Status outcome = refunded ? Transaction.Status.COMPLETE : mapStatus(banchileStatus);
        if (outcome != Transaction.Status.COMPLETE && outcome != Transaction.Status.FAILED) {
            if (lastReviewReason != null) {
                log.warn("Sesión Banchile invalidada requestId={} de la reserva {}: Banchile no informa un estado final ({}) y {}. "
                    + "Revisarla en el portal de Banchile", requestId, transaction.getReservationId(), banchileStatus, lastReviewReason);
            }
            return InvalidatedSessionReview.NOT_FINAL;
        }

        boolean paid = outcome == Transaction.Status.COMPLETE;
        if (paid) {
            sendInvalidatedSessionAlert(purchaseContext, transaction, remoteSession, banchileStatus, refunded, rejectedSession);
        }
        Map<String, String> metadata = buildBanchileMetadata(transaction, syntheticPayload(requestId, remoteSession),
            remoteSession, banchileStatus);
        metadata.put(INVALIDATED_SESSION_REVIEW, paid ? "alerted" : "rejected");
        transactionRepository.updateMetadata(transaction.getId(), metadata);
        log.info("Sesión Banchile invalidada requestId={} de la reserva {}: {} en Banchile (reembolsado={})",
            requestId, transaction.getReservationId(), banchileStatus, refunded);
        return paid ? InvalidatedSessionReview.ALERTED : InvalidatedSessionReview.REJECTED;
    }

    /**
     * true si la sesión invalidada ya se revisó en Banchile con un resultado final (alertada o
     * rechazada): no hay que volver a consultarla.
     *
     * @param transaction transacción BANCHILE en INVALID
     * @return true si su metadata tiene la marca de revisión
     */
    public static boolean isInvalidatedSessionReviewed(Transaction transaction) {
        return transaction.getMetadata().containsKey(INVALIDATED_SESSION_REVIEW);
    }

    /**
     * Avisa a la organización del evento de un pago aprobado en Banchile que no se aplicó a la
     * reserva, porque la reserva no esperaba el pago o porque Banchile informa el pago como
     * reembolsado. Correo en texto plano, como la alerta de reservas STUCK. Son tres alertas:
     * <ul>
     *   <li>pago aprobado y reserva no confirmada: hay que reembolsarlo si corresponde;</li>
     *   <li>pago reembolsado y reserva no confirmada: ya se devolvió, no hay nada que reembolsar;</li>
     *   <li>pago reembolsado y reserva confirmada (solo se llega acá así con la reserva confirmada):
     *       la reserva quedó confirmada sin pago.</li>
     * </ul>
     *
     * @param purchaseContext   evento de la reserva
     * @param transaction       transacción Banchile (todavía PENDING)
     * @param remoteSession     respuesta de querySession
     * @param banchileStatus    estado de la sesión en Banchile
     * @param reservationStatus estado actual de la reserva, o null si no existe
     * @param refunded          true si Banchile informa el pago como reembolsado
     */
    private void sendApprovedPaymentAlert(PurchaseContext purchaseContext,
                                          Transaction transaction,
                                          SessionResponse remoteSession,
                                          String banchileStatus,
                                          TicketReservationStatus reservationStatus,
                                          boolean refunded) {
        boolean confirmed = RESERVATION_CONFIRMED.contains(reservationStatus);
        String sentence;
        String subject;
        if (confirmed) {
            sentence = REFUNDED_ON_CONFIRMED_RESERVATION_ALERT + "\n" + VERIFY_BEFORE_ANNULLING;
            subject = "Banchile: pago reembolsado en una reserva confirmada";
        } else if (refunded) {
            sentence = REFUNDED_PAYMENT_ALERT;
            subject = "Banchile: pago reembolsado en una reserva no confirmada";
        } else {
            sentence = APPROVED_PAYMENT_ALERT;
            subject = "Banchile: pago aprobado en una reserva que no está activa";
        }
        var payment = selectPaymentDetails(remoteSession);
        var text = alertHeader(sentence, purchaseContext, transaction, payment, reservationStatus);
        appendPaymentDetails(text, payment, banchileStatus);
        if (confirmed) {
            text.append("\nBanchile informa este pago como reembolsado y la reserva sigue confirmada en alf.io.\n");
        }
        text.append("\nEsta revisión cerró la transacción como COMPLETE y no modificó la reserva.");
        appendCleanupNote(text, reservationStatus);

        subject += " (requestId " + transaction.getPaymentId() + ")";
        String recipient = sendAlert(purchaseContext, transaction, subject, text.toString());
        log.warn("Alerta enviada a {}: pago Banchile aprobado (requestId={}, reembolsado={}) en la reserva {} en estado {}",
            recipient, transaction.getPaymentId(), refunded, transaction.getReservationId(), reservationStatus);
    }

    /**
     * Avisa a la organización del evento de un pago que Banchile informa como rechazado en una
     * reserva que alf.io tiene confirmada: se confirmó sin cobro, por ejemplo a mano (incidente
     * BDD21987), así que puede haber entradas y boleta emitidas sin pago. La reserva no se toca:
     * anular las entradas y emitir la nota de crédito lo decide la organización, después de
     * descartar que se haya pagado en otra sesión o por otro medio.
     *
     * @param purchaseContext   evento de la reserva
     * @param transaction       transacción Banchile (todavía PENDING)
     * @param remoteSession     respuesta de querySession
     * @param banchileStatus    estado de la sesión en Banchile
     * @param reservationStatus estado actual de la reserva (COMPLETE, FINALIZING u OFFLINE_FINALIZING)
     */
    private void sendRejectedPaymentAlert(PurchaseContext purchaseContext,
                                          Transaction transaction,
                                          SessionResponse remoteSession,
                                          String banchileStatus,
                                          TicketReservationStatus reservationStatus) {
        var status = remoteSession.status();
        var text = alertHeader(REJECTED_PAYMENT_ALERT + "\n" + VERIFY_BEFORE_ANNULLING, purchaseContext, transaction,
                selectPaymentDetails(remoteSession), reservationStatus)
            .append("Estado en Banchile: ").append(banchileStatus).append('\n')
            .append("Razón Banchile: ").append(status.reason() != null ? status.reason() : NOT_REPORTED).append('\n')
            .append("Mensaje Banchile: ").append(status.message() != null ? status.message() : NOT_REPORTED).append('\n')
            .append("\nEsta revisión cerró la transacción como FAILED y no modificó la reserva.\n");

        String subject = "Banchile: pago rechazado en una reserva confirmada (requestId " + transaction.getPaymentId() + ")";
        String recipient = sendAlert(purchaseContext, transaction, subject, text.toString());
        log.warn("Alerta enviada a {}: pago Banchile {} (requestId={}) en la reserva confirmada {} ({})",
            recipient, banchileStatus, transaction.getPaymentId(), transaction.getReservationId(), reservationStatus);
    }

    /**
     * Avisa a la organización del evento de un pago aprobado (o reembolsado) en una sesión que alf.io
     * dejó INVALID al crear otra para la misma reserva: el comprador pudo haber pagado dos veces. Las
     * demás sesiones de la reserva, con su estado, van en la lista del encabezado, para compararlas
     * en el portal.
     *
     * @param purchaseContext evento de la reserva
     * @param transaction     transacción INVALID de la sesión pagada
     * @param remoteSession   respuesta de querySession
     * @param banchileStatus  estado de la sesión en Banchile
     * @param refunded        true si Banchile informa el pago como reembolsado
     * @param rejectedSession sesión vigente que Banchile informó rechazada, si la revisión se hace
     *                        antes del flujo de pago fallido; null si revisa el cron
     */
    private void sendInvalidatedSessionAlert(PurchaseContext purchaseContext,
                                             Transaction transaction,
                                             SessionResponse remoteSession,
                                             String banchileStatus,
                                             boolean refunded,
                                             RejectedSession rejectedSession) {
        String reservationId = transaction.getReservationId();
        TicketReservationStatus reservationStatus = ticketReservationRepository.findOptionalStatusAndValidationById(reservationId)
            .map(TicketReservationStatusAndValidation::getStatus)
            .orElse(null);
        var payment = selectPaymentDetails(remoteSession);
        var text = alertHeader(INVALIDATED_SESSION_ALERT, purchaseContext, transaction, payment, reservationStatus);
        appendPaymentDetails(text, payment, banchileStatus);
        if (refunded) {
            text.append("\nBanchile informa este pago como reembolsado: ya se devolvió, así que no hay que reembolsarlo.\n");
        }
        if (rejectedSession != null) {
            text.append("\nSe revisó al procesar el rechazo de la sesión vigente de la reserva (requestId ")
                .append(rejectedSession.requestId()).append(", ").append(rejectedSession.banchileStatus()).append(" en Banchile): ")
                .append("a continuación alf.io corre su flujo de pago fallido, que anula la reserva si está vencida y borra todas sus ")
                .append("transacciones, también la de esta sesión; si la reserva sigue vigente, la devuelve a PENDING y la limpieza ")
                .append("automática la borra si vence sin otro pago. Este correo puede quedar como el único registro del pago.\n");
        }
        text.append("\nEsta revisión no modificó la reserva ni la transacción de esta sesión, que sigue INVALID, y no ")
            .append("volverá a consultarla.");
        appendCleanupNote(text, reservationStatus);

        String subject = "Banchile: pago aprobado en una sesión invalidada (requestId " + transaction.getPaymentId() + ")";
        String recipient = sendAlert(purchaseContext, transaction, subject, text.toString());
        log.warn("Alerta enviada a {}: pago Banchile {} (requestId={}, reembolsado={}) en una sesión invalidada de la reserva {} en estado {}",
            recipient, banchileStatus, transaction.getPaymentId(), refunded, reservationId, reservationStatus);
    }

    /**
     * Encabezado común de las alertas: la frase que explica qué revisar y los datos para ubicar el
     * pago en alf.io y en el portal de Banchile, con el comprador, el medio de pago de la reserva, la
     * referencia con que se buscan todas sus sesiones y sus otras transacciones Banchile. Si la
     * reserva se borra después (la limpieza automática, el flujo de pago fallido), el correo queda
     * como el único registro de quién pagó.
     *
     * @param sentence          frase inicial de la alerta
     * @param purchaseContext   evento de la reserva
     * @param transaction       transacción Banchile
     * @param payment           intento de pago elegido por {@link #selectPaymentDetails}, o null
     * @param reservationStatus estado actual de la reserva, o null si no existe
     * @return el texto, para agregarle las líneas propias de cada alerta
     */
    private StringBuilder alertHeader(String sentence,
                                      PurchaseContext purchaseContext,
                                      Transaction transaction,
                                      SessionResponse.PaymentDetails payment,
                                      TicketReservationStatus reservationStatus) {
        String reservationId = transaction.getReservationId();
        // el ID con el que se reembolsa por /api/reverse (banchile_internal_reference en el metadata)
        String internalReference = payment != null && payment.internalReference() != null
            ? String.valueOf(payment.internalReference()) : NOT_REPORTED;
        String amount = MonetaryUtil.formatCents(transaction.getPriceInCents(), transaction.getCurrency())
            + " " + transaction.getCurrency();
        var reservation = ticketReservationRepository.findOptionalReservationById(reservationId);
        // la reserva confirmada a mano queda con ADMIN; la que no se confirmó nunca, sin medio de pago
        String paymentMethod = reservation
            .map(TicketReservation::getPaymentMethod)
            .map(Enum::name)
            .orElse(NOT_REPORTED);
        String buyerName = reservation.map(TicketReservation::getFullName).filter(StringUtils::isNotBlank).orElse(NOT_REPORTED);
        String buyerEmail = reservation.map(TicketReservation::getEmail).filter(StringUtils::isNotBlank).orElse(NOT_REPORTED);
        var text = new StringBuilder()
            .append(sentence).append("\n\n")
            .append("Evento: ").append(purchaseContext.getDisplayName()).append('\n')
            .append("Reserva: ").append(reservationId).append('\n')
            .append("Comprador: ").append(buyerName).append('\n')
            .append("Correo del comprador: ").append(buyerEmail).append('\n')
            .append("Estado de la reserva en alf.io: ").append(reservationStatus != null ? reservationStatus : "no encontrada").append('\n')
            .append("Medio de pago de la reserva en alf.io: ").append(paymentMethod).append('\n')
            .append("Referencia del comercio en Banchile: ").append(merchantReference(reservationId))
            .append(" (con ella se buscan en el portal todas las sesiones de la reserva)\n")
            .append("requestId Banchile: ").append(transaction.getPaymentId()).append('\n')
            .append("internalReference Banchile: ").append(internalReference).append('\n')
            .append("Monto: ").append(amount).append('\n');
        var others = transactionRepository.findAllByReservationIdAndPaymentProxy(reservationId, PaymentProxy.BANCHILE).stream()
            .filter(tx -> tx.getId() != transaction.getId())
            .toList();
        if (others.isEmpty()) {
            text.append("Otras transacciones Banchile de la reserva: ninguna\n");
        } else {
            text.append("Otras transacciones Banchile de la reserva:\n");
            for (var other : others) {
                String banchileStatus = other.getMetadata().get("banchile_status");
                text.append("- requestId ").append(other.getPaymentId()).append(": ").append(other.getStatus())
                    .append(banchileStatus != null ? " (Banchile: " + banchileStatus + ")" : "").append('\n');
            }
        }
        return text;
    }

    /**
     * Agrega el recibo, la autorización y el estado de la sesión en Banchile.
     */
    private static void appendPaymentDetails(StringBuilder text, SessionResponse.PaymentDetails payment, String banchileStatus) {
        String receipt = payment != null && payment.receipt() != null ? payment.receipt() : NOT_REPORTED;
        String authorization = payment != null && payment.authorization() != null ? payment.authorization() : "no informada";
        text.append("Recibo: ").append(receipt).append('\n')
            .append("Autorización: ").append(authorization).append('\n')
            .append("Estado en Banchile: ").append(banchileStatus).append('\n');
    }

    /**
     * Cierra el párrafo final de la alerta. Si la limpieza automática de alf.io puede borrar la
     * reserva (PENDING u OFFLINE_PAYMENT vencida), avisa que el correo puede quedar como el único
     * registro del pago; a las demás reservas la limpieza no las toca.
     */
    private static void appendCleanupNote(StringBuilder text, TicketReservationStatus reservationStatus) {
        if (RESERVATION_REMOVED_BY_CLEANUP.contains(reservationStatus)) {
            text.append(CLEANUP_NOTE);
        }
        text.append('\n');
    }

    /**
     * Referencia del comercio que alf.io manda a Banchile en createSession: el ID de la reserva sin
     * guiones. Banchile rechaza la referencia con guiones; sin ellos sigue siendo única y se puede
     * llevar de vuelta al ID de la reserva. Con ella se buscan en el portal todas las sesiones de la
     * reserva.
     */
    private static String merchantReference(String reservationId) {
        return reservationId.replace("-", "");
    }

    /**
     * Deja la reserva vigente hasta {@link #RESERVATION_VALIDITY_AFTER_SESSION} después del vencimiento
     * de la sesión, como Mollie, Saferpay y PayPal al crear el pago. La validez se alarga, nunca se
     * acorta: max(validez actual, vencimiento de la sesión + 15 minutos). Corre después de crear la
     * sesión, en la transacción de {@code TicketReservationManager#performPayment}, que tiene la reserva
     * bloqueada ({@code select ... for update}): queda junto con el paso a EXTERNAL_PROCESSING_PAYMENT
     * y la transacción PENDING, o no queda ninguno.
     *
     * @param reservationId     reserva
     * @param reservation       la reserva leída dentro de esa transacción, o null si no se encontró
     * @param sessionExpiration vencimiento de la sesión que se mandó a Banchile
     * @return la validez con que queda la reserva
     */
    private Date extendValidity(String reservationId, TicketReservation reservation, ZonedDateTime sessionExpiration) {
        Date extended = Date.from(sessionExpiration.plus(RESERVATION_VALIDITY_AFTER_SESSION).toInstant());
        Date current = reservation != null ? reservation.getValidity() : null;
        if (current != null && !current.before(extended)) {
            return current;
        }
        ticketReservationRepository.updateValidity(reservationId, extended);
        return extended;
    }

    /**
     * Descripción del pago en Banchile: {@code <nombre del evento> - reserva <ID corto>}, con el ID
     * corto de los correos de alf.io, para reconocer el pago en el portal. Sin el nombre del evento
     * queda {@code Reserva <ID corto>}, y sin ID corto la de antes, {@code Reserva <referencia>}.
     * Siempre tiene más de 4 caracteres, el mínimo que acepta Banchile.
     *
     * @param purchaseContext evento de la reserva
     * @param reservation     reserva, o null si no se encontró
     * @param reference       referencia del comercio que va en la sesión
     * @return la descripción, de hasta {@value #MAX_DESCRIPTION_LENGTH} caracteres
     */
    private String paymentDescription(PurchaseContext purchaseContext, TicketReservation reservation, String reference) {
        String shortId = reservation != null
            ? StringUtils.trimToNull(configurationManager.getShortReservationID(purchaseContext, reservation))
            : null;
        if (shortId == null) {
            return "Reserva " + reference;
        }
        String suffix = " - reserva " + shortId;
        String eventName = singleLine(DESCRIPTION_DISCARDED_CHARACTERS.matcher(singleLine(purchaseContext.getDisplayName())).replaceAll(""));
        eventName = cut(eventName, Math.max(0, MAX_DESCRIPTION_LENGTH - suffix.length()));
        return eventName.isEmpty() ? "Reserva " + shortId : eventName + suffix;
    }

    /**
     * Comprador para la sesión, con el nombre, el apellido y el correo de la reserva: Banchile los
     * precarga en su checkout. Los nombres van en una línea y cortados en
     * {@value #MAX_BUYER_NAME_LENGTH} caracteres.
     *
     * @param reservation reserva, o null si no se encontró
     * @return el comprador, o null si falta el nombre, el apellido o el correo, si un nombre tiene
     *         caracteres que no son de un nombre o si el correo no tiene un formato conservador o pasa
     *         de {@value #MAX_BUYER_EMAIL_LENGTH} caracteres: la sesión se crea sin comprador
     */
    private static Buyer buyerOf(TicketReservation reservation) {
        if (reservation == null) {
            return null;
        }
        String name = cut(singleLine(reservation.getFirstName()), MAX_BUYER_NAME_LENGTH);
        String surname = cut(singleLine(reservation.getLastName()), MAX_BUYER_NAME_LENGTH);
        String email = StringUtils.strip(reservation.getEmail());
        if (!BUYER_NAME.matcher(name).matches() || !BUYER_NAME.matcher(surname).matches()
            || email == null || email.length() > MAX_BUYER_EMAIL_LENGTH || !BUYER_EMAIL.matcher(email).matches()) {
            return null;
        }
        return new Buyer(name, surname, email, null, null, null);
    }

    /**
     * El request HTTP del hilo actual, si lo hay: el que confirma la reserva cuando doPayment corre
     * desde {@code ReservationApiV2Controller#confirmOverview}.
     *
     * @return el request, o null fuera de un request
     */
    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
            ? attributes.getRequest()
            : null;
    }

    /**
     * IP del comprador para la sesión. Con {@code server.forward-headers-strategy=native}, Jetty
     * entrega en {@code getRemoteAddr()} la IP del {@code X-Forwarded-For} que pone Caddy (una IPv6,
     * entre corchetes). Solo se acepta una IP literal, que se valida sin resolver nombres.
     *
     * @param request request del comprador, o null
     * @return la IP sin corchetes, o {@value #UNKNOWN_IP_ADDRESS} si no hay request o no es una IP
     */
    private static String clientIpAddress(HttpServletRequest request) {
        String address = request != null ? StringUtils.strip(request.getRemoteAddr()) : null;
        if (address != null && address.length() > 1 && address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        return isIpLiteral(address) ? address : UNKNOWN_IP_ADDRESS;
    }

    /**
     * true si el texto es una IPv4 o una IPv6 literal. No resuelve nombres: la IPv4 se valida con una
     * expresión regular y la IPv6 (solo dígitos hexadecimales, ':' y '.') con el parser de
     * {@link URI}, que valida la sintaxis del literal IPv6 de la RFC 2732 sin consultar el DNS.
     */
    private static boolean isIpLiteral(String address) {
        if (StringUtils.isEmpty(address) || address.length() > MAX_IP_ADDRESS_LENGTH) {
            return false;
        }
        if (IPV4.matcher(address).matches()) {
            return true;
        }
        if (address.indexOf(':') < 0 || !IPV6_CHARACTERS.matcher(address).matches()) {
            return false;
        }
        try {
            return new URI("http://[" + address + "]/").getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Navegador del comprador para la sesión: su header {@code User-Agent} en una línea, sin
     * caracteres de control y cortado en {@value #MAX_USER_AGENT_LENGTH} caracteres.
     *
     * @param request request del comprador, o null
     * @return el user-agent, o {@value #DEFAULT_USER_AGENT} si no hay request o viene vacío
     */
    private static String clientUserAgent(HttpServletRequest request) {
        String userAgent = request != null ? cut(singleLine(request.getHeader(HttpHeaders.USER_AGENT)), MAX_USER_AGENT_LENGTH) : "";
        return userAgent.isEmpty() ? DEFAULT_USER_AGENT : userAgent;
    }

    /**
     * El texto en una línea: en NFC (una letra y su tilde combinada quedan como un solo carácter), sin
     * caracteres de formato, con los caracteres de control y los separadores convertidos en un
     * espacio, y sin espacios repetidos ni en los extremos.
     *
     * @param value texto, o null
     * @return el texto limpio; vacío si era null
     */
    private static String singleLine(String value) {
        if (value == null) {
            return "";
        }
        String text = FORMAT_CHARACTERS.matcher(Normalizer.normalize(value, Normalizer.Form.NFC)).replaceAll("");
        return CONTROL_OR_SEPARATOR_RUNS.matcher(text).replaceAll(" ").strip();
    }

    /**
     * Corta el texto en {@code maxLength} caracteres sin partir un carácter de dos unidades UTF-16
     * (un emoji), y le quita los espacios que queden al final.
     */
    private static String cut(String text, int maxLength) {
        if (text.codePointCount(0, text.length()) <= maxLength) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxLength)).strip();
    }

    /**
     * Payload sintético para reusar {@link #buildBanchileMetadata} cuando se llega por polling y no
     * por webhook: se usan los datos que sí hay, el requestId de la transacción y el estado remoto.
     */
    private static BanchileWebhookPayload syntheticPayload(int requestId, SessionResponse remoteSession) {
        return new BanchileWebhookPayload(
            requestId,
            null,                              // reference: no disponible en este path
            remoteSession.status(),
            null                               // signature: no aplica (no es un webhook entrante)
        );
    }

    /**
     * Encola la alerta en texto plano para el correo de la organización del evento, como la
     * alerta de reservas STUCK.
     *
     * @param purchaseContext evento de la reserva
     * @param transaction     transacción Banchile
     * @param subject         asunto del correo
     * @param body            texto del correo
     * @return el destinatario
     */
    private String sendAlert(PurchaseContext purchaseContext, Transaction transaction, String subject, String body) {
        String recipient = organizationRepository.getById(purchaseContext.getOrganizationId()).getEmail();
        notificationManager.sendSimpleEmail(purchaseContext, transaction.getReservationId(), recipient, subject,
            () -> RenderedTemplate.plaintext(body, Map.of()));
        return recipient;
    }

    /**
     * Banchile firma sus webhooks. Se requiere body firmado para validación.
     */
    @Override
    public boolean requiresSignedBody() {
        return true;
    }

    // -------------------------------------------------------------------------
    // PaymentInfo
    // -------------------------------------------------------------------------

    /**
     * Consulta el estado actual del pago en Banchile y retorna la información de pago.
     *
     * @param transaction   Transacción registrada en Alfio
     * @param purchaseContext Contexto del evento/suscripción
     * @return PaymentInformation con el monto aprobado, o vacío si falla la consulta
     */
    @Override
    public Optional<PaymentInformation> getInfo(Transaction transaction, PurchaseContext purchaseContext) {
        try {
            var configuration = getConfiguration(purchaseContext.getConfigurationLevel());
            String login   = configuration.get(BANCHILE_LOGIN).getRequiredValue();
            String tranKey = configuration.get(BANCHILE_TRANKEY).getRequiredValue();
            String baseUrl = configuration.get(BANCHILE_BASE_URL).getRequiredValue();

            int requestId = Integer.parseInt(transaction.getPaymentId());
            Auth freshAuth = BanchilePagosClient.buildAuth(login, tranKey);
            SessionResponse remoteSession = banchilePagosClient.querySession(requestId, freshAuth, baseUrl);

            if (remoteSession == null) {
                return Optional.empty();
            }

            // Extraer monto aprobado si está disponible (payment es ARRAY en respuestas de Banchile)
            String paidAmount = null;
            var approved = remoteSession.firstApprovedPayment();
            if (approved != null && approved.amount() != null) {
                paidAmount = MonetaryUtil.formatCents(
                    (int) approved.amount().total(),
                    approved.amount().currency()
                );
            }

            // Banchile no soporta reembolsos en MVP
            return Optional.of(new PaymentInformation(paidAmount, null, null, null));

        } catch (Exception e) {
            log.warn("Error al obtener info de Banchile para transacción {}", transaction.getId(), e);
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // RefundRequest
    // -------------------------------------------------------------------------

    /**
     * Los reembolsos via Banchile no están implementados en el MVP.
     *
     * @throws UnsupportedOperationException siempre
     */
    @Override
    public boolean refund(Transaction transaction, PurchaseContext purchaseContext, Integer amount) {
        throw new UnsupportedOperationException(
            "Refunds via Banchile no implementados en MVP — gestionar manualmente en el portal Banchile"
        );
    }

    // -------------------------------------------------------------------------
    // Helpers públicos (testeables de forma aislada)
    // -------------------------------------------------------------------------

    /**
     * Mapea el estado de Banchile Pagos a {@link Transaction.Status} de Alfio.
     *
     * <p>Mapeo:
     * <ul>
     *   <li>PENDING → PENDING</li>
     *   <li>APPROVED → COMPLETE</li>
     *   <li>REJECTED → FAILED</li>
     *   <li>APPROVED_PARTIAL → PENDING (no soportado en MVP; se loguea warning en processWebhook)</li>
     *   <li>PARTIAL_EXPIRED → FAILED</li>
     *   <li>Cualquier otro → PENDING (conservador)</li>
     * </ul>
     *
     * @param banchileStatus Estado string devuelto por Banchile
     * @return Transaction.Status correspondiente en Alfio
     */
    public static Transaction.Status mapStatus(String banchileStatus) {
        if (banchileStatus == null) {
            return Transaction.Status.PENDING;
        }
        return switch (banchileStatus) {
            case "APPROVED"         -> Transaction.Status.COMPLETE;
            case "REJECTED",
                 "PARTIAL_EXPIRED"  -> Transaction.Status.FAILED;
            case "PENDING",
                 "APPROVED_PARTIAL" -> Transaction.Status.PENDING;
            default -> {
                log.warn("Estado Banchile desconocido: {} — tratando como PENDING", banchileStatus);
                yield Transaction.Status.PENDING;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    /**
     * true si Banchile informa que el pago aprobado fue reembolsado: la sesión en estado
     * REFUNDED, o la sesión APPROVED con algún intento de pago reembolsado.
     *
     * <p>Qué está verificado contra Banchile y qué no:
     * <ul>
     *   <li>Verificado con la respuesta real de querySession de un pago aprobado y no reembolsado
     *       (rq 34028): cada elemento de {@code payment[]} trae las claves amount, authorization,
     *       franchise, internalReference, issuerName, paymentMethod, paymentMethodName,
     *       processorFields, receipt, reference, refunded y status; {@code refunded} es un booleano
     *       ({@code false}) y {@code status.status} es "APPROVED". {@code amount} viene como
     *       {@code {"from": {"total", "currency"}, "to": {"total", "currency"}, "factor"}}.</li>
     *   <li>No verificado: no hay una muestra real de un pago reembolsado. Las dos formas que se
     *       aceptan acá ({@code refunded == true} en un intento, o estado REFUNDED en la sesión o en
     *       un intento) no se han visto en una respuesta de Banchile; si un reembolso llegara de
     *       otra forma, se trataría como un pago aprobado.</li>
     * </ul>
     *
     * @param remoteSession respuesta de querySession
     * @return true si el pago fue reembolsado en Banchile
     */
    private static boolean isRefunded(SessionResponse remoteSession) {
        String sessionStatus = remoteSession.status() != null ? remoteSession.status().status() : null;
        if (REFUNDED.equals(sessionStatus)) {
            return true;
        }
        return "APPROVED".equals(sessionStatus)
            && remoteSession.payment() != null
            && remoteSession.payment().stream().anyMatch(BanchilePagosWebhookManager::isRefundedPayment);
    }

    private static boolean isRefundedPayment(SessionResponse.PaymentDetails payment) {
        return payment != null
            && (Boolean.TRUE.equals(payment.refunded())
                || (payment.status() != null && REFUNDED.equals(payment.status().status())));
    }

    /**
     * Intento de pago del que se toman los detalles (recibo, autorización, medio de pago...).
     * Una sesión puede tener varios intentos (reintentos tras un rechazo): se prefiere el
     * APPROVED; si no hay, el reembolsado (Banchile puede informarlo con estado REFUNDED en vez
     * de APPROVED); si tampoco hay, el primero disponible (útil para rechazos).
     *
     * @param remoteSession respuesta de querySession, puede ser null
     * @return el intento elegido, o null si la sesión no tiene intentos de pago
     */
    private static SessionResponse.PaymentDetails selectPaymentDetails(SessionResponse remoteSession) {
        if (remoteSession == null || remoteSession.payment() == null || remoteSession.payment().isEmpty()) {
            return null;
        }
        var approved = remoteSession.firstApprovedPayment();
        if (approved != null) {
            return approved;
        }
        return remoteSession.payment().stream()
            .filter(BanchilePagosWebhookManager::isRefundedPayment)
            .findFirst()
            .orElse(remoteSession.payment().get(0));
    }

    /**
     * Construye el {@code metadata Map<String,String>} que se persiste en
     * {@code b_transaction.metadata} (columna jsonb) con prefijo {@code banchile_*}.
     *
     * <p>Preserva todo metadata previamente persistido en {@link Transaction#getMetadata()}
     * y agrega los campos extraídos del payload del webhook + la respuesta de
     * {@code querySession} (ground truth remoto). Los detalles del pago salen del intento que
     * elige {@link #selectPaymentDetails}: el APPROVED, si no el reembolsado, si no el primero.
     *
     * <p>Solo invocar en estados finales (COMPLETE / FAILED). En PENDING, preservar
     * intacto el metadata actual.
     *
     * @param transaction    Transacción Alfio (puede tener metadata previo de createSession)
     * @param whPayload      Payload del webhook entrante
     * @param remoteSession  Respuesta de querySession (ground truth)
     * @param banchileStatus Status string de Banchile (e.g., "APPROVED", "REJECTED")
     * @return Mapa con prefijo {@code banchile_*} más todo metadata previo del transaction
     */
    private static Map<String, String> buildBanchileMetadata(Transaction transaction,
                                                              alfio.model.transaction.banchile.BanchileWebhookPayload whPayload,
                                                              SessionResponse remoteSession,
                                                              String banchileStatus) {
        Map<String, String> metadata = new HashMap<>();
        if (transaction.getMetadata() != null) {
            metadata.putAll(transaction.getMetadata());
        }

        if (whPayload != null && whPayload.requestId() != null) {
            metadata.put("banchile_request_id", String.valueOf(whPayload.requestId()));
        }
        if (whPayload != null && whPayload.reference() != null) {
            metadata.put("banchile_reference", whPayload.reference());
        }
        if (banchileStatus != null) {
            metadata.put("banchile_status", banchileStatus);
        }
        if (remoteSession != null && remoteSession.status() != null) {
            var st = remoteSession.status();
            if (st.date() != null)    metadata.put("banchile_status_date", st.date());
            if (st.reason() != null)  metadata.put("banchile_status_reason", st.reason());
            if (st.message() != null) metadata.put("banchile_status_message", st.message());
        }

        SessionResponse.PaymentDetails pd = selectPaymentDetails(remoteSession);
        if (pd != null) {
            if (pd.internalReference() != null)
                metadata.put("banchile_internal_reference", String.valueOf(pd.internalReference()));
            if (pd.authorization() != null)
                metadata.put("banchile_authorization", pd.authorization());
            if (pd.paymentMethod() != null)
                metadata.put("banchile_payment_method", pd.paymentMethod());
            if (pd.paymentMethodName() != null)
                metadata.put("banchile_payment_method_name", pd.paymentMethodName());
            if (pd.issuerName() != null)
                metadata.put("banchile_issuer", pd.issuerName());
            if (pd.franchise() != null)
                metadata.put("banchile_franchise", pd.franchise());
            if (pd.receipt() != null)
                metadata.put("banchile_receipt", pd.receipt());
            if (Boolean.TRUE.equals(pd.refunded()))
                metadata.put("banchile_refunded", "true");

            // Aplanar processorFields[] como banchile_pf_<keyword> = <value>
            // (solo cuando value es String — otros tipos se omiten para mantener Map<String,String>)
            if (pd.processorFields() != null) {
                for (var f : pd.processorFields()) {
                    if (f != null && f.keyword() != null && f.value() instanceof String stringValue) {
                        metadata.put("banchile_pf_" + f.keyword(), stringValue);
                    }
                }
            }
        }

        return metadata;
    }

    private Map<ConfigurationKeys, MaybeConfiguration> getConfiguration(ConfigurationLevel configurationLevel) {
        return configurationManager.getFor(ALL_OPTIONS, configurationLevel);
    }

    private boolean checkIfActive(Map<ConfigurationKeys, MaybeConfiguration> configuration) {
        return configuration.get(BANCHILE_ENABLED).getValueAsBooleanOrDefault()
            && configuration.get(BANCHILE_LOGIN).isPresent()
            && configuration.get(BANCHILE_TRANKEY).isPresent()
            && configuration.get(BANCHILE_BASE_URL).isPresent();
    }

    /**
     * Valida la firma del webhook de Banchile.
     *
     * <p>Fórmula según doc oficial Web Checkout (Notificaciones / Verificación de Firma):
     * {@code SHA-256(requestId + status.status + status.date + secretKey)}
     *
     * <p>El campo {@code signature} llega con el prefijo {@code sha256:} que se descarta
     * antes de la comparación. El digest se compara como hex lowercase contra el
     * receivedSignature. Si Banchile devuelve el digest en Base64 en lugar de hex,
     * se acepta también como fallback (la doc no es explícita y el ejemplo está truncado).
     *
     * @param payload   Payload del webhook entrante
     * @param secretKey Secret key del comercio (mismo valor usado en auth.tranKey)
     * @return true si la firma es válida, false en caso contrario
     */
    private boolean validateWebhookSignature(BanchileWebhookPayload payload, String secretKey) {
        try {
            String statusStr = payload.status() != null ? payload.status().status() : "";
            String dateStr   = payload.status() != null ? payload.status().date()   : "";
            String input     = payload.requestId() + statusStr + dateStr + secretKey;

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            String expectedHex    = bytesToHex(digest);
            String expectedBase64 = Base64.getEncoder().encodeToString(digest);

            String received = payload.signature();
            if (received.regionMatches(true, 0, "sha256:", 0, "sha256:".length())) {
                received = received.substring("sha256:".length());
            }

            return constantTimeEquals(received, expectedHex)
                || constantTimeEquals(received, expectedBase64);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en este JDK", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // -------------------------------------------------------------------------
    // Tipos internos
    // -------------------------------------------------------------------------

    /**
     * Implementación de {@link TransactionWebhookPayload} para Banchile.
     */
    public static class BanchileTransactionWebhookPayload implements TransactionWebhookPayload {

        private final BanchileWebhookPayload webhookPayload;
        private final String reservationId;

        public BanchileTransactionWebhookPayload(BanchileWebhookPayload webhookPayload, String reservationId) {
            this.webhookPayload = webhookPayload;
            this.reservationId = reservationId;
        }

        public BanchileWebhookPayload getWebhookPayload() {
            return webhookPayload;
        }

        @Override
        public Object getPayload() {
            return webhookPayload;
        }

        @Override
        public String getType() {
            return "banchile";
        }

        @Override
        public String getReservationId() {
            return reservationId;
        }

        @Override
        public Status getStatus() {
            if (webhookPayload.status() == null) {
                return null;
            }
            String s = webhookPayload.status().status();
            return "APPROVED".equals(s) ? Status.SUCCESS : Status.FAILURE;
        }
    }

    /**
     * Token de pago Banchile para confirmar la reserva en Alfio.
     */
    public static class BanchilePaymentToken implements PaymentToken {

        private final String requestId;

        public BanchilePaymentToken(String requestId) {
            this.requestId = requestId;
        }

        @Override
        public String getToken() {
            return requestId;
        }

        @Override
        public PaymentMethod getPaymentMethod() {
            return PaymentMethod.CREDIT_CARD;
        }

        @Override
        public PaymentProxy getPaymentProvider() {
            return PaymentProxy.BANCHILE;
        }
    }
}
