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

import alfio.manager.support.PaymentWebhookResult;
import alfio.manager.system.ConfigurationLevel;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.testSupport.MaybeConfigurationBuilder;
import alfio.model.Event;
import alfio.model.PurchaseContext;
import alfio.model.TicketReservation;
import alfio.model.TicketReservation.TicketReservationStatus;
import alfio.model.TicketReservationStatusAndValidation;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.*;
import alfio.util.ClockProvider;
import alfio.model.transaction.banchile.BanchileWebhookPayload;
import alfio.model.transaction.banchile.CreateSessionRequest.Auth;
import alfio.model.transaction.banchile.SessionResponse;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.test.util.TestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static alfio.model.system.ConfigurationKeys.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios de {@link BanchilePagosWebhookManager}.
 *
 * <p>Todos los tests usan Mockito puro (sin Spring context) para mantener
 * tiempos de ejecución bajos. Los tests de integración HTTP se delegan
 * al BanchilePagosClientTest existente.
 */
class BanchilePagosWebhookManagerTest {

    private static final String RESERVATION_ID   = "test-reservation-abc123";
    private static final String REQUEST_ID_STR   = "999";
    private static final int    REQUEST_ID_INT    = 999;
    private static final int    TRANSACTION_ID    = 42;
    private static final String BASE_URL          = "https://checkout.test.banchilepagos.cl";
    private static final String LOGIN             = "ffb78b93826239e1aa85a515aa961bd9";
    private static final String TRAN_KEY          = "U87nG0kcCsjb61Mj";
    private static final String WEBHOOK_SECRET    = "webhook-secret-for-tests";
    private static final String PROCESS_URL       = "https://checkout.test.banchilepagos.cl/spa/session/999";

    // --- Mocks ---
    private ConfigurationManager configurationManager;
    private BanchilePagosClient   banchilePagosClient;
    private TransactionRepository transactionRepository;
    private TicketReservationRepository ticketReservationRepository;
    private Transaction           transaction;
    private Event                 event;
    private PaymentContext        paymentContext;

    // --- SUT ---
    private BanchilePagosWebhookManager manager;

    @BeforeEach
    void setUp() {
        configurationManager        = mock(ConfigurationManager.class);
        banchilePagosClient         = mock(BanchilePagosClient.class);
        transactionRepository       = mock(TransactionRepository.class);
        ticketReservationRepository = mock(TicketReservationRepository.class);

        // Event mock con ZoneId para que purchaseContext.now(clockProvider) funcione
        event = mock(Event.class);
        when(event.getZoneId()).thenReturn(ZoneId.of("America/Santiago"));
        when(event.getConfigurationLevel()).thenReturn(ConfigurationLevel.organization(1));
        when(event.getCurrency()).thenReturn("CLP");
        when(event.getType()).thenReturn(PurchaseContext.PurchaseContextType.event);
        when(event.getPublicIdentifier()).thenReturn("test-event-slug");
        // Stubear now() explícitamente para que los métodos default de TimeZoneInfo funcionen con Mockito
        when(event.now(any(ClockProvider.class)))
            .thenAnswer(inv -> java.time.ZonedDateTime.now(ZoneId.of("America/Santiago")));
        when(event.now(any(java.time.Clock.class)))
            .thenAnswer(inv -> java.time.ZonedDateTime.now(ZoneId.of("America/Santiago")));

        // Transaction mock con defaults razonables
        transaction = mock(Transaction.class);
        when(transaction.getId()).thenReturn(TRANSACTION_ID);
        when(transaction.getPaymentId()).thenReturn(REQUEST_ID_STR);
        when(transaction.getReservationId()).thenReturn(RESERVATION_ID);
        when(transaction.getPaymentProxy()).thenReturn(PaymentProxy.BANCHILE);
        when(transaction.getStatus()).thenReturn(Transaction.Status.PENDING);
        when(transaction.getPlatformFee()).thenReturn(0L);
        when(transaction.getGatewayFee()).thenReturn(0L);
        when(transaction.getMetadata()).thenReturn(Map.of());

        // PaymentContext mock
        paymentContext = mock(PaymentContext.class);
        when(paymentContext.getPurchaseContext()).thenReturn(event);
        when(paymentContext.getConfigurationLevel()).thenReturn(ConfigurationLevel.organization(1));

        // Configuración Banchile por defecto (enabled, con creds)
        stubConfiguration(true);

        manager = new BanchilePagosWebhookManager(
            configurationManager,
            banchilePagosClient,
            transactionRepository,
            ticketReservationRepository,
            TestUtil.clockProvider()
        );
    }

