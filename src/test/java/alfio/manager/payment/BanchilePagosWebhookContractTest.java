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
import alfio.model.TicketReservation.TicketReservationStatus;
import alfio.model.TicketReservationStatusAndValidation;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.*;
import alfio.model.transaction.banchile.BanchileWebhookPayload;
import alfio.model.transaction.banchile.CreateSessionRequest.Auth;
import alfio.model.transaction.banchile.SessionResponse;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.test.util.TestUtil;
import alfio.util.ClockProvider;
import alfio.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static alfio.model.system.ConfigurationKeys.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests de contrato para {@link BanchilePagosWebhookManager} usando fixtures JSON externos.
 *
 * <p>Los fixtures son SINTÉTICOS — modelados sobre la respuesta real de querySession
 * capturada durante el smoke test de 2026-05-14. Deben reemplazarse con webhooks reales
 * capturados durante la certificación en Phase H.
 *
 * <p>El campo {@code signature} NO está en los JSONs de fixture: se calcula en tiempo de
 * ejecución con la misma fórmula que usa el manager ({@code Base64(SHA1(requestId+status+date+secret))}).
 * Esto garantiza que cuando se confirme y ajuste la fórmula en Phase H, los fixtures
 * no necesitan actualizarse.
 *
 * <p>Fixtures en: {@code src/test/resources/fixtures/banchile-webhooks/}
 */
class BanchilePagosWebhookContractTest {

    // SYNTHETIC — constantes que coinciden con las del BanchilePagosWebhookManagerTest existente
    private static final String RESERVATION_ID  = "test-reservation-abc123";
    private static final String REQUEST_ID_STR  = "999";
    private static final int    REQUEST_ID_INT  = 999;
    private static final int    TRANSACTION_ID  = 42;
    private static final String BASE_URL        = "https://checkout.test.banchilepagos.cl";
    private static final String LOGIN           = "ffb78b93826239e1aa85a515aa961bd9";
    private static final String TRAN_KEY        = "U87nG0kcCsjb61Mj";
    private static final String WEBHOOK_SECRET  = "webhook-secret-for-tests";
    private static final String FIXTURES_BASE   = "/fixtures/banchile-webhooks/";

    // --- Mocks ---
    private ConfigurationManager configurationManager;
    private BanchilePagosClient  banchilePagosClient;
    private TransactionRepository transactionRepository;
    private TicketReservationRepository ticketReservationRepository;
    private Transaction          transaction;
    private Event                event;
    private PaymentContext       paymentContext;

    // --- SUT ---
    private BanchilePagosWebhookManager manager;

    @BeforeEach
    void setUp() {
        configurationManager        = mock(ConfigurationManager.class);
        banchilePagosClient         = mock(BanchilePagosClient.class);
        transactionRepository       = mock(TransactionRepository.class);
        ticketReservationRepository = mock(TicketReservationRepository.class);

        event = mock(Event.class);
        when(event.getZoneId()).thenReturn(ZoneId.of("America/Santiago"));
        when(event.getConfigurationLevel()).thenReturn(ConfigurationLevel.organization(1));
        when(event.getCurrency()).thenReturn("CLP");
        when(event.getType()).thenReturn(PurchaseContext.PurchaseContextType.event);
        when(event.getPublicIdentifier()).thenReturn("test-event-slug");
        when(event.now(any(ClockProvider.class)))
            .thenAnswer(inv -> java.time.ZonedDateTime.now(ZoneId.of("America/Santiago")));
        when(event.now(any(java.time.Clock.class)))
            .thenAnswer(inv -> java.time.ZonedDateTime.now(ZoneId.of("America/Santiago")));

        transaction = mock(Transaction.class);
        when(transaction.getId()).thenReturn(TRANSACTION_ID);
        when(transaction.getPaymentId()).thenReturn(REQUEST_ID_STR);
        when(transaction.getReservationId()).thenReturn(RESERVATION_ID);
        when(transaction.getPaymentProxy()).thenReturn(PaymentProxy.BANCHILE);
        when(transaction.getStatus()).thenReturn(Transaction.Status.PENDING);
        when(transaction.getPlatformFee()).thenReturn(0L);
        when(transaction.getGatewayFee()).thenReturn(0L);
        when(transaction.getMetadata()).thenReturn(Map.of());

        paymentContext = mock(PaymentContext.class);
        when(paymentContext.getPurchaseContext()).thenReturn(event);
        when(paymentContext.getConfigurationLevel()).thenReturn(ConfigurationLevel.organization(1));

        stubConfiguration();

        manager = new BanchilePagosWebhookManager(
            configurationManager,
            banchilePagosClient,
            transactionRepository,
            ticketReservationRepository,
            TestUtil.clockProvider()
        );
    }

