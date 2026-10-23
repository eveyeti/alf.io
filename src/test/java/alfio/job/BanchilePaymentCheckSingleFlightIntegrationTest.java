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
import alfio.controller.api.v2.model.ReservationStatusInfo;
import alfio.controller.api.v2.user.ReservationApiV2Controller;
import alfio.controller.payment.api.PaymentApiController;
import alfio.manager.EventManager;
import alfio.manager.PurchaseContextManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.payment.BanchilePagosClient;
import alfio.manager.payment.BanchilePagosWebhookManager;
import alfio.manager.payment.PaymentSpecification;
import alfio.manager.support.PaymentResult;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.TicketCategory;
import alfio.model.TicketReservation.TicketReservationStatus;
import alfio.model.TicketReservationStatusAndValidation;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.model.modification.TicketReservationModification;
import alfio.model.modification.TicketReservationWithOptionalCodeModification;
import alfio.repository.EventRepository;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.ClockProvider;
import alfio.util.RefreshableDataSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.zaxxer.hikari.pool.HikariPool;
import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static alfio.model.TicketReservation.TicketReservationStatus.*;
import static alfio.model.system.ConfigurationKeys.*;
import static alfio.test.util.IntegrationTestUtil.AVAILABLE_SEATS;
import static alfio.test.util.IntegrationTestUtil.initEvent;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Una sola consulta a Banchile por pago a la vez desde las requests públicas: el polling del
 * navegador ({@code GET /api/v2/public/reservation/{id}/status}, cada 2 s) y el force-check
 * ({@code GET /api/v2/public/reservation/{id}/transaction/force-check}).
 *
 * <p>Sin esto, cada request entra a la transacción de {@code TicketReservationManager#forceTransactionCheck}
 * y espera el {@code FOR UPDATE} de la transacción de pago con una conexión del pool tomada, mientras
 * otra request está en la llamada a Banchile. Con Banchile lento y el frontend consultando cada 2 s
 * sin esperar la respuesta anterior, la cola crece sin límite y agota el pool (el "convoy").
 *
 * <p>El Banchile falso es un {@link HttpServer} del JDK que cuenta las consultas de cada pago y
 * cuántas hay en curso a la vez. Los jobs de alf.io están deshabilitados.
 *
 * <p>Las aserciones no dependen de cuánto tarde la máquina: en vez de exigir que una request
 * responda en menos de tantos milisegundos, el Banchile falso retiene la consulta en curso hasta que
 * el test la suelta ({@link FakeBanchile#hold}), y el test verifica que las demás requests
 * respondieron mientras seguía retenida. Solo el convoy y la base lenta usan latencias reales,
 * porque reproducen el ritmo del frontend (un poll cada 2 s).
 */
@AlfioIntegrationTest
@ContextConfiguration(classes = {
    DataSourceConfiguration.class,
    TestConfiguration.class,
    ControllerConfiguration.class,
    BanchilePaymentCheckSingleFlightIntegrationTest.BanchileTimeoutConfiguration.class,
    PollThrottleTestClock.FollowingRealTime.class
})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class BanchilePaymentCheckSingleFlightIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BanchilePaymentCheckSingleFlightIntegrationTest.class);

    /** Tope de las llamadas a Banchile en este contexto: más que la latencia del convoy (en producción es 20 s). */
    private static final Duration BANCHILE_TIMEOUT = Duration.ofSeconds(8);
    /**
     * Cuánto se espera, con la consulta a Banchile retenida, a que respondan las requests que no la
     * esperan: menos que {@link #BANCHILE_TIMEOUT}, para que el cliente no abandone antes la consulta retenida.
     */
    private static final Duration ANSWERED_WHILE_HELD = Duration.ofSeconds(5);
    /** Cuánto se espera a que una request llegue a Banchile, o a que la base muestre una request esperando un lock. */
    private static final Duration START_WAIT = Duration.ofSeconds(5);
    private static final int SIMULTANEOUS_REQUESTS = 8;
    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final String BUYER_EMAIL = "comprador@example.com";

    // escenario de la revisión adversarial: Banchile tarda L = 6 s y el frontend consulta cada 2 s
    private static final Duration CONVOY_LATENCY = Duration.ofSeconds(6);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration CONVOY_WINDOW = Duration.ofSeconds(30);
    /** Solo para el log del convoy: cuántos polls tardaron esto o más. */
    private static final Duration IMMEDIATE = Duration.ofMillis(500);
    // la base lenta de la revisión adversarial (ronda 4): la tabla de reservas bloqueada entre 3,5 s y 8,7 s
    private static final Duration STALL_FROM = Duration.ofMillis(3_500);
    private static final Duration STALL_UNTIL = Duration.ofMillis(8_700);
    /** connectionTimeout del pool de producción ({@code DataSourceConfiguration#getDataSource}). */
    private static final long PRODUCTION_CONNECTION_TIMEOUT_MS = 1000L;
    /** Tamaño del pool de producción: {@code datasource.connections.max-active}, 10 por defecto ({@code PlatformProvider#getMaxActive}). */
    private static final int PRODUCTION_POOL_SIZE = 10;

    @Autowired
    private ReservationApiV2Controller reservationApiV2Controller;
    @Autowired
    private PaymentApiController paymentApiController;
    @Autowired
    private TicketReservationManager ticketReservationManager;
    @Autowired
    private TicketReservationRepository ticketReservationRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private PurchaseContextManager purchaseContextManager;
    @Autowired
    private TicketCategoryRepository ticketCategoryRepository;
    @Autowired
    private BanchilePagosWebhookManager banchilePagosWebhookManager;
    @Autowired
    private ConfigurationRepository configurationRepository;
    @Autowired
    private EventManager eventManager;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserManager userManager;
    @Autowired
    private RefreshableDataSource dataSource;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ClockProvider clockProvider;
    @Autowired
    private PollThrottleTestClock pollThrottleClock;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private FakeBanchile banchile;

    @BeforeEach
    void setUp() throws IOException {
        banchile = new FakeBanchile();
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);
        configurationRepository.insert(BANCHILE_LOGIN.name(), "ffb78b93826239e1aa85a515aa961bd9", "");
        configurationRepository.insert(BANCHILE_TRANKEY.name(), "U87nG0kcCsjb61Mj", "");
        configurationRepository.insert(BANCHILE_BASE_URL.name(), banchile.baseUrl(), "");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        banchile.close();
    }

    // -------------------------------------------------------------------------
    // Polling del navegador
    // -------------------------------------------------------------------------

    @Test
    void simultaneousPollsOfTheSamePaymentQueryBanchileOnceAndTheOthersRespondWithoutWaitingForIt() throws Exception {
        var reservationId = reservationPaidWithBanchile(51001);
        banchile.hold(51001, pending(51001));

        var polls = simultaneously(SIMULTANEOUS_REQUESTS, () -> reservationApiV2Controller.getReservationStatus(reservationId));
        HeldQuery held;
        try {
            held = whileTheQueryIsHeld(51001, polls, SIMULTANEOUS_REQUESTS - 1);
        } finally {
            banchile.release(51001);
        }
        var outcomes = awaitAll(polls);

        log.info("[single-flight] {} polls simultáneos con la consulta a Banchile retenida: consultas={} latencias={} | {}",
            SIMULTANEOUS_REQUESTS, banchile.queries(51001), latencies(outcomes), held);
        assertEquals(SIMULTANEOUS_REQUESTS - 1, held.answered(),
            () -> "los polls que no consultan debían responder mientras la consulta seguía retenida en Banchile: " + held);
        assertEquals(1, banchile.queries(51001), "una sola consulta a Banchile por pago a la vez");
        for (var outcome : outcomes) {
            assertEquals(EXTERNAL_PROCESSING_PAYMENT, statusOf(outcome), "cada poll responde con el estado actual de la reserva");
        }
        assertEquals(1, held.pool().maxActive(), () -> "solo la request que consulta a Banchile retiene una conexión: " + held.pool());
        assertEquals(0, held.pool().maxAwaiting(), () -> "nadie espera una conexión: " + held.pool());

        // terminada la consulta en curso, el próximo poll que deja pasar el freno consulta de nuevo
        banchile.respond(51001, pending(51001), Duration.ZERO);
        pollThrottleClock.letTheNextPollQuery();
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationApiV2Controller.getReservationStatus(reservationId).getBody().getStatus());
        assertEquals(2, banchile.queries(51001));
    }

    // -------------------------------------------------------------------------
    // Force-check
    // -------------------------------------------------------------------------

    @Test
    void simultaneousForceChecksOfTheSamePaymentQueryBanchileOnceAndTheOthersRespondWithoutWaitingForIt() throws Exception {
        var reservationId = reservationPaidWithBanchile(51002);
        banchile.hold(51002, pending(51002));

        var checks = simultaneously(SIMULTANEOUS_REQUESTS, () -> paymentApiController.forceCheckStatus(reservationId));
        HeldQuery held;
        try {
            held = whileTheQueryIsHeld(51002, checks, SIMULTANEOUS_REQUESTS - 1);
        } finally {
            banchile.release(51002);
        }
        var outcomes = awaitAll(checks);

        log.info("[single-flight] {} force-checks simultáneos con la consulta a Banchile retenida: consultas={} latencias={} | {}",
            SIMULTANEOUS_REQUESTS, banchile.queries(51002), latencies(outcomes), held);
        assertEquals(SIMULTANEOUS_REQUESTS - 1, held.answered(),
            () -> "los force-checks que no consultan debían responder mientras la consulta seguía retenida en Banchile: " + held);
        assertEquals(1, banchile.queries(51002), "una sola consulta a Banchile por pago a la vez");
        for (var outcome : outcomes) {
            assertNull(outcome.failure(), () -> "el force-check falló: " + outcome.failure());
            assertEquals(HttpStatus.OK, outcome.value().getStatusCode());
            assertEquals(PaymentResult.pending("51002"), outcome.value().getBody(), "el pago sigue pendiente");
        }
        assertEquals(1, held.pool().maxActive(), () -> "solo la request que consulta a Banchile retiene una conexión: " + held.pool());
        assertEquals(0, held.pool().maxAwaiting(), () -> "nadie espera una conexión: " + held.pool());

        // terminada la consulta en curso, el force-check siguiente consulta de nuevo y aplica el resultado
        banchile.respond(51002, approved(51002), Duration.ZERO);
        var afterwards = paymentApiController.forceCheckStatus(reservationId);
        assertNotNull(afterwards.getBody());
        assertTrue(afterwards.getBody().isSuccessful(), afterwards.getBody().toString());
        assertEquals(2, banchile.queries(51002));
        assertEquals(COMPLETE, reservationStatus(reservationId));
    }

    @Test
    void forceCheckWithoutPendingTransactionStillAnswersNotFound() {
        var reservationId = reservationPaidWithBanchile(51005);
        banchile.respond(51005, approved(51005), Duration.ZERO);
        assertTrue(paymentApiController.forceCheckStatus(reservationId).getBody().isSuccessful());

        assertEquals(HttpStatus.NOT_FOUND, paymentApiController.forceCheckStatus(reservationId).getStatusCode());
        assertEquals(1, banchile.queries(51005));
    }

    @Test
    void forceCheckDuringAPollDoesNotQueryBanchileAgain() throws Exception {
        var reservationId = reservationPaidWithBanchile(51003);
        banchile.hold(51003, pending(51003));

        var poll = executor.submit(() -> timed(() -> reservationApiV2Controller.getReservationStatus(reservationId)));
        Timed<ResponseEntity<PaymentResult>> forceCheck;
        boolean pollStillQuerying;
        try {
            assertTrue(banchile.awaitQueryInFlight(51003, START_WAIT), "el poll no consultó a Banchile");
            forceCheck = timed(() -> paymentApiController.forceCheckStatus(reservationId));
            pollStillQuerying = !poll.isDone();
        } finally {
            banchile.release(51003);
        }
        var pollOutcome = poll.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertNull(forceCheck.failure(), () -> "el force-check falló: " + forceCheck.failure());
        assertTrue(pollStillQuerying, "el force-check debía responder mientras la consulta del poll seguía retenida, sin esperarla");
        assertEquals(PaymentResult.pending("51003"), forceCheck.value().getBody());
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, statusOf(pollOutcome));
        assertEquals(1, banchile.queries(51003), "el poll y el force-check comparten el turno del pago");
    }

    // -------------------------------------------------------------------------
    // El cron no pasa por el single-flight
    // -------------------------------------------------------------------------

    @Test
    void theCronDoesNotSkipThePaymentWhileAPollIsQueryingItAndWaitsForTheLock() throws Exception {
        var reservationId = reservationPaidWithBanchile(51006);
        banchile.hold(51006, pending(51006));
        var job = newReconciliationJob();

        var poll = executor.submit(() -> timed(() -> reservationApiV2Controller.getReservationStatus(reservationId)));
        Future<Timed<Integer>> cron;
        boolean cronWaitedForTheLock;
        int queriesWhileTheCronWaited;
        try {
            assertTrue(banchile.awaitQueryInFlight(51006, START_WAIT), "el poll no consultó a Banchile");
            // la consulta del poll ya tomó su respuesta (PENDING); la del cron verá el pago aprobado
            banchile.respond(51006, approved(51006), Duration.ZERO);
            cron = executor.submit(() -> timed(job::reconcilePendingBanchileTransactions));
            cronWaitedForTheLock = awaitARequestWaitingForALock(START_WAIT);
            queriesWhileTheCronWaited = banchile.queries(51006);
        } finally {
            banchile.release(51006);
        }
        var cronOutcome = cron.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        var pollOutcome = poll.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertTrue(cronWaitedForTheLock, "el cron debía quedar esperando el lock de la transacción que tiene el poll");
        assertEquals(1, queriesWhileTheCronWaited, "mientras espera el lock, el cron no consulta a Banchile");
        assertNull(cronOutcome.failure(), () -> "el cron falló: " + cronOutcome.failure());
        assertEquals(1, cronOutcome.value(), "el cron consulta y concilia el pago aunque haya un poll en curso");
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, statusOf(pollOutcome));
        assertEquals(2, banchile.queries(51006));
        assertEquals(COMPLETE, reservationStatus(reservationId));
    }

    // -------------------------------------------------------------------------
    // El turno se libera aunque Banchile no responda
    // -------------------------------------------------------------------------

    @Test
    void afterABanchileTimeoutTheNextPollQueriesAgain() throws Exception {
        var reservationId = reservationPaidWithBanchile(51004);
        // Banchile colgado: más que el tope del cliente
        banchile.respond(51004, approved(51004), Duration.ofSeconds(30));

        var hungPoll = executor.submit(() -> timed(() -> reservationApiV2Controller.getReservationStatus(reservationId)));
        assertTrue(banchile.awaitQueryInFlight(51004, START_WAIT), "el poll no consultó a Banchile");
        var duringTheHang = timed(() -> reservationApiV2Controller.getReservationStatus(reservationId));
        boolean hungPollStillWaiting = !hungPoll.isDone();
        var hung = hungPoll.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertTrue(hungPollStillWaiting, "mientras Banchile no responde, los demás polls responden sin esperar al que está colgado");
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, statusOf(duringTheHang));
        // cota inferior: el cliente espera su tope completo antes de rendirse, y la carga solo lo alarga
        assertTrue(hung.elapsed().compareTo(BANCHILE_TIMEOUT.minusSeconds(1)) > 0,
            () -> "el poll colgado debía volver por el tope del cliente y tardó " + hung.elapsed().toMillis() + " ms");
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, statusOf(hung), "un timeout no cambia nada");

        // el turno se liberó con el timeout: el próximo poll que deja pasar el freno consulta de nuevo y
        // aplica el resultado (acá lo deja pasar adelantando su reloj, para no depender de cuánto duró el cuelgue)
        banchile.respond(51004, approved(51004), Duration.ZERO);
        pollThrottleClock.letTheNextPollQuery();
        assertEquals(COMPLETE, reservationApiV2Controller.getReservationStatus(reservationId).getBody().getStatus());
        assertEquals(2, banchile.queries(51004));
    }

    // -------------------------------------------------------------------------
    // El convoy de la revisión adversarial
    // -------------------------------------------------------------------------

    /**
     * Un comprador en la pantalla de "procesando pago": el frontend hace un poll cada 2 s con
     * {@code setInterval}, sin esperar la respuesta anterior, y Banchile tarda 6 s en cada consulta.
     * El pool es el de los tests (5 conexiones; en producción son 10) con el connectionTimeout de
     * producción (1 s): una request que no consigue conexión en 1 s falla, como en el sitio real.
     */
    @Test
    void convoyOfPollsWithASlowBanchileDoesNotExhaustThePool() throws Exception {
        var reservationId = reservationPaidWithBanchile(55001);
        banchile.respond(55001, pending(55001), CONVOY_LATENCY);
        var hikari = (HikariDataSource) dataSource.getTargetDataSource();
        var pool = hikari.getHikariPoolMXBean();
        warmUp(hikari);
        long originalConnectionTimeout = setConnectionTimeout(hikari, PRODUCTION_CONNECTION_TIMEOUT_MS);
        var monitor = new ConvoyMonitor(pool, maxPoolSize(), jdbc);
        try {
            long start = System.nanoTime();
            var polls = pollEvery(POLL_INTERVAL, CONVOY_WINDOW, start, reservationId);
            sleepUntil(start, CONVOY_WINDOW.toMillis());
            int queriesInTheWindow = endTheWindow(55001);
            var outcomes = awaitAll(polls);
            monitor.stop();

            var failed = outcomes.stream().filter(o -> o.failure() != null).toList();
            var overImmediate = outcomes.stream().filter(o -> o.elapsed().compareTo(IMMEDIATE) >= 0).count();
            var waitedForBanchile = outcomes.stream().filter(o -> o.elapsed().compareTo(CONVOY_LATENCY) >= 0).count();
            log.info("""
                    [convoy] L={} s, un poll cada {} s durante {} s, pool de {} conexiones con connectionTimeout de {} ms:
                      polls={} ok={} fallidos={} {} | latencia p50={} ms máx={} ms | polls de {} ms o más={}, de L o más={}
                      Banchile: consultas en la ventana={} totales={} simultáneas máx={}
                      pool: {} | {}
                      canario (otra request del sitio, cada {} ms): {}""",
                CONVOY_LATENCY.toSeconds(), POLL_INTERVAL.toSeconds(), CONVOY_WINDOW.toSeconds(),
                maxPoolSize(), PRODUCTION_CONNECTION_TIMEOUT_MS,
                outcomes.size(), outcomes.size() - failed.size(), failed.size(), failureKinds(failed),
                percentile(outcomes, 50), percentile(outcomes, 100), IMMEDIATE.toMillis(), overImmediate, waitedForBanchile,
                queriesInTheWindow, banchile.queries(55001), banchile.maxQueriesInFlight(),
                monitor.poolSummary(), monitor.connectionTimeoutSummary(), ConvoyMonitor.CANARY_PERIOD.toMillis(), monitor.canarySummary());

            // el escenario solo vale si corrió con el connectionTimeout de producción de principio a fin
            assertEquals(List.of(PRODUCTION_CONNECTION_TIMEOUT_MS, PRODUCTION_CONNECTION_TIMEOUT_MS),
                List.of(monitor.minConnectionTimeout(), monitor.maxConnectionTimeout()),
                () -> "el connectionTimeout del pool debía quedar en " + PRODUCTION_CONNECTION_TIMEOUT_MS
                    + " ms durante todo el convoy: " + monitor.connectionTimeoutSummary());
            assertNoConvoy(outcomes, monitor, banchile, queriesInTheWindow, CONVOY_LATENCY);
        } finally {
            monitor.stop();
            setConnectionTimeout(hikari, originalConnectionTimeout);
        }
    }

    /**
     * Caracterización (revisión adversarial, ronda 4): con la base lenta y sin convoy, el veredicto
     * no falla. El mismo ritmo que el convoy (un poll cada 2 s, Banchile tarda 6 s), con la tabla de
     * reservas bloqueada desde una conexión fuera del pool entre 3,5 s y 8,7 s, como en una máquina
     * muy cargada: los polls omitidos esperan la base con su conexión tomada y llegan a ocupar casi
     * todo el pool, pero nadie espera una conexión, ningún poll falla, el resto del sitio sigue y hay
     * una sola consulta a Banchile a la vez. El veredicto de la ronda 4, que además exigía a lo sumo
     * 3 conexiones en uso, lo daba por convoy.
     */
    @Test
    void aSlowDatabaseWithoutAConvoyPassesTheConvoyVerdict() throws Exception {
        var reservationId = reservationPaidWithBanchile(55301);
        banchile.respond(55301, pending(55301), CONVOY_LATENCY);
        var hikari = (HikariDataSource) dataSource.getTargetDataSource();
        warmUp(hikari);
        long originalConnectionTimeout = setConnectionTimeout(hikari, PRODUCTION_CONNECTION_TIMEOUT_MS);
        var monitor = new ConvoyMonitor(hikari.getHikariPoolMXBean(), maxPoolSize(), jdbc);
        try {
            long start = System.nanoTime();
            var stall = executor.submit(() -> lockTheReservationsTable(start, STALL_FROM, STALL_UNTIL));
            var polls = pollEvery(POLL_INTERVAL, CONVOY_WINDOW, start, reservationId);
            sleepUntil(start, CONVOY_WINDOW.toMillis());
            int queriesInTheWindow = endTheWindow(55301);
            var stalled = stall.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            var outcomes = awaitAll(polls);
            monitor.stop();

            log.info("[base-lenta] tabla de reservas bloqueada de {} a {} ms | latencias={} | Banchile: consultas en la ventana={} totales={} "
                    + "simultáneas máx={} | {} | canario: {}",
                stalled.acquiredAtMillis(), stalled.releasedAtMillis(), outcomes.stream().map(o -> o.elapsed().toMillis()).toList(),
                queriesInTheWindow, banchile.queries(55301), banchile.maxQueriesInFlight(), monitor.poolSummary(), monitor.canarySummary());
            // el escenario solo vale si la base se trabó dentro de la ventana y retuvo varias conexiones a la vez
            assertTrue(stalled.acquiredAtMillis() < STALL_UNTIL.toMillis(), () -> "la tabla debía quedar bloqueada dentro de la ventana: " + stalled);
            assertTrue(monitor.maxActive() > 3,
                () -> "la base lenta debía retener más de 3 conexiones a la vez (el tope que tenía el veredicto): " + monitor.poolSummary());
            assertNoConvoy(outcomes, monitor, banchile, queriesInTheWindow, CONVOY_LATENCY);
        } finally {
            monitor.stop();
            setConnectionTimeout(hikari, originalConnectionTimeout);
        }
    }

    /**
     * Veredicto del convoy: ningún poll falló, el resto del sitio siguió consiguiendo conexiones, el
     * pool no se agotó, hubo una sola consulta a Banchile a la vez y solo tardaron lo que tarda
     * Banchile los polls que lo consultaron.
     *
     * <p>No se acota la cantidad de conexiones en uso: con la base lenta, varios polls omitidos
     * esperan la base con su conexión tomada y pueden ocupar el pool entero sin que haya convoy
     * (ver {@link #aSlowDatabaseWithoutAConvoyPassesTheConvoyVerdict}). El convoy deja requests
     * esperando una conexión y las hace fallar por el connectionTimeout de 1 s: eso es lo que se exige.
     *
     * <p>Tampoco se exige que ningún hilo figure esperando una conexión: HikariCP 5.1.0 cuenta como
     * esperando a todo hilo que busca una conexión libre en la lista compartida del pool
     * ({@code ConcurrentBag.borrow} incrementa {@code waiters} antes de recorrerla), así que una
     * muestra puede ver un hilo "esperando" con conexiones libres, sobre todo con la máquina cargada,
     * que lo deja sin CPU a mitad de la búsqueda. Lo que se exige es que el pool no se agote: todas
     * sus conexiones en uso con un hilo esperando, en dos muestras seguidas.
     *
     * <p>Un poll omitido no tiene un tope de latencia: una pausa de la base o del GC lo puede demorar.
     * Lo que delata un poll encolado detrás del lock de la transacción es tardar lo que tarda
     * Banchile: por eso solo cuentan como lentos los polls de {@code latency} o más (el que consulta
     * tarda al menos eso, porque el Banchile falso duerme {@code latency}), y no pueden ser más que
     * las consultas que respondió Banchile con esa latencia. Las consultas posteriores a la ventana,
     * que responde al instante, no cuentan: si contaran, cada poll encolado que drena la cola después
     * de la ventana sumaría una consulta y taparía su propia demora.
     *
     * @param outcomes           resultado de cada poll del convoy
     * @param monitor            mediciones del pool y del canario durante el convoy
     * @param banchile           Banchile falso del escenario
     * @param queriesInTheWindow consultas que Banchile respondió con {@code latency}, según {@link #endTheWindow}
     * @param latency            lo que tarda Banchile en responder cada consulta de la ventana
     */
    static void assertNoConvoy(List<? extends Timed<?>> outcomes, ConvoyMonitor monitor, FakeBanchile banchile, int queriesInTheWindow,
                               Duration latency) {
        var failed = outcomes.stream().filter(o -> o.failure() != null).toList();
        var slow = outcomes.stream().filter(o -> o.elapsed().compareTo(latency) >= 0).count();
        assertTrue(failed.isEmpty(), () -> failed.size() + " polls fallaron: " + failureKinds(failed));
        assertEquals(0, monitor.canaryFailures(), () -> "el resto del sitio se quedó sin conexiones: " + monitor.canarySummary());
        assertFalse(monitor.wasExhausted(), () -> "el pool se agotó, con requests esperando una conexión: " + monitor.poolSummary());
        assertEquals(1, banchile.maxQueriesInFlight(), "una sola consulta a Banchile a la vez");
        assertTrue(slow <= queriesInTheWindow,
            () -> "solo los polls que consultan a Banchile pueden tardar " + latency.toMillis() + " ms o más: lentos=" + slow
                + " consultas en la ventana=" + queriesInTheWindow + " latencias=" + latencies(outcomes));
    }

    /**
     * Un poll cada {@code interval} durante {@code window}, desde {@code startNanos}, sin esperar la
     * respuesta del anterior: como el {@code setInterval} del frontend.
     */
    private List<Future<Timed<ResponseEntity<ReservationStatusInfo>>>> pollEvery(Duration interval, Duration window, long startNanos,
                                                                                  String reservationId) throws InterruptedException {
        var polls = new ArrayList<Future<Timed<ResponseEntity<ReservationStatusInfo>>>>();
        for (long at = 0; at < window.toMillis(); at += interval.toMillis()) {
            sleepUntil(startNanos, at);
            polls.add(executor.submit(() -> timed(() -> reservationApiV2Controller.getReservationStatus(reservationId))));
        }
        return polls;
    }

    /**
     * Fin de la ventana del convoy: Banchile vuelve a responder al instante, para que la cola drene.
     * El Banchile falso cuenta cada consulta al recibirla, antes de leer la latencia configurada: las
     * consultas que alcanzaron a tomar la latencia de la ventana ya están contadas cuando se lee el
     * contador, después del cambio.
     *
     * @return las consultas del pago hasta el fin de la ventana
     */
    private int endTheWindow(int requestId) {
        banchile.respond(requestId, pending(requestId), Duration.ZERO);
        return banchile.queries(requestId);
    }

    /**
     * Cuándo se tomó y cuándo se soltó el bloqueo de la tabla de reservas, en ms desde el inicio del escenario.
     */
    record Stall(long acquiredAtMillis, long releasedAtMillis) {}

    /**
     * Bloquea la tabla de reservas desde una conexión propia, fuera del pool, entre {@code from} y
     * {@code until} (contados desde {@code startNanos}): mientras tanto, toda consulta a la tabla
     * espera, como con una base trabada.
     */
    private Stall lockTheReservationsTable(long startNanos, Duration from, Duration until) throws Exception {
        var hikari = (HikariDataSource) dataSource.getTargetDataSource();
        sleepUntil(startNanos, from.toMillis());
        try (var connection = DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("lock table tickets_reservation in access exclusive mode");
            }
            long acquiredAt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            sleepUntil(startNanos, until.toMillis());
            connection.rollback();
            return new Stall(acquiredAt, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        }
    }

    // -------------------------------------------------------------------------
    // Tope global: a lo sumo 5 consultas a Banchile a la vez desde las requests públicas
    // -------------------------------------------------------------------------

    /**
     * 8 compradores distintos en "procesando pago" a la vez, con las consultas a Banchile retenidas y
     * el pool de producción (10 conexiones, connectionTimeout de 1 s): 5 consultan y quedan
     * esperando a Banchile con una conexión cada uno, los otros 3 responden con el estado actual sin
     * consultar, y el resto del sitio sigue consiguiendo conexiones.
     */
    @Test
    void eightSimultaneousBuyersQueryBanchileAtMostFiveAtATime() throws Exception {
        var buyers = new ArrayList<String>();
        for (int i = 0; i < 8; i++) {
            buyers.add(reservationPaidWithBanchile(57001 + i));
            banchile.hold(57001 + i, pending(57001 + i));
        }
        try (var ignored = productionPool()) {
            var polls = simultaneously(buyers.stream()
                .<Supplier<ResponseEntity<ReservationStatusInfo>>>map(id -> () -> reservationApiV2Controller.getReservationStatus(id))
                .toList());
            boolean fiveQuerying;
            int answered;
            PoolUsage poolWhileHeld;
            Timed<Integer> canary;
            int stillQuerying;
            try {
                fiveQuerying = banchile.awaitQueriesInFlight(5, START_WAIT);
                answered = awaitDone(polls, 3, ANSWERED_WHILE_HELD);
                poolWhileHeld = poolUsage();
                // otra request del sitio, con las 5 consultas en curso
                canary = timed(() -> jdbc.queryForObject("select 1", Map.of(), Integer.class));
                stillQuerying = polls.size() - done(polls);
            } finally {
                for (int i = 0; i < 8; i++) {
                    banchile.release(57001 + i);
                }
            }
            var outcomes = awaitAll(polls);
            int queries = 0;
            for (int i = 0; i < 8; i++) {
                queries += banchile.queries(57001 + i);
            }

            log.info("[tope] 8 compradores simultáneos con las consultas retenidas: consultas={} simultáneas máx={} "
                    + "respondieron mientras tanto={} latencias={} | pool con las consultas retenidas: {} | canario: {}",
                queries, banchile.maxQueriesInFlight(), answered, latencies(outcomes), poolWhileHeld,
                canary.failure() != null ? canary.failure() : canary.elapsed().toMillis() + " ms");
            assertTrue(fiveQuerying, "5 compradores debían quedar consultando a Banchile");
            assertEquals(5, queries, "5 compradores consultan; los otros 3 no");
            assertEquals(5, banchile.maxQueriesInFlight(), "nunca más de 5 consultas a Banchile a la vez");
            assertEquals(3, answered, "los 3 que no consultan debían responder mientras las 5 consultas seguían retenidas");
            assertEquals(5, stillQuerying, "las 5 requests que consultan seguían esperando a Banchile");
            for (var outcome : outcomes) {
                assertEquals(EXTERNAL_PROCESSING_PAYMENT, statusOf(outcome), "cada poll responde con el estado actual de su reserva");
            }
            assertEquals(5, poolWhileHeld.maxActive(), () -> "solo las 5 requests que consultan retienen una conexión: " + poolWhileHeld);
            assertEquals(0, poolWhileHeld.maxAwaiting(), () -> "nadie debía esperar una conexión: " + poolWhileHeld);
            assertNull(canary.failure(), () -> "el resto del sitio se quedó sin conexiones: " + canary.failure());
        }
    }

    @Test
    void withTheFiveSlotsTakenOtherBuyersAreAnsweredWithoutQueryingUntilASlotIsFreed() throws Exception {
        // 5 compradores ocupan los cupos, con sus consultas retenidas en Banchile
        var holders = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            holders.add(reservationPaidWithBanchile(57101 + i));
            banchile.hold(57101 + i, pending(57101 + i));
        }
        var next = reservationPaidWithBanchile(57106);
        banchile.respond(57106, pending(57106), Duration.ZERO);
        try (var ignored = productionPool()) {
            var holding = new ArrayList<Future<Timed<ResponseEntity<ReservationStatusInfo>>>>();
            try {
                for (int i = 0; i < 5; i++) {
                    var holder = holders.get(i);
                    holding.add(executor.submit(() -> timed(() -> reservationApiV2Controller.getReservationStatus(holder))));
                    assertTrue(banchile.awaitQueryInFlight(57101 + i, START_WAIT), "el comprador " + i + " no consultó a Banchile");
                }

                // con los 5 cupos ocupados, el poll y el force-check de otro comprador responden sin consultar
                var poll = timed(() -> reservationApiV2Controller.getReservationStatus(next));
                var forceCheck = timed(() -> paymentApiController.forceCheckStatus(next));
                assertEquals(0, done(holding), "las 5 consultas seguían retenidas mientras respondían el poll y el force-check");
                assertEquals(EXTERNAL_PROCESSING_PAYMENT, statusOf(poll));
                assertNull(forceCheck.failure(), () -> "el force-check falló: " + forceCheck.failure());
                assertEquals(HttpStatus.OK, forceCheck.value().getStatusCode());
                assertEquals(PaymentResult.pending("57106"), forceCheck.value().getBody(), "el force-check responde como uno omitido");
                assertEquals(0, banchile.queries(57106), "con los 5 cupos ocupados no se consulta a Banchile");

                // termina la primera consulta: el cupo libre lo toma el próximo poll que deja pasar el freno
                // (el poll rechazado por el tope contó como consulta para el freno: con los cupos llenos, el
                // comprador espera los 10 s antes de volver a intentar)
                banchile.release(57101);
                assertEquals(EXTERNAL_PROCESSING_PAYMENT, statusOf(holding.get(0).get(WAIT.toSeconds(), TimeUnit.SECONDS)));
                pollThrottleClock.letTheNextPollQuery();
                assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationApiV2Controller.getReservationStatus(next).getBody().getStatus());
                assertEquals(1, banchile.queries(57106), "liberado un cupo, el poll siguiente consulta");
            } finally {
                for (int i = 0; i < 5; i++) {
                    banchile.release(57101 + i);
                }
            }
            for (var outcome : awaitAll(holding)) {
                assertEquals(EXTERNAL_PROCESSING_PAYMENT, statusOf(outcome));
            }
            assertTrue(banchile.maxQueriesInFlight() <= 5, () -> "hubo " + banchile.maxQueriesInFlight() + " consultas a la vez");
        }
    }

    /**
     * El tope es solo para las requests públicas: con los 5 cupos ocupados, la limpieza de reservas
     * vencidas, el stuck-check y el cron de conciliación igual consultan a Banchile.
     */
    @Test
    void theCleanupTheStuckCheckAndTheCronIgnoreTheLimitOfPublicChecks() throws Exception {
        var holders = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            holders.add(reservationPaidWithBanchile(57201 + i));
            banchile.hold(57201 + i, pending(57201 + i));
        }
        // reserva PENDING vencida con la transacción Banchile PENDING: la revisa la limpieza
        var expiredPending = reservationPaidWithBanchile(57206);
        ticketReservationRepository.updateReservationStatus(expiredPending, PENDING.name());
        ticketReservationRepository.updateValidity(expiredPending, DateUtils.addMinutes(new Date(), -5));
        banchile.respond(57206, pending(57206), Duration.ZERO);
        // reserva vencida esperando el pago: la revisa el stuck-check
        var expiredInPayment = reservationPaidWithBanchile(57207);
        ticketReservationRepository.updateValidity(expiredInPayment, DateUtils.addMinutes(new Date(), -40));
        banchile.respond(57207, pending(57207), Duration.ZERO);
        // reserva vigente esperando el pago: la revisa el cron. El cron recorre las transacciones por
        // t_timestamp: esta va primero, para que la consulte con los 5 cupos ocupados (las de los 5
        // compradores las tienen bloqueadas sus polls, y el cron espera esos locks)
        reservationPaidWithBanchile(57208);
        backdate(57208, Duration.ofMinutes(1));
        banchile.respond(57208, pending(57208), Duration.ZERO);
        try (var ignored = productionPool()) {
            var holding = new ArrayList<Future<Timed<ResponseEntity<ReservationStatusInfo>>>>();
            Future<Timed<Integer>> cron;
            try {
                for (int i = 0; i < 5; i++) {
                    var holder = holders.get(i);
                    holding.add(executor.submit(() -> timed(() -> reservationApiV2Controller.getReservationStatus(holder))));
                    assertTrue(banchile.awaitQueryInFlight(57201 + i, START_WAIT), "el comprador " + i + " no consultó a Banchile");
                }

                ticketReservationManager.cleanupExpiredReservations(new Date());
                assertEquals(1, banchile.queries(57206), "la limpieza consulta aunque los 5 cupos estén ocupados");
                ticketReservationManager.markExpiredInPaymentReservationAsStuck(new Date());
                assertEquals(1, banchile.queries(57207), "el stuck-check consulta aunque los 5 cupos estén ocupados");
                var job = newReconciliationJob();
                cron = executor.submit(() -> timed(job::reconcilePendingBanchileTransactions));
                assertTrue(banchile.awaitQueries(57208, 1, START_WAIT), "el cron consulta aunque los 5 cupos estén ocupados");
                assertEquals(0, done(holding), "los 5 cupos seguían ocupados mientras consultaban la limpieza, el stuck-check y el cron");
            } finally {
                for (int i = 0; i < 5; i++) {
                    banchile.release(57201 + i);
                }
            }
            var cronOutcome = cron.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertNull(cronOutcome.failure(), () -> "el cron falló: " + cronOutcome.failure());
            awaitAll(holding);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers: escenario
    // -------------------------------------------------------------------------

    /**
     * Reserva de 2 entradas que el comprador mandó a pagar con Banchile: pasa por
     * {@link BanchilePagosWebhookManager#doPayment}, que deja la reserva en
     * EXTERNAL_PROCESSING_PAYMENT y la transacción BANCHILE en PENDING con el requestId dado.
     */
    private String reservationPaidWithBanchile(int requestId) {
        var event = createEvent();
        var category = ticketCategoryRepository.findAllTicketCategories(event.getId()).get(0);
        var ticketRequest = new TicketReservationModification();
        ticketRequest.setTicketCategoryId(category.getId());
        ticketRequest.setQuantity(2);
        var reservationId = ticketReservationManager.createTicketReservation(event,
            List.of(new TicketReservationWithOptionalCodeModification(ticketRequest, Optional.empty())), List.of(),
            DateUtils.addMinutes(new Date(), 25), Optional.empty(), Locale.ENGLISH, false, null);
        ticketReservationRepository.updateTicketReservation(reservationId, PENDING.name(), BUYER_EMAIL,
            "Comprador Prueba", "Comprador", "Prueba", "en", null, null, null, null);

        banchile.nextSession(requestId);
        var reservation = ticketReservationRepository.findReservationById(reservationId);
        var totalPrice = ticketReservationManager.totalReservationCostWithVAT(reservation).getLeft();
        var spec = new PaymentSpecification(reservation, totalPrice, event, null,
            ticketReservationManager.orderSummaryForReservation(reservation, event), true, true);
        assertTrue(banchilePagosWebhookManager.doPayment(spec).isRedirect(), "doPayment debía redirigir a Banchile");
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
        return reservationId;
    }

    private Event createEvent() {
        var clock = ClockProvider.clock();
        var categories = List.of(new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
            new DateTimeModification(LocalDate.now(clock).minusDays(1), LocalTime.now(clock)),
            new DateTimeModification(LocalDate.now(clock).plusDays(1), LocalTime.now(clock)),
            Map.of("en", "desc"), BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        return initEvent(categories, organizationRepository, userManager, eventManager, eventRepository).getLeft();
    }

    private TicketReservationStatus reservationStatus(String reservationId) {
        return ticketReservationRepository.findOptionalStatusAndValidationById(reservationId)
            .map(TicketReservationStatusAndValidation::getStatus)
            .orElseThrow(() -> new AssertionError("la reserva " + reservationId + " no existe"));
    }

    private BanchileReconciliationJob newReconciliationJob() {
        return new BanchileReconciliationJob(transactionRepository, ticketReservationManager,
            ticketReservationRepository, purchaseContextManager, banchilePagosWebhookManager,
            new TransactionTemplate(transactionManager), clockProvider);
    }

    /**
     * Mueve hacia atrás el {@code t_timestamp} de la transacción del pago: con el reloj fijo de los
     * tests, todas las transacciones tienen la misma hora y el cron las recorre en cualquier orden.
     */
    private void backdate(int requestId, Duration age) {
        var createdAt = ZonedDateTime.now(ClockProvider.clock()).minus(age).toOffsetDateTime();
        assertEquals(1, jdbc.update("update b_transaction set t_timestamp = :createdAt where gtw_payment_id = :requestId",
            Map.of("createdAt", createdAt, "requestId", String.valueOf(requestId))));
    }

    // -------------------------------------------------------------------------
    // Helpers: concurrencia y medición
    // -------------------------------------------------------------------------

    /**
     * Resultado de una request con lo que tardó: la respuesta o la excepción que lanzó.
     */
    record Timed<T>(Duration elapsed, T value, RuntimeException failure) {}

    static <T> Timed<T> timed(Supplier<T> request) {
        long start = System.nanoTime();
        try {
            var value = request.get();
            return new Timed<>(Duration.ofNanos(System.nanoTime() - start), value, null);
        } catch (RuntimeException e) {
            return new Timed<>(Duration.ofNanos(System.nanoTime() - start), null, e);
        }
    }

    /**
     * Lanza {@code count} requests que arrancan a la vez.
     */
    private <T> List<Future<Timed<T>>> simultaneously(int count, Supplier<T> request) throws InterruptedException {
        return simultaneously(Collections.nCopies(count, request));
    }

    /**
     * Lanza las requests dadas, que arrancan a la vez.
     */
    private <T> List<Future<Timed<T>>> simultaneously(List<Supplier<T>> requests) throws InterruptedException {
        var ready = new CountDownLatch(requests.size());
        var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<Timed<T>>>();
        for (var request : requests) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await();
                return timed(request);
            }));
        }
        assertTrue(ready.await(START_WAIT.toSeconds(), TimeUnit.SECONDS), "no arrancaron todas las requests");
        go.countDown();
        return futures;
    }

    private static <T> List<Timed<T>> awaitAll(List<Future<Timed<T>>> futures) throws Exception {
        var outcomes = new ArrayList<Timed<T>>();
        for (var future : futures) {
            outcomes.add(future.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        }
        return outcomes;
    }

    /**
     * Cuántas de las requests terminaron.
     */
    private static int done(List<? extends Future<?>> requests) {
        return (int) requests.stream().filter(Future::isDone).count();
    }

    /**
     * Espera, a lo sumo {@code timeout}, a que terminen {@code expected} de las requests.
     *
     * @return cuántas habían terminado al cumplirse la espera
     */
    private static int awaitDone(List<? extends Future<?>> requests, int expected, Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        int done;
        while ((done = done(requests)) < expected && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        return done;
    }

    /**
     * Lo que pasó mientras Banchile retenía la consulta en curso: cuántas requests respondieron y el
     * uso del pool después de que respondieran, con la consulta todavía retenida.
     */
    record HeldQuery(int answered, int doneAfterSampling, PoolUsage pool) {
        @Override
        public String toString() {
            return "respondieron con la consulta retenida=" + answered + " (terminadas después de medir el pool=" + doneAfterSampling
                + ") | pool con la consulta retenida: " + pool;
        }
    }

    /**
     * Con la consulta del pago retenida en Banchile: espera a que respondan las requests que no la
     * esperan, mide el pool y verifica que la que consulta sigue esperando a Banchile. El llamador
     * suelta la consulta después.
     *
     * @param requestId        pago cuya consulta está retenida
     * @param requests         todas las requests del escenario, también la que consulta
     * @param expectedAnswered cuántas deberían responder sin esperar la consulta
     */
    private HeldQuery whileTheQueryIsHeld(int requestId, List<? extends Future<?>> requests, int expectedAnswered) throws InterruptedException {
        assertTrue(banchile.awaitQueryInFlight(requestId, START_WAIT), "nadie consultó a Banchile");
        int answered = awaitDone(requests, expectedAnswered, ANSWERED_WHILE_HELD);
        var pool = poolUsage();
        int doneAfterSampling = done(requests);
        assertEquals(answered, doneAfterSampling, "la request que consulta debía seguir esperando a Banchile mientras se medía el pool");
        return new HeldQuery(answered, doneAfterSampling, pool);
    }

    /**
     * Espera, a lo sumo {@code timeout}, a que alguna conexión de la base quede esperando un lock
     * ({@code pg_stat_activity}): la forma de saber, sin medir tiempos, que una request está
     * bloqueada en un {@code FOR UPDATE}.
     */
    private boolean awaitARequestWaitingForALock(Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
                + "and wait_event_type = 'Lock'", Map.of(), Integer.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static TicketReservationStatus statusOf(Timed<ResponseEntity<ReservationStatusInfo>> outcome) {
        assertNull(outcome.failure(), () -> "el poll falló: " + outcome.failure());
        assertNotNull(outcome.value().getBody());
        return outcome.value().getBody().getStatus();
    }

    private static List<Long> latencies(List<? extends Timed<?>> outcomes) {
        return outcomes.stream().map(o -> o.elapsed().toMillis()).sorted().toList();
    }

    private static long percentile(List<? extends Timed<?>> outcomes, int percentile) {
        var sorted = latencies(outcomes);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static Map<String, Long> failureKinds(List<? extends Timed<?>> failed) {
        var kinds = new TreeMap<String, Long>();
        failed.forEach(o -> kinds.merge(o.failure().getClass().getSimpleName(), 1L, Long::sum));
        return kinds;
    }

    private static void sleepUntil(long startNanos, long offsetMillis) throws InterruptedException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(startNanos + TimeUnit.MILLISECONDS.toNanos(offsetMillis) - System.nanoTime());
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }

    /**
     * Uso del pool en este momento: el máximo de conexiones en uso y de hilos esperando una
     * conexión, en 10 muestras durante 200 ms.
     */
    private PoolUsage poolUsage() throws InterruptedException {
        var pool = pool();
        int maxActive = 0;
        int maxAwaiting = 0;
        for (int i = 0; i < 10; i++) {
            maxActive = Math.max(maxActive, pool.getActiveConnections());
            maxAwaiting = Math.max(maxAwaiting, pool.getThreadsAwaitingConnection());
            Thread.sleep(20);
        }
        return new PoolUsage(maxActive, maxAwaiting, maxPoolSize());
    }

    record PoolUsage(int maxActive, int maxAwaiting, int poolSize) {
        @Override
        public String toString() {
            return "conexiones en uso máx=" + maxActive + "/" + poolSize + ", hilos esperando conexión máx=" + maxAwaiting;
        }
    }

    private HikariPoolMXBean pool() {
        return ((HikariDataSource) dataSource.getTargetDataSource()).getHikariPoolMXBean();
    }

    private int maxPoolSize() {
        return ((HikariDataSource) dataSource.getTargetDataSource()).getMaximumPoolSize();
    }

    /**
     * Deja el pool de este test con el tamaño y el connectionTimeout de producción hasta que se
     * cierre el resultado. Abre de antemano las conexiones nuevas, para que ninguna request espere a
     * que se creen.
     */
    private AutoCloseable productionPool() throws Exception {
        var hikari = (HikariDataSource) dataSource.getTargetDataSource();
        int originalSize = hikari.getMaximumPoolSize();
        hikari.getHikariConfigMXBean().setMaximumPoolSize(PRODUCTION_POOL_SIZE);
        warmUp(hikari);
        long originalTimeout = setConnectionTimeout(hikari, PRODUCTION_CONNECTION_TIMEOUT_MS);
        return () -> {
            setConnectionTimeout(hikari, originalTimeout);
            hikari.getHikariConfigMXBean().setMaximumPoolSize(originalSize);
        };
    }

    /**
     * Abre de antemano todas las conexiones del pool. {@code DataCleaner} recrea el pool después de
     * cada test, y el pool nuevo se llena en segundo plano: mientras tanto, una request que no
     * encuentra una conexión libre espera a que se cree otra y figura esperando una conexión aunque
     * el pool esté lejos de agotarse.
     */
    private static void warmUp(HikariDataSource hikari) throws java.sql.SQLException {
        var connections = new ArrayList<java.sql.Connection>();
        try {
            for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
                connections.add(hikari.getConnection());
            }
        } finally {
            for (var connection : connections) {
                connection.close();
            }
        }
    }

    /**
     * El tiempo máximo que una request espera hoy una conexión del pool: el campo con que el pool
     * decide, no el valor configurado.
     */
    static long effectiveConnectionTimeout(HikariPoolMXBean pool) {
        try {
            Field field = HikariPool.class.getSuperclass().getDeclaredField("connectionTimeout");
            field.setAccessible(true);
            return field.getLong(pool);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Fija el tiempo máximo que una request espera una conexión del pool. HikariCP 5.1.0 lo copia de
     * la configuración al pool al crearlo, y su HouseKeeper lo vuelve a copiar a los 100 ms y después
     * cada 30 s: por eso se fija en la configuración (el MXBean), para que el HouseKeeper no lo
     * revierta en medio del escenario, y también en el pool por reflexión, para que rija desde ya y no
     * recién en la próxima pasada del HouseKeeper. {@code DataCleaner} recrea el pool con la
     * configuración original después de cada test.
     *
     * @return el valor configurado antes del cambio
     */
    static long setConnectionTimeout(HikariDataSource dataSource, long timeoutMillis) throws ReflectiveOperationException {
        var config = dataSource.getHikariConfigMXBean();
        long previous = config.getConnectionTimeout();
        config.setConnectionTimeout(timeoutMillis);
        Field field = HikariPool.class.getSuperclass().getDeclaredField("connectionTimeout");
        field.setAccessible(true);
        field.setLong(dataSource.getHikariPoolMXBean(), timeoutMillis);
        return previous;
    }

    /**
     * Mide el pool y la disponibilidad del resto del sitio durante el convoy: muestrea el pool cada
     * 25 ms (también el connectionTimeout con que decide) y cada 250 ms corre un "canario", una
     * consulta cualquiera que necesita una conexión, como cualquier otra request del sitio.
     *
     * <p>El pool está agotado en una muestra si todas sus conexiones están en uso y hay un hilo
     * esperando una. Cuenta como agotado recién con {@value #EXHAUSTED_SAMPLES} muestras seguidas
     * (25 ms o más): las dos lecturas de una muestra no son atómicas, y un hilo que busca una
     * conexión figura como esperando aunque la encuentre (ver {@link #assertNoConvoy}).
     */
    static final class ConvoyMonitor {

        static final Duration CANARY_PERIOD = Duration.ofMillis(250);
        static final int EXHAUSTED_SAMPLES = 2;

        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        private final long start = System.nanoTime();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicInteger maxAwaiting = new AtomicInteger();
        private final AtomicLong exhaustedSinceMillis = new AtomicLong(-1);
        private final AtomicInteger exhaustedStreak = new AtomicInteger();
        private final AtomicInteger longestExhaustedStreak = new AtomicInteger();
        private final AtomicLong minConnectionTimeout = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxConnectionTimeout = new AtomicLong(Long.MIN_VALUE);
        private final AtomicLong connectionTimeoutChangedAtMillis = new AtomicLong(-1);
        private final AtomicInteger canaryRuns = new AtomicInteger();
        private final AtomicInteger canaryFailures = new AtomicInteger();
        private final AtomicLong canaryMaxLatencyMillis = new AtomicLong();
        private final AtomicReference<String> canaryFirstFailure = new AtomicReference<>();
        private final int poolSize;

        ConvoyMonitor(HikariPoolMXBean pool, int poolSize, NamedParameterJdbcTemplate jdbc) {
            this.poolSize = poolSize;
            long initialConnectionTimeout = effectiveConnectionTimeout(pool);
            scheduler.scheduleAtFixedRate(() -> {
                int active = pool.getActiveConnections();
                int awaiting = pool.getThreadsAwaitingConnection();
                maxActive.accumulateAndGet(active, Math::max);
                maxAwaiting.accumulateAndGet(awaiting, Math::max);
                if (active >= poolSize && awaiting > 0) {
                    exhaustedSinceMillis.compareAndSet(-1, elapsedMillis());
                    longestExhaustedStreak.accumulateAndGet(exhaustedStreak.incrementAndGet(), Math::max);
                } else {
                    exhaustedStreak.set(0);
                }
                long connectionTimeout = effectiveConnectionTimeout(pool);
                minConnectionTimeout.accumulateAndGet(connectionTimeout, Math::min);
                maxConnectionTimeout.accumulateAndGet(connectionTimeout, Math::max);
                if (connectionTimeout != initialConnectionTimeout) {
                    connectionTimeoutChangedAtMillis.compareAndSet(-1, elapsedMillis());
                }
            }, 0, 25, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(() -> {
                long canaryStart = System.nanoTime();
                try {
                    jdbc.queryForObject("select 1", Map.of(), Integer.class);
                } catch (RuntimeException e) {
                    canaryFailures.incrementAndGet();
                    canaryFirstFailure.compareAndSet(null, e.getClass().getSimpleName() + " a los " + elapsedMillis() + " ms");
                } finally {
                    canaryRuns.incrementAndGet();
                    canaryMaxLatencyMillis.accumulateAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - canaryStart), Math::max);
                }
            }, 0, CANARY_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
        }

        private long elapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }

        void stop() throws InterruptedException {
            scheduler.shutdownNow();
            assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
        }

        int maxActive() {
            return maxActive.get();
        }

        int maxAwaiting() {
            return maxAwaiting.get();
        }

        /**
         * true si el pool estuvo agotado, con todas sus conexiones en uso y un hilo esperando una,
         * en {@value #EXHAUSTED_SAMPLES} muestras seguidas o más.
         */
        boolean wasExhausted() {
            return longestExhaustedStreak.get() >= EXHAUSTED_SAMPLES;
        }

        int canaryFailures() {
            return canaryFailures.get();
        }

        long minConnectionTimeout() {
            return minConnectionTimeout.get();
        }

        long maxConnectionTimeout() {
            return maxConnectionTimeout.get();
        }

        String connectionTimeoutSummary() {
            long changedAt = connectionTimeoutChangedAtMillis.get();
            return "connectionTimeout mín=" + minConnectionTimeout.get() + " ms máx=" + maxConnectionTimeout.get() + " ms"
                + (changedAt < 0 ? ", sin cambios" : ", cambió a los " + changedAt + " ms");
        }

        String poolSummary() {
            long exhausted = exhaustedSinceMillis.get();
            return "conexiones en uso máx=" + maxActive.get() + "/" + poolSize + ", hilos esperando conexión máx=" + maxAwaiting.get()
                + ", pool agotado " + (exhausted < 0 ? "nunca" : "desde t=" + exhausted + " ms, " + longestExhaustedStreak.get()
                + " muestras seguidas como máximo");
        }

        String canarySummary() {
            return "ejecuciones=" + canaryRuns.get() + " fallidas=" + canaryFailures.get() + " latencia máx="
                + canaryMaxLatencyMillis.get() + " ms" + (canaryFirstFailure.get() != null ? ", primera falla: " + canaryFirstFailure.get() : "");
        }
    }

    // -------------------------------------------------------------------------
    // Banchile falso
    // -------------------------------------------------------------------------

    private static String pending(int requestId) {
        return """
            {"requestId": %d,
             "status": {"status": "PENDING", "reason": "PC", "message": "La petición se encuentra activa", "date": "2026-10-13T11:45:00-03:00"}}
            """.formatted(requestId);
    }

    private static String approved(int requestId) {
        return """
            {"requestId": %d,
             "status": {"status": "APPROVED", "reason": "00", "message": "La petición ha sido aprobada exitosamente", "date": "2026-10-13T11:52:10-03:00"},
             "payment": [{"status": {"status": "APPROVED", "reason": "00", "message": "Aprobada", "date": "2026-10-13T11:52:10-03:00"},
                          "internalReference": %d, "paymentMethod": "visa", "paymentMethodName": "Visa", "issuerName": "BANCO DE PRUEBA",
                          "amount": {"from": {"total": 20, "currency": "CHF"}, "to": {"total": 20, "currency": "CHF"}, "factor": 1},
                          "authorization": "000003", "reference": "REF%d", "receipt": "6145%d", "franchise": "visa", "refunded": false,
                          "processorFields": [{"keyword": "lastDigits", "value": "4242", "displayOn": "none"}]}]}
            """.formatted(requestId, 900000 + requestId, requestId, requestId);
    }

    /**
     * Banchile falso sobre el {@link HttpServer} del JDK: responde {@code createSession} al instante
     * con el requestId que se le indique y {@code querySession} con el cuerpo y la latencia
     * configurados para cada pago, o retiene la consulta hasta que el test la suelte
     * ({@link #hold}). Cuenta las consultas de cada pago y cuántas hay en curso a la vez.
     */
    static final class FakeBanchile implements AutoCloseable {

        private static final String LOOPBACK = "127.0.0.1";
        /** Tope de seguridad de una consulta retenida: si el test no la suelta, responde igual. */
        private static final Duration HOLD_LIMIT = Duration.ofSeconds(60);

        private record QueryResponse(String body, Duration latency, CountDownLatch gate) {}

        private final ExecutorService handlers = Executors.newCachedThreadPool();
        private final HttpServer server;
        private final Map<Integer, QueryResponse> responses = new ConcurrentHashMap<>();
        private final Map<Integer, CountDownLatch> gates = new ConcurrentHashMap<>();
        private final Map<Integer, AtomicInteger> queries = new ConcurrentHashMap<>();
        private final Map<Integer, AtomicInteger> queriesInFlight = new ConcurrentHashMap<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private volatile int nextSessionRequestId;

        FakeBanchile() throws IOException {
            server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
            // un hilo por request: el executor por defecto del HttpServer atiende de a una
            server.setExecutor(handlers);
            server.createContext("/api/session", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://" + LOOPBACK + ":" + server.getAddress().getPort();
        }

        void nextSession(int requestId) {
            nextSessionRequestId = requestId;
        }

        void respond(int requestId, String body, Duration latency) {
            responses.put(requestId, new QueryResponse(body, latency, null));
        }

        /**
         * Las consultas del pago quedan retenidas, sin responder, hasta {@link #release}: así el test
         * decide cuándo termina la consulta en curso, sin depender de cuánto tarde la máquina.
         */
        void hold(int requestId, String body) {
            var gate = new CountDownLatch(1);
            gates.put(requestId, gate);
            responses.put(requestId, new QueryResponse(body, Duration.ZERO, gate));
        }

        /**
         * Suelta las consultas retenidas del pago; las que lleguen después responden al instante.
         */
        void release(int requestId) {
            var gate = gates.get(requestId);
            if (gate != null) {
                gate.countDown();
            }
        }

        int queries(int requestId) {
            return counter(queries, requestId).get();
        }

        int maxQueriesInFlight() {
            return maxInFlight.get();
        }

        boolean awaitQueryInFlight(int requestId, Duration timeout) throws InterruptedException {
            return awaitCondition(() -> counter(queriesInFlight, requestId).get() > 0, timeout);
        }

        /**
         * Espera a que haya {@code count} consultas en curso, sumando todos los pagos.
         */
        boolean awaitQueriesInFlight(int count, Duration timeout) throws InterruptedException {
            return awaitCondition(() -> inFlight.get() >= count, timeout);
        }

        /**
         * Espera a que el pago haya recibido {@code count} consultas.
         */
        boolean awaitQueries(int requestId, int count, Duration timeout) throws InterruptedException {
            return awaitCondition(() -> queries(requestId) >= count, timeout);
        }

        private static boolean awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
            long end = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < end) {
                if (condition.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(10);
            }
            return condition.getAsBoolean();
        }

        private static AtomicInteger counter(Map<Integer, AtomicInteger> counters, int requestId) {
            return counters.computeIfAbsent(requestId, k -> new AtomicInteger());
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                var path = exchange.getRequestURI().getPath();
                String body;
                if ("/api/session".equals(path)) {
                    int requestId = nextSessionRequestId;
                    body = """
                        {"status": {"status": "OK", "reason": "PC", "message": "La petición se ha procesado correctamente",
                                    "date": "2026-10-13T11:40:00-03:00"},
                         "requestId": %d, "processUrl": "https://checkout.banchilepagos.cl/spa/session/%d/abc"}
                        """.formatted(requestId, requestId);
                } else {
                    int requestId = Integer.parseInt(path.substring("/api/session/".length()));
                    // se cuenta antes de leer la respuesta configurada (ver endTheWindow)
                    counter(queries, requestId).incrementAndGet();
                    var response = Objects.requireNonNull(responses.get(requestId), "sin respuesta configurada para " + requestId);
                    counter(queriesInFlight, requestId).incrementAndGet();
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        if (response.gate() != null) {
                            response.gate().await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
                        } else {
                            Thread.sleep(response.latency().toMillis());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } finally {
                        inFlight.decrementAndGet();
                        counter(queriesInFlight, requestId).decrementAndGet();
                    }
                    body = response.body();
                }
                byte[] bytes = body.getBytes(UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    /**
     * Cliente Banchile con un tope de {@link #BANCHILE_TIMEOUT}, por encima de la latencia del
     * convoy. Vive en {@code alfio.job}, paquete que ningún {@code @ComponentScan} recorre: solo lo
     * usa este contexto.
     */
    @Configuration(proxyBeanMethods = false)
    static class BanchileTimeoutConfiguration {

        @Bean
        @Primary
        BanchilePagosClient banchilePagosClientWithTestTimeout(HttpClient httpClient) {
            return new BanchilePagosClient(httpClient, BANCHILE_TIMEOUT);
        }
    }
}