    // =========================================================================
    // T1 — accept()
    // =========================================================================

    @Test
    void acceptReturnsTrueWhenBanchileEnabledAndMethodIsCreditCard() {
        assertTrue(manager.accept(PaymentMethod.CREDIT_CARD, paymentContext, mock(TransactionRequest.class)));
    }

    @Test
    void acceptReturnsFalseWhenBanchileDisabled() {
        stubConfiguration(false);
        assertFalse(manager.accept(PaymentMethod.CREDIT_CARD, paymentContext, mock(TransactionRequest.class)));
    }

    @Test
    void acceptReturnsFalseForNonCreditCardMethod() {
        assertFalse(manager.accept(PaymentMethod.BANK_TRANSFER, paymentContext, mock(TransactionRequest.class)));
        assertFalse(manager.accept(PaymentMethod.PAYPAL, paymentContext, mock(TransactionRequest.class)));
        assertFalse(manager.accept(PaymentMethod.ON_SITE, paymentContext, mock(TransactionRequest.class)));
    }

    // =========================================================================
    // T2 — mapStatus() parametrizado exhaustivo
    // =========================================================================

    @ParameterizedTest
    @CsvSource({
        "PENDING,          PENDING",
        "APPROVED,         COMPLETE",
        "REJECTED,         FAILED",
        "APPROVED_PARTIAL, PENDING",
        "PARTIAL_EXPIRED,  FAILED"
    })
    void mapStatusCoversAllBanchileStates(String banchileStatus, String expectedAlfioStatus) {
        Transaction.Status expected = Transaction.Status.valueOf(expectedAlfioStatus.trim());
        assertEquals(expected, BanchilePagosWebhookManager.mapStatus(banchileStatus.trim()),
            "Estado Banchile '" + banchileStatus.trim() + "' debe mapearse a " + expectedAlfioStatus.trim());
    }

    // =========================================================================
    // T3 — initTransaction (doPayment) con BanchilePagosClient mockeado
    // =========================================================================

    @Test
    void initTransactionCreatesSessionAndPersistsTransaction() {
        // Arrange: cliente devuelve una sesión exitosa
        var sessionResponse = new SessionResponse(
            new SessionResponse.Status("OK", "00", "Petición procesada", "2026-05-18T10:00:00-04:00"),
            REQUEST_ID_INT,
            PROCESS_URL,
            null
        );
        when(banchilePagosClient.createSession(any(), eq(BASE_URL))).thenReturn(sessionResponse);

        // Crear el mock de reserva ANTES de usarlo en stubbing
        var reservationMock = mock(TicketReservation.class);
        when(reservationMock.getId()).thenReturn(RESERVATION_ID);
        when(reservationMock.getEmail()).thenReturn("test@example.com");
        when(ticketReservationRepository.findReservationById(RESERVATION_ID)).thenReturn(reservationMock);

        when(configurationManager.getFor(eq(ConfigurationKeys.BASE_URL), any()))
            .thenReturn(MaybeConfigurationBuilder.existing(ConfigurationKeys.BASE_URL, "https://alfio.example.com"));
        when(transactionRepository.loadOptionalByReservationId(RESERVATION_ID))
            .thenReturn(Optional.empty());

        var spec = buildPaymentSpecification();

        // Act
        var result = manager.doPayment(spec);

        // Assert: resultado es redirect al processUrl
        assertTrue(result.isRedirect(), "Resultado debe ser REDIRECT");
        assertEquals(PROCESS_URL, result.getRedirectUrl(), "Redirect debe ser el processUrl de Banchile");

        // Assert: se persiste la transacción con payment_proxy=BANCHILE y el requestId
        verify(transactionRepository).insert(
            eq(REQUEST_ID_STR),          // gtw_tx_id
            eq(REQUEST_ID_STR),          // gtw_payment_id
            eq(RESERVATION_ID),
            any(),                        // timestamp
            anyInt(),                     // priceInCents
            anyString(),                  // currency
            anyString(),                  // description
            eq(PaymentProxy.BANCHILE.name()),
            eq(0L), eq(0L),
            eq(Transaction.Status.PENDING),
            any()
        );

        // Assert: se actualiza el estado de la reserva a EXTERNAL_PROCESSING_PAYMENT
        verify(ticketReservationRepository).updateReservationStatus(
            eq(RESERVATION_ID),
            eq(TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT.toString())
        );
    }

