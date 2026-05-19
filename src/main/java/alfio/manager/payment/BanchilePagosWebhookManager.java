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

import alfio.manager.support.PaymentResult;
import alfio.manager.support.PaymentWebhookResult;
import alfio.manager.system.ConfigurationLevel;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.system.ConfigurationManager.MaybeConfiguration;
import alfio.model.PaymentInformation;
import alfio.model.PurchaseContext;
import alfio.model.TicketReservation;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.*;
import alfio.model.transaction.banchile.BanchileWebhookPayload;
import alfio.model.transaction.banchile.CreateSessionRequest;
import alfio.model.transaction.banchile.CreateSessionRequest.Amount;
import alfio.model.transaction.banchile.CreateSessionRequest.Auth;
import alfio.model.transaction.banchile.CreateSessionRequest.Payment;
import alfio.model.transaction.banchile.SessionResponse;
import alfio.model.transaction.capabilities.PaymentInfo;
import alfio.model.transaction.capabilities.RefundRequest;
import alfio.model.transaction.capabilities.WebhookHandler;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.util.ClockProvider;
import alfio.util.Json;
import alfio.util.MonetaryUtil;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.*;

import static alfio.manager.payment.PaymentManagerUtils.invalidateExistingTransactions;
import static alfio.model.TicketReservation.TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT;
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

    public static final String WEBHOOK_URL_TEMPLATE = "/api/payment/webhook/banchile/reservation/{reservationId}";

    protected static final Set<ConfigurationKeys> ALL_OPTIONS = EnumSet.of(
        BANCHILE_ENABLED,
        BANCHILE_LOGIN,
        BANCHILE_TRANKEY,
        BANCHILE_WEBHOOK_SECRET,
        BANCHILE_BASE_URL
    );

    private final ConfigurationManager configurationManager;
    private final BanchilePagosClient banchilePagosClient;
    private final TransactionRepository transactionRepository;
    private final TicketReservationRepository ticketReservationRepository;
    private final ClockProvider clockProvider;

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
            String reference = reservationId; // usamos el reservationId como referencia interna
            String description = "Reserva " + reservationId;
            String currency = purchaseContext.getCurrency();
            long totalCents = spec.getPriceWithVAT();
            // Banchile espera el total en unidades enteras (CLP no usa decimales)
            Amount amount = new Amount(currency, totalCents);
            Payment payment = new Payment(reference, description, amount);

            // URL de retorno: la misma "book" que usa Mollie como patrón
            String alfioBaseUrl = configurationManager.getFor(BASE_URL, purchaseContext.getConfigurationLevel()).getRequiredValue();
            String returnUrl = alfioBaseUrl + "/" + purchaseContext.getType().getUrlComponent()
                + "/" + purchaseContext.getPublicIdentifier()
                + "/reservation/" + reservationId + "/book";

            // Expiración: 30 minutos desde ahora
            String expiration = purchaseContext.now(clockProvider).plusMinutes(30).toInstant().toString();

            CreateSessionRequest req = new CreateSessionRequest(
                auth,
                "es_CL",
                null, // buyer opcional — no disponible en spec básica
                payment,
                returnUrl,
                "0.0.0.0", // IP no disponible en PaymentSpecification
                "alfio/banchile",
                expiration
            );

            SessionResponse sessionResponse = banchilePagosClient.createSession(req, baseUrl);

            if (sessionResponse == null || sessionResponse.requestId() == null || sessionResponse.processUrl() == null) {
                log.warn("createSession devolvió respuesta incompleta para reserva {}", reservationId);
                return PaymentResult.failed("banchile_session_creation_failed");
            }

            String requestIdStr = String.valueOf(sessionResponse.requestId());
            String processUrl = sessionResponse.processUrl();

            // Actualizar estado de la reserva
            ticketReservationRepository.updateReservationStatus(reservationId, EXTERNAL_PROCESSING_PAYMENT.toString());

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

            log.info("Sesión Banchile creada: requestId={} para reserva={}", requestIdStr, reservationId);
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
     * Retorna la clave del webhook secret para que el framework verifique la firma
     * a nivel de controlador (si aplica). En Banchile la validación se hace dentro
     * de processWebhook.
     */
    @Override
    public String getWebhookSignatureKey(ConfigurationLevel configurationLevel) {
        return configurationManager.getForSystem(BANCHILE_WEBHOOK_SECRET).getValue().orElse(null);
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
     *   <li>Valida la firma HMAC-SHA1</li>
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
            // --- Validación de firma ---
            String webhookSecret = configurationManager.getForSystem(BANCHILE_WEBHOOK_SECRET)
                .getValue().orElse(null);

            if (webhookSecret != null && whPayload.signature() != null) {
                if (!validateWebhookSignature(whPayload, webhookSecret)) {
                    log.warn("Firma de webhook Banchile inválida para requestId={}", whPayload.requestId());
                    return PaymentWebhookResult.error("firma inválida");
                }
            } else {
                // Si no hay secret configurado o la firma viene nula, loguear y continuar
                // (útil en sandbox donde Banchile puede no firmar los webhooks)
                log.debug("Webhook Banchile sin validación de firma (secret o firma ausente) requestId={}",
                    whPayload.requestId());
            }

            // --- Idempotencia: si ya está COMPLETE, no hacer nada ---
            if (transaction.getStatus() == Transaction.Status.COMPLETE) {
                log.info("Webhook Banchile recibido para transacción ya COMPLETE requestId={}, ignorando",
                    whPayload.requestId());
                return PaymentWebhookResult.notRelevant("already_complete");
            }

            // --- Obtener configuración para consultar el estado remoto ---
            var purchaseContext = paymentContext.getPurchaseContext();
            var configuration = getConfiguration(purchaseContext.getConfigurationLevel());
            String login   = configuration.get(BANCHILE_LOGIN).getRequiredValue();
            String tranKey = configuration.get(BANCHILE_TRANKEY).getRequiredValue();
            String baseUrl = configuration.get(BANCHILE_BASE_URL).getRequiredValue();

            // --- Consultar estado remoto para evitar spoofing ---
            Auth freshAuth = BanchilePagosClient.buildAuth(login, tranKey);
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

                    transactionRepository.update(
                        transaction.getId(),
                        requestIdStr, requestIdStr,
                        now,
                        0L, 0L,
                        Transaction.Status.COMPLETE,
                        transaction.getMetadata()
                    );

                    log.info("Pago Banchile APPROVED — reserva={} requestId={}",
                        transaction.getReservationId(), requestIdStr);
                    return PaymentWebhookResult.successful(new BanchilePaymentToken(requestIdStr));
                }

                case FAILED -> {
                    transactionRepository.update(
                        transaction.getId(),
                        requestIdStr, requestIdStr,
                        now,
                        transaction.getPlatformFee(), transaction.getGatewayFee(),
                        Transaction.Status.FAILED,
                        transaction.getMetadata()
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
     * Usado por el scheduler de Alfio para reconciliar reservas en limbo.
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
            // Reutilizamos el mismo payload vacío: la lógica real está en processWebhook
            // Aquí retornamos solo el resultado sin modificar estado (lo hace el caller)
            return switch (mapStatus(banchileStatus)) {
                case COMPLETE -> PaymentWebhookResult.successful(new BanchilePaymentToken(transaction.getPaymentId()));
                case FAILED   -> PaymentWebhookResult.failed(banchileStatus);
                default       -> PaymentWebhookResult.notRelevant(banchileStatus);
            };
        } catch (Exception e) {
            log.warn("Error en forceTransactionCheck para reserva {}", reservation.getId(), e);
            return PaymentWebhookResult.error(e.getMessage());
        }
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

            // Extraer monto aprobado si está disponible
            String paidAmount = null;
            if (remoteSession.payment() != null && remoteSession.payment().amount() != null) {
                paidAmount = MonetaryUtil.formatCents(
                    (int) remoteSession.payment().amount().total(),
                    remoteSession.payment().amount().currency()
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
     * <p>Fórmula esperada según especificación:
     * {@code Base64(SHA1(requestId + status + date + WEBHOOK_SECRET))}
     *
     * <p><b>TODO:</b> confirmar esta fórmula contra el primer webhook real capturado
     * en Phase F del plan de integración. El spec interno no ha sido validado contra
     * un webhook real de Banchile en producción.
     *
     * @param payload       Payload del webhook entrante
     * @param webhookSecret Secret configurado en Alfio
     * @return true si la firma es válida, false en caso contrario
     */
    private boolean validateWebhookSignature(BanchileWebhookPayload payload, String webhookSecret) {
        try {
            String status = payload.status() != null ? payload.status().status() : "";
            String date   = payload.status() != null ? payload.status().date() : "";
            String input  = payload.requestId() + status + date + webhookSecret;

            // SHA-1 requerido por el protocolo Banchile/PlacetoPay
            // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(digest);

            return expected.equals(payload.signature());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 no disponible en este JDK", e);
        }
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