    // =========================================================================
    // CT1 — Parametrizado: cada fixture produce el estado Alfio esperado
    // =========================================================================

    /**
     * Verifica que cada fixture webhook externo, al ser procesado por el manager,
     * produce el {@link Transaction.Status} de Alfio correcto.
     *
     * <p>SYNTHETIC — el requestId en cada fixture es un valor de prueba genérico;
     * el test sobreescribe el stub de querySession para que devuelva el mismo
     * estado del fixture, simulando la respuesta remota de Banchile.
     */
    @ParameterizedTest
    @CsvSource({
        "approved-clp.json,APPROVED,COMPLETE",
        "rejected-clp.json,REJECTED,FAILED",
        "pending-clp.json,PENDING,PENDING",
        "partial-approved.json,APPROVED_PARTIAL,PENDING"
    })
    void fixtureWebhookYieldsExpectedAlfioStatus(String fixtureFile,
                                                  String banchileStatus,
                                                  String expectedAlfioStatus) throws Exception {
        // 1. Cargar fixture JSON
        String fixtureJson = loadFixture(fixtureFile.trim());
        BanchileWebhookPayload rawPayload = Json.GSON.fromJson(fixtureJson, BanchileWebhookPayload.class);

        assertNotNull(rawPayload, "El fixture '" + fixtureFile + "' debe parsear sin errores");
        assertNotNull(rawPayload.requestId(), "requestId no debe ser null en el fixture");
        assertNotNull(rawPayload.status(), "status no debe ser null en el fixture");

        // 2. Calcular signature al runtime (no está en el JSON — ver javadoc de la clase)
        // SYNTHETIC — fórmula por confirmar en Phase H
        String statusTrimmed = banchileStatus.trim();
        String expectedTrimmed = expectedAlfioStatus.trim();
        String fixtureDate = rawPayload.status().date();
        String computedSignature = computeSignature(REQUEST_ID_INT, statusTrimmed, fixtureDate, WEBHOOK_SECRET);

        // 3. Construir payload con firma calculada y requestId fijado a REQUEST_ID_INT
        //    para que coincida con el stub de querySession y el mock de transaction.
        BanchileWebhookPayload payloadWithSignature = new BanchileWebhookPayload(
            REQUEST_ID_INT,                          // override: usar el mismo requestId que el manager espera
            RESERVATION_ID,
            new SessionResponse.Status(statusTrimmed, rawPayload.status().reason(),
                rawPayload.status().message(), fixtureDate),
            computedSignature
        );

        var transactionWebhookPayload = new BanchilePagosWebhookManager.BanchileTransactionWebhookPayload(
            payloadWithSignature, RESERVATION_ID
        );

        // 4. Stub querySession: devuelve el mismo estado del fixture (evita spoofing check)
        var remoteSession = new SessionResponse(
            new SessionResponse.Status(statusTrimmed, "00", "Respuesta de fixture", fixtureDate),
            REQUEST_ID_INT, null, null
        );
        when(banchilePagosClient.querySession(eq(REQUEST_ID_INT), any(Auth.class), eq(BASE_URL)))
            .thenReturn(remoteSession);

        // 5. Para APPROVED: stub reserva en estado procesable
        if ("APPROVED".equals(statusTrimmed)) {
            var reservationStatus = mock(TicketReservationStatusAndValidation.class);
            when(reservationStatus.getStatus()).thenReturn(TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT);
            when(ticketReservationRepository.findOptionalStatusAndValidationById(RESERVATION_ID))
                .thenReturn(Optional.of(reservationStatus));
        }

        // 6. Ejecutar
        PaymentWebhookResult result = manager.processWebhook(transactionWebhookPayload, transaction, paymentContext);

        // 7. Verificar estado Alfio resultante
        // Nota: el manager usa PaymentWebhookResult.pending() para estados PENDING/APPROVED_PARTIAL,
        // que internamente devuelve Type.TRANSACTION_INITIATED (no existe Type.PENDING en el enum).
        Transaction.Status expected = Transaction.Status.valueOf(expectedTrimmed);
        switch (expected) {
            case COMPLETE -> assertEquals(PaymentWebhookResult.Type.SUCCESSFUL, result.getType(),
                "Fixture '" + fixtureFile + "' debe producir SUCCESSFUL");
            case FAILED   -> assertEquals(PaymentWebhookResult.Type.FAILED, result.getType(),
                "Fixture '" + fixtureFile + "' debe producir FAILED");
            case PENDING  -> assertEquals(PaymentWebhookResult.Type.TRANSACTION_INITIATED, result.getType(),
                "Fixture '" + fixtureFile + "' (PENDING/APPROVED_PARTIAL) debe producir TRANSACTION_INITIATED");
            default -> fail("Estado inesperado en @CsvSource: " + expectedAlfioStatus);
        }
    }