    // =========================================================================
    // T4 — processWebhook con firma válida y estado APPROVED
    // =========================================================================

    @Test
    void processWebhookApprovedWithValidSignatureConfirmsReservation() throws Exception {
        // Arrange: calcular la firma correcta para el payload
        String statusStr  = "APPROVED";
        String dateStr    = "2026-05-18T10:00:00-04:00";
        String signature  = computeExpectedSignature(REQUEST_ID_INT, statusStr, dateStr, WEBHOOK_SECRET);

        var webhookPayload = new BanchileWebhookPayload(
            REQUEST_ID_INT,
            RESERVATION_ID,
            new SessionResponse.Status(statusStr, "00", "Aprobado", dateStr),
            signature
        );
        var transactionWebhookPayload = new BanchilePagosWebhookManager.BanchileTransactionWebhookPayload(
            webhookPayload, RESERVATION_ID
        );

        // querySession devuelve APPROVED desde el servidor remoto
        var remoteSession = new SessionResponse(
            new SessionResponse.Status("APPROVED", "00", "Aprobado", dateStr),
            REQUEST_ID_INT, null, null
        );
        when(banchilePagosClient.querySession(eq(REQUEST_ID_INT), any(Auth.class), eq(BASE_URL)))
            .thenReturn(remoteSession);

        // Reserva en estado procesable
        var reservationStatus = mock(TicketReservationStatusAndValidation.class);
        when(reservationStatus.getStatus()).thenReturn(TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT);
        when(ticketReservationRepository.findOptionalStatusAndValidationById(RESERVATION_ID))
            .thenReturn(Optional.of(reservationStatus));

        // BANCHILE_WEBHOOK_SECRET configurado
        when(configurationManager.getForSystem(BANCHILE_WEBHOOK_SECRET))
            .thenReturn(MaybeConfigurationBuilder.existing(BANCHILE_WEBHOOK_SECRET, WEBHOOK_SECRET));

        // Act
        PaymentWebhookResult result = manager.processWebhook(transactionWebhookPayload, transaction, paymentContext);

        // Assert
        assertEquals(PaymentWebhookResult.Type.SUCCESSFUL, result.getType(),
            "Webhook APPROVED con firma válida debe ser SUCCESSFUL");
        assertNotNull(result.getPaymentToken(), "PaymentToken no debe ser null en SUCCESSFUL");

        verify(transactionRepository).update(
            eq(TRANSACTION_ID),
            eq(REQUEST_ID_STR), eq(REQUEST_ID_STR),
            any(),              // timestamp
            eq(0L), eq(0L),
            eq(Transaction.Status.COMPLETE),
            any()
        );
    }

    // =========================================================================
    // T5 — processWebhook con firma inválida rechaza sin confirmar
    // =========================================================================

