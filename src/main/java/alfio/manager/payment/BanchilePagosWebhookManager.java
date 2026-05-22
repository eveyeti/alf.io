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

    public static final String WEBHOOK_URL_TEMPLATE = "/api/payment/webhook/banchile";

    protected static final Set<ConfigurationKeys> ALL_OPTIONS = EnumSet.of(
        BANCHILE_ENABLED,
        BANCHILE_LOGIN,
        BANCHILE_TRANKEY,
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
            // Banchile rechaza UUIDs con dashes ("reference" inválido). Strip dashes para mantener
            // solo 32 chars hex — sigue siendo único y mapeable de vuelta al reservationId completo.
            String reference = reservationId.replace("-", "");
            String description = "Reserva " + reference;
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

            // Expiración: 30 minutos desde ahora
            String expiration = purchaseContext.now(clockProvider).plusMinutes(30).toInstant().toString();

            CreateSessionRequest req = new CreateSessionRequest(
                auth,
                "es_CL",
                null, // buyer opcional — no disponible en spec básica
                payment,
                returnUrl,
                cancelUrl,
                notificationUrl,
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
     * Usado por el scheduler de Alfio para reconciliar reservas en limbo.
     *
     * <p>En estados finales (COMPLETE/FAILED) persiste el metadata banchile_* con
     * todos los detalles de la transacción (auth, internalReference, etc.) — mismo
     * comportamiento que processWebhook. Esto cubre el caso polling cuando el webhook
     * entrante no llegó (común en sandbox sin panel de comercio).
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
            Transaction.Status alfioStatus = mapStatus(banchileStatus);

            // Persistir metadata en estados finales (incluso cuando se llega vía polling y
            // no por webhook). En PENDING se preserva el metadata existente.
            if (alfioStatus == Transaction.Status.COMPLETE || alfioStatus == Transaction.Status.FAILED) {
                // Construir un payload sintético para reusar el helper de metadata.
                // El payload del webhook no está disponible en este path (es polling), así que
                // se usan los datos que SÍ tenemos: requestId del transaction y status remoto.
                var syntheticPayload = new alfio.model.transaction.banchile.BanchileWebhookPayload(
                    requestId,
                    null,                              // reference: no disponible en este path
                    remoteSession.status(),
                    null                               // signature: no aplica (no es un webhook entrante)
                );
                Map<String, String> metadata = buildBanchileMetadata(transaction, syntheticPayload, remoteSession, banchileStatus);
                String requestIdStr = String.valueOf(requestId);
                java.time.ZonedDateTime now = purchaseContext.now(clockProvider);
                transactionRepository.update(
                    transaction.getId(),
                    requestIdStr, requestIdStr,
                    now,
                    transaction.getPlatformFee(), transaction.getGatewayFee(),
                    alfioStatus,
                    metadata
                );
                log.info("forceTransactionCheck Banchile {} — reserva={} requestId={}",
                    alfioStatus, reservation.getId(), requestIdStr);
            }

            return switch (alfioStatus) {
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
     * Construye el {@code metadata Map<String,String>} que se persiste en
     * {@code b_transaction.metadata} (columna jsonb) con prefijo {@code banchile_*}.
     *
     * <p>Preserva todo metadata previamente persistido en {@link Transaction#getMetadata()}
     * y agrega los campos extraídos del payload del webhook + la respuesta de
     * {@code querySession} (ground truth remoto). Si el primer {@code PaymentDetails}
     * APPROVED no existe (rechazo), cae al primer intento de pago disponible.
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

        // PaymentDetails: preferir el APPROVED, sino caer al primero disponible (útil para rechazos)
        SessionResponse.PaymentDetails pd = null;
        if (remoteSession != null) {
            pd = remoteSession.firstApprovedPayment();
            if (pd == null && remoteSession.payment() != null && !remoteSession.payment().isEmpty()) {
                pd = remoteSession.payment().get(0);
            }
        }
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