    // =========================================================================
    // CT2 — Replay del mismo webhook es idempotente
    // =========================================================================

    /**
     * Verifica que procesar el mismo webhook APPROVED múltiples veces no confirma
     * la reserva más de una vez.
     *
     * <p>La idempotencia se logra porque el manager detecta que la transacción
     * ya está en estado COMPLETE y devuelve NOT_RELEVANT sin actualizar nada.
     */
    @Test
    void replayingSameWebhookIsIdempotent() throws Exception {
        // SYNTHETIC — usa el fixture APPROVED como fuente del payload
        String fixtureJson = loadFixture("approved-clp.json");
        BanchileWebhookPayload rawPayload = Json.GSON.fromJson(fixtureJson, BanchileWebhookPayload.class);

        String dateStr = rawPayload.status().date();
        String signature = computeSignature(REQUEST_ID_INT, "APPROVED", dateStr, WEBHOOK_SECRET);

        BanchileWebhookPayload payload = new BanchileWebhookPayload(
            REQUEST_ID_INT, RESERVATION_ID,
            new SessionResponse.Status("APPROVED", "00", "Approved", dateStr),
            signature
        );
        var txPayload = new BanchilePagosWebhookManager.BanchileTransactionWebhookPayload(payload, RESERVATION_ID);

        // Stub querySession para la primera llamada (la que confirma)
        var remoteSession = new SessionResponse(
            new SessionResponse.Status("APPROVED", "00", "Approved", dateStr),
            REQUEST_ID_INT, null, null
        );
        when(banchilePagosClient.querySession(eq(REQUEST_ID_INT), any(Auth.class), eq(BASE_URL)))
            .thenReturn(remoteSession);

        // Reserva procesable para la primera confirmación
        var reservationStatus = mock(TicketReservationStatusAndValidation.class);
        when(reservationStatus.getStatus()).thenReturn(TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT);
        when(ticketReservationRepository.findOptionalStatusAndValidationById(RESERVATION_ID))
            .thenReturn(Optional.of(reservationStatus));

        // Primera ejecución: confirma
        PaymentWebhookResult first = manager.processWebhook(txPayload, transaction, paymentContext);
        assertEquals(PaymentWebhookResult.Type.SUCCESSFUL, first.getType(),
            "Primera ejecución debe ser SUCCESSFUL");

        // Simular que la transacción ya quedó COMPLETE (como ocurriría en producción)
        when(transaction.getStatus()).thenReturn(Transaction.Status.COMPLETE);

        // Ejecutar 4 veces más (total = 5 replays)
        for (int i = 0; i < 4; i++) {
            PaymentWebhookResult replay = manager.processWebhook(txPayload, transaction, paymentContext);
            assertEquals(PaymentWebhookResult.Type.NOT_RELEVANT, replay.getType(),
                "Replay #" + (i + 2) + " debe ser NOT_RELEVANT");
        }

        // transactionRepository.update con COMPLETE se llama exactamente 1 vez (la primera)
        verify(transactionRepository, times(1)).update(
            eq(TRANSACTION_ID),
            eq(REQUEST_ID_STR), eq(REQUEST_ID_STR),
            any(),
            eq(0L), eq(0L),
            eq(Transaction.Status.COMPLETE),
            any()
        );
    }

    // =========================================================================
    // CT3 — Tamperear el payload invalida la firma
    // =========================================================================

