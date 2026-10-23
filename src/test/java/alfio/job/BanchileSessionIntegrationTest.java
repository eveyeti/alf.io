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
package alfio.job;

import alfio.TestConfiguration;
import alfio.config.DataSourceConfiguration;
import alfio.config.Initializer;
import alfio.controller.api.ControllerConfiguration;
import alfio.controller.api.v2.user.ReservationApiV2Controller;
import alfio.manager.EventManager;
import alfio.manager.NotificationManager;
import alfio.manager.PurchaseContextManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.payment.BanchilePagosClient;
import alfio.manager.payment.BanchilePagosWebhookManager;
import alfio.manager.payment.PaymentSpecification;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.Ticket;
import alfio.model.TicketCategory;
import alfio.model.TicketReservation.TicketReservationStatus;
import alfio.model.TicketReservationStatusAndValidation;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.model.modification.TicketReservationModification;
import alfio.model.modification.TicketReservationWithOptionalCodeModification;
import alfio.model.transaction.Transaction;
import alfio.model.transaction.PaymentProxy;
import alfio.repository.EventRepository;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketRepository;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.ClockProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.ClearType;
import org.mockserver.model.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static alfio.model.TicketReservation.TicketReservationStatus.COMPLETE;
import static alfio.model.TicketReservation.TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT;
import static alfio.model.TicketReservation.TicketReservationStatus.PENDING;
import static alfio.model.system.ConfigurationKeys.*;
import static alfio.test.util.IntegrationTestUtil.AVAILABLE_SEATS;
import static alfio.test.util.IntegrationTestUtil.initEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * La sesión que alf.io crea en Banchile cuando el comprador aprieta "Pagar", y qué pasa con la
 * reserva cuando esa sesión termina sin pago, contra un Banchile falso local (MockServer).
 *
 * <p>El comprador entra por el controller ({@code POST /api/v2/public/reservation/{id}}, con
 * MockMvc): así se prueba que {@code doPayment} corre en el hilo del request y saca de ahí la IP y
 * el navegador del comprador.
 *
 * <p>La reserva queda vigente hasta 15 minutos después de que vence la sesión: si Banchile informa la
 * sesión vencida (EX) o cancelada (?C), el cron o el poll la encuentran vigente y el flujo de pago
 * fallido de alf.io le da al comprador la opción de reintentar en vez de anularla.
 */