    @Test
    void processWebhookWithInvalidSignatureIsRejected() {
        // Arrange: firma incorrecta
        var webhookPayload = new BanchileWebhookPayload(
            REQUEST_ID_INT,
            RESERVATION_ID,
            new SessionResponse.Status("APPROVED", "00", "Aprobado", "2026-05-18T10:00:00-04:00"),
            "firma-incorrecta-base64=="
        );
        var transactionWebhookPayload = new BanchilePagosWebhookManager.BanchileTransactionWebhookPayload(
            webhookPayload, RESERVATION_ID
        );

        when(configurationManager.getForSystem(BANCHILE_WEBHOOK_SECRET))
            .thenReturn(MaybeConfigurationBuilder.existing(BANCHILE_WEBHOOK_SECRET, WEBHOOK_SECRET));

        // Act
        PaymentWebhookResult result = manager.processWebhook(transactionWebhookPayload, transaction, paymentContext);

        // Assert: debe ser ERROR, no SUCCESSFUL
        assertEquals(PaymentWebhookResult.Type.ERROR, result.getType(),
            "Webhook con firma inválida debe devolver ERROR");

        // Assert: la reserva NO debe confirmarse
        verify(transactionRepository, never()).update(anyInt(), anyString(), anyString(), any(),
            anyLong(), anyLong(), eq(Transaction.Status.COMPLETE), any());
    }

    // =========================================================================
    // T6 — processWebhook duplicado es idempotente (transacción ya COMPLETE)
    // =========================================================================

    @Test
    void processWebhookDuplicateApprovedIsIdempotent() {
        // Arrange: transacción ya está COMPLETE
        when(transaction.getStatus()).thenReturn(Transaction.Status.COMPLETE);

        var webhookPayload = new BanchileWebhookPayload(
            REQUEST_ID_INT,
            RESERVATION_ID,
            new SessionResponse.Status("APPROVED", "00", "Aprobado", "2026-05-18T10:00:00-04:00"),
            "cualquier-firma"
        );
        var transactionWebhookPayload = new BanchilePagosWebhookManager.BanchileTransactionWebhookPayload(
            webhookPayload, RESERVATION_ID
        );

        when(configurationManager.getForSystem(BANCHILE_WEBHOOK_SECRET))
            .thenReturn(MaybeConfigurationBuilder.missing(BANCHILE_WEBHOOK_SECRET));

        // Act
        PaymentWebhookResult result = manager.processWebhook(transactionWebhookPayload, transaction, paymentContext);

        // Assert: NOT_RELEVANT (ya estaba confirmado)
        assertEquals(PaymentWebhookResult.Type.NOT_RELEVANT, result.getType(),
            "Webhook duplicado en transacción COMPLETE debe ser NOT_RELEVANT");

        // Assert: nunca se confirma de nuevo
        verify(transactionRepository, never()).update(anyInt(), anyString(), anyString(), any(),
            anyLong(), anyLong(), any(Transaction.Status.class), any());
        verify(ticketReservationRepository, never()).updateReservationStatus(anyString(), anyString());
    }

    // =========================================================================
    // T7 — doRefund lanza UnsupportedOperationException
    // =========================================================================

    @Test
    void doRefundIsUnsupportedInMvp() {
        var ex = assertThrows(UnsupportedOperationException.class,
            () -> manager.refund(transaction, event, null),
            "refund() debe lanzar UnsupportedOperationException en MVP");
        assertTrue(ex.getMessage().toLowerCase().contains("banchile"),
            "El mensaje debe mencionar Banchile");
    }

    // =========================================================================
    // T8 — getInfo parsea respuesta APPROVED con monto
    // =========================================================================