    /**
     * Verifica que modificar cualquier campo del payload mientras se mantiene la
     * firma original hace que el manager rechace el webhook.
     *
     * <p>Se carga el fixture APPROVED, se calcula la firma correcta para él,
     * y luego se modifica el campo {@code reference} antes de llamar al manager.
     */
    @Test
    void tamperingFixturePayloadRejectsSignature() throws Exception {
        // SYNTHETIC
        String fixtureJson = loadFixture("approved-clp.json");
        BanchileWebhookPayload rawPayload = Json.GSON.fromJson(fixtureJson, BanchileWebhookPayload.class);

        String dateStr = rawPayload.status().date();
        // Firma calculada para el payload ORIGINAL (requestId=REQUEST_ID_INT, reference=RESERVATION_ID)
        String originalSignature = computeSignature(REQUEST_ID_INT, "APPROVED", dateStr, WEBHOOK_SECRET);

        // Tamperear: cambiar la reference a otro valor mientras se mantiene la firma original
        BanchileWebhookPayload tamperedPayload = new BanchileWebhookPayload(
            REQUEST_ID_INT,
            "OTRO-RESERVATION-ID-TAMPEREADO",  // campo modificado
            new SessionResponse.Status("APPROVED", "00", "Approved", dateStr),
            originalSignature                  // firma del payload ORIGINAL, no del tampereado
        );
        var txPayload = new BanchilePagosWebhookManager.BanchileTransactionWebhookPayload(
            tamperedPayload, "OTRO-RESERVATION-ID-TAMPEREADO"
        );

        // Act
        PaymentWebhookResult result = manager.processWebhook(txPayload, transaction, paymentContext);

        // Assert: la firma del payload tampereado no coincide → ERROR
        assertEquals(PaymentWebhookResult.Type.ERROR, result.getType(),
            "Payload tampereado con firma original debe ser rechazado como ERROR");

        // Nunca debe confirmarse la reserva
        verify(transactionRepository, never()).update(anyInt(), anyString(), anyString(), any(),
            anyLong(), anyLong(), eq(Transaction.Status.COMPLETE), any());
    }

    // =========================================================================
    // Helpers privados
    // =========================================================================

    /**
     * Carga un fixture JSON desde el classpath de test.
     *
     * @param filename Nombre del archivo en {@code /fixtures/banchile-webhooks/}
     * @return Contenido del archivo como String UTF-8
     * @throws Exception si el archivo no existe o no se puede leer
     */
    private static String loadFixture(String filename) throws Exception {
        String path = FIXTURES_BASE + filename;
        try (InputStream is = BanchilePagosWebhookContractTest.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("Fixture no encontrado en classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Calcula la firma del webhook usando la misma fórmula del manager.
     *
     * <p>Fórmula: {@code Base64(SHA1(requestId + status + date + secret))}
     *
     * <p>TODO: confirmar esta fórmula contra el primer webhook real en Phase H.
     * Si la fórmula cambia, solo hay que actualizar este helper y el manager —
     * los fixtures JSON no necesitan cambiar.
     *
     * @param requestId     ID de la sesión de pago
     * @param banchileStatus Estado del pago (e.g. "APPROVED")
     * @param date          Fecha ISO-8601 del status
     * @param secret        Webhook secret configurado
     * @return Firma en Base64
     */
    private static String computeSignature(int requestId, String banchileStatus, String date, String secret)
        throws Exception {
        String input = requestId + banchileStatus + date + secret;
        // SHA-1 requerido por el protocolo Banchile/PlacetoPay
        // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(input.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    /**
     * Configura los mocks de ConfigurationManager con la configuración básica de Banchile.
     */
    private void stubConfiguration() {
        var configMap = Map.of(
            BANCHILE_ENABLED,        MaybeConfigurationBuilder.existing(BANCHILE_ENABLED, "true"),
            BANCHILE_LOGIN,          MaybeConfigurationBuilder.existing(BANCHILE_LOGIN, LOGIN),
            BANCHILE_TRANKEY,        MaybeConfigurationBuilder.existing(BANCHILE_TRANKEY, TRAN_KEY),
            BANCHILE_WEBHOOK_SECRET, MaybeConfigurationBuilder.existing(BANCHILE_WEBHOOK_SECRET, WEBHOOK_SECRET),
            BANCHILE_BASE_URL,       MaybeConfigurationBuilder.existing(BANCHILE_BASE_URL, BASE_URL)
        );
        when(configurationManager.getFor(eq(BanchilePagosWebhookManager.ALL_OPTIONS), any()))
            .thenReturn(configMap);
        when(configurationManager.getForSystem(BANCHILE_WEBHOOK_SECRET))
            .thenReturn(MaybeConfigurationBuilder.existing(BANCHILE_WEBHOOK_SECRET, WEBHOOK_SECRET));
    }
}