@AlfioIntegrationTest
@ContextConfiguration(classes = {
    DataSourceConfiguration.class,
    TestConfiguration.class,
    ControllerConfiguration.class,
    BanchileReconciliationIntegrationTest.ShortBanchileTimeoutConfiguration.class,
    PollThrottleTestClock.Manual.class
})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class BanchileSessionIntegrationTest {

    private static final String BUYER_EMAIL = "maria.nunez@example.com";
    private static final String IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
        + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    /** Lo que manda el checkout de alf.io al apretar "Pagar" con Banchile. */
    private static final String PAY_WITH_BANCHILE = """
        {"paymentProxy": "BANCHILE", "selectedPaymentMethod": "CREDIT_CARD",
         "termAndConditionsAccepted": true, "privacyPolicyAccepted": true}
        """;
    /** Correo de pago fallido con la reserva vigente (email-transaction-failed.retry, en inglés). */
    private static final String RETRY_EMAIL = "You can safely retry by opening the reservation page at";
    /** Correo de pago fallido con la reserva anulada (email-transaction-failed.cancelled, en inglés). */
    private static final String CANCELLED_EMAIL = "The reservation has been cancelled";

    private static ClientAndServer banchile;

    @Autowired
    private TicketReservationManager ticketReservationManager;
    @Autowired
    private TicketReservationRepository ticketReservationRepository;
    @Autowired
    private TicketCategoryRepository ticketCategoryRepository;
    @Autowired
    private ConfigurationRepository configurationRepository;
    @Autowired
    private ConfigurationManager configurationManager;
    @Autowired
    private EventManager eventManager;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserManager userManager;
    @Autowired
    private ReservationApiV2Controller reservationApiV2Controller;
    @Autowired
    private BanchilePagosWebhookManager banchilePagosWebhookManager;
    @Autowired
    private BanchilePagosClient banchilePagosClient;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private PurchaseContextManager purchaseContextManager;
    @Autowired
    private NotificationManager notificationManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ClockProvider clockProvider;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private PollThrottleTestClock pollThrottleClock;

    @BeforeAll
    static void startFakeBanchile() {
        banchile = ClientAndServer.startClientAndServer(0);
    }

    @AfterAll
    static void stopFakeBanchile() {
        banchile.stop();
    }

    @BeforeEach
    void setUp() {
        banchile.reset();
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);
        configurationRepository.insert(BANCHILE_ENABLED.name(), "true", "");
        configurationRepository.insert(BANCHILE_LOGIN.name(), "ffb78b93826239e1aa85a515aa961bd9", "");
        configurationRepository.insert(BANCHILE_TRANKEY.name(), "U87nG0kcCsjb61Mj", "");
        configurationRepository.insert(BANCHILE_BASE_URL.name(), "http://localhost:" + banchile.getPort(), "");
    }

    // -------------------------------------------------------------------------
    // createSession con los datos del comprador
    // -------------------------------------------------------------------------

    /**
     * El comprador aprieta "Pagar": Banchile recibe su nombre, apellido y correo, su IP (la que Jetty
     * saca del {@code X-Forwarded-For} de Caddy) y su navegador, {@code skipResult} y una descripción
     * con el nombre del evento y el ID corto de la reserva. La referencia no cambia.
     */
    @Test
    void payingFromTheOverviewSendsTheBuyerTheClientAndAReadableDescriptionToBanchile() throws Exception {
        var event = createEvent();
        var reservationId = reservationOf(event, DateUtils.addMinutes(new Date(), 25));
        banchileCreatesTheSession(51001);

        MockMvcBuilders.standaloneSetup(reservationApiV2Controller).build()
            .perform(post("/api/v2/public/reservation/{reservationId}", reservationId)
                .param("lang", "en")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(PAY_WITH_BANCHILE)
                .header(HttpHeaders.USER_AGENT, IPHONE_SAFARI)
                .with(request -> {
                    request.setRemoteAddr("190.98.210.4");
                    return request;
                }))
            .andExpect(status().isOk());

        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
        var body = lastCreateSessionBody();
        var buyer = body.getAsJsonObject("buyer");
        assertNotNull(buyer, "createSession sin buyer");
        assertEquals("María José", buyer.get("name").getAsString());
        assertEquals("Núñez Peña", buyer.get("surname").getAsString());
        assertEquals(BUYER_EMAIL, buyer.get("email").getAsString());
        assertEquals("190.98.210.4", body.get("ipAddress").getAsString());
        assertEquals(IPHONE_SAFARI, body.get("userAgent").getAsString());
        assertTrue(body.has("skipResult") && body.get("skipResult").getAsBoolean(), "createSession sin skipResult: true");
        var reservation = ticketReservationRepository.findReservationById(reservationId);
        String shortId = configurationManager.getShortReservationID(event, reservation);
        assertEquals("event display name - reserva " + shortId, body.getAsJsonObject("payment").get("description").getAsString());
        assertEquals(reservationId.replace("-", ""), body.getAsJsonObject("payment").get("reference").getAsString());
    }

    // -------------------------------------------------------------------------
    // La validez de la reserva cubre la sesión: el pago fallido permite reintentar
    // -------------------------------------------------------------------------

    /**
     * Al crear la sesión, la reserva que vencía a los 25 minutos queda vigente hasta 15 minutos
     * después de que vence la sesión, en la misma transacción que la deja en EXTERNAL_PROCESSING_PAYMENT.
     * Corre con la hora real (ver {@link #withTheRealClock}), así que lo comprueba a cualquier hora.
     */
    @Test
    void payingFromTheOverviewExtendsTheReservationUntilFifteenMinutesAfterTheSession() throws Exception {
        var event = createEvent();
        var reservationId = reservationOf(event, DateUtils.addMinutes(new Date(), 25));
        banchileCreatesTheSession(51002);
        var before = Instant.now();

        withTheRealClock(() -> MockMvcBuilders.standaloneSetup(reservationApiV2Controller).build()
            .perform(post("/api/v2/public/reservation/{reservationId}", reservationId)
                .param("lang", "en")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(PAY_WITH_BANCHILE))
            .andExpect(status().isOk()));

        var after = Instant.now();
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
        var sessionExpiration = Instant.parse(lastCreateSessionBody().get("expiration").getAsString());
        assertFalse(sessionExpiration.isBefore(before.plus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MILLIS)),
            "la sesión vence 30 minutos después de apretar \"Pagar\": " + sessionExpiration);
        assertFalse(sessionExpiration.isAfter(after.plus(Duration.ofMinutes(30))), "la sesión vence 30 minutos después: " + sessionExpiration);
        assertEquals(Date.from(sessionExpiration.plus(Duration.ofMinutes(15))).toInstant(), validity(reservationId).toInstant(),
            "la reserva vence 15 minutos después de la sesión");
    }

    /**
     * La sesión vence sin pago (Banchile la informa REJECTED con razón EX, como 42 de las 51 sesiones
     * fallidas de "Ya no te gusto") y el cron la detecta 12 minutos después, en el peor caso. La
     * reserva sigue vigente: vuelve a PENDING con al menos 10 minutos para reintentar y el comprador
     * recibe el correo de reintento, no el de reserva anulada.
     */
    @Test
    void anExpiredSessionThatTheCronDetectsTwelveMinutesLaterLetsTheBuyerRetry() {
        var event = createEvent();
        var now = Instant.now();
        // creada hace 47 minutos (vence a los 25) y mandada a pagar 5 minutos después: la sesión venció hace 12
        var reservationId = reservationOf(event, Date.from(now.minus(Duration.ofMinutes(22))));
        var payTime = now.minus(Duration.ofMinutes(42));
        payWithBanchileAt(event, reservationId, 52001, payTime);
        assertEquals(Date.from(payTime.plus(Duration.ofMinutes(45))).toInstant(), validity(reservationId).toInstant(),
            "la reserva vence 15 minutos después de la sesión");
        banchileResponds(52001, rejected(52001, "EX", "La petición ha expirado"));

        cron().reconcilePendingBanchileTransactions();

        assertTheBuyerCanRetry(event, reservationId);
    }

    /**
     * El comprador apretó "Pagar" con la reserva a punto de vencer, le rechazaron la tarjeta y canceló
     * en Banchile (REJECTED con razón ?C) cuando la validez original ya había pasado. El poll del
     * navegador lo detecta y le permite reintentar en vez de anular la reserva.
     */
    @Test
    void aSessionCancelledAfterTheOriginalValidityLetsTheBuyerRetryFromThePoll() {
        var event = createEvent();
        var now = Instant.now();
        // creada hace 30 minutos: su validez original venció hace 5. Se mandó a pagar hace 10
        var reservationId = reservationOf(event, Date.from(now.minus(Duration.ofMinutes(5))));
        payWithBanchileAt(event, reservationId, 52002, now.minus(Duration.ofMinutes(10)));
        banchileResponds(52002, rejected(52002, "?C", "El usuario canceló la transacción"));

        reservationApiV2Controller.getReservationStatus(reservationId);

        assertTheBuyerCanRetry(event, reservationId);
    }

    /**
     * El margen es de 15 minutos: si el resultado se detecta después (el cron no corrió durante más de
     * un ciclo), la reserva ya venció y alf.io la anula, como antes.
     */
    @Test
    void anExpirationDetectedAfterTheMarginStillCancelsTheReservation() {
        var event = createEvent();
        var now = Instant.now();
        // mandada a pagar hace 47 minutos: la sesión venció hace 17 y la reserva, hace 2
        var reservationId = reservationOf(event, Date.from(now.minus(Duration.ofMinutes(27))));
        payWithBanchileAt(event, reservationId, 52003, now.minus(Duration.ofMinutes(47)));
        banchileResponds(52003, rejected(52003, "EX", "La petición ha expirado"));

        cron().reconcilePendingBanchileTransactions();

        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty(),
            "el flujo de pago fallido anula la reserva vencida");
        assertTrue(buyerEmails(event).stream().anyMatch(m -> m.contains(CANCELLED_EMAIL)), String.valueOf(buyerEmails(event)));
    }

    /**
     * El banco rechaza la tarjeta: la sesión queda REJECTED y el intento rechazado trae
     * {@code authorization: "000000"}, como vio el QA en el sandbox el 15-oct. Después del flujo de
     * pago fallido, el resumen recibe el estado de la sesión y el motivo, para mostrar el aviso, pero
     * no ese "código de autorización", que antes ocultaba el aviso.
     */
    @Test
    void afterARejectedCardTheOverviewGetsTheRejectionWithoutAnAuthorization() {
        var event = createEvent();
        var now = Instant.now();
        var reservationId = reservationOf(event, Date.from(now.plus(Duration.ofMinutes(20))));
        payWithBanchileAt(event, reservationId, 52101, now.minus(Duration.ofMinutes(2)));
        banchileResponds(52101, rejectedCard(52101));

        reservationApiV2Controller.getReservationStatus(reservationId);

        assertEquals(PENDING, reservationStatus(reservationId), "con la reserva vigente, el rechazo la devuelve a PENDING");
        var response = reservationApiV2Controller.getReservationInfo(reservationId, null);
        assertNotNull(response.getBody());
        var details = response.getBody().getPaymentDetails();
        assertNotNull(details, "el resumen debía recibir los detalles del intento fallido");
        assertEquals("REJECTED", details.getStatus());
        assertEquals("51", details.getStatusReason());
        assertEquals("Fondos insuficientes", details.getStatusMessage());
        assertNull(details.getAuthorization(), "el 000000 del intento rechazado no es un código de autorización");
    }

    // -------------------------------------------------------------------------
    // El freno del poll de "procesando pago"
    // -------------------------------------------------------------------------

    /**
     * El comprador espera en "procesando pago", que consulta el estado cada 2 s: el poll consulta a
     * Banchile en el primero y después cada 10 s; entre medio responde con el estado conocido de la
     * reserva, sin llamar a Banchile. Antes, cada poll llegaba a Banchile.
     */
    @Test
    void whileTheBuyerWaitsThePollQueriesBanchileAtMostEveryTenSeconds() {
        var event = createEvent();
        var reservationId = reservationOf(event, DateUtils.addMinutes(new Date(), 25));
        payWithBanchileAt(event, reservationId, 54001, Instant.now());
        banchileResponds(54001, pending(54001));

        var queries = new ArrayList<Integer>();
        // un poll cada 2 s durante 20 s
        for (int second = 0; second <= 20; second += 2) {
            var status = reservationApiV2Controller.getReservationStatus(reservationId).getBody();
            assertNotNull(status);
            assertEquals(EXTERNAL_PROCESSING_PAYMENT, status.getStatus(), "segundo " + second);
            queries.add(queriesTo(54001));
            pollThrottleClock.advance(Duration.ofSeconds(2));
        }

        // consultas acumuladas después de cada poll: a los 0, 10 y 20 s
        assertEquals(List.of(1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3), queries);
    }

    /**
     * Entre consulta y consulta el poll no ve lo que pasó en Banchile: la tarjeta se rechazó a los 4 s,
     * pero el rechazo se aplica con la consulta de los 10 s. La reserva vuelve a PENDING igual.
     */
    @Test
    void aRejectionBetweenQueriesIsAppliedByTheNextQuery() {
        var event = createEvent();
        var reservationId = reservationOf(event, DateUtils.addMinutes(new Date(), 25));
        payWithBanchileAt(event, reservationId, 54002, Instant.now());
        banchileResponds(54002, pending(54002));
        reservationApiV2Controller.getReservationStatus(reservationId);
        pollThrottleClock.advance(Duration.ofSeconds(2));
        reservationApiV2Controller.getReservationStatus(reservationId);
        banchileResponds(54002, rejectedCard(54002));

        for (int second = 4; second < 10; second += 2) {
            pollThrottleClock.advance(Duration.ofSeconds(2));
            assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationApiV2Controller.getReservationStatus(reservationId).getBody().getStatus(),
                "segundo " + second + ": el último estado conocido");
        }
        pollThrottleClock.advance(Duration.ofSeconds(2));

        assertEquals(PENDING, reservationApiV2Controller.getReservationStatus(reservationId).getBody().getStatus(),
            "a los 10 s el poll consulta y aplica el rechazo");
        assertEquals(2, queriesTo(54002));
    }

    /**
     * El comprador vuelve de Banchile con el pago aprobado. Antes de irse, "procesando pago" consultó
     * hace menos de 10 s (volvió con "Atrás" y siguió pagando con "Adelante"), pero mientras pagaba
     * nadie hizo polls: el primer poll al volver consulta igual y la reserva queda confirmada al tiro.
     */
    @Test
    void theFirstPollAfterComingBackFromBanchileQueriesAndConfirmsRightAway() {
        var event = createEvent();
        var reservationId = reservationOf(event, DateUtils.addMinutes(new Date(), 25));
        payWithBanchileAt(event, reservationId, 54003, Instant.now());
        banchileResponds(54003, pending(54003));
        reservationApiV2Controller.getReservationStatus(reservationId);
        pollThrottleClock.advance(Duration.ofSeconds(2));
        reservationApiV2Controller.getReservationStatus(reservationId);
        assertEquals(1, queriesTo(54003));

        // paga en Banchile: 6 s sin polls
        banchileResponds(54003, approved(54003));
        pollThrottleClock.advance(Duration.ofSeconds(6));

        assertEquals(COMPLETE, reservationApiV2Controller.getReservationStatus(reservationId).getBody().getStatus(),
            "el primer poll al volver confirma, 8 s después de la consulta anterior");
        assertEquals(2, queriesTo(54003));
    }

    /**
     * Si Banchile no crea la sesión, la reserva no cambia: ni su validez ni su estado.
     */
    @Test
    void ifBanchileDoesNotCreateTheSessionTheValidityIsNotTouched() {
        var event = createEvent();
        var reservationId = reservationOf(event, DateUtils.addMinutes(new Date(), 25));
        var validityBefore = validity(reservationId);
        banchile.when(request().withMethod("POST").withPath("/api/session"), Times.once())
            .respond(response().withStatusCode(500).withContentType(MediaType.APPLICATION_JSON).withBody("{}"));

        assertTrue(banchilePagosWebhookManager.doPayment(paymentSpecification(event, reservationId)).isFailed());

        assertEquals(validityBefore.toInstant(), validity(reservationId).toInstant());
        assertEquals(PENDING, reservationStatus(reservationId));
    }

    // -------------------------------------------------------------------------
    // Tests del revisor (rama revision-v5-local)
    // -------------------------------------------------------------------------

    /**
     * El reintento de verdad: después del flujo de pago fallido la reserva vuelve a PENDING, y el
     * comprador, desde el link del correo, aprieta "Pagar" de nuevo. alf.io crea otra sesión: la
     * transacción vieja (FAILED) queda INVALID, la nueva PENDING, la reserva en
     * EXTERNAL_PROCESSING_PAYMENT y su validez vuelve a ser la de la sesión nueva + 15 minutos.
     */
    @Test
    void reviewerTheBuyerPaysAgainFromTheOverviewAfterTheFailedPaymentFlow() throws Exception {
        var event = createEvent();
        var now = Instant.now();
        var reservationId = reservationOf(event, Date.from(now.minus(Duration.ofMinutes(22))));
        payWithBanchileAt(event, reservationId, 53001, now.minus(Duration.ofMinutes(42)));
        banchileResponds(53001, rejected(53001, "EX", "La petición ha expirado"));
        cron().reconcilePendingBanchileTransactions();
        assertTheBuyerCanRetry(event, reservationId);

        var validityBeforePayingAgain = validity(reservationId);
        banchileCreatesTheSession(53002);
        MockMvcBuilders.standaloneSetup(reservationApiV2Controller).build()
            .perform(post("/api/v2/public/reservation/{reservationId}", reservationId)
                .param("lang", "en")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(PAY_WITH_BANCHILE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.value.redirectUrl").value("https://checkout.banchilepagos.cl/spa/session/53002/abc"));

        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
        var statusByRequestId = new HashMap<String, Transaction.Status>();
        transactionRepository.findAllByReservationIdAndPaymentProxy(reservationId, PaymentProxy.BANCHILE)
            .forEach(tx -> statusByRequestId.put(tx.getPaymentId(), tx.getStatus()));
        assertEquals(Map.of("53001", Transaction.Status.INVALID, "53002", Transaction.Status.PENDING), statusByRequestId);
        // el reloj de los tests está fijo en las 10:00 UTC: la validez esperada es max(antes, sesión + 15)
        var sessionExpiration = Instant.parse(lastCreateSessionBody().get("expiration").getAsString());
        var extended = Date.from(sessionExpiration.plus(Duration.ofMinutes(15)));
        var expected = extended.after(validityBeforePayingAgain) ? extended : validityBeforePayingAgain;
        assertEquals(expected.toInstant(), validity(reservationId).toInstant());
    }

    /**
     * Con una validez más larga que la sesión + 15 minutos (por ejemplo, alargada por el admin) la
     * reserva la conserva y el comprador va a Banchile igual: ejercita el log que usa la validez leída
     * de la base de datos.
     */
    @Test
    void reviewerALongerValidityIsKeptAndTheBuyerStillGoesToBanchile() throws Exception {
        var event = createEvent();
        var reservationId = reservationOf(event, DateUtils.addDays(new Date(), 2));
        var validityBefore = validity(reservationId);
        banchileCreatesTheSession(53003);

        MockMvcBuilders.standaloneSetup(reservationApiV2Controller).build()
            .perform(post("/api/v2/public/reservation/{reservationId}", reservationId)
                .param("lang", "en")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(PAY_WITH_BANCHILE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.value.redirectUrl").value("https://checkout.banchilepagos.cl/spa/session/53003/abc"));

        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
        assertEquals(validityBefore.toInstant(), validity(reservationId).toInstant());
    }

    /**
     * Con la primera sesión todavía abierta, "Pagar" de nuevo (otra pestaña) crea otra sesión: la
     * primera queda INVALID (pagable en Banchile hasta que vence) y la validez pasa a max(validez,
     * sesión nueva + 15). En producción, con el reloj real, cada "Pagar" vuelve a alargarla: apretando
     * "Pagar" antes de que venza, la reserva no vence nunca (el mismo patrón de Mollie y Saferpay).
     */
    @Test
    void reviewerPayingAgainWhileTheFirstSessionIsOpenCreatesASecondSession() throws Exception {
        var event = createEvent();
        var reservationId = reservationOf(event, DateUtils.addMinutes(new Date(), 25));
        var mvc = MockMvcBuilders.standaloneSetup(reservationApiV2Controller).build();

        banchileCreatesTheSession(53004);
        mvc.perform(post("/api/v2/public/reservation/{reservationId}", reservationId)
                .param("lang", "en").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(PAY_WITH_BANCHILE))
            .andExpect(status().isOk());
        var firstValidity = validity(reservationId);

        // la reserva sigue en EXTERNAL_PROCESSING_PAYMENT y confirmOverview no mira el estado
        banchileCreatesTheSession(53005);
        mvc.perform(post("/api/v2/public/reservation/{reservationId}", reservationId)
                .param("lang", "en").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(PAY_WITH_BANCHILE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.value.redirectUrl").value("https://checkout.banchilepagos.cl/spa/session/53005/abc"));

        var sessionExpiration = Instant.parse(lastCreateSessionBody().get("expiration").getAsString());
        var extended = Date.from(sessionExpiration.plus(Duration.ofMinutes(15)));
        var expected = extended.after(firstValidity) ? extended : firstValidity;
        assertEquals(expected.toInstant(), validity(reservationId).toInstant());
        var statusByRequestId = new HashMap<String, Transaction.Status>();
        transactionRepository.findAllByReservationIdAndPaymentProxy(reservationId, PaymentProxy.BANCHILE)
            .forEach(tx -> statusByRequestId.put(tx.getPaymentId(), tx.getStatus()));
        assertEquals(Map.of("53004", Transaction.Status.INVALID, "53005", Transaction.Status.PENDING), statusByRequestId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Event createEvent() {
        var clock = ClockProvider.clock();
        var categories = List.of(new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
            new DateTimeModification(LocalDate.now(clock).minusDays(1), LocalTime.now(clock)),
            new DateTimeModification(LocalDate.now(clock).plusDays(1), LocalTime.now(clock)),
            Map.of("en", "desc"), BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        return initEvent(categories, organizationRepository, userManager, eventManager, eventRepository).getLeft();
    }

    /**
     * Reserva de 2 entradas con los datos del comprador ya ingresados, lista para pagar.
     *
     * @param validity validez de la reserva
     */
    private String reservationOf(Event event, Date validity) {
        var category = ticketCategoryRepository.findAllTicketCategories(event.getId()).get(0);
        var ticketRequest = new TicketReservationModification();
        ticketRequest.setTicketCategoryId(category.getId());
        ticketRequest.setQuantity(2);
        var reservationId = ticketReservationManager.createTicketReservation(event,
            List.of(new TicketReservationWithOptionalCodeModification(ticketRequest, Optional.empty())), List.of(),
            validity, Optional.empty(), Locale.ENGLISH, false, null);
        ticketReservationRepository.updateTicketReservation(reservationId, PENDING.name(), BUYER_EMAIL,
            "María José Núñez Peña", "María José", "Núñez Peña", "en", null, null, null, null);
        return reservationId;
    }

    private PaymentSpecification paymentSpecification(Event event, String reservationId) {
        var reservation = ticketReservationRepository.findReservationById(reservationId);
        var totalPrice = ticketReservationManager.totalReservationCostWithVAT(reservation).getLeft();
        return new PaymentSpecification(reservation, totalPrice, event, null,
            ticketReservationManager.orderSummaryForReservation(reservation, event), true, true);
    }

    /**
     * {@link BanchilePagosWebhookManager#doPayment} como si el comprador hubiera apretado "Pagar" en
     * {@code payTime}. El reloj de los tests está fijo en las 10:00 UTC de hoy y el flujo de pago
     * fallido de alf.io compara con la hora real: por eso esta sesión se crea con un manager cuyo
     * reloj marca {@code payTime}.
     */
    private void payWithBanchileAt(Event event, String reservationId, int requestId, Instant payTime) {
        banchileCreatesTheSession(requestId);
        var clock = mock(ClockProvider.class);
        when(clock.getClock()).thenReturn(Clock.fixed(payTime, ZoneOffset.UTC));
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(payTime, invocation.getArgument(0)));
        var managerAtPayTime = new BanchilePagosWebhookManager(configurationManager, banchilePagosClient, transactionRepository,
            ticketReservationRepository, clock, notificationManager, organizationRepository);
        assertTrue(managerAtPayTime.doPayment(paymentSpecification(event, reservationId)).isRedirect(),
            "doPayment debía redirigir a Banchile");
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
    }

    /** Un paso de test que puede lanzar excepciones (MockMvc las declara). */
    private interface TestStep {
        void run() throws Exception;
    }

    /**
     * Corre {@code step} con el {@link BanchilePagosWebhookManager} de Spring usando la hora real, y
     * después le devuelve su reloj. El reloj de los tests está fijo en las 10:00 UTC de hoy y
     * confirmOverview compara la validez con la hora real: con el reloj fijo, la sesión vence a las
     * 10:30 UTC, y después de las 10:20 UTC (07:20 en Chile) una reserva recién creada ya vence más tarde
     * que la sesión + 15 minutos, así que no se vería la extensión. {@code ClockProvider} guarda el
     * reloj en un estático que se fija una sola vez: por eso se cambia solo el de este bean.
     */
    private void withTheRealClock(TestStep step) throws Exception {
        Object manager = AopTestUtils.getUltimateTargetObject(banchilePagosWebhookManager);
        Object fixedClock = ReflectionTestUtils.getField(manager, "clockProvider");
        var realClock = mock(ClockProvider.class);
        when(realClock.getClock()).thenReturn(Clock.systemUTC());
        when(realClock.withZone(any())).thenAnswer(invocation -> Clock.system(invocation.getArgument(0)));
        ReflectionTestUtils.setField(manager, "clockProvider", realClock);
        try {
            step.run();
        } finally {
            ReflectionTestUtils.setField(manager, "clockProvider", fixedClock);
        }
    }

    /** El cron de conciliación de 12 minutos, ejecutado a mano. */
    private BanchileReconciliationJob cron() {
        return new BanchileReconciliationJob(transactionRepository, ticketReservationManager, ticketReservationRepository,
            purchaseContextManager, banchilePagosWebhookManager, new TransactionTemplate(transactionManager), clockProvider);
    }

    /**
     * La reserva sigue viva para reintentar: PENDING, vigente al menos 10 minutos más, con sus
     * entradas, la transacción cerrada como FAILED y el correo de reintento al comprador, no el de
     * reserva anulada.
     */
    private void assertTheBuyerCanRetry(Event event, String reservationId) {
        assertEquals(PENDING, reservationStatus(reservationId), "la reserva vuelve a PENDING para reintentar");
        var validity = validity(reservationId);
        assertTrue(validity.after(DateUtils.addMinutes(new Date(), 9)), "debía quedar vigente al menos 10 minutos más: " + validity);
        assertEquals(Transaction.Status.FAILED, transactionRepository.loadOptionalByReservationId(reservationId).orElseThrow().getStatus());
        var tickets = ticketRepository.findTicketsInReservation(reservationId);
        assertEquals(2, tickets.size(), "la reserva conserva sus entradas");
        assertTrue(tickets.stream().allMatch(t -> t.getStatus() == Ticket.TicketStatus.PENDING), String.valueOf(tickets));
        var emails = buyerEmails(event);
        assertTrue(emails.stream().anyMatch(m -> m.contains(RETRY_EMAIL)), "el comprador debía recibir el correo de reintento: " + emails);
        assertTrue(emails.stream().noneMatch(m -> m.contains(CANCELLED_EMAIL)), "no el de reserva anulada: " + emails);
    }

    private Date validity(String reservationId) {
        return ticketReservationRepository.findReservationById(reservationId).getValidity();
    }

    private List<String> buyerEmails(Event event) {
        return jdbc.queryForList("select message from email_message where event_id = :eventId and recipient = :recipient order by id",
            Map.of("eventId", event.getId(), "recipient", BUYER_EMAIL), String.class);
    }

    /**
     * Desde ahora, querySession de la sesión responde {@code body}. Cambia solo la respuesta: las
     * consultas que el Banchile falso ya registró siguen contando en {@link #queriesTo}.
     */
    private static void banchileResponds(int requestId, String body) {
        var querySession = request().withMethod("POST").withPath("/api/session/" + requestId);
        banchile.clear(querySession, ClearType.EXPECTATIONS);
        banchile.when(querySession).respond(response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody(body));
    }

    /**
     * querySession de una sesión cerrada sin pago: REJECTED con la razón de Banchile (EX si venció,
     * ?C si el comprador canceló).
     */
    private static String rejected(int requestId, String reason, String message) {
        return """
            {"requestId": %d,
             "status": {"status": "REJECTED", "reason": "%s", "message": "%s", "date": "2026-11-28T18:40:00-03:00"}}
            """.formatted(requestId, reason, message);
    }

    /** querySession de una sesión abierta, sin pago todavía. */
    private static String pending(int requestId) {
        return """
            {"requestId": %d,
             "status": {"status": "PENDING", "reason": "PC", "message": "La petición se encuentra activa", "date": "2026-11-28T18:01:00-03:00"}}
            """.formatted(requestId);
    }

    /** querySession de un pago aprobado. */
    private static String approved(int requestId) {
        return """
            {"requestId": %d,
             "status": {"status": "APPROVED", "reason": "00", "message": "La petición ha sido aprobada exitosamente", "date": "2026-11-28T18:05:00-03:00"},
             "payment": [{"status": {"status": "APPROVED", "reason": "00", "message": "Aprobada", "date": "2026-11-28T18:05:00-03:00"},
                          "internalReference": %d, "paymentMethod": "visa", "paymentMethodName": "Visa", "issuerName": "BANCO DE PRUEBA",
                          "amount": {"from": {"total": 20, "currency": "CHF"}, "to": {"total": 20, "currency": "CHF"}, "factor": 1},
                          "authorization": "004393", "reference": "REF%d", "receipt": "6145%d", "franchise": "visa", "refunded": false,
                          "processorFields": [{"keyword": "lastDigits", "value": "4242", "displayOn": "none"}]}]}
            """.formatted(requestId, 900000 + requestId, requestId, requestId);
    }

    /** Cuántas veces alf.io consultó la sesión en el Banchile falso (querySession). */
    private static int queriesTo(int requestId) {
        return banchile.retrieveRecordedRequests(request().withMethod("POST").withPath("/api/session/" + requestId)).length;
    }

    /**
     * querySession de una tarjeta rechazada por el banco: la sesión REJECTED con el motivo del banco y
     * el intento rechazado con {@code authorization: "000000"}.
     */
    private static String rejectedCard(int requestId) {
        return """
            {"requestId": %d,
             "status": {"status": "REJECTED", "reason": "51", "message": "Fondos insuficientes", "date": "2026-11-28T18:20:00-03:00"},
             "payment": [{"status": {"status": "REJECTED", "reason": "51", "message": "Fondos insuficientes", "date": "2026-11-28T18:19:50-03:00"},
                          "internalReference": %d, "paymentMethod": "visa", "paymentMethodName": "Visa", "issuerName": "BANCO DE PRUEBA",
                          "amount": {"from": {"total": 20, "currency": "CHF"}, "to": {"total": 20, "currency": "CHF"}, "factor": 1},
                          "authorization": "000000", "reference": "REF%d", "receipt": null, "franchise": "visa", "refunded": false,
                          "processorFields": [{"keyword": "lastDigits", "value": "0016", "displayOn": "none"}]}]}
            """.formatted(requestId, 800000 + requestId, requestId);
    }

    private static void banchileCreatesTheSession(int requestId) {
        banchile.when(request().withMethod("POST").withPath("/api/session"), Times.once())
            .respond(response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody("""
                {"status": {"status": "OK", "reason": "PC", "message": "La petición se ha procesado correctamente",
                            "date": "2026-11-28T18:00:00-03:00"},
                 "requestId": %d, "processUrl": "https://checkout.banchilepagos.cl/spa/session/%d/abc"}
                """.formatted(requestId, requestId)));
    }

    /** El último createSession que recibió el Banchile falso. */
    private static JsonObject lastCreateSessionBody() {
        var requests = banchile.retrieveRecordedRequests(request().withMethod("POST").withPath("/api/session"));
        assertTrue(requests.length > 0, "alf.io no llamó a createSession");
        return JsonParser.parseString(requests[requests.length - 1].getBodyAsString()).getAsJsonObject();
    }

    private TicketReservationStatus reservationStatus(String reservationId) {
        return ticketReservationRepository.findOptionalStatusAndValidationById(reservationId)
            .map(TicketReservationStatusAndValidation::getStatus)
            .orElseThrow(() -> new AssertionError("la reserva " + reservationId + " no existe"));
    }
}