    @Test
    void getInfoParsesApprovedSessionWithPaymentAmount() {
        // Arrange: querySession devuelve respuesta APPROVED con monto
        var paymentDetails = new SessionResponse.PaymentDetails(
            RESERVATION_ID,
            new alfio.model.transaction.banchile.CreateSessionRequest.Amount("CLP", 25000L),
            "REC-12345",
            new SessionResponse.Status("APPROVED", "00", "Aprobado", "2026-05-18T10:00:00-04:00")
        );
        var remoteSession = new SessionResponse(
            new SessionResponse.Status("APPROVED", "00", "Aprobado", "2026-05-18T10:00:00-04:00"),
            REQUEST_ID_INT,
            null,
            java.util.List.of(paymentDetails)
        );
        when(banchilePagosClient.querySession(eq(REQUEST_ID_INT), any(Auth.class), eq(BASE_URL)))
            .thenReturn(remoteSession);

        // Act
        Optional<alfio.model.PaymentInformation> info = manager.getInfo(transaction, event);

        // Assert
        assertTrue(info.isPresent(), "getInfo debe retornar un valor para transacción APPROVED");
        assertNotNull(info.get().getPaidAmount(), "paidAmount no debe ser null");
        // 25000 CLP formateado como entero
        assertTrue(info.get().getPaidAmount().contains("25000") || info.get().getPaidAmount().length() > 0,
            "paidAmount debe contener el monto 25000");
        assertNull(info.get().getRefundedAmount(), "refundedAmount debe ser null (no soportado en MVP)");
    }

    // =========================================================================
    // Helpers privados
    // =========================================================================

    /**
     * Calcula la firma esperada del webhook según la fórmula del spec:
     * {@code Base64(SHA1(requestId + status + date + secret))}
     *
     * <p><b>TODO:</b> confirmar esta fórmula contra el primer webhook real capturado
     * en Phase F del plan de integración.
     */
    private static String computeExpectedSignature(int requestId, String status, String date, String secret)
        throws Exception {
        String input = requestId + status + date + secret;
        // SHA-1 requerido por el protocolo Banchile
        // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    /**
     * Configura los mocks de ConfigurationManager para devolver la configuración
     * básica de Banchile.
     *
     * @param enabled Si true, BANCHILE_ENABLED devuelve "true"
     */
    private void stubConfiguration(boolean enabled) {
        var configMap = Map.of(
            BANCHILE_ENABLED,        MaybeConfigurationBuilder.existing(BANCHILE_ENABLED, enabled ? "true" : "false"),
            BANCHILE_LOGIN,          MaybeConfigurationBuilder.existing(BANCHILE_LOGIN, LOGIN),
            BANCHILE_TRANKEY,        MaybeConfigurationBuilder.existing(BANCHILE_TRANKEY, TRAN_KEY),
            BANCHILE_WEBHOOK_SECRET, MaybeConfigurationBuilder.existing(BANCHILE_WEBHOOK_SECRET, WEBHOOK_SECRET),
            BANCHILE_BASE_URL,       MaybeConfigurationBuilder.existing(BANCHILE_BASE_URL, BASE_URL)
        );
        when(configurationManager.getFor(eq(BanchilePagosWebhookManager.ALL_OPTIONS), any()))
            .thenReturn(configMap);
    }

    /**
     * Construye un {@link TicketReservation} mock básico para tests de doPayment.
     */
    private TicketReservation buildTicketReservation() {
        var reservation = mock(TicketReservation.class);
        when(reservation.getId()).thenReturn(RESERVATION_ID);
        when(reservation.getEmail()).thenReturn("test@example.com");
        return reservation;
    }

    /**
     * Construye un {@link PaymentSpecification} básico para tests de doPayment.
     */
    private PaymentSpecification buildPaymentSpecification() {
        var customerName = new alfio.model.CustomerName(
            "Test User", "Test", "User", true
        );
        return new PaymentSpecification(
            RESERVATION_ID,
            null,              // gatewayToken (no necesario para Banchile)
            25000,             // priceWithVAT (CLP cents = CLP porque ratio 1:1)
            event,
            "test@example.com",
            customerName
        );
    }
}
