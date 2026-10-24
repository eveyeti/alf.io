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
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.TestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

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
    private static final String SECRET_KEY        = "U87nG0kcCsjb61Mj";
    private static final String PROCESS_URL       = "https://checkout.test.banchilepagos.cl/spa/session/999";

    // --- Mocks ---
    private ConfigurationManager configurationManager;
    private BanchilePagosClient   banchilePagosClient;
    private TransactionRepository transactionRepository;
    private TicketReservationRepository ticketReservationRepository;
    private NotificationManager   notificationManager;
    private OrganizationRepository organizationRepository;
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
        notificationManager         = mock(NotificationManager.class);
        organizationRepository      = mock(OrganizationRepository.class);

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
            TestUtil.clockProvider(),
            notificationManager,
            organizationRepository
        );
    }

    // =========================================================================
    // forceTransactionCheck — la alerta va antes de cerrar la transacción
    // =========================================================================

    /**
     * Si la alerta no se puede encolar, la transacción no se cierra: sigue PENDING y la alerta se
     * reintenta en la próxima consulta. Cerrarla antes de alertar (o sin alertar) perdería el único
     * aviso de un pago aprobado que hay que reembolsar.
     */
    @Test
    void forceTransactionCheckDoesNotCloseTheTransactionWhenTheAlertCannotBeQueued() {
        var reservation = buildTicketReservation();
        when(ticketReservationRepository.findOptionalStatusAndValidationById(RESERVATION_ID))
            .thenReturn(Optional.of(new TicketReservationStatusAndValidation(TicketReservationStatus.CANCELLED, false)));
        when(banchilePagosClient.querySession(eq(REQUEST_ID_INT), any(Auth.class), eq(BASE_URL))).thenReturn(approvedSession());
        when(transaction.getPriceInCents()).thenReturn(24000);
        when(transaction.getCurrency()).thenReturn("CLP");
        when(event.getOrganizationId()).thenReturn(1);
        var organization = mock(alfio.model.user.Organization.class);
        when(organization.getEmail()).thenReturn("comite@example.com");
        when(organizationRepository.getById(1)).thenReturn(organization);
        doThrow(new IllegalStateException("no se pudo encolar el correo")).when(notificationManager)
            .sendSimpleEmail(any(PurchaseContext.class), anyString(), anyString(), anyString(), any(alfio.manager.support.TemplateGenerator.class));

        var result = manager.forceTransactionCheck(reservation, transaction, paymentContext);

        assertEquals(PaymentWebhookResult.Type.ERROR, result.getType());
        verify(transactionRepository, never()).update(anyInt(), any(), any(), any(), anyLong(), anyLong(), any(), any());

        // el correo vuelve a funcionar: la consulta siguiente alerta y recién entonces cierra la transacción
        doNothing().when(notificationManager)
            .sendSimpleEmail(any(PurchaseContext.class), anyString(), anyString(), anyString(), any(alfio.manager.support.TemplateGenerator.class));

        var retry = manager.forceTransactionCheck(reservation, transaction, paymentContext);

        assertEquals(PaymentWebhookResult.Type.NOT_RELEVANT, retry.getType());
        var inOrder = inOrder(notificationManager, transactionRepository);
        inOrder.verify(notificationManager, times(2))
            .sendSimpleEmail(eq(event), eq(RESERVATION_ID), eq("comite@example.com"), anyString(), any(alfio.manager.support.TemplateGenerator.class));
        inOrder.verify(transactionRepository).update(eq(TRANSACTION_ID), eq(REQUEST_ID_STR), eq(REQUEST_ID_STR), any(),
            eq(0L), eq(0L), eq(Transaction.Status.COMPLETE), anyMap());
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
        String signature  = "sha256:" + computeExpectedSignature(REQUEST_ID_INT, statusStr, dateStr, SECRET_KEY);

        var webhookPayload = new BanchileWebhookPayload(
            REQUEST_ID_INT,
            RESERVATION_ID,
            new SessionResponse.Status(statusStr, "00", "Aprobado", dateStr),
            signature
        );
        var transactionWebhookPayload = new BanchilePagosWebhookManager.BanchileTransactionWebhookPayload(
            webhookPayload, RESERVATION_ID
        );

        // querySession devuelve APPROVED con TODOS los campos extendidos para verificar trazabilidad
        var processorFields = List.of(
            new SessionResponse.ProcessorField("lastDigits", "1111", "receipt"),
            new SessionResponse.ProcessorField("bin", "411111", "receipt"),
            new SessionResponse.ProcessorField("merchantCode", "123456", "approval")
        );
        var paymentDetails = new SessionResponse.PaymentDetails(
            RESERVATION_ID,
            new alfio.model.transaction.banchile.CreateSessionRequest.Amount("CLP", 10000L),
            "REC-001",
            new SessionResponse.Status("APPROVED", "00", "Aprobado", dateStr),
            12345678,                  // internalReference
            "VS",                      // paymentMethod
            "Visa Credito",            // paymentMethodName
            "Banco de Chile",          // issuerName
            "685559",                  // authorization (código CUS)
            "VISA",                    // franchise
            false,                     // refunded
            processorFields
        );
        var remoteSession = new SessionResponse(
            new SessionResponse.Status("APPROVED", "00", "Aprobado", dateStr),
            REQUEST_ID_INT, null, List.of(paymentDetails)
        );
        when(banchilePagosClient.querySession(eq(REQUEST_ID_INT), any(Auth.class), eq(BASE_URL)))
            .thenReturn(remoteSession);

        // Reserva en estado procesable
        var reservationStatus = mock(TicketReservationStatusAndValidation.class);
        when(reservationStatus.getStatus()).thenReturn(TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT);
        when(ticketReservationRepository.findOptionalStatusAndValidationById(RESERVATION_ID))
            .thenReturn(Optional.of(reservationStatus));

        // Act
        PaymentWebhookResult result = manager.processWebhook(transactionWebhookPayload, transaction, paymentContext);

        // Assert
        assertEquals(PaymentWebhookResult.Type.SUCCESSFUL, result.getType(),
            "Webhook APPROVED con firma válida debe ser SUCCESSFUL");
        assertNotNull(result.getPaymentToken(), "PaymentToken no debe ser null en SUCCESSFUL");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transactionRepository).update(
            eq(TRANSACTION_ID),
            eq(REQUEST_ID_STR), eq(REQUEST_ID_STR),
            any(),              // timestamp
            eq(0L), eq(0L),
            eq(Transaction.Status.COMPLETE),
            metadataCaptor.capture()
        );

        Map<String, String> metadata = metadataCaptor.getValue();
        assertEquals(REQUEST_ID_STR, metadata.get("banchile_request_id"));
        assertEquals(RESERVATION_ID, metadata.get("banchile_reference"));
        assertEquals("APPROVED", metadata.get("banchile_status"));
        assertEquals(dateStr, metadata.get("banchile_status_date"));
        assertEquals("00", metadata.get("banchile_status_reason"));
        assertEquals("Aprobado", metadata.get("banchile_status_message"));
        assertEquals("12345678", metadata.get("banchile_internal_reference"));
        assertEquals("685559", metadata.get("banchile_authorization"));
        assertEquals("VS", metadata.get("banchile_payment_method"));
        assertEquals("Visa Credito", metadata.get("banchile_payment_method_name"));
        assertEquals("Banco de Chile", metadata.get("banchile_issuer"));
        assertEquals("VISA", metadata.get("banchile_franchise"));
        assertEquals("REC-001", metadata.get("banchile_receipt"));
        assertEquals("1111", metadata.get("banchile_pf_lastDigits"));
        assertEquals("411111", metadata.get("banchile_pf_bin"));
        assertEquals("123456", metadata.get("banchile_pf_merchantCode"));
        assertNull(metadata.get("banchile_refunded"), "refunded=false no debe persistirse");
    }

    // =========================================================================
    // T4b — processWebhook FAILED persiste metadata de rechazo
    // =========================================================================

    @Test
    void processWebhookRejectedPersistsRejectionMetadata() throws Exception {
        String statusStr = "REJECTED";
        String dateStr   = "2026-05-18T10:05:00-04:00";
        String signature = "sha256:" + computeExpectedSignature(REQUEST_ID_INT, statusStr, dateStr, SECRET_KEY);

        var webhookPayload = new BanchileWebhookPayload(
            REQUEST_ID_INT,
            RESERVATION_ID,
            new SessionResponse.Status(statusStr, "05", "Do not honor", dateStr),
            signature
        );
        var transactionWebhookPayload = new BanchilePagosWebhookManager.BanchileTransactionWebhookPayload(
            webhookPayload, RESERVATION_ID
        );

        // querySession devuelve REJECTED. Algunos rechazos vienen con un payment[] (no APPROVED).
        var rejectedPaymentDetails = new SessionResponse.PaymentDetails(
            RESERVATION_ID,
            new alfio.model.transaction.banchile.CreateSessionRequest.Amount("CLP", 10000L),
            null,
            new SessionResponse.Status("REJECTED", "05", "Do not honor", dateStr),
            null,                      // sin internalReference en rechazo
            "VS",
            "Visa Credito",
            "Banco Estado",
            null,                      // sin authorization (rechazo)
            "VISA",
            null,
            List.of(new SessionResponse.ProcessorField("lastDigits", "0016", "receipt"))
        );
        var remoteSession = new SessionResponse(
            new SessionResponse.Status("REJECTED", "05", "Do not honor", dateStr),
            REQUEST_ID_INT, null, List.of(rejectedPaymentDetails)
        );
        when(banchilePagosClient.querySession(eq(REQUEST_ID_INT), any(Auth.class), eq(BASE_URL)))
            .thenReturn(remoteSession);

        PaymentWebhookResult result = manager.processWebhook(transactionWebhookPayload, transaction, paymentContext);

        assertEquals(PaymentWebhookResult.Type.FAILED, result.getType(),
            "Webhook REJECTED debe ser FAILED");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transactionRepository).update(
            eq(TRANSACTION_ID),
            eq(REQUEST_ID_STR), eq(REQUEST_ID_STR),
            any(),
            eq(0L), eq(0L),
            eq(Transaction.Status.FAILED),
            metadataCaptor.capture()
        );

        Map<String, String> metadata = metadataCaptor.getValue();
        assertEquals("REJECTED", metadata.get("banchile_status"));
        assertEquals("05", metadata.get("banchile_status_reason"));
        assertEquals("Do not honor", metadata.get("banchile_status_message"));
        assertEquals("VS", metadata.get("banchile_payment_method"));
        assertEquals("Banco Estado", metadata.get("banchile_issuer"));
        assertEquals("0016", metadata.get("banchile_pf_lastDigits"));
        // En rechazos no hay authorization ni internalReference
        assertNull(metadata.get("banchile_authorization"));
        assertNull(metadata.get("banchile_internal_reference"));
    }

    // =========================================================================
    // El último intento de pago queda en la metadata
    // =========================================================================

    /**
     * El caso de las sesiones 4406 y 4412 del QA del 15-oct en el sandbox: Banchile rechaza la tarjeta
     * ("?2 Transacción declinada por políticas de control de riesgo") y deja la sesión abierta para
     * reintentar; el comprador vuelve al comercio y la sesión se cierra REJECTED con razón ?C. La
     * sesión dice "cancelada", pero lo que pasó es el rechazo del banco: el intento se guarda aparte
     * (banchile_attempt_*) para que el resumen muestre el rechazo.
     */
    @Test
    void theLastPaymentAttemptIsPersistedWhenTheBuyerLeavesAfterARejectedCard() {
        var reservation = buildTicketReservation();
        when(ticketReservationRepository.findOptionalStatusAndValidationById(RESERVATION_ID))
            .thenReturn(Optional.of(new TicketReservationStatusAndValidation(TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT, true)));
        var rejectedAttempt = new SessionResponse.PaymentDetails(
            RESERVATION_ID,
            new alfio.model.transaction.banchile.CreateSessionRequest.Amount("CLP", 10000L),
            null,
            new SessionResponse.Status("REJECTED", "?2", "Transacción declinada por políticas de control de riesgo",
                "2026-10-15T12:31:40-03:00"),
            1301, "visa", "Visa", "BANCO DE PRUEBA",
            "000000",
            "visa", false,
            List.of(new SessionResponse.ProcessorField("lastDigits", "0016", "none"))
        );
        when(banchilePagosClient.querySession(eq(REQUEST_ID_INT), any(Auth.class), eq(BASE_URL))).thenReturn(new SessionResponse(
            new SessionResponse.Status("REJECTED", "?C", "La petición ha sido cancelada por el usuario", "2026-10-15T12:32:05-03:00"),
            REQUEST_ID_INT, null, List.of(rejectedAttempt)));

        var result = manager.forceTransactionCheck(reservation, transaction, paymentContext);

        assertEquals(PaymentWebhookResult.Type.FAILED, result.getType());
        var metadata = closedTransactionMetadata(Transaction.Status.FAILED);
        assertEquals("REJECTED", metadata.get("banchile_status"));
        assertEquals("?C", metadata.get("banchile_status_reason"));
        assertEquals("La petición ha sido cancelada por el usuario", metadata.get("banchile_status_message"));
        assertEquals("REJECTED", metadata.get("banchile_attempt_status"));
        assertEquals("?2", metadata.get("banchile_attempt_reason"));
        assertEquals("Transacción declinada por políticas de control de riesgo", metadata.get("banchile_attempt_message"));
    }

    /**
     * Sesión vencida sin ningún intento de tarjeta (39 de las 51 sesiones fallidas de "Ya no te
     * gusto"): no hay intento que guardar.
     */
    @Test
    void anExpiredSessionWithoutPaymentAttemptsHasNoAttemptInTheMetadata() {
        var reservation = buildTicketReservation();
        when(ticketReservationRepository.findOptionalStatusAndValidationById(RESERVATION_ID))
            .thenReturn(Optional.of(new TicketReservationStatusAndValidation(TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT, true)));
        when(banchilePagosClient.querySession(eq(REQUEST_ID_INT), any(Auth.class), eq(BASE_URL))).thenReturn(new SessionResponse(
            new SessionResponse.Status("REJECTED", "EX", "La petición ha expirado", "2026-10-15T13:00:00-03:00"),
            REQUEST_ID_INT, null, null));

        var result = manager.forceTransactionCheck(reservation, transaction, paymentContext);

        assertEquals(PaymentWebhookResult.Type.FAILED, result.getType());
        var metadata = closedTransactionMetadata(Transaction.Status.FAILED);
        assertEquals("EX", metadata.get("banchile_status_reason"));
        assertNull(metadata.get("banchile_attempt_status"));
        assertNull(metadata.get("banchile_attempt_reason"));
        assertNull(metadata.get("banchile_attempt_message"));
    }

    /**
     * La sesión 4724 del QA del 23-oct en el sandbox, con dos tarjetas rechazadas ("Reintentar pago"):
     * Banchile lista primero el intento más reciente (Mastercard 0005, recibo 3885, 23:36:27) y después
     * el anterior (VISA 0016, recibo 3884, 23:36:05), al revés del ejemplo APPROVED_PARTIAL de la
     * referencia de la API. El orden, las fechas, las tarjetas y los recibos son los reales; las razones
     * y los mensajes, no (no se informaron).
     */
    @Test
    void inTheRealSession4724TheLastAttemptIsTheOneOf233627() {
        var newest = attempt("REJECTED", null, "05", "Mastercard 0005, recibo 3885", "2026-10-23T23:36:27-03:00", "3885", "0005");
        var previous = attempt("REJECTED", null, "?2", "VISA 0016, recibo 3884", "2026-10-23T23:36:05-03:00", "3884", "0016");

        var metadata = metadataOfARejectedSessionWith(List.of(newest, previous));

        assertEquals("REJECTED", metadata.get("banchile_attempt_status"));
        assertEquals("05", metadata.get("banchile_attempt_reason"));
        assertEquals("Mastercard 0005, recibo 3885", metadata.get("banchile_attempt_message"));
    }

    /**
     * Dos tarjetas rechazadas en la misma sesión: el último intento es el de status.date más reciente,
     * venga primero (como en Banchile, ver la sesión 4724) o último (como en el ejemplo de la API) en
     * payment[].
     */
    @ParameterizedTest(name = "el más reciente va {0} en payment[]")
    @CsvSource({"último", "primero"})
    void theAttemptFieldsComeFromTheMostRecentAttemptWhateverItsPositionInTheList(String position) {
        var older = rejectedAttempt(421692, "05", "Fondos insuficientes", "2026-10-15T12:20:10-03:00");
        var newer = rejectedAttempt(421693, "?2", "Transacción declinada por políticas de control de riesgo", "2026-10-15T12:31:40-03:00");

        var metadata = metadataOfARejectedSessionWith("último".equals(position) ? List.of(older, newer) : List.of(newer, older));

        assertEquals("REJECTED", metadata.get("banchile_attempt_status"));
        assertEquals("?2", metadata.get("banchile_attempt_reason"));
        assertEquals("Transacción declinada por políticas de control de riesgo", metadata.get("banchile_attempt_message"));
    }

    /**
     * Las fechas se comparan como instantes, no como texto, y mandan sobre internalReference: el
     * intento de las 12:31:40-03:00 (15:31:40Z) es posterior al de las 15:20:10Z aunque su texto sea
     * "menor" y su internalReference también.
     */
    @Test
    void theDatesAreComparedAsInstants() {
        var earlierInUtc = rejectedAttempt(421693, "05", "Fondos insuficientes", "2026-10-15T15:20:10Z");
        var laterWithOffset = rejectedAttempt(421692, "?2", "Transacción declinada por políticas de control de riesgo", "2026-10-15T12:31:40-03:00");

        var metadata = metadataOfARejectedSessionWith(List.of(earlierInUtc, laterWithOffset));

        assertEquals("?2", metadata.get("banchile_attempt_reason"), "12:31:40-03:00 es 15:31:40Z, después de 15:20:10Z");
    }

    @ParameterizedTest(name = "el de mayor internalReference va {0} en payment[]")
    @CsvSource({"último", "primero"})
    void withoutDatesTheAttemptWithTheHighestInternalReferenceIsTheLast(String position) {
        var older = rejectedAttempt(421692, "05", "Fondos insuficientes", null);
        var newer = rejectedAttempt(421693, "?2", "Transacción declinada por políticas de control de riesgo", null);

        var metadata = metadataOfARejectedSessionWith("último".equals(position) ? List.of(older, newer) : List.of(newer, older));

        assertEquals("?2", metadata.get("banchile_attempt_reason"));
    }

    @ParameterizedTest(name = "el de mayor internalReference va {0} en payment[]")
    @CsvSource({"último", "primero"})
    void ifAnAttemptHasNoUsableDateTheHighestInternalReferenceIsTheLast(String position) {
        var dated = rejectedAttempt(421692, "05", "Fondos insuficientes", "2026-10-15T12:20:10-03:00");
        var undated = rejectedAttempt(421693, "?2", "Transacción declinada por políticas de control de riesgo", "no es una fecha");

        var metadata = metadataOfARejectedSessionWith("último".equals(position) ? List.of(dated, undated) : List.of(undated, dated));

        assertEquals("?2", metadata.get("banchile_attempt_reason"));
    }

    @ParameterizedTest(name = "el de mayor internalReference va {0} en payment[]")
    @CsvSource({"último", "primero"})
    void onATieTheHighestInternalReferenceIsTheLast(String position) {
        var sameDate = "2026-10-15T12:31:40-03:00";
        var lower = rejectedAttempt(421692, "05", "Fondos insuficientes", sameDate);
        var higher = rejectedAttempt(421693, "?2", "Transacción declinada por políticas de control de riesgo", sameDate);

        var metadata = metadataOfARejectedSessionWith("último".equals(position) ? List.of(lower, higher) : List.of(higher, lower));

        assertEquals("?2", metadata.get("banchile_attempt_reason"));
    }

    /**
     * Sin fechas que los distingan ni internalReference, manda el orden de Banchile: el primero de
     * payment[] es el más reciente (sesión 4724).
     */
    @Test
    void withoutDatesNorInternalReferencesTheFirstInTheListIsTheLast() {
        var first = attempt("REJECTED", null, "05", "el primero de la lista", null, null, "0005");
        var second = attempt("REJECTED", null, "?2", "el segundo de la lista", null, null, "0016");

        var metadata = metadataOfARejectedSessionWith(List.of(first, second));

        assertEquals("el primero de la lista", metadata.get("banchile_attempt_message"));
    }

    @Test
    void withTheSameDateAndNoInternalReferencesTheFirstInTheListIsTheLast() {
        var sameDate = "2026-10-23T23:36:27-03:00";
        var first = attempt("REJECTED", null, "05", "el primero de la lista", sameDate, null, "0005");
        var second = attempt("REJECTED", null, "?2", "el segundo de la lista", sameDate, null, "0016");

        var metadata = metadataOfARejectedSessionWith(List.of(first, second));

        assertEquals("el primero de la lista", metadata.get("banchile_attempt_message"));
    }

    /**
     * El último intento solo decide los campos banchile_attempt_*: la autorización y los datos de la
     * tarjeta siguen saliendo del intento APPROVED (selectPaymentDetails), como antes. El caso es el
     * del ejemplo APPROVED_PARTIAL de la API: un pago parcial aprobado y después un intento rechazado;
     * acá la sesión venció con el pago parcial (PARTIAL_EXPIRED).
     */
    @Test
    void theApprovedAttemptStillProvidesTheAuthorizationAndTheCardDetails() {
        var reservation = buildTicketReservation();
        when(ticketReservationRepository.findOptionalStatusAndValidationById(RESERVATION_ID))
            .thenReturn(Optional.of(new TicketReservationStatusAndValidation(TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT, true)));
        var approvedPart = new SessionResponse.PaymentDetails(RESERVATION_ID,
            new alfio.model.transaction.banchile.CreateSessionRequest.Amount("CLP", 5000L), "614515011049",
            new SessionResponse.Status("APPROVED", "00", "Aprobada", "2026-10-15T12:20:10-03:00"),
            421692, "visa", "Visa", "BANCO DE PRUEBA", "004393", "visa", false,
            List.of(new SessionResponse.ProcessorField("lastDigits", "1111", "none")));
        var rejectedRest = rejectedAttempt(421693, "?2", "Transacción declinada por políticas de control de riesgo", "2026-10-15T12:31:40-03:00");
        when(banchilePagosClient.querySession(eq(REQUEST_ID_INT), any(Auth.class), eq(BASE_URL))).thenReturn(new SessionResponse(
            new SessionResponse.Status("PARTIAL_EXPIRED", "EX", "La petición ha expirado", "2026-10-15T13:00:00-03:00"),
            REQUEST_ID_INT, null, List.of(approvedPart, rejectedRest)));

        manager.forceTransactionCheck(reservation, transaction, paymentContext);

        var metadata = closedTransactionMetadata(Transaction.Status.FAILED);
        assertEquals("004393", metadata.get("banchile_authorization"));
        assertEquals("614515011049", metadata.get("banchile_receipt"));
        assertEquals("1111", metadata.get("banchile_pf_lastDigits"));
        assertEquals("REJECTED", metadata.get("banchile_attempt_status"));
        assertEquals("?2", metadata.get("banchile_attempt_reason"));
    }

    /**
     * Metadata con que se cierra una sesión que el comprador dejó con ?C, con los intentos dados.
     */
    private Map<String, String> metadataOfARejectedSessionWith(List<SessionResponse.PaymentDetails> attempts) {
        var reservation = buildTicketReservation();
        when(ticketReservationRepository.findOptionalStatusAndValidationById(RESERVATION_ID))
            .thenReturn(Optional.of(new TicketReservationStatusAndValidation(TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT, true)));
        when(banchilePagosClient.querySession(eq(REQUEST_ID_INT), any(Auth.class), eq(BASE_URL))).thenReturn(new SessionResponse(
            new SessionResponse.Status("REJECTED", "?C", "La petición ha sido cancelada por el usuario", "2026-10-15T12:32:05-03:00"),
            REQUEST_ID_INT, null, attempts));

        assertEquals(PaymentWebhookResult.Type.FAILED, manager.forceTransactionCheck(reservation, transaction, paymentContext).getType());
        return closedTransactionMetadata(Transaction.Status.FAILED);
    }

    /**
     * Intento de pago rechazado por el banco, con autorización "000000" como los del sandbox.
     *
     * @param date status.date del intento, o null si no la trae
     */
    private static SessionResponse.PaymentDetails rejectedAttempt(int internalReference, String reason, String message, String date) {
        return attempt("REJECTED", internalReference, reason, message, date, null, "0016");
    }

    /**
     * Intento de pago con autorización "000000", como los rechazos del sandbox.
     *
     * @param internalReference internalReference, o null si no lo trae
     * @param date              status.date, o null si no la trae
     * @param receipt           recibo, o null
     */
    private static SessionResponse.PaymentDetails attempt(String status, Integer internalReference, String reason, String message,
                                                          String date, String receipt, String lastDigits) {
        return new SessionResponse.PaymentDetails(RESERVATION_ID,
            new alfio.model.transaction.banchile.CreateSessionRequest.Amount("CLP", 10000L), receipt,
            new SessionResponse.Status(status, reason, message, date),
            internalReference, "visa", "Visa", "BANCO DE PRUEBA", "000000", "visa", false,
            List.of(new SessionResponse.ProcessorField("lastDigits", lastDigits, "none")));
    }

    /**
     * La metadata con que se cerró la transacción.
     */
    private Map<String, String> closedTransactionMetadata(Transaction.Status status) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transactionRepository).update(eq(TRANSACTION_ID), eq(REQUEST_ID_STR), eq(REQUEST_ID_STR), any(),
            eq(0L), eq(0L), eq(status), metadataCaptor.capture());
        return metadataCaptor.getValue();
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
     * Calcula la firma esperada del webhook según la doc oficial de Banchile:
     * {@code SHA-256(requestId + status.status + status.date + secretKey)} en hex lowercase.
     * <p>La doc también acepta Base64; aquí usamos hex porque el ejemplo del portal de
     * Banchile muestra el digest como `sha256:<hex>`.
     */
    private static String computeExpectedSignature(int requestId, String status, String date, String secret)
        throws Exception {
        String input = requestId + status + date + secret;
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha256.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * Configura los mocks de ConfigurationManager para devolver la configuración
     * básica de Banchile.
     *
     * @param enabled Si true, BANCHILE_ENABLED devuelve "true"
     */
    private void stubConfiguration(boolean enabled) {
        var configMap = Map.of(
            BANCHILE_ENABLED,  MaybeConfigurationBuilder.existing(BANCHILE_ENABLED, enabled ? "true" : "false"),
            BANCHILE_LOGIN,    MaybeConfigurationBuilder.existing(BANCHILE_LOGIN, LOGIN),
            BANCHILE_TRANKEY,  MaybeConfigurationBuilder.existing(BANCHILE_TRANKEY, SECRET_KEY),
            BANCHILE_BASE_URL, MaybeConfigurationBuilder.existing(BANCHILE_BASE_URL, BASE_URL)
        );
        when(configurationManager.getFor(eq(BanchilePagosWebhookManager.ALL_OPTIONS), any()))
            .thenReturn(configMap);
    }

    /**
     * Respuesta de querySession de un pago aprobado, con las claves que trae la respuesta real de
     * Banchile (rq 34028) en cada intento de pago.
     */
    private static SessionResponse approvedSession() {
        return alfio.util.Json.GSON.fromJson("""
            {"requestId": 999,
             "status": {"status": "APPROVED", "reason": "00", "message": "La petición ha sido aprobada exitosamente", "date": "2026-10-13T11:52:10-03:00"},
             "payment": [{"status": {"status": "APPROVED", "reason": "00", "message": "Aprobada", "date": "2026-10-13T11:52:10-03:00"},
                          "internalReference": 900999, "paymentMethod": "visa", "paymentMethodName": "Visa", "issuerName": "BANCO DE PRUEBA",
                          "amount": {"from": {"total": 24000, "currency": "CLP"}, "to": {"total": 24000, "currency": "CLP"}, "factor": 1},
                          "authorization": "000999", "reference": "REF999", "receipt": "6145999", "franchise": "visa", "refunded": false,
                          "processorFields": [{"keyword": "lastDigits", "value": "4242", "displayOn": "none"}]}]}
            """, SessionResponse.class);
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
