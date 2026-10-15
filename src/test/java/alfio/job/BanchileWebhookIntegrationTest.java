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
import alfio.config.MvcConfiguration;
import alfio.config.WebSecurityConfig;
import alfio.config.authentication.FormBasedWebSecurity;
import alfio.controller.api.ControllerConfiguration;
import alfio.controller.api.v2.model.ReservationStatusInfo;
import alfio.controller.api.v2.user.ReservationApiV2Controller;
import alfio.controller.payment.api.banchile.BanchilePaymentWebhookController;
import alfio.job.BanchilePaymentCheckSingleFlightIntegrationTest.FakeBanchile;
import alfio.job.BanchileReconciliationIntegrationTest.LogCapture;
import alfio.manager.AdminReservationManager;
import alfio.manager.EventManager;
import alfio.manager.PurchaseContextManager;
import alfio.manager.RecaptchaService;
import alfio.manager.TicketReservationManager;
import alfio.manager.payment.BanchilePagosWebhookManager;
import alfio.manager.payment.BanchileWebhookDispatcher;
import alfio.manager.payment.BanchileWebhookDispatcher.CheckResult;
import alfio.manager.payment.BanchileWebhookDispatcher.Dispatch;
import alfio.manager.payment.BanchileWebhookDispatcher.Notification;
import alfio.manager.payment.BanchileWebhookDispatcher.Outcome;
import alfio.manager.payment.MollieConnectManager;
import alfio.manager.payment.PaymentCheckSingleFlight;
import alfio.manager.payment.PaymentSpecification;
import alfio.manager.payment.StripeConnectManager;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.PurchaseContext.PurchaseContextType;
import alfio.model.TicketCategory;
import alfio.model.TicketReservation.TicketReservationStatus;
import alfio.model.TicketReservationStatusAndValidation;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.model.modification.TicketReservationModification;
import alfio.model.modification.TicketReservationWithOptionalCodeModification;
import alfio.model.transaction.Transaction;
import alfio.repository.EventRepository;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.ClockProvider;
import alfio.util.Json;
import alfio.util.RefreshableDataSource;
import alfio.util.TemplateManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.reflect.TypeToken;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.Filter;
import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static alfio.model.TicketReservation.TicketReservationStatus.*;
import static alfio.model.system.ConfigurationKeys.*;
import static alfio.test.util.IntegrationTestUtil.AVAILABLE_SEATS;
import static alfio.test.util.IntegrationTestUtil.initEvent;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Webhook de Banchile Pagos ({@code POST /api/payment/webhook/banchile}) de punta a punta: la
 * request entra por MockMvc con los convertidores de mensajes de producción
 * ({@link MvcConfiguration#configureMessageConverters}) y la cadena de filtros de seguridad de
 * producción ({@link FormBasedWebSecurity#filterChain}, CSRF incluido), llega al controller real y,
 * con la firma válida, dispara la misma verificación que el cron y el poll contra un Banchile falso
 * local. Los jobs de alf.io están deshabilitados.
 *
 * <p>Los convertidores importan: la configuración de los tests ({@link ControllerConfiguration})
 * pone el de String antes que el de Jackson, al revés que producción, y con ese orden el 400 de
 * producción no se reproduce.
 *
 * <p>La cadena de seguridad se arma a mano con el código de producción: el contexto de los tests ya
 * registra otra (la de {@code TestCheckRestApiStability}, que el component scan de
 * {@link ControllerConfiguration} recoge), y dos cadenas para cualquier request no conviven.
 *
 * <p>La verificación corre en segundo plano. Los tests no miden tiempos: esperan el efecto en la
 * base (con una cota de espera, no de duración), o el resultado de la verificación que devuelve
 * {@link BanchileWebhookDispatcher#dispatch}; y para la concurrencia, el Banchile falso retiene la
 * consulta en curso hasta que el test la suelta.
 */
@AlfioIntegrationTest
@ContextConfiguration(classes = {
    DataSourceConfiguration.class,
    TestConfiguration.class,
    ControllerConfiguration.class,
    BanchilePaymentCheckSingleFlightIntegrationTest.BanchileTimeoutConfiguration.class
})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class BanchileWebhookIntegrationTest {

    private static final String WEBHOOK = "/api/payment/webhook/banchile";
    /** secretKey del sandbox público de Banchile: el mismo que usan los demás tests. */
    private static final String SECRET_KEY = "U87nG0kcCsjb61Mj";
    private static final String STATUS_DATE = "2026-10-14T10:15:30-03:00";
    private static final String BUYER_EMAIL = "comprador@example.com";
    /** Email de la organización que crea {@link IntegrationTestUtil#initEvent}. */
    private static final String ORGANIZATION_EMAIL = "email@example.com";
    private static final String APPROVED_ALERT_SENTENCE = "pago aprobado en Banchile para una reserva que no está activa";
    private static final String INVALIDATED_SESSION_ALERT_SENTENCE = "pago aprobado en una sesión Banchile invalidada";
    private static final String INVALIDATED_SESSION_REVIEW = BanchilePagosWebhookManager.INVALIDATED_SESSION_REVIEW;
    private static final String ACCEPTED = "accepted";
    private static final String IGNORED = "ignored";
    /** Cota de la espera a que la verificación disparada por el webhook deje su efecto, o termine. */
    private static final Duration WAIT = Duration.ofSeconds(30);
    /** Cuánto se espera a que una consulta llegue a Banchile, o a que la base muestre una request esperando un lock. */
    private static final Duration START_WAIT = Duration.ofSeconds(10);
    /** Tamaño del pool de producción ({@code datasource.connections.max-active}). */
    private static final int PRODUCTION_POOL_SIZE = 10;

    @Autowired
    private BanchilePaymentWebhookController webhookController;
    @Autowired
    private BanchileWebhookDispatcher dispatcher;
    @Autowired
    private ReservationApiV2Controller reservationApiV2Controller;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private Environment environment;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RecaptchaService recaptchaService;
    @Autowired
    private ConfigurationManager configurationManager;
    @Autowired
    private RefreshableDataSource dataSource;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private FindByIndexNameSessionRepository<?> sessionRepository;
    @Autowired
    private TemplateManager templateManager;
    @Autowired
    private StripeConnectManager stripeConnectManager;
    @Autowired
    private MollieConnectManager mollieConnectManager;
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
    private PaymentCheckSingleFlight paymentCheckSingleFlight;
    @Autowired
    private AdminReservationManager adminReservationManager;
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
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ClockProvider clockProvider;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private FakeBanchile banchile;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        banchile = new FakeBanchile();
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);
        configurationRepository.insert(BANCHILE_LOGIN.name(), "ffb78b93826239e1aa85a515aa961bd9", "");
        configurationRepository.insert(BANCHILE_TRANKEY.name(), SECRET_KEY, "");
        configurationRepository.insert(BANCHILE_BASE_URL.name(), banchile.baseUrl(), "");
        mvc = MockMvcBuilders.standaloneSetup(webhookController)
            .setMessageConverters(productionMessageConverters().toArray(HttpMessageConverter[]::new))
            .addFilters(productionSecurityFilterChain())
            .build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        banchile.close();
    }

    // -------------------------------------------------------------------------
    // A — recepción: el 400 de producción y la política de "siempre 200"
    // -------------------------------------------------------------------------

    /**
     * Lo que manda Banchile en producción: un objeto JSON con {@code Content-Type: application/json}.
     * Con {@code @RequestBody String} y Jackson como primer convertidor, Spring intentaba convertir el
     * objeto a String y respondía 400 sin entrar al método.
     */
    @Test
    void aJsonNotificationFromBanchileIsAnswered200AndTriggersTheVerification() throws Exception {
        var purchase = reservationPaidWithBanchile(71001);
        banchile.respond(71001, approved(71001), Duration.ZERO);

        var result = postNotification(notification(71001, purchase.reservationId(), "APPROVED", STATUS_DATE,
            "sha256:" + sha256Hex(71001, "APPROVED", STATUS_DATE)), MediaType.APPLICATION_JSON_VALUE);

        assertEquals(200, result.getResponse().getStatus(),
            () -> "Banchile debía recibir 200; resolvedException=" + result.getResolvedException());
        assertEquals(ACCEPTED, result.getResponse().getContentAsString());
        awaitCondition(() -> reservationStatus(purchase.reservationId()) == COMPLETE,
            () -> "la verificación disparada por el webhook debía completar la reserva; está " + reservationStatus(purchase.reservationId()));
        assertEquals(Transaction.Status.COMPLETE, transactionByRequestId(71001).status());
        assertEquals(1, banchile.queries(71001), "una consulta a Banchile");
    }

    /**
     * El endpoint no restringe el Content-Type: lee el body tal cual llega, sin pasar por los
     * convertidores de Spring. Un Content-Type que no se puede interpretar también responde 200.
     */
    @ParameterizedTest(name = "Content-Type: {0}")
    @ValueSource(strings = {"application/json;charset=UTF-8", "text/plain", "application/octet-stream",
        "application/x-www-form-urlencoded", "(sin Content-Type)", "esto-no-es-un-media-type"})
    void anyContentTypeIsAccepted(String contentType) throws Exception {
        int requestId = 71100 + Math.abs(contentType.hashCode() % 90);
        var purchase = reservationPaidWithBanchile(requestId);
        banchile.respond(requestId, approved(requestId), Duration.ZERO);
        var body = notification(requestId, purchase.reservationId(), "APPROVED", STATUS_DATE,
            "sha256:" + sha256Hex(requestId, "APPROVED", STATUS_DATE));

        var request = post(WEBHOOK).content(body.getBytes(UTF_8));
        if (!contentType.startsWith("(")) {
            request = request.header("Content-Type", contentType);
        }
        var result = mvc.perform(request).andReturn();

        assertEquals(200, result.getResponse().getStatus(), () -> "resolvedException=" + result.getResolvedException());
        assertEquals(ACCEPTED, result.getResponse().getContentAsString());
        awaitCondition(() -> reservationStatus(purchase.reservationId()) == COMPLETE,
            () -> "la reserva debía completarse; está " + reservationStatus(purchase.reservationId()));
    }

    /**
     * Bodies malformados o incompletos, de una reserva real con su transacción PENDING: siempre
     * 200, sin consultar a Banchile y sin tocar nada.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedBodies")
    void malformedBodiesAreAnswered200WithoutEffects(String description, MalformedBody malformed) throws Exception {
        var purchase = reservationPaidWithBanchile(71201);
        banchile.respond(71201, approved(71201), Duration.ZERO);

        var result = postNotification(malformed.body(purchase.reservationId()), MediaType.APPLICATION_JSON_VALUE);

        assertEquals(200, result.getResponse().getStatus(), () -> "resolvedException=" + result.getResolvedException());
        assertEquals(IGNORED, result.getResponse().getContentAsString());
        assertNoEffects(purchase, 71201);
    }

    /**
     * Cómo se arma cada body malformado a partir del ID de la reserva.
     */
    @FunctionalInterface
    interface MalformedBody {
        String body(String reservationId);
    }

    static Stream<Arguments> malformedBodies() {
        String signature = "sha256:" + sha256Hex(71201, "APPROVED", STATUS_DATE);
        return Stream.of(
            Arguments.of("body vacío", (MalformedBody) id -> ""),
            Arguments.of("body en blanco", (MalformedBody) id -> "   \n"),
            Arguments.of("no es JSON", (MalformedBody) id -> "requestId=71201&reference=" + id.replace("-", "")),
            Arguments.of("JSON truncado", (MalformedBody) id -> notification(71201, id, "APPROVED", STATUS_DATE, signature).substring(0, 60)),
            Arguments.of("un arreglo JSON", (MalformedBody) id -> "[" + notification(71201, id, "APPROVED", STATUS_DATE, signature) + "]"),
            Arguments.of("objeto vacío", (MalformedBody) id -> "{}"),
            Arguments.of("sin requestId", (MalformedBody) id -> withoutField(notification(71201, id, "APPROVED", STATUS_DATE, signature), "requestId")),
            Arguments.of("requestId no numérico", (MalformedBody) id -> notification(71201, id, "APPROVED", STATUS_DATE, signature)
                .replace("\"requestId\": 71201", "\"requestId\": \"abc\"")),
            Arguments.of("requestId decimal", (MalformedBody) id -> notification(71201, id, "APPROVED", STATUS_DATE, signature)
                .replace("\"requestId\": 71201", "\"requestId\": 71201.5")),
            Arguments.of("requestId negativo", (MalformedBody) id -> notification(71201, id, "APPROVED", STATUS_DATE, signature)
                .replace("\"requestId\": 71201", "\"requestId\": -71201")),
            Arguments.of("requestId fuera de rango", (MalformedBody) id -> notification(71201, id, "APPROVED", STATUS_DATE, signature)
                .replace("\"requestId\": 71201", "\"requestId\": 99999999999")),
            Arguments.of("sin reference", (MalformedBody) id -> withoutField(notification(71201, id, "APPROVED", STATUS_DATE, signature), "reference")),
            Arguments.of("reference con guiones", (MalformedBody) id -> notification(71201, id, "APPROVED", STATUS_DATE, signature)
                .replace(id.replace("-", ""), id)),
            Arguments.of("reference que no es hex", (MalformedBody) id -> notification(71201, id, "APPROVED", STATUS_DATE, signature)
                .replace(id.replace("-", ""), "z".repeat(32))),
            Arguments.of("sin status", (MalformedBody) id -> """
                {"requestId": 71201, "reference": "%s", "signature": "%s"}""".formatted(id.replace("-", ""), signature)),
            Arguments.of("sin status.status", (MalformedBody) id -> notification(71201, id, "APPROVED", STATUS_DATE, signature)
                .replace("\"status\": \"APPROVED\", ", "")),
            Arguments.of("sin status.date", (MalformedBody) id -> notification(71201, id, "APPROVED", STATUS_DATE, signature)
                .replace(", \"date\": \"" + STATUS_DATE + "\"", "")),
            Arguments.of("body de más de 16 KiB", (MalformedBody) id -> notification(71201, id, "APPROVED", STATUS_DATE, signature)
                .replace("\"requestId\"", "\"relleno\": \"" + "x".repeat(17 * 1024) + "\", \"requestId\""))
        );
    }

    // -------------------------------------------------------------------------
    // B — la firma
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "firma válida: {0}")
    @ValueSource(strings = {"hex con prefijo", "hex en mayúsculas con prefijo", "hex sin prefijo", "base64 con prefijo", "base64 sin prefijo"})
    void aValidSignatureTriggersTheVerification(String variant) throws Exception {
        int requestId = 72001 + Math.abs(variant.hashCode() % 90);
        var purchase = reservationPaidWithBanchile(requestId);
        banchile.respond(requestId, approved(requestId), Duration.ZERO);
        String hex = sha256Hex(requestId, "APPROVED", STATUS_DATE);
        String base64 = Base64.getEncoder().encodeToString(digest("SHA-256", requestId + "APPROVED" + STATUS_DATE + SECRET_KEY));
        String signature = switch (variant) {
            case "hex con prefijo" -> "sha256:" + hex;
            case "hex en mayúsculas con prefijo" -> "sha256:" + hex.toUpperCase(Locale.ROOT);
            case "hex sin prefijo" -> hex;
            case "base64 con prefijo" -> "sha256:" + base64;
            default -> base64;
        };

        var result = postNotification(notification(requestId, purchase.reservationId(), "APPROVED", STATUS_DATE, signature),
            MediaType.APPLICATION_JSON_VALUE);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(ACCEPTED, result.getResponse().getContentAsString());
        awaitCondition(() -> reservationStatus(purchase.reservationId()) == COMPLETE,
            () -> "la reserva debía completarse; está " + reservationStatus(purchase.reservationId()));
        assertEquals(1, banchile.queries(requestId));
    }

    /**
     * Firma ausente o que no calza: 200, ninguna consulta a Banchile, nada cambia, y un WARN con el
     * requestId, la reserva y las variantes que se probaron, sin el secreto.
     */
    @ParameterizedTest(name = "firma inválida: {0}")
    @ValueSource(strings = {"otro secreto", "firmada para REJECTED", "SHA-1 en hex", "SHA-1 en base64", "basura", "vacía",
        "solo el prefijo", "sin el campo signature", "signature null"})
    void anInvalidOrMissingSignatureIsAnswered200WithoutEffects(String variant) throws Exception {
        var purchase = reservationPaidWithBanchile(72101);
        banchile.respond(72101, approved(72101), Duration.ZERO);
        String body = notification(72101, purchase.reservationId(), "APPROVED", STATUS_DATE, "FIRMA");
        String input = 72101 + "APPROVED" + STATUS_DATE;
        body = switch (variant) {
            case "otro secreto" -> body.replace("FIRMA", "sha256:" + HexFormat.of().formatHex(digest("SHA-256", input + "otro-secreto")));
            case "firmada para REJECTED" -> body.replace("FIRMA", "sha256:" + sha256Hex(72101, "REJECTED", STATUS_DATE));
            case "SHA-1 en hex" -> body.replace("FIRMA", "sha256:" + HexFormat.of().formatHex(digest("SHA-1", input + SECRET_KEY)));
            case "SHA-1 en base64" -> body.replace("FIRMA", "sha256:" + Base64.getEncoder().encodeToString(digest("SHA-1", input + SECRET_KEY)));
            case "basura" -> body.replace("FIRMA", "firma-incorrecta");
            case "vacía" -> body.replace("FIRMA", "");
            case "solo el prefijo" -> body.replace("FIRMA", "sha256:");
            case "sin el campo signature" -> withoutField(body, "signature");
            default -> body.replace("\"FIRMA\"", "null");
        };

        MvcResult result;
        List<String> warnings;
        try (var logs = new LogCapture(BanchileWebhookDispatcher.class)) {
            result = postNotification(body, MediaType.APPLICATION_JSON_VALUE);
            // la captura puede ver el mismo evento por log4j2 y por java.util.logging: se cuentan los mensajes distintos
            warnings = logs.warningsAbout("72101").stream().distinct().toList();
        }

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(IGNORED, result.getResponse().getContentAsString());
        assertNoEffects(purchase, 72101);
        assertEquals(1, warnings.size(), () -> "se esperaba un WARN de la firma: " + warnings);
        var warning = warnings.get(0);
        assertTrue(warning.contains(purchase.reservationId()), warning);
        assertTrue(warning.contains("hex") && warning.contains("base64"), () -> "el WARN debía decir qué variantes se probaron: " + warning);
        assertFalse(warning.contains(SECRET_KEY), warning);
        assertFalse(warning.toLowerCase(Locale.ROOT).contains(sha256Hex(72101, "APPROVED", STATUS_DATE)), "el WARN no lleva el digest esperado");
    }

    /**
     * El secretKey de la firma se lee con el nivel de configuración de la compra, el mismo que usa
     * {@link BanchilePagosWebhookManager}: en producción está a nivel de organización, y una compra
     * puede tenerlo también a nivel de evento. El de un nivel menos específico no sirve.
     */
    @ParameterizedTest(name = "secretKey a nivel de {0}")
    @ValueSource(strings = {"organización", "evento"})
    void theSignatureIsValidatedWithTheSecretKeyOfThePurchaseConfigurationLevel(String level) throws Exception {
        int requestId = "evento".equals(level) ? 72302 : 72301;
        var purchase = reservationPaidWithBanchile(requestId);
        banchile.respond(requestId, approved(requestId), Duration.ZERO);
        String lessSpecificSecret = "secreto-menos-especifico";
        int organizationId = purchase.event().getOrganizationId();
        jdbc.update("update configuration set c_value = :value where c_key = :key",
            Map.of("value", lessSpecificSecret, "key", BANCHILE_TRANKEY.name()));
        if ("evento".equals(level)) {
            configurationRepository.insertOrganizationLevel(organizationId, BANCHILE_TRANKEY.name(), lessSpecificSecret, "");
            configurationRepository.insertEventLevel(organizationId, purchase.event().getId(), BANCHILE_TRANKEY.name(), SECRET_KEY, "");
        } else {
            configurationRepository.insertOrganizationLevel(organizationId, BANCHILE_TRANKEY.name(), SECRET_KEY, "");
        }
        String signedWithTheLessSpecificSecret = "sha256:" + HexFormat.of().formatHex(
            digest("SHA-256", requestId + "APPROVED" + STATUS_DATE + lessSpecificSecret));

        var withTheLessSpecificSecret = postNotification(notification(requestId, purchase.reservationId(), "APPROVED", STATUS_DATE,
            signedWithTheLessSpecificSecret), MediaType.APPLICATION_JSON_VALUE);
        assertEquals(IGNORED, withTheLessSpecificSecret.getResponse().getContentAsString());
        assertNoEffects(purchase, requestId);

        var withThePurchaseSecret = postNotification(notification(requestId, purchase.reservationId(), "APPROVED", STATUS_DATE,
            "sha256:" + sha256Hex(requestId, "APPROVED", STATUS_DATE)), MediaType.APPLICATION_JSON_VALUE);
        assertEquals(ACCEPTED, withThePurchaseSecret.getResponse().getContentAsString());
        awaitCondition(() -> reservationStatus(purchase.reservationId()) == COMPLETE,
            () -> "la reserva debía completarse; está " + reservationStatus(purchase.reservationId()));
    }

    // -------------------------------------------------------------------------
    // C — a qué pago corresponde la notificación
    // -------------------------------------------------------------------------

    @Test
    void aNotificationForAnUnknownReservationIsIgnored() throws Exception {
        var purchase = reservationPaidWithBanchile(73001);
        banchile.respond(73001, approved(73001), Duration.ZERO);
        String unknownReservation = UUID.randomUUID().toString();

        var result = postNotification(notification(73001, unknownReservation, "APPROVED", STATUS_DATE,
            "sha256:" + sha256Hex(73001, "APPROVED", STATUS_DATE)), MediaType.APPLICATION_JSON_VALUE);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(IGNORED, result.getResponse().getContentAsString());
        assertNoEffects(purchase, 73001);
    }

    /**
     * Firma válida, pero el requestId no es de ninguna transacción de la reserva de la referencia:
     * no se consulta nada (ni ese requestId ni el de la reserva).
     */
    @Test
    void aRequestIdThatIsNotATransactionOfTheReservationIsIgnored() throws Exception {
        var purchase = reservationPaidWithBanchile(73101);
        banchile.respond(73101, approved(73101), Duration.ZERO);
        banchile.respond(73199, approved(73199), Duration.ZERO);

        var result = postNotification(notification(73199, purchase.reservationId(), "APPROVED", STATUS_DATE,
            "sha256:" + sha256Hex(73199, "APPROVED", STATUS_DATE)), MediaType.APPLICATION_JSON_VALUE);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(IGNORED, result.getResponse().getContentAsString());
        assertNoEffects(purchase, 73101);
        assertEquals(0, banchile.queries(73199));
    }

    /**
     * Firma válida y el requestId es de la transacción PENDING de OTRA reserva, con la referencia de
     * esta. La firma de Banchile no cubre {@code reference}: lo que impide verificar un pago con el
     * contexto de otra reserva es que el requestId se busque solo entre las transacciones de la
     * reserva de la referencia. La respuesta {@code ignored} quiere decir que no quedó nada en cola,
     * así que lo que se mira después ya no puede cambiar.
     */
    @Test
    void aRequestIdThatIsThePendingPaymentOfAnotherReservationIsIgnored() throws Exception {
        var other = reservationPaidWithBanchile(73301);
        var purchase = reservationPaidWithBanchile(73302);
        banchile.respond(73301, approved(73301), Duration.ZERO);
        banchile.respond(73302, approved(73302), Duration.ZERO);

        var result = postNotification(notification(73301, purchase.reservationId(), "APPROVED", STATUS_DATE,
            "sha256:" + sha256Hex(73301, "APPROVED", STATUS_DATE)), MediaType.APPLICATION_JSON_VALUE);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(IGNORED, result.getResponse().getContentAsString(), "el requestId no es de la reserva de la referencia");
        assertNoEffects(other, 73301);
        assertNoEffects(purchase, 73302);
    }

    /**
     * Lo mismo con una sesión que alf.io dejó INVALID en OTRA reserva y que se pagó en Banchile: no se
     * revisa con el contexto de la reserva de la referencia, ni se alerta a su organización por un pago
     * que no es suyo. Se espera el resultado de la verificación antes de mirar los efectos: si algo
     * hubiera quedado en cola, ya habría terminado.
     */
    @Test
    void aRequestIdThatIsAPaidInvalidatedSessionOfAnotherReservationIsIgnoredWithoutAlerts() throws Exception {
        var other = reservationPaidWithBanchile(73401);
        payAgain(other, 73402);
        var purchase = reservationPaidWithBanchile(73403);
        banchile.respond(73401, approved(73401), Duration.ZERO);
        banchile.respond(73402, pending(73402), Duration.ZERO);
        banchile.respond(73403, pending(73403), Duration.ZERO);

        var dispatch = dispatcher.dispatch(signed(73401, purchase.reservationId(), "APPROVED"));
        var check = dispatch.check().get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertEquals(Outcome.UNKNOWN_REQUEST_ID, dispatch.outcome());
        assertNull(check, () -> "no debía quedar nada en cola; la verificación terminó " + check);
        assertEquals(0, banchile.queries(73401), "la sesión invalidada de la otra reserva no se consulta");
        assertEquals(Transaction.Status.INVALID, transactionByRequestId(73401).status());
        assertNull(transactionByRequestId(73401).metadata().get(INVALIDATED_SESSION_REVIEW), "la sesión no queda revisada");
        // ni la reserva dueña de la sesión ni la de la referencia: sin consultas, sin cambios y sin alertas
        assertNoEffects(other, 73402);
        assertNoEffects(purchase, 73403);
    }

    /**
     * Una notificación repetida después de que la verificación cerró la transacción: ya no hay
     * nada que verificar.
     */
    @Test
    void aNotificationForATransactionThatIsNoLongerPendingIsIgnored() throws Exception {
        var purchase = reservationPaidWithBanchile(73201);
        banchile.respond(73201, approved(73201), Duration.ZERO);
        String body = notification(73201, purchase.reservationId(), "APPROVED", STATUS_DATE, "sha256:" + sha256Hex(73201, "APPROVED", STATUS_DATE));
        assertEquals(ACCEPTED, postNotification(body, MediaType.APPLICATION_JSON_VALUE).getResponse().getContentAsString());
        awaitCondition(() -> reservationStatus(purchase.reservationId()) == COMPLETE, () -> "la primera notificación debía completar la reserva");
        int emails = emailsFor(purchase.event()).size();

        var repeated = postNotification(body, MediaType.APPLICATION_JSON_VALUE);

        assertEquals(200, repeated.getResponse().getStatus());
        assertEquals(IGNORED, repeated.getResponse().getContentAsString());
        assertEquals(1, banchile.queries(73201), "la transacción ya está cerrada: no se consulta de nuevo");
        assertEquals(emails, emailsFor(purchase.event()).size(), "ningún correo más");
        assertEquals(COMPLETE, reservationStatus(purchase.reservationId()));
    }

    // -------------------------------------------------------------------------
    // D — las reglas de forceTransactionCheck (la verdad la da querySession)
    // -------------------------------------------------------------------------

    @Test
    void cancelledReservationWithApprovedPaymentAlertsTheOrganizationAndIsNotTouched() throws Exception {
        var purchase = reservationPaidWithBanchile(74001);
        cancelFromAdmin(purchase);
        banchile.respond(74001, approved(74001), Duration.ZERO);

        var result = postNotification(notification(74001, purchase.reservationId(), "APPROVED", STATUS_DATE,
            "sha256:" + sha256Hex(74001, "APPROVED", STATUS_DATE)), MediaType.APPLICATION_JSON_VALUE);

        assertEquals(ACCEPTED, result.getResponse().getContentAsString());
        awaitCondition(() -> transactionByRequestId(74001).status() == Transaction.Status.COMPLETE,
            () -> "la verificación debía cerrar la transacción; está " + transactionByRequestId(74001).status());
        assertEquals(CANCELLED, reservationStatus(purchase.reservationId()), "la reserva cancelada no se toca");
        var alerts = emailsFor(purchase.event()).stream().filter(m -> ((String) m.get("message")).contains(APPROVED_ALERT_SENTENCE)).toList();
        assertEquals(1, alerts.size(), () -> "se esperaba UNA alerta: " + emailsFor(purchase.event()));
        assertEquals(ORGANIZATION_EMAIL, alerts.get(0).get("recipient"));
        assertTrue(emailsTo(purchase.event(), BUYER_EMAIL).isEmpty(), "al comprador no se le escribe");
    }

    /**
     * El payload dice APPROVED (y está bien firmado), pero Banchile informa REJECTED: se aplica el
     * rechazo. El estado del payload nunca se aplica.
     */
    @Test
    void thePayloadSaysApprovedButBanchileSaysRejectedAndTheRejectionIsApplied() throws Exception {
        var purchase = reservationPaidWithBanchile(74101);
        banchile.respond(74101, rejected(74101), Duration.ZERO);

        var result = postNotification(notification(74101, purchase.reservationId(), "APPROVED", STATUS_DATE,
            "sha256:" + sha256Hex(74101, "APPROVED", STATUS_DATE)), MediaType.APPLICATION_JSON_VALUE);

        assertEquals(ACCEPTED, result.getResponse().getContentAsString());
        awaitCondition(() -> transactionByRequestId(74101).status() == Transaction.Status.FAILED,
            () -> "la verificación debía cerrar la transacción como FAILED; está " + transactionByRequestId(74101).status());
        assertEquals(PENDING, reservationStatus(purchase.reservationId()), "la reserva vigente vuelve a PENDING para reintentar");
        assertEquals("REJECTED", transactionByRequestId(74101).metadata().get("banchile_status"));
        assertFalse(emailsTo(purchase.event(), BUYER_EMAIL).isEmpty(), "el comprador recibe el aviso del pago fallido");
    }

    /**
     * Al revés: el payload dice REJECTED (y está bien firmado), pero Banchile informa APPROVED. El
     * estado del payload tampoco decide si se verifica: la notificación dispara la verificación y la
     * reserva se completa.
     */
    @Test
    void thePayloadSaysRejectedButBanchileSaysApprovedAndTheReservationIsCompleted() throws Exception {
        var purchase = reservationPaidWithBanchile(74301);
        banchile.respond(74301, approved(74301), Duration.ZERO);

        var result = postNotification(notification(74301, purchase.reservationId(), "REJECTED", STATUS_DATE,
            "sha256:" + sha256Hex(74301, "REJECTED", STATUS_DATE)), MediaType.APPLICATION_JSON_VALUE);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(ACCEPTED, result.getResponse().getContentAsString(), "una notificación REJECTED bien firmada también se verifica");
        awaitCondition(() -> reservationStatus(purchase.reservationId()) == COMPLETE,
            () -> "la verificación debía completar la reserva; está " + reservationStatus(purchase.reservationId()));
        assertEquals(Transaction.Status.COMPLETE, transactionByRequestId(74301).status());
        assertEquals(1, banchile.queries(74301), "una consulta a Banchile");
    }

    @Test
    void whenBanchileStillSaysPendingNothingChanges() throws Exception {
        var purchase = reservationPaidWithBanchile(74201);
        banchile.respond(74201, pending(74201), Duration.ZERO);

        var dispatch = dispatcher.dispatch(signed(74201, purchase.reservationId(), "APPROVED"));

        assertEquals(Outcome.VERIFICATION_QUEUED, dispatch.outcome());
        assertEquals(CheckResult.CHECKED, dispatch.check().get(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertEquals(1, banchile.queries(74201));
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(purchase.reservationId()));
        assertEquals(Transaction.Status.PENDING, transactionByRequestId(74201).status());
        assertTrue(emailsFor(purchase.event()).isEmpty());
    }

    // -------------------------------------------------------------------------
    // E — sesiones que alf.io dejó INVALID al crear otra para la misma reserva
    // -------------------------------------------------------------------------

    /**
     * El comprador apretó "Pagar" dos veces y pagó la sesión vieja: el webhook de esa sesión dispara
     * la misma revisión de sesiones invalidadas que el cron, sin esperarlo. No consulta la sesión
     * vigente ni toca la reserva.
     */
    @Test
    void aNotificationForAPaidInvalidatedSessionTriggersItsReviewAndAlerts() throws Exception {
        var purchase = reservationPaidWithBanchile(75001);
        payAgain(purchase, 75002);
        banchile.respond(75001, approved(75001), Duration.ZERO);
        banchile.respond(75002, pending(75002), Duration.ZERO);

        var result = postNotification(notification(75001, purchase.reservationId(), "APPROVED", STATUS_DATE,
            "sha256:" + sha256Hex(75001, "APPROVED", STATUS_DATE)), MediaType.APPLICATION_JSON_VALUE);

        assertEquals(ACCEPTED, result.getResponse().getContentAsString());
        awaitCondition(() -> "alerted".equals(transactionByRequestId(75001).metadata().get(INVALIDATED_SESSION_REVIEW)),
            () -> "la sesión invalidada debía quedar revisada: " + transactionByRequestId(75001).metadata());
        var alerts = emailsFor(purchase.event()).stream().filter(m -> ((String) m.get("message")).contains(INVALIDATED_SESSION_ALERT_SENTENCE)).toList();
        assertEquals(1, alerts.size(), () -> "se esperaba UNA alerta de sesión invalidada: " + emailsFor(purchase.event()));
        assertEquals(ORGANIZATION_EMAIL, alerts.get(0).get("recipient"));
        assertEquals(Transaction.Status.INVALID, transactionByRequestId(75001).status());
        assertEquals(Transaction.Status.PENDING, transactionByRequestId(75002).status());
        assertEquals(0, banchile.queries(75002), "la sesión vigente no se consulta");
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(purchase.reservationId()));

        // la misma notificación otra vez: la sesión ya está revisada
        var repeated = postNotification(notification(75001, purchase.reservationId(), "APPROVED", STATUS_DATE,
            "sha256:" + sha256Hex(75001, "APPROVED", STATUS_DATE)), MediaType.APPLICATION_JSON_VALUE);
        assertEquals(IGNORED, repeated.getResponse().getContentAsString());
        assertEquals(1, banchile.queries(75001));
    }

    @Test
    void aNotificationForAnInvalidatedSessionOutsideTheWindowIsIgnored() throws Exception {
        var purchase = reservationPaidWithBanchile(75101);
        backdate(75101, BanchilePagosWebhookManager.INVALIDATED_SESSION_WINDOW.plusMinutes(1));
        payAgain(purchase, 75102);
        banchile.respond(75101, approved(75101), Duration.ZERO);
        banchile.respond(75102, pending(75102), Duration.ZERO);

        var dispatch = dispatcher.dispatch(signed(75101, purchase.reservationId(), "APPROVED"));

        assertEquals(Outcome.INVALIDATED_SESSION_OUTSIDE_WINDOW, dispatch.outcome());
        assertEquals(0, banchile.queries(75101));
        assertEquals(0, banchile.queries(75102));
        assertNull(transactionByRequestId(75101).metadata().get(INVALIDATED_SESSION_REVIEW));
        assertTrue(emailsFor(purchase.event()).isEmpty());
    }

    // -------------------------------------------------------------------------
    // F — concurrencia: una sola consulta y un solo efecto
    // -------------------------------------------------------------------------

    /**
     * El webhook está revisando en Banchile una sesión invalidada cuando corre el cron: el cron espera
     * el lock ({@code FOR UPDATE}) de esa sesión y, cuando lo obtiene, la encuentra revisada. Una sola
     * consulta y una sola alerta.
     */
    @Test
    void theCronWaitsForTheWebhookReviewOfAnInvalidatedSessionAndDoesNotAlertAgain() throws Exception {
        var purchase = reservationPaidWithBanchile(75201);
        payAgain(purchase, 75202);
        banchile.hold(75201, approved(75201));
        banchile.respond(75202, pending(75202), Duration.ZERO);
        var job = new BanchileReconciliationJob(transactionRepository, ticketReservationManager, ticketReservationRepository,
            purchaseContextManager, banchilePagosWebhookManager, new TransactionTemplate(transactionManager), clockProvider);

        Dispatch webhook;
        Future<Integer> cron;
        boolean cronWaitedForTheLock;
        try {
            webhook = dispatcher.dispatch(signed(75201, purchase.reservationId(), "APPROVED"));
            assertTrue(banchile.awaitQueryInFlight(75201, START_WAIT), "el webhook no consultó la sesión invalidada");
            cron = executor.submit(job::reconcilePendingBanchileTransactions);
            cronWaitedForTheLock = awaitARequestWaitingForALock(START_WAIT);
        } finally {
            banchile.release(75201);
        }

        assertEquals(Outcome.INVALIDATED_SESSION_REVIEW_QUEUED, webhook.outcome());
        assertTrue(cronWaitedForTheLock, "el cron debía quedar esperando el lock de la sesión que revisa el webhook");
        assertEquals(CheckResult.CHECKED, webhook.check().get(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertEquals(0, cron.get(WAIT.toSeconds(), TimeUnit.SECONDS), "la sesión vigente sigue PENDING en Banchile");
        assertEquals(1, banchile.queries(75201), "una sola consulta de la sesión invalidada");
        assertEquals("alerted", transactionByRequestId(75201).metadata().get(INVALIDATED_SESSION_REVIEW));
        var alerts = emailsFor(purchase.event()).stream().filter(m -> ((String) m.get("message")).contains(INVALIDATED_SESSION_ALERT_SENTENCE)).toList();
        assertEquals(1, alerts.size(), () -> "se esperaba UNA alerta: " + emailsFor(purchase.event()));
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(purchase.reservationId()));
    }


    /**
     * Dos notificaciones iguales mientras la primera verificación sigue en Banchile: la segunda no
     * consulta (la primera tiene el turno del pago) y la reserva se completa una vez.
     */
    @Test
    void aDuplicateNotificationWhileTheFirstIsBeingVerifiedDoesNotQueryAgain() throws Exception {
        var purchase = reservationPaidWithBanchile(76001);
        var reference = reservationPaidWithBanchile(76002);
        banchile.respond(76002, approved(76002), Duration.ZERO);
        banchile.hold(76001, approved(76001));

        Dispatch first;
        Dispatch duplicate;
        CheckResult duplicateResult;
        try {
            first = dispatcher.dispatch(signed(76001, purchase.reservationId(), "APPROVED"));
            assertTrue(banchile.awaitQueryInFlight(76001, START_WAIT), "la primera verificación no consultó a Banchile");
            duplicate = dispatcher.dispatch(signed(76001, purchase.reservationId(), "APPROVED"));
            duplicateResult = duplicate.check().get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertFalse(first.check().isDone(), "la primera verificación seguía retenida en Banchile");
        } finally {
            banchile.release(76001);
        }

        assertEquals(Outcome.VERIFICATION_QUEUED, first.outcome());
        assertEquals(Outcome.VERIFICATION_QUEUED, duplicate.outcome());
        assertEquals(CheckResult.BUSY, duplicateResult, "la duplicada no consulta: hay una verificación en curso para el pago");
        assertEquals(CheckResult.CHECKED, first.check().get(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertEquals(1, banchile.queries(76001), "una sola consulta a Banchile");
        assertEquals(COMPLETE, reservationStatus(purchase.reservationId()));
        // un solo efecto: los mismos correos que una reserva completada por una sola notificación
        assertEquals(CheckResult.CHECKED, dispatcher.dispatch(signed(76002, reference.reservationId(), "APPROVED")).check()
            .get(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertEquals(COMPLETE, reservationStatus(reference.reservationId()));
        assertEquals(emailsFor(reference.event()).size(), emailsFor(purchase.event()).size(), "un solo efecto: " + emailsFor(purchase.event()));
    }

    /**
     * El poll del navegador está consultando a Banchile cuando llega el webhook: el webhook no
     * consulta (comparten el turno del pago) y el poll aplica el resultado una vez.
     */
    @Test
    void aNotificationWhileThePollIsQueryingDoesNotQueryAgain() throws Exception {
        var purchase = reservationPaidWithBanchile(76101);
        banchile.hold(76101, approved(76101));

        Future<ResponseEntity<ReservationStatusInfo>> poll;
        Dispatch webhook;
        CheckResult webhookResult;
        try {
            poll = executor.submit(() -> reservationApiV2Controller.getReservationStatus(purchase.reservationId()));
            assertTrue(banchile.awaitQueryInFlight(76101, START_WAIT), "el poll no consultó a Banchile");
            webhook = dispatcher.dispatch(signed(76101, purchase.reservationId(), "APPROVED"));
            webhookResult = webhook.check().get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertFalse(poll.isDone(), "el poll seguía retenido en Banchile");
        } finally {
            banchile.release(76101);
        }

        assertEquals(Outcome.VERIFICATION_QUEUED, webhook.outcome());
        assertEquals(CheckResult.BUSY, webhookResult);
        assertNotNull(poll.get(WAIT.toSeconds(), TimeUnit.SECONDS).getBody());
        assertEquals(1, banchile.queries(76101), "una sola consulta a Banchile");
        assertEquals(COMPLETE, reservationStatus(purchase.reservationId()));
    }

    /**
     * Al revés: el webhook está consultando a Banchile cuando llega el poll. El poll responde con el
     * estado actual sin consultar y el webhook aplica el resultado una vez.
     */
    @Test
    void aPollWhileTheNotificationIsBeingVerifiedDoesNotQueryAgain() throws Exception {
        var purchase = reservationPaidWithBanchile(76201);
        banchile.hold(76201, approved(76201));

        Dispatch webhook;
        ResponseEntity<ReservationStatusInfo> poll;
        try {
            webhook = dispatcher.dispatch(signed(76201, purchase.reservationId(), "APPROVED"));
            assertTrue(banchile.awaitQueryInFlight(76201, START_WAIT), "el webhook no consultó a Banchile");
            poll = reservationApiV2Controller.getReservationStatus(purchase.reservationId());
            assertFalse(webhook.check().isDone(), "la verificación del webhook seguía retenida en Banchile");
        } finally {
            banchile.release(76201);
        }

        assertNotNull(poll.getBody());
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, poll.getBody().getStatus(), "el poll responde con el estado actual, sin esperar");
        assertEquals(CheckResult.CHECKED, webhook.check().get(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertEquals(1, banchile.queries(76201), "una sola consulta a Banchile");
        assertEquals(COMPLETE, reservationStatus(purchase.reservationId()));
    }

    /**
     * El cron no pasa por el single-flight: espera el lock ({@code FOR UPDATE}) de la transacción
     * que tiene la verificación del webhook y, cuando la obtiene, la transacción ya no está PENDING.
     */
    @Test
    void theCronWaitsForTheLockOfTheWebhookVerificationAndDoesNotQueryAgain() throws Exception {
        var purchase = reservationPaidWithBanchile(76301);
        banchile.hold(76301, approved(76301));
        var job = new BanchileReconciliationJob(transactionRepository, ticketReservationManager, ticketReservationRepository,
            purchaseContextManager, banchilePagosWebhookManager, new TransactionTemplate(transactionManager), clockProvider);

        Dispatch webhook;
        Future<Integer> cron;
        boolean cronWaitedForTheLock;
        try {
            webhook = dispatcher.dispatch(signed(76301, purchase.reservationId(), "APPROVED"));
            assertTrue(banchile.awaitQueryInFlight(76301, START_WAIT), "el webhook no consultó a Banchile");
            cron = executor.submit(job::reconcilePendingBanchileTransactions);
            cronWaitedForTheLock = awaitARequestWaitingForALock(START_WAIT);
        } finally {
            banchile.release(76301);
        }

        assertTrue(cronWaitedForTheLock, "el cron debía quedar esperando el lock de la transacción que tiene el webhook");
        assertEquals(CheckResult.CHECKED, webhook.check().get(WAIT.toSeconds(), TimeUnit.SECONDS));
        // el cron cuenta la transacción como conciliada porque dejó de estar PENDING durante su run, aunque
        // al obtener el lock ya no la encontró PENDING y no consultó a Banchile
        assertEquals(1, cron.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertEquals(1, banchile.queries(76301), "una sola consulta a Banchile: el cron no consulta la transacción ya cerrada");
        assertEquals(COMPLETE, reservationStatus(purchase.reservationId()));
    }

    /**
     * El tope global de 5 verificaciones de las requests públicas también vale para el webhook:
     * con los 5 cupos tomados por polls, la verificación del webhook no consulta.
     */
    @Test
    void withTheFiveSlotsTakenByPollsTheWebhookDoesNotQuery() throws Exception {
        var holders = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            holders.add(reservationPaidWithBanchile(76401 + i).reservationId());
            banchile.hold(76401 + i, pending(76401 + i));
        }
        var purchase = reservationPaidWithBanchile(76406);
        banchile.respond(76406, approved(76406), Duration.ZERO);
        try (var ignored = productionPool()) {
            var polls = new ArrayList<Future<ResponseEntity<ReservationStatusInfo>>>();
            CheckResult webhookResult;
            try {
                for (int i = 0; i < 5; i++) {
                    var holder = holders.get(i);
                    polls.add(executor.submit(() -> reservationApiV2Controller.getReservationStatus(holder)));
                    assertTrue(banchile.awaitQueryInFlight(76401 + i, START_WAIT), "el poll " + i + " no consultó a Banchile");
                }
                webhookResult = dispatcher.dispatch(signed(76406, purchase.reservationId(), "APPROVED")).check()
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
                assertEquals(0, polls.stream().filter(Future::isDone).count(), "los 5 polls seguían retenidos");
            } finally {
                for (int i = 0; i < 5; i++) {
                    banchile.release(76401 + i);
                }
            }
            for (var poll : polls) {
                poll.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            }

            assertEquals(CheckResult.BUSY, webhookResult, "sin cupo, el webhook no consulta");
            assertEquals(0, banchile.queries(76406));
            assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(purchase.reservationId()));
        }
    }

    /**
     * Las verificaciones del webhook corren en un executor de 2 hilos: a lo sumo 2 consultas a la
     * vez desde el webhook, aunque lleguen notificaciones de más pagos; las demás esperan en la cola.
     */
    @Test
    void theWebhookQueriesAtMostTwoPaymentsAtATime() throws Exception {
        var purchases = new ArrayList<Purchase>();
        for (int i = 0; i < 3; i++) {
            purchases.add(reservationPaidWithBanchile(76501 + i));
            banchile.hold(76501 + i, approved(76501 + i));
        }
        var dispatches = new ArrayList<Dispatch>();
        boolean twoInFlight;
        int inFlightWhileHeld;
        try {
            for (int i = 0; i < 3; i++) {
                dispatches.add(dispatcher.dispatch(signed(76501 + i, purchases.get(i).reservationId(), "APPROVED")));
            }
            twoInFlight = banchile.awaitQueriesInFlight(2, START_WAIT);
            inFlightWhileHeld = banchile.queries(76501) + banchile.queries(76502) + banchile.queries(76503);
            assertEquals(0, dispatches.stream().filter(d -> d.check().isDone()).count(), "las 3 verificaciones seguían pendientes");
        } finally {
            for (int i = 0; i < 3; i++) {
                banchile.release(76501 + i);
            }
        }
        for (var dispatch : dispatches) {
            assertEquals(Outcome.VERIFICATION_QUEUED, dispatch.outcome());
            assertEquals(CheckResult.CHECKED, dispatch.check().get(WAIT.toSeconds(), TimeUnit.SECONDS));
        }

        assertTrue(twoInFlight, "2 verificaciones debían quedar consultando a Banchile");
        assertEquals(2, inFlightWhileHeld, "la tercera esperaba en la cola sin consultar");
        assertEquals(2, banchile.maxQueriesInFlight(), "nunca más de 2 consultas a la vez desde el webhook");
        for (var purchase : purchases) {
            assertEquals(COMPLETE, reservationStatus(purchase.reservationId()));
        }
    }

    /**
     * La verificación espera en la cola y, mientras tanto, el comprador vuelve a apretar "Pagar": la
     * transacción de la notificación queda INVALID y hay otra PENDING. La verificación no consulta
     * la transacción nueva, que no es la de la notificación (ni la del turno que tomó).
     */
    @Test
    void aQueuedVerificationDoesNotQueryAPendingTransactionCreatedAfterTheNotification() throws Exception {
        var running = reservationPaidWithBanchile(77301);
        var purchase = reservationPaidWithBanchile(77302);
        banchile.hold(77301, approved(77301));
        var small = newDispatcher(1, 1, WAIT);
        try {
            Dispatch queued;
            try {
                assertEquals(Outcome.VERIFICATION_QUEUED, small.dispatch(signed(77301, running.reservationId(), "APPROVED")).outcome());
                assertTrue(banchile.awaitQueryInFlight(77301, START_WAIT), "la primera no consultó a Banchile");
                queued = small.dispatch(signed(77302, purchase.reservationId(), "APPROVED"));
                payAgain(purchase, 77303);
                banchile.respond(77302, approved(77302), Duration.ZERO);
                banchile.respond(77303, approved(77303), Duration.ZERO);
            } finally {
                banchile.release(77301);
            }

            assertEquals(Outcome.VERIFICATION_QUEUED, queued.outcome());
            assertEquals(CheckResult.NOT_PENDING, queued.check().get(WAIT.toSeconds(), TimeUnit.SECONDS));
            assertEquals(0, banchile.queries(77302));
            assertEquals(0, banchile.queries(77303), "la transacción nueva no es la de la notificación");
            assertEquals(Transaction.Status.PENDING, transactionByRequestId(77303).status());
            assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(purchase.reservationId()));
        } finally {
            small.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // G — cola acotada y apagado
    // -------------------------------------------------------------------------

    /**
     * Con el hilo ocupado y la cola llena, la notificación siguiente se descarta con un WARN: la
     * resuelven el poll o el cron.
     */
    @Test
    void withTheQueueFullTheNotificationIsDiscardedWithAWarning() throws Exception {
        var running = reservationPaidWithBanchile(77001);
        var queued = reservationPaidWithBanchile(77002);
        var discarded = reservationPaidWithBanchile(77003);
        banchile.hold(77001, approved(77001));
        banchile.respond(77002, approved(77002), Duration.ZERO);
        banchile.respond(77003, approved(77003), Duration.ZERO);
        var small = newDispatcher(1, 1, WAIT);
        try (var logs = new LogCapture(BanchileWebhookDispatcher.class)) {
            Dispatch first;
            Dispatch second;
            Dispatch third;
            try {
                first = small.dispatch(signed(77001, running.reservationId(), "APPROVED"));
                assertTrue(banchile.awaitQueryInFlight(77001, START_WAIT), "la primera no consultó a Banchile");
                second = small.dispatch(signed(77002, queued.reservationId(), "APPROVED"));
                third = small.dispatch(signed(77003, discarded.reservationId(), "APPROVED"));
            } finally {
                banchile.release(77001);
            }

            assertEquals(Outcome.VERIFICATION_QUEUED, first.outcome());
            assertEquals(Outcome.VERIFICATION_QUEUED, second.outcome());
            assertEquals(Outcome.DISCARDED, third.outcome());
            assertEquals(CheckResult.DISCARDED, third.check().get(WAIT.toSeconds(), TimeUnit.SECONDS));
            assertEquals(CheckResult.CHECKED, first.check().get(WAIT.toSeconds(), TimeUnit.SECONDS));
            assertEquals(CheckResult.CHECKED, second.check().get(WAIT.toSeconds(), TimeUnit.SECONDS));
            assertEquals(0, banchile.queries(77003));
            assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(discarded.reservationId()));
            assertFalse(logs.warningsAbout(discarded.reservationId()).isEmpty(), "el descarte debía quedar en un WARN");
        } finally {
            small.shutdown();
        }
    }

    /**
     * Al apagar: las verificaciones en cola se descartan con un WARN, la que está en curso termina
     * y las notificaciones que llegan mientras tanto se descartan.
     */
    @Test
    void onShutdownTheQueuedChecksAreDiscardedAndTheRunningOneFinishes() throws Exception {
        var running = reservationPaidWithBanchile(77101);
        var queued = reservationPaidWithBanchile(77102);
        var late = reservationPaidWithBanchile(77103);
        banchile.hold(77101, approved(77101));
        banchile.respond(77102, approved(77102), Duration.ZERO);
        banchile.respond(77103, approved(77103), Duration.ZERO);
        var small = newDispatcher(1, 1, WAIT);
        try (var logs = new LogCapture(BanchileWebhookDispatcher.class)) {
            Dispatch first;
            Dispatch second;
            Dispatch afterShutdown;
            Future<?> shutdown;
            CheckResult secondResult;
            try {
                first = small.dispatch(signed(77101, running.reservationId(), "APPROVED"));
                assertTrue(banchile.awaitQueryInFlight(77101, START_WAIT), "la primera no consultó a Banchile");
                second = small.dispatch(signed(77102, queued.reservationId(), "APPROVED"));
                shutdown = executor.submit(small::shutdown);
                secondResult = second.check().get(WAIT.toSeconds(), TimeUnit.SECONDS);
                afterShutdown = small.dispatch(signed(77103, late.reservationId(), "APPROVED"));
                assertFalse(shutdown.isDone(), "el apagado espera la verificación en curso");
                assertFalse(first.check().isDone());
            } finally {
                banchile.release(77101);
            }

            shutdown.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertEquals(CheckResult.CHECKED, first.check().get(WAIT.toSeconds(), TimeUnit.SECONDS));
            assertEquals(COMPLETE, reservationStatus(running.reservationId()), "la verificación en curso termina");
            assertEquals(CheckResult.DISCARDED, secondResult, "la que estaba en cola se descarta");
            assertEquals(Outcome.DISCARDED, afterShutdown.outcome());
            assertEquals(0, banchile.queries(77102));
            assertEquals(0, banchile.queries(77103));
            assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(queued.reservationId()));
            assertFalse(logs.warningsAbout(queued.reservationId()).isEmpty(), "el descarte al apagar debía quedar en un WARN");
        }
    }

    /**
     * Si la verificación en curso no termina dentro de la espera del apagado, se interrumpe: la
     * llamada a Banchile se corta y la reserva queda como estaba.
     */
    @Test
    void aCheckThatDoesNotFinishWithinTheShutdownWaitIsInterruptedWithoutChanges() throws Exception {
        var running = reservationPaidWithBanchile(77201);
        banchile.hold(77201, approved(77201));
        var small = newDispatcher(1, 1, Duration.ofMillis(200));
        try (var logs = new LogCapture(BanchileWebhookDispatcher.class)) {
            Dispatch first;
            try {
                first = small.dispatch(signed(77201, running.reservationId(), "APPROVED"));
                assertTrue(banchile.awaitQueryInFlight(77201, START_WAIT), "la verificación no consultó a Banchile");
                small.shutdown();
                first.check().get(WAIT.toSeconds(), TimeUnit.SECONDS);
            } finally {
                banchile.release(77201);
            }

            assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(running.reservationId()));
            assertEquals(Transaction.Status.PENDING, transactionByRequestId(77201).status());
            assertFalse(logs.warningsAbout("interrump").isEmpty(), "la interrupción debía quedar en un WARN");
        }
    }

    // -------------------------------------------------------------------------
    // H — CSRF y sesión
    // -------------------------------------------------------------------------

    /**
     * La cadena de seguridad de producción excluye {@code /api/payment/webhook/} de CSRF: el POST de
     * Banchile, sin token ni cookie de sesión, llega al controller y no crea una sesión. Control: el
     * mismo POST a otra ruta de la API pública lo rechaza CSRF.
     */
    @Test
    void theWebhookNeedsNeitherACsrfTokenNorASessionAndDoesNotCreateOne() throws Exception {
        var result = postNotification(notification(78001, UUID.randomUUID().toString(), "APPROVED", STATUS_DATE,
            "sha256:" + sha256Hex(78001, "APPROVED", STATUS_DATE)), MediaType.APPLICATION_JSON_VALUE);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(IGNORED, result.getResponse().getContentAsString(), "la request llegó al controller");
        assertNull(result.getRequest().getSession(false), "el webhook no crea una sesión");

        var protectedPost = mvc.perform(post("/api/v2/public/reservation/" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertEquals(403, protectedPost.getResponse().getStatus(), "control: sin el token, CSRF rechaza un POST a otra ruta");
    }

    // -------------------------------------------------------------------------
    // Helpers: requests al webhook
    // -------------------------------------------------------------------------

    private MvcResult postNotification(String body, String contentType) throws Exception {
        var request = post(WEBHOOK).content(body.getBytes(UTF_8));
        if (contentType != null) {
            request = request.contentType(contentType);
        }
        return mvc.perform(request).andReturn();
    }

    /**
     * Notificación con la forma documentada por Banchile.
     */
    private static String notification(int requestId, String reservationId, String status, String date, String signature) {
        return """
            {"status": {"status": "%s", "reason": "00", "message": "La petición ha sido aprobada exitosamente", "date": "%s"},
             "requestId": %d,
             "reference": "%s",
             "signature": "%s"}
            """.formatted(status, date, requestId, reservationId.replace("-", ""), signature);
    }

    private static String withoutField(String json, String field) {
        try {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) Json.OBJECT_MAPPER.readTree(json);
            node.remove(field);
            return Json.OBJECT_MAPPER.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * La notificación ya interpretada, bien firmada, para llamar al dispatcher sin pasar por HTTP.
     */
    private static Notification signed(int requestId, String reservationId, String status) {
        return new Notification(requestId, reservationId, status, STATUS_DATE, "sha256:" + sha256Hex(requestId, status, STATUS_DATE));
    }

    /**
     * Firma de Banchile calculada aparte del código de producción:
     * {@code SHA-256(requestId + status.status + status.date + secretKey)} en hex.
     */
    private static String sha256Hex(int requestId, String status, String date) {
        return HexFormat.of().formatHex(digest("SHA-256", requestId + status + date + SECRET_KEY));
    }

    private static byte[] digest(String algorithm, String input) {
        try {
            return MessageDigest.getInstance(algorithm).digest(input.getBytes(UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * La cadena de filtros de seguridad de producción ({@link FormBasedWebSecurity#filterChain}) con
     * el repositorio de tokens CSRF de producción ({@link WebSecurityConfig#getCsrfTokenRepository},
     * en la sesión HTTP), no el de cookies que define la configuración de los tests.
     */
    private Filter productionSecurityFilterChain() throws Exception {
        var security = new FormBasedWebSecurity(environment, userManager, recaptchaService, configurationManager,
            new WebSecurityConfig().getCsrfTokenRepository(), dataSource, passwordEncoder,
            new SpringSessionBackedSessionRegistry<>(sessionRepository), null, null, templateManager);
        var chain = security.filterChain(applicationContext.getBean(HttpSecurity.class), stripeConnectManager, mollieConnectManager);
        var proxy = new FilterChainProxy(chain);
        proxy.afterPropertiesSet();
        return proxy;
    }

    /**
     * Los convertidores de mensajes que arma {@link MvcConfiguration} en producción, en su orden.
     */
    private List<HttpMessageConverter<?>> productionMessageConverters() {
        var converters = new ArrayList<HttpMessageConverter<?>>();
        new MvcConfiguration(environment, "test", objectMapper).configureMessageConverters(converters);
        return converters;
    }

    /**
     * Un dispatcher propio, con su executor del tamaño dado, para probar la cola y el apagado sin
     * tocar el del contexto.
     */
    private BanchileWebhookDispatcher newDispatcher(int workers, int queueCapacity, Duration shutdownWait) {
        return new BanchileWebhookDispatcher(purchaseContextManager, ticketReservationManager, ticketReservationRepository,
            transactionRepository, banchilePagosWebhookManager, paymentCheckSingleFlight, transactionManager, clockProvider,
            workers, queueCapacity, shutdownWait);
    }

    // -------------------------------------------------------------------------
    // Helpers: escenario
    // -------------------------------------------------------------------------

    /**
     * Una compra: el evento, el usuario que lo creó y la reserva.
     */
    record Purchase(Event event, String username, String reservationId) {}

    /**
     * Reserva de 2 entradas, en un evento propio, que el comprador mandó a pagar con Banchile: pasa
     * por {@link BanchilePagosWebhookManager#doPayment}, que deja la reserva en
     * EXTERNAL_PROCESSING_PAYMENT y la transacción BANCHILE en PENDING con el requestId dado.
     */
    private Purchase reservationPaidWithBanchile(int requestId) {
        var clock = ClockProvider.clock();
        var categories = List.of(new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
            new DateTimeModification(LocalDate.now(clock).minusDays(1), LocalTime.now(clock)),
            new DateTimeModification(LocalDate.now(clock).plusDays(1), LocalTime.now(clock)),
            Map.of("en", "desc"), BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        var eventAndUsername = initEvent(categories, organizationRepository, userManager, eventManager, eventRepository);
        var event = eventAndUsername.getLeft();
        var category = ticketCategoryRepository.findAllTicketCategories(event.getId()).get(0);
        var ticketRequest = new TicketReservationModification();
        ticketRequest.setTicketCategoryId(category.getId());
        ticketRequest.setQuantity(2);
        var reservationId = ticketReservationManager.createTicketReservation(event,
            List.of(new TicketReservationWithOptionalCodeModification(ticketRequest, Optional.empty())), List.of(),
            DateUtils.addMinutes(new Date(), 25), Optional.empty(), Locale.ENGLISH, false, null);
        ticketReservationRepository.updateTicketReservation(reservationId, PENDING.name(), BUYER_EMAIL,
            "Comprador Prueba", "Comprador", "Prueba", "en", null, null, null, null);
        var purchase = new Purchase(event, eventAndUsername.getRight(), reservationId);
        payWithBanchile(purchase, requestId);
        return purchase;
    }

    /**
     * El comprador volvió a apretar "Pagar": alf.io crea otra sesión Banchile para la misma reserva y
     * deja INVALID la transacción de la anterior, sin cancelarla en Banchile.
     */
    private void payAgain(Purchase purchase, int requestId) {
        payWithBanchile(purchase, requestId);
        assertEquals(Transaction.Status.PENDING, transactionByRequestId(requestId).status());
    }

    private void payWithBanchile(Purchase purchase, int requestId) {
        banchile.nextSession(requestId);
        var reservation = ticketReservationRepository.findReservationById(purchase.reservationId());
        var totalPrice = ticketReservationManager.totalReservationCostWithVAT(reservation).getLeft();
        var spec = new PaymentSpecification(reservation, totalPrice, purchase.event(), null,
            ticketReservationManager.orderSummaryForReservation(reservation, purchase.event()), true, true);
        assertTrue(banchilePagosWebhookManager.doPayment(spec).isRedirect(), "doPayment debía redirigir a Banchile");
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(purchase.reservationId()));
    }

    /**
     * Cancelación desde el admin: libera las entradas y deja la reserva CANCELLED, sin tocar la
     * transacción Banchile.
     */
    private void cancelFromAdmin(Purchase purchase) {
        var result = adminReservationManager.removeReservation(PurchaseContextType.event, purchase.event().getShortName(),
            purchase.reservationId(), false, false, false, purchase.username());
        assertTrue(result.isSuccess(), "el admin no pudo cancelar la reserva");
        assertEquals(CANCELLED, reservationStatus(purchase.reservationId()));
    }

    /**
     * Mueve hacia atrás la creación de la sesión: con el reloj fijo de los tests, es la forma de
     * tener una sesión creada hace {@code age}.
     */
    private void backdate(int requestId, Duration age) {
        var createdAt = ZonedDateTime.now(ClockProvider.clock()).minus(age).toOffsetDateTime();
        assertEquals(1, jdbc.update("update b_transaction set t_timestamp = :createdAt where gtw_payment_id = :requestId",
            Map.of("createdAt", createdAt, "requestId", String.valueOf(requestId))));
    }

    /**
     * Nada cambió: Banchile no recibió consultas, la reserva sigue esperando el pago, la transacción
     * sigue PENDING y no hay correos.
     */
    private void assertNoEffects(Purchase purchase, int requestId) {
        assertEquals(0, banchile.queries(requestId), "no se debía consultar a Banchile");
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(purchase.reservationId()));
        assertEquals(Transaction.Status.PENDING, transactionByRequestId(requestId).status());
        assertTrue(emailsFor(purchase.event()).isEmpty(), () -> "no se debían enviar correos: " + emailsFor(purchase.event()));
    }

    private TicketReservationStatus reservationStatus(String reservationId) {
        return ticketReservationRepository.findOptionalStatusAndValidationById(reservationId)
            .map(TicketReservationStatusAndValidation::getStatus)
            .orElseThrow(() -> new AssertionError("la reserva " + reservationId + " no existe"));
    }

    /** Estado y metadata de una transacción, también si está INVALID. */
    private record StoredTransaction(Transaction.Status status, Map<String, String> metadata) {}

    private StoredTransaction transactionByRequestId(int requestId) {
        var row = jdbc.queryForMap("select status, coalesce(metadata::text, '{}') as metadata from b_transaction where gtw_payment_id = :requestId",
            Map.of("requestId", String.valueOf(requestId)));
        Map<String, String> metadata = Json.GSON.fromJson((String) row.get("metadata"), new TypeToken<Map<String, String>>() {}.getType());
        return new StoredTransaction(Transaction.Status.valueOf(((String) row.get("status")).trim()), metadata);
    }

    private List<Map<String, Object>> emailsFor(Event event) {
        return jdbc.queryForList("select recipient, subject, message from email_message where event_id = :eventId order by id",
            Map.of("eventId", event.getId()));
    }

    private List<Map<String, Object>> emailsTo(Event event, String recipient) {
        return emailsFor(event).stream().filter(m -> recipient.equals(m.get("recipient"))).toList();
    }

    /**
     * Espera, a lo sumo {@link #WAIT}, a que se cumpla la condición: la verificación que dispara el
     * webhook corre en segundo plano. No mide cuánto tarda; solo acota la espera.
     */
    private static void awaitCondition(BooleanSupplier condition, Supplier<String> failureMessage) throws InterruptedException {
        long end = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                fail(failureMessage.get());
            }
            Thread.sleep(20);
        }
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

    /**
     * Deja el pool con el tamaño de producción hasta que se cierre el resultado, con las conexiones
     * abiertas de antemano. {@code DataCleaner} recrea el pool después de cada test.
     */
    private AutoCloseable productionPool() throws Exception {
        var hikari = (HikariDataSource) dataSource.getTargetDataSource();
        int originalSize = hikari.getMaximumPoolSize();
        hikari.getHikariConfigMXBean().setMaximumPoolSize(PRODUCTION_POOL_SIZE);
        var connections = new ArrayList<java.sql.Connection>();
        try {
            for (int i = 0; i < PRODUCTION_POOL_SIZE; i++) {
                connections.add(hikari.getConnection());
            }
        } finally {
            for (var connection : connections) {
                connection.close();
            }
        }
        return () -> hikari.getHikariConfigMXBean().setMaximumPoolSize(originalSize);
    }

    // -------------------------------------------------------------------------
    // Respuestas de querySession del Banchile falso
    // -------------------------------------------------------------------------

    private static String approved(int requestId) {
        return """
            {"requestId": %d,
             "status": {"status": "APPROVED", "reason": "00", "message": "La petición ha sido aprobada exitosamente", "date": "2026-10-14T10:15:30-03:00"},
             "payment": [{"status": {"status": "APPROVED", "reason": "00", "message": "Aprobada", "date": "2026-10-14T10:15:30-03:00"},
                          "internalReference": %d, "paymentMethod": "visa", "paymentMethodName": "Visa", "issuerName": "BANCO DE PRUEBA",
                          "amount": {"from": {"total": 20, "currency": "CHF"}, "to": {"total": 20, "currency": "CHF"}, "factor": 1},
                          "authorization": "000003", "reference": "REF%d", "receipt": "6145%d", "franchise": "visa", "refunded": false,
                          "processorFields": [{"keyword": "lastDigits", "value": "4242", "displayOn": "none"}]}]}
            """.formatted(requestId, 900000 + requestId, requestId, requestId);
    }

    private static String rejected(int requestId) {
        return """
            {"requestId": %d,
             "status": {"status": "REJECTED", "reason": "?C", "message": "La petición ha sido cancelada por el usuario", "date": "2026-10-14T10:16:00-03:00"},
             "payment": [{"status": {"status": "REJECTED", "reason": "05", "message": "Rechazada", "date": "2026-10-14T10:15:50-03:00"},
                          "internalReference": %d, "paymentMethod": "visa", "paymentMethodName": "Visa", "issuerName": "BANCO DE PRUEBA",
                          "amount": {"from": {"total": 20, "currency": "CHF"}, "to": {"total": 20, "currency": "CHF"}, "factor": 1},
                          "authorization": null, "reference": "REF%d", "receipt": null, "franchise": "visa", "refunded": false,
                          "processorFields": [{"keyword": "lastDigits", "value": "0016", "displayOn": "none"}]}]}
            """.formatted(requestId, 800000 + requestId, requestId);
    }

    private static String pending(int requestId) {
        return """
            {"requestId": %d,
             "status": {"status": "PENDING", "reason": "PC", "message": "La petición se encuentra activa", "date": "2026-10-14T10:15:00-03:00"}}
            """.formatted(requestId);
    }
}
