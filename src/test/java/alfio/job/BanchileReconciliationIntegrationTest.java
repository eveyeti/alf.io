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
import alfio.controller.payment.api.PaymentApiController;
import alfio.manager.AdminReservationManager;
import alfio.manager.EventManager;
import alfio.manager.PurchaseContextManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.payment.BanchilePagosClient;
import alfio.manager.payment.BanchilePagosWebhookManager;
import alfio.manager.payment.PaymentSpecification;
import alfio.manager.system.AdminJobExecutor;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.PurchaseContext.PurchaseContextType;
import alfio.model.Ticket;
import alfio.model.TicketCategory;
import alfio.model.TicketReservation.TicketReservationStatus;
import alfio.model.TicketReservationStatusAndValidation;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.AdminReservationModification.Notification;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.model.modification.TicketReservationModification;
import alfio.model.modification.TicketReservationWithOptionalCodeModification;
import alfio.model.transaction.Transaction;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketRepository;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.repository.EventRepository;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.ClockProvider;
import alfio.util.Json;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static alfio.model.system.ConfigurationKeys.*;
import static alfio.model.TicketReservation.TicketReservationStatus.*;
import static alfio.test.util.IntegrationTestUtil.AVAILABLE_SEATS;
import static alfio.test.util.IntegrationTestUtil.initEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Conciliación de transacciones Banchile PENDING contra un Banchile falso local (MockServer
 * que responde como {@code querySession}).
 *
 * <p>La regla vive en {@code BanchilePagosWebhookManager#forceTransactionCheck} y aplica a todo
 * lo que consulta el estado del pago: el cron de 12 minutos, el polling del navegador
 * ({@code GET /reservation/{id}/status}) y los jobs de alf.io que fuerzan la verificación.
 * <ul>
 *   <li>Reserva en curso (EXTERNAL_PROCESSING_PAYMENT, WAITING_EXTERNAL_CONFIRMATION, STUCK):
 *       APPROVED la completa, REJECTED corre el flujo FAILED de alf.io.</li>
 *   <li>Reserva que no está en curso (CANCELLED, COMPLETE, PENDING, ...): no se toca; solo se
 *       cierra la transacción con el estado final de Banchile y se alerta al organizador si el pago
 *       vino aprobado y la reserva no está COMPLETE (hay que reembolsar), o si vino rechazado y la
 *       reserva está COMPLETE (se confirmó sin pago, como en el incidente BDD21987).</li>
 *   <li>Pago aprobado que Banchile informa como reembolsado: nunca completa la reserva; se
 *       trata como el caso anterior, con alerta.</li>
 *   <li>Sesión que alf.io dejó INVALID al crear otra para la misma reserva: el cron la revisa
 *       durante 90 minutos desde su creación y, si se pagó, alerta una vez de un posible doble cobro.
 *       No toca la reserva ni la transacción, salvo la marca de revisión en su metadata.</li>
 * </ul>
 *
 * <p>Los jobs de alf.io están deshabilitados: el cron se ejecuta a mano para controlar cuándo corre.
 */
@AlfioIntegrationTest
@ContextConfiguration(classes = {
    DataSourceConfiguration.class,
    TestConfiguration.class,
    ControllerConfiguration.class,
    BanchileReconciliationIntegrationTest.ShortBanchileTimeoutConfiguration.class
})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class BanchileReconciliationIntegrationTest {

    /** Tope de las llamadas a Banchile en este contexto (en producción es 20 s). */
    private static final Duration BANCHILE_TIMEOUT = Duration.ofSeconds(2);
    /**
     * Cota de lo que tarda un run del cron con Banchile colgado 30 s: el tope del cliente y bastante
     * margen para una máquina cargada, pero muy por debajo de los 30 s que tardaría sin tope.
     */
    private static final Duration CRON_RETURNS_WITHIN = BANCHILE_TIMEOUT.multipliedBy(5);
    private static final String BUYER_EMAIL = "comprador@example.com";
    /** Email de la organización que crea {@link IntegrationTestUtil#initEvent}. */
    private static final String ORGANIZATION_EMAIL = "email@example.com";
    private static final String ALERT_SENTENCE = "pago aprobado en Banchile para una reserva que no está activa: "
        + "verificar y reembolsar desde el portal de Banchile si corresponde";
    /** Incidente BDD21987 (29-ago): se confirmó a mano una reserva sin pago y se emitió la boleta SII 16104. */
    private static final String REJECTED_ALERT_SENTENCE = "pago rechazado en Banchile para una reserva confirmada en alf.io: "
        + "puede haber entradas y boleta emitidas sin pago. Revisar la reserva y, si corresponde, anular las entradas "
        + "y emitir la nota de crédito";
    /** Cierre de la alerta de pago aprobado: la revisión no tocó la reserva. */
    private static final String APPROVED_ALERT_CLOSING = "Esta revisión cerró la transacción como COMPLETE y no modificó la reserva.";
    /**
     * Nota que solo corresponde cuando la limpieza automática de alf.io puede borrar la reserva con
     * su transacción: las reservas PENDING vencidas (y las OFFLINE_PAYMENT vencidas, si la anulación
     * automática está activa). No promete que la reserva siga ahí.
     */
    private static final String CLEANUP_NOTE = "Si la reserva estaba vencida, alf.io puede eliminarla, junto con su "
        + "transacción, en su limpieza automática.";
    private static final String APPROVED_ALERT_RESERVATION_NOTE = APPROVED_ALERT_CLOSING + " " + CLEANUP_NOTE;
    /** Lo que hay que descartar antes de anular las entradas de una reserva confirmada. */
    private static final String VERIFY_BEFORE_ANNULLING = "verificar que no se haya pagado en otra sesión o por otro medio antes de anular";
    private static final String REFUNDED_CONFIRMED_ALERT_SENTENCE = "pago reembolsado en Banchile para una reserva confirmada en alf.io: "
        + "la reserva sigue confirmada aunque el pago se devolvió, así que puede haber entradas y boleta emitidas sin pago. "
        + "Revisar la reserva y, si corresponde, anular las entradas y emitir la nota de crédito";
    /**
     * Pago reembolsado de una reserva que no está confirmada: no se pide reembolsarlo (ya se devolvió)
     * ni se lo trata como un pago aprobado.
     */
    private static final String REFUNDED_NOT_CONFIRMED_ALERT_SENTENCE = "pago reembolsado en Banchile para una reserva que no está "
        + "confirmada en alf.io: alf.io no aplicó el pago a la reserva y Banchile informa que ya se devolvió, así que no hay que "
        + "reembolsarlo. Verificar en el portal de Banchile que la devolución esté hecha";
    /**
     * Nombre y correo del comprador que deja {@link #reservationPaidWithBanchile}: van en todas las alertas, porque si la
     * reserva se borra el correo es el único registro de quién pagó.
     */
    private static final String BUYER_LINES = "Comprador: Comprador Prueba\nCorreo del comprador: " + BUYER_EMAIL + "\n";
    /** Sesión que alf.io dejó INVALID al crear otra para la misma reserva y que el comprador pagó igual. */
    private static final String INVALID_SESSION_ALERT_SENTENCE = "pago aprobado en una sesión Banchile invalidada: posible doble cobro; "
        + "verificar y reembolsar desde el portal si corresponde";
    /** Marca de una sesión invalidada ya revisada en Banchile, para no volver a consultarla. */
    private static final String INVALIDATED_SESSION_REVIEW = "banchile_invalidated_session_review";
    /** Párrafo de la alerta de sesión invalidada cuando se revisó al procesar el rechazo de la sesión vigente. */
    private static final String REVIEWED_ON_THE_REJECTION = "al procesar el rechazo de la sesión vigente de la reserva (requestId ";
    /** Dos entradas de 10.00 CHF (precio de la categoría que crea {@link #createEvent()}). */
    private static final String RESERVATION_AMOUNT = "20.00 CHF";

    private static ClientAndServer banchile;

    @Autowired
    private TicketReservationManager ticketReservationManager;
    @Autowired
    private TicketReservationRepository ticketReservationRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private PurchaseContextManager purchaseContextManager;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private TicketCategoryRepository ticketCategoryRepository;
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
    private AdminReservationManager adminReservationManager;
    @Autowired
    private BanchilePagosWebhookManager banchilePagosWebhookManager;
    @Autowired
    private ReservationApiV2Controller reservationApiV2Controller;
    @Autowired
    private PaymentApiController paymentApiController;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ClockProvider clockProvider;

    private BanchileReconciliationJob job;
    private String username;
    /** payment.reference que alf.io mandó a Banchile en createSession, por reserva. */
    private final Map<String, String> referenceSentToBanchile = new HashMap<>();

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
        configurationRepository.insert(BANCHILE_LOGIN.name(), "ffb78b93826239e1aa85a515aa961bd9", "");
        configurationRepository.insert(BANCHILE_TRANKEY.name(), "U87nG0kcCsjb61Mj", "");
        configurationRepository.insert(BANCHILE_BASE_URL.name(), "http://localhost:" + banchile.getPort(), "");
        job = new BanchileReconciliationJob(transactionRepository, ticketReservationManager,
            ticketReservationRepository, purchaseContextManager, banchilePagosWebhookManager,
            new TransactionTemplate(transactionManager), clockProvider);
    }

    // -------------------------------------------------------------------------
    // B — reservas que no están en curso
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "CANCELLED + REJECTED solo cierra la transacción (reserva vencida: {0})")
    @ValueSource(booleans = {true, false})
    void cancelledReservationWithRejectedPaymentOnlyClosesTheTransaction(boolean reservationExpired) {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 17593);
        cancelFromAdmin(event, reservationId);
        if (reservationExpired) {
            expire(reservationId);
        }
        banchileResponds(17593, rejected(17593));

        job.reconcilePendingBanchileTransactions();

        var tx = banchileTransaction(reservationId);
        assertEquals(Transaction.Status.FAILED, tx.getStatus());
        assertEquals("REJECTED", tx.getMetadata().get("banchile_status"));
        assertEquals("17593", tx.getMetadata().get("banchile_request_id"));
        assertEquals(CANCELLED, reservationStatus(reservationId));
        assertTrue(ticketRepository.findTicketsInReservation(reservationId).isEmpty());
        assertTrue(emailsFor(event).isEmpty(), "un pago rechazado de una reserva cancelada no genera correos");
    }

    @Test
    void cancelledReservationWithApprovedPaymentClosesTheTransactionAndAlertsTheOrganizer() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 17412);
        cancelFromAdmin(event, reservationId);
        expire(reservationId);
        banchileResponds(17412, approved(17412));

        job.reconcilePendingBanchileTransactions();

        var tx = banchileTransaction(reservationId);
        assertEquals(Transaction.Status.COMPLETE, tx.getStatus());
        assertEquals("APPROVED", tx.getMetadata().get("banchile_status"));
        assertEquals(receipt(17412), tx.getMetadata().get("banchile_receipt"));
        assertEquals(authorization(17412), tx.getMetadata().get("banchile_authorization"));
        assertEquals(CANCELLED, reservationStatus(reservationId), "la reserva cancelada no se toca");
        assertTrue(ticketRepository.findTicketsInReservation(reservationId).isEmpty(), "las entradas siguen liberadas");
        assertEquals(0, pendingFinalizationRetries(), "no debe quedar una confirmación reintentándose");

        var alert = singleAlert(event);
        assertEquals(ORGANIZATION_EMAIL, alert.get("recipient"));
        var message = (String) alert.get("message");
        assertTrue(message.contains(ALERT_SENTENCE), message);
        assertTrue(message.contains(reservationId), message);
        assertTrue(message.contains("17412"), message);
        assertTrue(message.contains(RESERVATION_AMOUNT), message);
        assertTrue(message.contains(receipt(17412)), message);
        assertTrue(message.contains(authorization(17412)), message);
        // el ID con el que se reembolsa por /api/reverse
        assertTrue(message.contains("internalReference Banchile: " + internalReference(17412)), message);
        assertTrue(message.contains(APPROVED_ALERT_CLOSING), message);
        // la limpieza automática solo borra reservas PENDING: a una CANCELLED no le corresponde la nota
        assertFalse(message.contains(CLEANUP_NOTE), message);
        assertTrue(message.contains(BUYER_LINES), message);
        assertTrue(emailsTo(event, BUYER_EMAIL).isEmpty(), "al comprador no se le escribe");
    }

    /**
     * Pago reembolsado de una reserva cancelada: la alerta no lo presenta como un pago aprobado ni
     * pide reembolsarlo, porque Banchile ya lo devolvió.
     */
    @Test
    void cancelledReservationWithRefundedPaymentAlertsThatItWasRefundedWithoutAskingToRefundIt() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 64201);
        cancelFromAdmin(event, reservationId);
        banchileResponds(64201, approvedThenRefunded(64201));

        job.reconcilePendingBanchileTransactions();

        assertEquals(Transaction.Status.COMPLETE, banchileTransaction(reservationId).getStatus(), "la transacción deja de consultarse");
        assertEquals(CANCELLED, reservationStatus(reservationId));
        var alert = singleRefundedAlert(event);
        assertEquals("Banchile: pago reembolsado en una reserva no confirmada (requestId 64201)", alert.get("subject"));
        var message = (String) alert.get("message");
        assertTrue(message.contains("Estado de la reserva en alf.io: CANCELLED"), message);
        assertTrue(message.contains("internalReference Banchile: " + internalReference(64201)), message);
        assertTrue(message.contains(BUYER_LINES), message);
        assertTrue(message.contains(APPROVED_ALERT_CLOSING), message);
        assertTrue(alertsFor(event).isEmpty(), "no es un pago aprobado por reembolsar");
        assertFalse(message.contains("reembolsar desde el portal"), message);
    }

    /**
     * FINALIZING y OFFLINE_FINALIZING son etapas de una confirmación en curso (el finalizador las
     * lleva a COMPLETE, o las reintenta si falla): cuentan como confirmadas, igual que COMPLETE.
     */
    @ParameterizedTest(name = "{0} + REJECTED cierra la transacción y alerta de una reserva confirmada sin pago (reserva vencida: {1})")
    @CsvSource({"COMPLETE, true", "COMPLETE, false", "FINALIZING, false", "OFFLINE_FINALIZING, false"})
    void confirmedReservationWithRejectedPaymentAlertsThatItMayHaveBeenConfirmedWithoutPayment(TicketReservationStatus confirmed,
                                                                                               boolean reservationExpired) {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 41001);
        markConfirmed(reservationId, confirmed);
        if (reservationExpired) {
            expire(reservationId);
        }
        banchileResponds(41001, rejected(41001));

        job.reconcilePendingBanchileTransactions();

        var tx = banchileTransaction(reservationId);
        assertEquals(Transaction.Status.FAILED, tx.getStatus());
        assertEquals("REJECTED", tx.getMetadata().get("banchile_status"));
        assertEquals(confirmed, reservationStatus(reservationId), "la reserva confirmada no se toca");
        assertTicketsInStatus(reservationId, ticketsOf(confirmed));

        var alert = singleRejectedAlert(event);
        assertEquals(ORGANIZATION_EMAIL, alert.get("recipient"));
        assertTrue(((String) alert.get("subject")).contains("41001"), (String) alert.get("subject"));
        var message = (String) alert.get("message");
        assertTrue(message.contains(REJECTED_ALERT_SENTENCE), message);
        assertTrue(message.contains(VERIFY_BEFORE_ANNULLING), message);
        assertTrue(message.contains("Reserva: " + reservationId), message);
        assertTrue(message.contains("Estado de la reserva en alf.io: " + confirmed), message);
        // doPayment no fija el medio de pago de la reserva y confirmarla así tampoco
        assertTrue(message.contains("Medio de pago de la reserva en alf.io: no informado"), message);
        // la referencia con que se buscan en el portal todas las sesiones de la reserva: la que alf.io mandó en createSession
        assertTrue(message.contains("Referencia del comercio en Banchile: " + referenceSentToBanchile.get(reservationId)), message);
        assertTrue(message.contains("Otras transacciones Banchile de la reserva: ninguna"), message);
        assertTrue(message.contains("requestId Banchile: 41001"), message);
        assertTrue(message.contains("Monto: " + RESERVATION_AMOUNT), message);
        assertTrue(message.contains("Estado en Banchile: REJECTED"), message);
        assertTrue(message.contains("Razón Banchile: EX"), message);
        assertTrue(message.contains("Mensaje Banchile: La petición ha expirado"), message);
        assertTrue(message.contains("internalReference Banchile: " + rejectedAttemptInternalReference(41001)), message);
        assertTrue(message.contains(BUYER_LINES), message);
        assertTrue(alertsFor(event).isEmpty(), "no es un pago aprobado: no corresponde la alerta de reembolso");
        assertTrue(emailsTo(event, BUYER_EMAIL).isEmpty(), "al comprador no se le escribe");

        // la transacción ya no está PENDING: el run siguiente no la consulta ni vuelve a alertar
        assertEquals(0, job.reconcilePendingBanchileTransactions());
        assertEquals(1, rejectedAlertsFor(event).size());
    }

    /**
     * El caso de la revisión: el comprador apretó "Pagar" dos veces, pagó en la pestaña de la primera
     * sesión, la reserva venció y el comité la confirmó a mano. Cuando la segunda sesión vence, la
     * alerta de "confirmada sin pago" muestra la otra sesión (y que Banchile la aprobó), y la sesión
     * invalidada pagada tiene su propia alerta de posible doble cobro.
     */
    @Test
    void manualConfirmationAfterPayingTheOldSessionGetsBothAlertsWithTheWholePicture() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 61101);
        payAgain(event, reservationId, 61102);
        banchileResponds(61101, approved(61101));
        banchileResponds(61102, pending(61102));
        // vence, el stuck-check la marca STUCK y el comité la confirma a mano: el comprador mostró el pago
        expire(reservationId);
        ticketReservationManager.markExpiredInPaymentReservationAsStuck(new Date());
        assertEquals(STUCK, reservationStatus(reservationId));
        var confirmation = adminReservationManager.confirmReservation(PurchaseContextType.event, event.getShortName(),
            reservationId, username, Notification.EMPTY);
        assertTrue(confirmation.isSuccess(), String.valueOf(confirmation.getErrors()));
        assertEquals(COMPLETE, reservationStatus(reservationId));
        // la segunda sesión vence en Banchile
        banchileResponds(61102, rejected(61102));

        job.reconcilePendingBanchileTransactions();

        var rejectedMessage = (String) singleRejectedAlert(event).get("message");
        assertTrue(rejectedMessage.contains("requestId Banchile: 61102"), rejectedMessage);
        assertTrue(rejectedMessage.contains("Medio de pago de la reserva en alf.io: ADMIN"), rejectedMessage);
        assertTrue(rejectedMessage.contains("- requestId 61101: INVALID (Banchile: APPROVED)"), rejectedMessage);
        assertTrue(rejectedMessage.contains(VERIFY_BEFORE_ANNULLING), rejectedMessage);
        var invalidSession = singleInvalidSessionAlert(event);
        assertTrue(((String) invalidSession.get("subject")).contains("61101"), (String) invalidSession.get("subject"));
        var invalidMessage = (String) invalidSession.get("message");
        assertTrue(invalidMessage.contains("requestId Banchile: 61101"), invalidMessage);
        assertTrue(invalidMessage.contains("Otras transacciones Banchile de la reserva:\n- requestId 61102: PENDING\n"), invalidMessage);
        assertFalse(invalidMessage.contains("Sesión vigente"), "la sesión vigente ya está en la lista de otras transacciones\n" + invalidMessage);
        assertEquals(COMPLETE, reservationStatus(reservationId), "la reserva confirmada no se toca");
    }

    @Test
    void completedReservationWithRejectedSessionWithoutPaymentAttemptsAlertsWithoutInternalReference() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 41003);
        confirmManually(reservationId);
        // la sesión venció sin que el comprador ingresara una tarjeta: Banchile no informa intentos de pago
        banchileResponds(41003, """
            {"requestId": 41003,
             "status": {"status": "REJECTED", "reason": "EX", "message": "La petición ha expirado", "date": "2026-08-29T12:10:00-04:00"}}
            """);

        job.reconcilePendingBanchileTransactions();

        assertEquals(Transaction.Status.FAILED, banchileTransaction(reservationId).getStatus());
        assertEquals(COMPLETE, reservationStatus(reservationId));
        var message = (String) singleRejectedAlert(event).get("message");
        assertTrue(message.contains(REJECTED_ALERT_SENTENCE), message);
        assertTrue(message.contains("internalReference Banchile: no informado"), message);
    }

    @ParameterizedTest(name = "{0} + APPROVED cierra la transacción sin alertar")
    @EnumSource(value = TicketReservationStatus.class, names = {"COMPLETE", "FINALIZING", "OFFLINE_FINALIZING"})
    void confirmedReservationWithApprovedPaymentClosesTheTransactionWithoutAlert(TicketReservationStatus confirmed) {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 41002);
        markConfirmed(reservationId, confirmed);
        banchileResponds(41002, approved(41002));

        job.reconcilePendingBanchileTransactions();

        var tx = banchileTransaction(reservationId);
        assertEquals(Transaction.Status.COMPLETE, tx.getStatus());
        assertEquals("APPROVED", tx.getMetadata().get("banchile_status"));
        assertEquals(confirmed, reservationStatus(reservationId));
        assertTicketsInStatus(reservationId, ticketsOf(confirmed));
        assertTrue(emailsFor(event).isEmpty(), "el pago corresponde a una reserva confirmada: no hay nada que alertar");
    }

    @ParameterizedTest(name = "{0} con el pago reembolsado: la alerta no contradice que la reserva está confirmada")
    @EnumSource(value = TicketReservationStatus.class, names = {"COMPLETE", "FINALIZING", "OFFLINE_FINALIZING"})
    void confirmedReservationWithRefundedPaymentAlertsWithoutContradictingItsStatus(TicketReservationStatus confirmed) {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 41004);
        markConfirmed(reservationId, confirmed);
        banchileResponds(41004, approvedThenRefunded(41004));

        job.reconcilePendingBanchileTransactions();

        assertEquals(Transaction.Status.COMPLETE, banchileTransaction(reservationId).getStatus(), "la transacción deja de consultarse");
        assertEquals(confirmed, reservationStatus(reservationId), "la reserva confirmada no se toca");
        assertTicketsInStatus(reservationId, ticketsOf(confirmed));
        var emails = emailsFor(event);
        assertEquals(1, emails.size(), () -> "se esperaba UNA alerta: " + emails);
        var subject = (String) emails.get(0).get("subject");
        assertEquals("Banchile: pago reembolsado en una reserva confirmada (requestId 41004)", subject);
        var message = (String) emails.get(0).get("message");
        assertTrue(message.contains(REFUNDED_CONFIRMED_ALERT_SENTENCE), message);
        assertTrue(message.contains(VERIFY_BEFORE_ANNULLING), message);
        assertTrue(message.contains("Estado de la reserva en alf.io: " + confirmed), message);
        assertTrue(message.contains("internalReference Banchile: " + internalReference(41004)), message);
        assertFalse(message.contains("no está activa"), message);
        assertFalse(message.contains("la reserva no se completó"), message);
        assertTrue(message.contains(APPROVED_ALERT_CLOSING), message);
        assertFalse(message.contains(CLEANUP_NOTE), message);
        assertTrue(message.contains(BUYER_LINES), message);
    }

    // -------------------------------------------------------------------------
    // A — reservas STUCK
    // -------------------------------------------------------------------------

    @Test
    void stuckReservationWithApprovedPaymentIsCompleted() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 42001);
        markAsStuck(reservationId, 42001);
        banchileResponds(42001, approved(42001));

        job.reconcilePendingBanchileTransactions();

        assertEquals(COMPLETE, reservationStatus(reservationId));
        assertTicketsInStatus(reservationId, Ticket.TicketStatus.ACQUIRED);
        assertEquals(Transaction.Status.COMPLETE, banchileTransaction(reservationId).getStatus());
        assertFalse(emailsTo(event, BUYER_EMAIL).isEmpty(), "el comprador recibe la confirmación");
        assertTrue(alertsFor(event).isEmpty());
        assertEquals(0, pendingFinalizationRetries());
    }

    @Test
    void stuckReservationWithRejectedPaymentIsCancelledAndTheBuyerNotified() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 42002);
        markAsStuck(reservationId, 42002);
        banchileResponds(42002, rejected(42002));

        job.reconcilePendingBanchileTransactions();

        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty(),
            "el flujo FAILED de alf.io anula la reserva vencida");
        assertTrue(ticketRepository.findTicketsInReservation(reservationId).isEmpty(), "las entradas vuelven a la venta");
        assertFalse(emailsTo(event, BUYER_EMAIL).isEmpty(), "el comprador recibe el aviso de pago fallido");
        assertTrue(alertsFor(event).isEmpty());
    }

    @ParameterizedTest(name = "STUCK con pago reembolsado ({0}) no se completa y alerta")
    @ValueSource(strings = {"refunded=true en el pago", "estado REFUNDED"})
    void stuckReservationWithRefundedPaymentIsNotCompletedAndAlerts(String refundShape) {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 42003);
        markAsStuck(reservationId, 42003);
        banchileResponds(42003, refundShape.startsWith("refunded") ? approvedThenRefunded(42003) : refundedSession(42003));

        job.reconcilePendingBanchileTransactions();

        assertEquals(STUCK, reservationStatus(reservationId), "un pago reembolsado no completa la reserva");
        assertTicketsInStatus(reservationId, Ticket.TicketStatus.PENDING);
        var tx = banchileTransaction(reservationId);
        assertEquals(Transaction.Status.COMPLETE, tx.getStatus(), "la transacción deja de consultarse");
        assertEquals(receipt(42003), tx.getMetadata().get("banchile_receipt"));
        assertEquals(0, pendingFinalizationRetries());
        var alert = singleRefundedAlert(event);
        assertEquals("Banchile: pago reembolsado en una reserva no confirmada (requestId 42003)", alert.get("subject"));
        var message = (String) alert.get("message");
        assertTrue(alertsFor(event).isEmpty(), "un pago reembolsado no es un pago aprobado por reembolsar");
        assertFalse(message.contains("reembolsar desde el portal"), message);
        assertTrue(message.contains("Estado de la reserva en alf.io: STUCK"), message);
        assertTrue(message.contains(BUYER_LINES), message);
        // el del pago reembolsado, no el del intento rechazado que lo precede en la sesión REFUNDED
        assertTrue(message.contains("internalReference Banchile: " + internalReference(42003)), message);
        assertTrue(message.contains(APPROVED_ALERT_CLOSING), message);
        // la limpieza automática no borra reservas STUCK
        assertFalse(message.contains(CLEANUP_NOTE), message);
        assertTrue(emailsTo(event, BUYER_EMAIL).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Regresión: reservas en EXTERNAL_PROCESSING_PAYMENT
    // -------------------------------------------------------------------------

    @Test
    void reservationInPaymentWithApprovedPaymentIsCompleted() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 43001);
        banchileResponds(43001, approved(43001));

        job.reconcilePendingBanchileTransactions();

        assertEquals(COMPLETE, reservationStatus(reservationId));
        assertTicketsInStatus(reservationId, Ticket.TicketStatus.ACQUIRED);
        assertEquals(Transaction.Status.COMPLETE, banchileTransaction(reservationId).getStatus());
        assertFalse(emailsTo(event, BUYER_EMAIL).isEmpty());
        assertTrue(alertsFor(event).isEmpty());
    }

    @Test
    void reservationInPaymentWithRejectedPaymentGoesBackToPendingAndTheBuyerIsNotified() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 43002);
        banchileResponds(43002, rejected(43002));

        job.reconcilePendingBanchileTransactions();

        assertEquals(PENDING, reservationStatus(reservationId), "la reserva sigue vigente: el comprador puede reintentar");
        assertTicketsInStatus(reservationId, Ticket.TicketStatus.PENDING);
        assertEquals(Transaction.Status.FAILED, banchileTransaction(reservationId).getStatus());
        assertFalse(emailsTo(event, BUYER_EMAIL).isEmpty());
        assertTrue(alertsFor(event).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Banchile sin estado final / Banchile colgado
    // -------------------------------------------------------------------------

    @Test
    void pendingPaymentInBanchileChangesNothing() {
        var event = createEvent();
        var inPayment = reservationPaidWithBanchile(event, 44001);
        var cancelled = reservationPaidWithBanchile(event, 44002);
        cancelFromAdmin(event, cancelled);
        banchileResponds(44001, pending(44001));
        banchileResponds(44002, pending(44002));

        job.reconcilePendingBanchileTransactions();

        assertEquals(Transaction.Status.PENDING, banchileTransaction(inPayment).getStatus());
        assertEquals(Transaction.Status.PENDING, banchileTransaction(cancelled).getStatus());
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(inPayment));
        assertEquals(CANCELLED, reservationStatus(cancelled));
        assertTrue(emailsFor(event).isEmpty());
    }

    @Test
    void banchileTimeoutIsAnErrorWithoutChangesAndTheCronReturnsWithinTheLimit() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 45001);
        banchile.when(request().withMethod("POST").withPath("/api/session/45001"))
            .respond(response().withDelay(TimeUnit.SECONDS, 30)
                .withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody(approved(45001)));

        long start = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> job.reconcilePendingBanchileTransactions());
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(elapsed.compareTo(CRON_RETURNS_WITHIN) < 0,
            () -> "el cron debía volver por el tope del cliente (" + BANCHILE_TIMEOUT.toSeconds() + " s), sin esperar los 30 s de Banchile, "
                + "y tardó " + elapsed.toMillis() + " ms");
        assertEquals(Transaction.Status.PENDING, banchileTransaction(reservationId).getStatus());
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
        assertTicketsInStatus(reservationId, Ticket.TicketStatus.PENDING);
        assertTrue(emailsFor(event).isEmpty());
    }

    // -------------------------------------------------------------------------
    // C — la misma regla aplica al polling del navegador
    // -------------------------------------------------------------------------

    @Test
    void browserPollingAppliesTheSameRuleToACancelledReservation() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 46001);
        cancelFromAdmin(event, reservationId);
        banchileResponds(46001, approved(46001));

        var response = reservationApiV2Controller.getReservationStatus(reservationId);

        assertNotNull(response.getBody());
        assertEquals(CANCELLED, response.getBody().getStatus());
        assertEquals(Transaction.Status.COMPLETE, banchileTransaction(reservationId).getStatus());
        assertTrue(ticketRepository.findTicketsInReservation(reservationId).isEmpty());
        assertEquals(0, pendingFinalizationRetries());
        assertTrue(((String) singleAlert(event).get("message")).contains(reservationId));
    }

    // -------------------------------------------------------------------------
    // D — reserva PENDING con tx Banchile PENDING
    // -------------------------------------------------------------------------

    @Test
    void pendingReservationWithApprovedPaymentIsNotCompletedAndAlerts() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 47001);
        // ningún flujo de alf.io deja la reserva así: solo una edición manual de la BD
        ticketReservationRepository.updateReservationStatus(reservationId, PENDING.name());
        banchileResponds(47001, approved(47001));

        job.reconcilePendingBanchileTransactions();

        assertEquals(PENDING, reservationStatus(reservationId));
        assertTicketsInStatus(reservationId, Ticket.TicketStatus.PENDING);
        assertEquals(Transaction.Status.COMPLETE, banchileTransaction(reservationId).getStatus());
        var message = (String) singleAlert(event).get("message");
        assertTrue(message.contains(reservationId), message);
        // una reserva PENDING sí la borra la limpieza automática cuando vence
        assertTrue(message.contains(APPROVED_ALERT_RESERVATION_NOTE), message);
        assertTrue(emailsTo(event, BUYER_EMAIL).isEmpty());
    }

    @Test
    void pendingReservationWithRejectedPaymentOnlyClosesTheTransaction() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 47002);
        ticketReservationRepository.updateReservationStatus(reservationId, PENDING.name());
        banchileResponds(47002, rejected(47002));

        job.reconcilePendingBanchileTransactions();

        assertEquals(PENDING, reservationStatus(reservationId));
        assertTicketsInStatus(reservationId, Ticket.TicketStatus.PENDING);
        assertEquals(Transaction.Status.FAILED, banchileTransaction(reservationId).getStatus());
        assertTrue(emailsFor(event).isEmpty());
    }

    @Test
    void expiredPendingReservationWithApprovedPaymentIsAlertedBeforeTheCleanupRemovesIt() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 47003);
        ticketReservationRepository.updateReservationStatus(reservationId, PENDING.name());
        ticketReservationRepository.updateValidity(reservationId, DateUtils.addMinutes(new Date(), -5));
        banchileResponds(47003, approved(47003));

        // job de alf.io que borra las reservas PENDING vencidas (fuerza la verificación del pago antes)
        ticketReservationManager.cleanupExpiredReservations(new Date());

        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty(),
            "la reserva vencida se borra como cualquier otra: no se completa sola");
        assertTrue(transactionRepository.loadOptionalByReservationId(reservationId).isEmpty(),
            "la limpieza borra también la transacción: el correo es el único registro del pago");
        var message = (String) singleAlert(event).get("message");
        assertTrue(message.contains(reservationId), message);
        assertTrue(message.contains(receipt(47003)), message);
        assertTrue(message.contains("internalReference Banchile: " + internalReference(47003)), message);
        assertFalse(message.contains("la reserva no se modificó"),
            "la limpieza borra la reserva justo después: el correo no puede prometer que quedó intacta\n" + message);
        assertTrue(message.contains(APPROVED_ALERT_RESERVATION_NOTE), message);
    }

    // -------------------------------------------------------------------------
    // F — el cron cuenta solo los cambios reales
    // -------------------------------------------------------------------------

    @Test
    void reconciledCountsOnlyTransactionsThatLeftPending() {
        var event = createEvent();
        var closed = reservationPaidWithBanchile(event, 48001);
        cancelFromAdmin(event, closed);
        reservationPaidWithBanchile(event, 48002);
        reservationPaidWithBanchile(event, 48003);
        banchileResponds(48001, rejected(48001));
        banchileResponds(48002, pending(48002));
        banchile.when(request().withMethod("POST").withPath("/api/session/48003"))
            .respond(response().withDelay(TimeUnit.SECONDS, 30)
                .withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody(approved(48003)));

        assertEquals(1, job.reconcilePendingBanchileTransactions(),
            "solo la transacción que dejó de estar PENDING cuenta: ni el PENDING de Banchile ni el timeout");
    }

    // -------------------------------------------------------------------------
    // G — sesiones invalidadas: el comprador apretó "Pagar" otra vez y alf.io dejó la sesión
    // anterior INVALID sin cancelarla en Banchile, donde se puede pagar hasta que vence (30 min)
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "sesión invalidada pagada ({0}): una alerta de posible doble cobro, que no se repite")
    @ValueSource(strings = {"aprobada", "aprobada y reembolsada", "estado REFUNDED"})
    void paidInvalidatedSessionInsideTheWindowAlertsOnceAndIsNotQueriedAgain(String paymentShape) {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 62001);
        payAgain(event, reservationId, 62002);
        banchileResponds(62001, switch (paymentShape) {
            case "aprobada" -> approved(62001);
            case "aprobada y reembolsada" -> approvedThenRefunded(62001);
            default -> refundedSession(62001);
        });
        banchileResponds(62002, pending(62002));

        job.reconcilePendingBanchileTransactions();

        var alert = singleInvalidSessionAlert(event);
        assertEquals(ORGANIZATION_EMAIL, alert.get("recipient"));
        assertEquals("Banchile: pago aprobado en una sesión invalidada (requestId 62001)", alert.get("subject"));
        var message = (String) alert.get("message");
        assertTrue(message.contains("Reserva: " + reservationId), message);
        assertTrue(message.contains("Estado de la reserva en alf.io: EXTERNAL_PROCESSING_PAYMENT"), message);
        assertTrue(message.contains("requestId Banchile: 62001"), message);
        assertTrue(message.contains("Otras transacciones Banchile de la reserva:\n- requestId 62002: PENDING\n"), message);
        assertFalse(message.contains("Sesión vigente"), "la sesión vigente ya está en la lista de otras transacciones\n" + message);
        assertTrue(message.contains(BUYER_LINES), message);
        assertTrue(message.contains("Monto: " + RESERVATION_AMOUNT), message);
        assertTrue(message.contains("Recibo: " + receipt(62001)), message);
        assertTrue(message.contains("Autorización: " + authorization(62001)), message);
        assertTrue(message.contains("internalReference Banchile: " + internalReference(62001)), message);
        assertTrue(message.contains("Referencia del comercio en Banchile: " + referenceSentToBanchile.get(reservationId)), message);
        assertEquals(!"aprobada".equals(paymentShape), message.toLowerCase(Locale.ROOT).contains("reembolsado"), message);
        // el titular de esta alerta se mantiene (decisión del dueño); el cuerpo aclara que no hay que reembolsar
        assertEquals(!"aprobada".equals(paymentShape), message.contains("no hay que reembolsarlo"), message);
        // ni la reserva ni la transacción de la sesión invalidada cambian
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
        assertTicketsInStatus(reservationId, Ticket.TicketStatus.PENDING);
        var invalidated = transactionByRequestId(62001);
        assertEquals(Transaction.Status.INVALID, invalidated.status());
        assertEquals("alerted", invalidated.metadata().get(INVALIDATED_SESSION_REVIEW));
        assertEquals(Transaction.Status.PENDING, transactionByRequestId(62002).status());
        assertTrue(emailsTo(event, BUYER_EMAIL).isEmpty(), "al comprador no se le escribe");

        // el run siguiente no la vuelve a consultar ni a alertar
        job.reconcilePendingBanchileTransactions();
        assertEquals(1, queriesTo(62001));
        assertEquals(1, invalidSessionAlertsFor(event).size());
    }

    @Test
    void rejectedInvalidatedSessionIsMarkedWithoutAlertAndNotQueriedAgain() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 62101);
        payAgain(event, reservationId, 62102);
        banchileResponds(62101, rejected(62101));
        banchileResponds(62102, pending(62102));

        job.reconcilePendingBanchileTransactions();
        job.reconcilePendingBanchileTransactions();

        assertEquals(1, queriesTo(62101), "una sesión invalidada rechazada se consulta una sola vez");
        var invalidated = transactionByRequestId(62101);
        assertEquals(Transaction.Status.INVALID, invalidated.status());
        assertEquals("rejected", invalidated.metadata().get(INVALIDATED_SESSION_REVIEW));
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
        assertTrue(emailsFor(event).isEmpty(), "un rechazo no genera correos");
    }

    @Test
    void invalidatedSessionStillPendingInBanchileIsQueriedAgainOnTheNextRun() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 62201);
        payAgain(event, reservationId, 62202);
        banchileResponds(62201, pending(62201));
        banchileResponds(62202, pending(62202));

        job.reconcilePendingBanchileTransactions();
        job.reconcilePendingBanchileTransactions();

        assertEquals(2, queriesTo(62201), "mientras Banchile la informe PENDING, se consulta en cada run");
        assertNull(transactionByRequestId(62201).metadata().get(INVALIDATED_SESSION_REVIEW));
        assertTrue(emailsFor(event).isEmpty());

        // el comprador la paga: el run siguiente alerta
        banchileResponds(62201, approved(62201));
        job.reconcilePendingBanchileTransactions();
        assertEquals(1, invalidSessionAlertsFor(event).size());
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
    }

    /**
     * La ventana se mide desde {@code t_timestamp}, que es la creación de la sesión: doPayment inserta la
     * transacción justo después de createSession, e invalidarla no cambia esa columna. Una sesión de
     * Banchile vence a los 30 minutos y el cron corre cada 12: 90 minutos dejan ver un pago hecho en el
     * último minuto de la sesión aunque se pierdan varios runs del cron (un reinicio, un run atrasado).
     */
    @Test
    void invalidatedSessionsAreQueriedUntilNinetyMinutesAfterTheirCreation() {
        var event = createEvent();
        var inside = reservationPaidWithBanchile(event, 62301);
        var outside = reservationPaidWithBanchile(event, 62401);
        // las sesiones se crearon hace 89 y 91 minutos y después el comprador apretó "Pagar" otra vez
        backdate(62301, Duration.ofMinutes(89));
        backdate(62401, Duration.ofMinutes(91));
        payAgain(event, inside, 62302);
        payAgain(event, outside, 62402);
        banchileResponds(62301, approved(62301));
        banchileResponds(62401, approved(62401));
        banchileResponds(62302, pending(62302));
        banchileResponds(62402, pending(62402));

        job.reconcilePendingBanchileTransactions();

        assertEquals(1, queriesTo(62301), "a los 89 minutos la sesión invalidada todavía se revisa");
        assertEquals(0, queriesTo(62401), "fuera de la ventana no se consulta");
        var alert = singleInvalidSessionAlert(event);
        assertTrue(((String) alert.get("message")).contains("requestId Banchile: 62301"), (String) alert.get("message"));
        assertNull(transactionByRequestId(62401).metadata().get(INVALIDATED_SESSION_REVIEW));
    }

    @Test
    void banchileTimeoutWhileReviewingAnInvalidatedSessionChangesNothing() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 62501);
        payAgain(event, reservationId, 62502);
        banchile.when(request().withMethod("POST").withPath("/api/session/62501"), Times.once())
            .respond(response().withDelay(TimeUnit.SECONDS, 30)
                .withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody(approved(62501)));
        banchileResponds(62502, pending(62502));

        long start = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> job.reconcilePendingBanchileTransactions());
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(elapsed.compareTo(CRON_RETURNS_WITHIN) < 0,
            () -> "el cron debía volver por el tope del cliente (" + BANCHILE_TIMEOUT.toSeconds() + " s), sin esperar los 30 s de Banchile, "
                + "y tardó " + elapsed.toMillis() + " ms");
        var invalidated = transactionByRequestId(62501);
        assertEquals(Transaction.Status.INVALID, invalidated.status());
        assertNull(invalidated.metadata().get(INVALIDATED_SESSION_REVIEW), "un timeout no marca la sesión como revisada");
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
        assertTrue(emailsFor(event).isEmpty());

        // Banchile vuelve a responder: el run siguiente la revisa y alerta
        banchileResponds(62501, approved(62501));
        job.reconcilePendingBanchileTransactions();
        assertEquals(1, invalidSessionAlertsFor(event).size());
    }

    /**
     * El flujo de pago fallido de alf.io anula una reserva vencida y borra todas sus transacciones,
     * también las INVALID: por eso el cron revisa las sesiones invalidadas antes que las PENDING.
     */
    @Test
    void theInvalidatedSessionIsReviewedBeforeThePendingOneCanDeleteTheReservation() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 62601);
        payAgain(event, reservationId, 62602);
        expire(reservationId);
        banchileResponds(62601, approved(62601));
        banchileResponds(62602, rejected(62602));

        job.reconcilePendingBanchileTransactions();

        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty(),
            "el rechazo de la sesión vigente anula la reserva vencida y borra sus transacciones");
        var message = (String) singleInvalidSessionAlert(event).get("message");
        assertTrue(message.contains("requestId Banchile: 62601"), message);
        assertTrue(message.contains("Recibo: " + receipt(62601)), message);
    }

    /**
     * El comprador abrió Banchile, volvió atrás y eligió transferencia: la transacción Banchile queda
     * INVALID y la reserva OFFLINE_PAYMENT, que la anulación automática puede borrar al vencer.
     */
    @Test
    void paidInvalidatedSessionOfAnOfflinePaymentReservationWarnsThatTheCleanupMayRemoveIt() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 62701);
        transactionRepository.invalidateForReservation(reservationId, null);
        ticketReservationRepository.updateReservationStatus(reservationId, OFFLINE_PAYMENT.name());
        banchileResponds(62701, approved(62701));

        job.reconcilePendingBanchileTransactions();

        var message = (String) singleInvalidSessionAlert(event).get("message");
        assertTrue(message.contains("Estado de la reserva en alf.io: OFFLINE_PAYMENT"), message);
        assertTrue(message.contains("Otras transacciones Banchile de la reserva: ninguna"), message);
        assertFalse(message.contains("Sesión vigente"), message);
        assertTrue(message.contains(CLEANUP_NOTE), message);
        assertEquals(OFFLINE_PAYMENT, reservationStatus(reservationId));
    }

    // -------------------------------------------------------------------------
    // H — el flujo de pago fallido de alf.io anula la reserva vencida y borra todas sus
    // transacciones, también las sesiones invalidadas que el comprador pudo haber pagado: antes de
    // devolver FAILED, la consulta que vio el rechazo revisa esas sesiones (revisión adversarial,
    // ronda 4: caminos b1, b2 y c)
    // -------------------------------------------------------------------------

    /**
     * (b1) El poll del comprador procesa, antes que el cron, el rechazo de la sesión vigente de una
     * reserva vencida. La sesión invalidada que el comprador pagó se revisa en esa misma consulta y
     * se alerta una sola vez; después el flujo de pago fallido sigue como siempre.
     */
    @Test
    void thePollThatRejectsAnExpiredReservationAlertsThePaidInvalidatedSessionBeforeItIsDeleted() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 63101);
        payAgain(event, reservationId, 63102);
        banchileResponds(63101, approved(63101));
        banchileResponds(63102, rejected(63102));
        expire(reservationId);

        reservationApiV2Controller.getReservationStatus(reservationId);

        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty(),
            "el flujo de pago fallido sigue anulando la reserva vencida");
        assertEquals(0, transactionsOf(reservationId), "y borra todas sus transacciones");
        var message = (String) singleInvalidSessionAlert(event).get("message");
        assertTrue(message.contains("requestId Banchile: 63101"), message);
        assertTrue(message.contains("Recibo: " + receipt(63101)), message);
        assertTrue(message.contains("internalReference Banchile: " + internalReference(63101)), message);
        assertTrue(message.contains(REVIEWED_ON_THE_REJECTION + "63102, REJECTED en Banchile)"), message);
        // la reserva y sus transacciones se borran: el correo es el único registro de quién pagó
        assertTrue(message.contains(BUYER_LINES), message);

        // el cron que pasa después no la vuelve a consultar ni a alertar
        job.reconcilePendingBanchileTransactions();
        assertEquals(1, invalidSessionAlertsFor(event).size());
        assertEquals(1, queriesTo(63101));
    }

    /**
     * (b2) Lo mismo desde el stuck-check de alf.io, que corre cada 30 s sobre las reservas que
     * siguen esperando el pago 10 minutos después de vencer.
     */
    @Test
    void theStuckCheckThatRejectsAnExpiredReservationAlertsThePaidInvalidatedSessionBeforeItIsDeleted() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 63201);
        payAgain(event, reservationId, 63202);
        banchileResponds(63201, approved(63201));
        banchileResponds(63202, rejected(63202));
        expire(reservationId);

        ticketReservationManager.markExpiredInPaymentReservationAsStuck(DateUtils.addMinutes(new Date(), -10));

        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty(),
            "el flujo de pago fallido sigue anulando la reserva vencida");
        assertEquals(0, transactionsOf(reservationId));
        var message = (String) singleInvalidSessionAlert(event).get("message");
        assertTrue(message.contains("requestId Banchile: 63201"), message);
        assertTrue(message.contains(REVIEWED_ON_THE_REJECTION + "63202, REJECTED en Banchile)"), message);

        job.reconcilePendingBanchileTransactions();
        assertEquals(1, invalidSessionAlertsFor(event).size());
        assertEquals(1, queriesTo(63201));
    }

    /**
     * El force-check ("verificar pago") pasa por el mismo {@code forceTransactionCheck}: también
     * revisa la sesión invalidada antes de que el flujo de pago fallido borre la reserva vencida.
     */
    @Test
    void theForceCheckThatRejectsAnExpiredReservationAlertsThePaidInvalidatedSessionBeforeItIsDeleted() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 63451);
        payAgain(event, reservationId, 63452);
        banchileResponds(63451, approved(63451));
        banchileResponds(63452, rejected(63452));
        expire(reservationId);

        var response = paymentApiController.forceCheckStatus(reservationId);

        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccessful(), "el pago vigente vino rechazado");
        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty(),
            "el flujo de pago fallido sigue anulando la reserva vencida");
        var message = (String) singleInvalidSessionAlert(event).get("message");
        assertTrue(message.contains("requestId Banchile: 63451"), message);
        assertTrue(message.contains(REVIEWED_ON_THE_REJECTION + "63452, REJECTED en Banchile)"), message);
        assertTrue(message.contains(BUYER_LINES), message);

        job.reconcilePendingBanchileTransactions();
        assertEquals(1, invalidSessionAlertsFor(event).size());
        assertEquals(1, queriesTo(63451));
    }

    /**
     * (c) Con la reserva vigente, el rechazo de la sesión vigente la devuelve a PENDING; si el
     * comprador no vuelve a pagar, la limpieza de reservas vencidas la borra con todas sus
     * transacciones sin consultar a Banchile, porque ya no tiene una transacción PENDING. La sesión
     * invalidada se revisa al procesar el rechazo, antes de la limpieza.
     */
    @Test
    void theRejectionOfAValidReservationAlertsThePaidInvalidatedSessionBeforeTheCleanupDeletesIt() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 63301);
        payAgain(event, reservationId, 63302);
        banchileResponds(63301, approved(63301));
        banchileResponds(63302, rejected(63302));

        reservationApiV2Controller.getReservationStatus(reservationId);

        assertEquals(PENDING, reservationStatus(reservationId), "con la reserva vigente, el rechazo la devuelve a PENDING");
        assertEquals("alerted", transactionByRequestId(63301).metadata().get(INVALIDATED_SESSION_REVIEW));
        assertEquals(Transaction.Status.INVALID, transactionByRequestId(63301).status());
        assertEquals(1, invalidSessionAlertsFor(event).size());

        // el cron que pasa mientras la reserva sigue vigente no la vuelve a consultar ni a alertar
        job.reconcilePendingBanchileTransactions();
        assertEquals(1, queriesTo(63301));

        // el comprador no vuelve a pagar: la reserva vence y la limpieza la borra con todas sus transacciones
        expire(reservationId);
        ticketReservationManager.cleanupExpiredReservations(DateUtils.addMinutes(new Date(), -10));
        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty());
        assertEquals(0, transactionsOf(reservationId));

        job.reconcilePendingBanchileTransactions();
        assertEquals(1, invalidSessionAlertsFor(event).size(), "una sola alerta");
        assertEquals(1, queriesTo(63301));
    }

    /**
     * Si Banchile no responde la consulta de la sesión invalidada, el rechazo todavía no se aplica:
     * la transacción vigente sigue PENDING y la reserva intacta, para que la consulta siguiente
     * revise la sesión invalidada antes de que el flujo de pago fallido la borre.
     */
    @Test
    void ifTheInvalidatedSessionCannotBeReviewedTheRejectionWaitsForTheNextCheck() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 63601);
        payAgain(event, reservationId, 63602);
        banchile.when(request().withMethod("POST").withPath("/api/session/63601"), Times.once())
            .respond(response().withStatusCode(500).withContentType(MediaType.APPLICATION_JSON).withBody("{}"));
        banchileResponds(63602, rejected(63602));
        expire(reservationId);

        reservationApiV2Controller.getReservationStatus(reservationId);

        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId),
            "la reserva no se anula mientras la sesión invalidada no se pudo revisar");
        assertEquals(Transaction.Status.PENDING, transactionByRequestId(63602).status(), "el rechazo se vuelve a consultar después");
        assertEquals(Transaction.Status.INVALID, transactionByRequestId(63601).status());
        assertNull(transactionByRequestId(63601).metadata().get(INVALIDATED_SESSION_REVIEW));
        assertTrue(emailsFor(event).isEmpty());

        // Banchile vuelve a responder: la consulta siguiente alerta y recién entonces corre el flujo de pago fallido
        banchileResponds(63601, approved(63601));
        reservationApiV2Controller.getReservationStatus(reservationId);

        assertEquals(1, invalidSessionAlertsFor(event).size());
        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty());
    }

    /**
     * La revisión antes del flujo de pago fallido usa la misma ventana que el cron: una sesión
     * invalidada creada hace más de 90 minutos venció en Banchile hace rato y no se consulta.
     */
    @Test
    void theRejectionDoesNotQueryInvalidatedSessionsOutsideTheWindow() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 63701);
        backdate(63701, Duration.ofMinutes(91));
        payAgain(event, reservationId, 63702);
        banchileResponds(63701, approved(63701));
        banchileResponds(63702, rejected(63702));
        expire(reservationId);

        reservationApiV2Controller.getReservationStatus(reservationId);

        assertEquals(0, queriesTo(63701), "fuera de la ventana no se consulta");
        assertTrue(invalidSessionAlertsFor(event).isEmpty());
        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty());
    }

    /**
     * Una sesión invalidada que el cron ya revisó (aquí, rechazada) no se vuelve a consultar al
     * procesar el rechazo de la sesión vigente.
     */
    @Test
    void theRejectionDoesNotQueryAnInvalidatedSessionTheCronAlreadyReviewed() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 63801);
        payAgain(event, reservationId, 63802);
        banchileResponds(63801, rejected(63801));
        banchileResponds(63802, pending(63802));
        job.reconcilePendingBanchileTransactions();
        assertEquals("rejected", transactionByRequestId(63801).metadata().get(INVALIDATED_SESSION_REVIEW));

        banchileResponds(63802, rejected(63802));
        expire(reservationId);
        reservationApiV2Controller.getReservationStatus(reservationId);

        assertEquals(1, queriesTo(63801), "la sesión invalidada ya revisada no se vuelve a consultar");
        assertTrue(invalidSessionAlertsFor(event).isEmpty());
        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty());
    }

    /**
     * Riesgo que queda: una sesión invalidada que Banchile todavía informa PENDING no tiene nada que
     * alertar, y el flujo de pago fallido borra su transacción con la reserva vencida. Si el
     * comprador la paga después, ningún proceso de alf.io la ve.
     */
    @Test
    void anInvalidatedSessionStillPendingInBanchileIsWarnedAndDoesNotHoldTheRejection() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 63901);
        payAgain(event, reservationId, 63902);
        banchileResponds(63901, pending(63901));
        banchileResponds(63902, rejected(63902));
        expire(reservationId);

        List<String> warnings;
        try (var logs = new LogCapture(BanchilePagosWebhookManager.class)) {
            reservationApiV2Controller.getReservationStatus(reservationId);
            warnings = logs.warningsAbout("requestId=63901");
        }

        assertEquals(1, queriesTo(63901), "la sesión invalidada se consulta antes del flujo de pago fallido");
        assertTrue(invalidSessionAlertsFor(event).isEmpty());
        assertTrue(ticketReservationRepository.findOptionalReservationById(reservationId).isEmpty(),
            "sin un estado final que alertar, el flujo de pago fallido sigue y borra la reserva vencida");
        assertEquals(0, transactionsOf(reservationId));
        // queda en el log, para buscarla en el portal: alf.io ya no la puede volver a consultar
        assertEquals(1, warnings.size(), () -> "se esperaba UN aviso de la sesión 63901: " + warnings);
        assertTrue(warnings.get(0).contains("(PENDING)"), warnings.get(0));
        assertTrue(warnings.get(0).contains("rechazo de la sesión vigente requestId=63902"), warnings.get(0));
        assertTrue(warnings.get(0).contains(reservationId), warnings.get(0));
    }

    /**
     * Una sesión invalidada que Banchile sigue informando sin un estado final (PENDING o
     * APPROVED_PARTIAL) deja de revisarse cuando sale de la ventana: en su última revisión queda un
     * aviso en el log, para buscarla en el portal. La que todavía tiene runs por delante no avisa.
     */
    @ParameterizedTest(name = "sesión invalidada que sale de la ventana sin estado final ({0}): un aviso en su última revisión")
    @ValueSource(strings = {"PENDING", "APPROVED_PARTIAL"})
    void anInvalidatedSessionLeavingTheWindowWithoutAFinalStatusIsWarnedOnItsLastReview(String banchileStatus) {
        var event = createEvent();
        var leaving = reservationPaidWithBanchile(event, 64301);
        var staying = reservationPaidWithBanchile(event, 64401);
        // creadas hace 80 y 70 minutos: el run siguiente, 12 minutos después, ya no revisa la primera
        backdate(64301, Duration.ofMinutes(80));
        backdate(64401, Duration.ofMinutes(70));
        payAgain(event, leaving, 64302);
        payAgain(event, staying, 64402);
        banchileResponds(64301, withoutFinalStatus(64301, banchileStatus));
        banchileResponds(64401, withoutFinalStatus(64401, banchileStatus));
        banchileResponds(64302, pending(64302));
        banchileResponds(64402, pending(64402));

        List<String> leavingWarnings;
        List<String> stayingWarnings;
        try (var logs = new LogCapture(BanchilePagosWebhookManager.class)) {
            job.reconcilePendingBanchileTransactions();
            leavingWarnings = logs.warningsAbout("requestId=64301");
            stayingWarnings = logs.warningsAbout("requestId=64401");
        }

        assertEquals(1, queriesTo(64301));
        assertEquals(1, queriesTo(64401));
        assertEquals(1, leavingWarnings.size(), () -> "se esperaba UN aviso de la sesión 64301: " + leavingWarnings);
        assertTrue(leavingWarnings.get(0).contains("(" + banchileStatus + ")"), leavingWarnings.get(0));
        assertTrue(leavingWarnings.get(0).contains("sale de la ventana de revisión de 90 minutos"), leavingWarnings.get(0));
        assertTrue(leavingWarnings.get(0).contains(leaving), leavingWarnings.get(0));
        assertTrue(stayingWarnings.isEmpty(), () -> "la sesión 64401 todavía se revisa en el run siguiente: " + stayingWarnings);
        assertNull(transactionByRequestId(64301).metadata().get(INVALIDATED_SESSION_REVIEW));
        assertTrue(emailsFor(event).isEmpty());
    }

    /**
     * Si Banchile falla en la última revisión de una sesión invalidada, el error lo dice: la sesión
     * sale de la ventana sin estado final y tampoco se volverá a consultar.
     */
    @Test
    void aBanchileErrorOnTheLastReviewOfAnInvalidatedSessionSaysItWillNotBeQueriedAgain() {
        var event = createEvent();
        var reservationId = reservationPaidWithBanchile(event, 64501);
        backdate(64501, Duration.ofMinutes(80));
        payAgain(event, reservationId, 64502);
        banchile.when(request().withMethod("POST").withPath("/api/session/64501"), Times.once())
            .respond(response().withStatusCode(500).withContentType(MediaType.APPLICATION_JSON).withBody("{}"));
        banchileResponds(64502, pending(64502));

        List<String> errors;
        try (var logs = new LogCapture(BanchileReconciliationJob.class)) {
            job.reconcilePendingBanchileTransactions();
            errors = logs.errorsAbout("requestId=64501");
        }

        assertEquals(1, errors.size(), () -> "se esperaba UN error de la sesión 64501: " + errors);
        assertTrue(errors.get(0).contains("última revisión"), errors.get(0));
        assertTrue(errors.get(0).contains(reservationId), errors.get(0));
        assertNull(transactionByRequestId(64501).metadata().get(INVALIDATED_SESSION_REVIEW));
    }

    // -------------------------------------------------------------------------
    // Primer run en producción
    // -------------------------------------------------------------------------

    @Test
    void firstProductionRunClosesTheFourPendingTransactionsOfCancelledReservations() {
        var signos = createEvent();
        var yntg = createEvent();
        var historic = createEvent();
        var paidButCancelled = reservationPaidWithBanchile(signos, 17412);
        var rejected1 = reservationPaidWithBanchile(signos, 17593);
        var rejected2 = reservationPaidWithBanchile(signos, 17594);
        var rejected3 = reservationPaidWithBanchile(yntg, 39876);
        // las 4 reservas de producción se cancelaron desde el admin hace meses: están vencidas
        for (var reservationId : List.of(paidButCancelled, rejected1, rejected2)) {
            cancelFromAdmin(signos, reservationId);
            expire(reservationId);
        }
        cancelFromAdmin(yntg, rejected3);
        expire(rejected3);
        banchileResponds(17412, approved(17412));
        banchileResponds(17593, rejected(17593));
        banchileResponds(17594, rejected(17594));
        banchileResponds(39876, rejected(39876));
        // las 6 transacciones INVALID de producción, de agosto y septiembre (todas REJECTED en Banchile)
        var historicInvalidated = List.of(16101, 16102, 16103, 33101, 33102, 33103);
        var ageInDays = List.of(55, 50, 45, 30, 25, 20);
        for (int i = 0; i < historicInvalidated.size(); i++) {
            int requestId = historicInvalidated.get(i);
            var reservationId = reservationPaidWithBanchile(historic, requestId);
            transactionRepository.invalidateForReservation(reservationId, null);
            backdate(requestId, Duration.ofDays(ageInDays.get(i)));
            confirmManually(reservationId);
            banchileResponds(requestId, rejected(requestId));
        }

        assertEquals(4, job.reconcilePendingBanchileTransactions());

        for (int requestId : historicInvalidated) {
            assertEquals(0, queriesTo(requestId), "la transacción INVALID " + requestId + " está fuera de la ventana: no se consulta");
            assertNull(transactionByRequestId(requestId).metadata().get(INVALIDATED_SESSION_REVIEW));
        }
        assertTrue(emailsFor(historic).isEmpty());

        assertEquals(Transaction.Status.COMPLETE, banchileTransaction(paidButCancelled).getStatus());
        assertEquals(Transaction.Status.FAILED, banchileTransaction(rejected1).getStatus());
        assertEquals(Transaction.Status.FAILED, banchileTransaction(rejected2).getStatus());
        assertEquals(Transaction.Status.FAILED, banchileTransaction(rejected3).getStatus());
        for (var reservationId : List.of(paidButCancelled, rejected1, rejected2, rejected3)) {
            assertEquals(CANCELLED, reservationStatus(reservationId));
        }
        assertTrue(((String) singleAlert(signos).get("message")).contains(paidButCancelled));
        assertTrue(rejectedAlertsFor(signos).isEmpty());
        // rq 39876 es la reserva del incidente BDD21987 (confirmada sin pago el 29-ago): hoy está
        // CANCELLED, así que su REJECTED no genera la alerta de "confirmada sin pago" ni ningún otro correo
        assertTrue(emailsFor(yntg).isEmpty());
        assertEquals(0, pendingFinalizationRetries());

        // el segundo run ya no tiene nada que consultar ni alertar
        assertEquals(0, job.reconcilePendingBanchileTransactions());
        assertEquals(1, alertsFor(signos).size());
        assertEquals(1, emailsFor(signos).size());
    }

    // -------------------------------------------------------------------------
    // Helpers: escenario
    // -------------------------------------------------------------------------

    /**
     * Evento con una categoría de 20 entradas de 10.00 CHF a la venta.
     */
    private Event createEvent() {
        var clock = ClockProvider.clock();
        var categories = List.of(new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
            new DateTimeModification(LocalDate.now(clock).minusDays(1), LocalTime.now(clock)),
            new DateTimeModification(LocalDate.now(clock).plusDays(1), LocalTime.now(clock)),
            Map.of("en", "desc"), BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        var eventAndUsername = initEvent(categories, organizationRepository, userManager, eventManager, eventRepository);
        username = eventAndUsername.getRight();
        return eventAndUsername.getLeft();
    }

    /**
     * Reserva de 2 entradas que el comprador mandó a pagar con Banchile: pasa por
     * {@link BanchilePagosWebhookManager#doPayment}, que deja la reserva en
     * EXTERNAL_PROCESSING_PAYMENT y la transacción BANCHILE en PENDING con el requestId dado.
     */
    private String reservationPaidWithBanchile(Event event, int requestId) {
        var category = ticketCategoryRepository.findAllTicketCategories(event.getId()).get(0);
        var ticketRequest = new TicketReservationModification();
        ticketRequest.setTicketCategoryId(category.getId());
        ticketRequest.setQuantity(2);
        var reservationId = ticketReservationManager.createTicketReservation(event,
            List.of(new TicketReservationWithOptionalCodeModification(ticketRequest, Optional.empty())), List.of(),
            DateUtils.addMinutes(new Date(), 25), Optional.empty(), Locale.ENGLISH, false, null);
        ticketReservationRepository.updateTicketReservation(reservationId, PENDING.name(), BUYER_EMAIL,
            "Comprador Prueba", "Comprador", "Prueba", "en", null, null, null, null);

        payWithBanchile(event, reservationId, requestId);
        assertEquals(Transaction.Status.PENDING, banchileTransaction(reservationId).getStatus());
        return reservationId;
    }

    /**
     * El comprador volvió a apretar "Pagar": alf.io crea otra sesión Banchile para la misma reserva y
     * deja INVALID la transacción de la anterior, sin cancelarla en Banchile.
     */
    private void payAgain(Event event, String reservationId, int requestId) {
        var previous = banchileTransaction(reservationId);
        payWithBanchile(event, reservationId, requestId);
        assertEquals(Transaction.Status.INVALID, transactionByRequestId(Integer.parseInt(previous.getPaymentId())).status());
        assertEquals(Transaction.Status.PENDING, transactionByRequestId(requestId).status());
    }

    /**
     * {@link BanchilePagosWebhookManager#doPayment} con un Banchile que crea la sesión con el
     * requestId dado. Guarda la referencia del comercio que alf.io le mandó a Banchile.
     */
    private void payWithBanchile(Event event, String reservationId, int requestId) {
        banchile.when(request().withMethod("POST").withPath("/api/session"), Times.once())
            .respond(json("""
                {"status": {"status": "OK", "reason": "PC", "message": "La petición se ha procesado correctamente",
                            "date": "2026-05-25T11:40:00-04:00"},
                 "requestId": %d, "processUrl": "https://checkout.banchilepagos.cl/spa/session/%d/abc"}
                """.formatted(requestId, requestId)));
        var reservation = ticketReservationRepository.findReservationById(reservationId);
        var totalPrice = ticketReservationManager.totalReservationCostWithVAT(reservation).getLeft();
        var spec = new PaymentSpecification(reservation, totalPrice, event, null,
            ticketReservationManager.orderSummaryForReservation(reservation, event), true, true);
        assertTrue(banchilePagosWebhookManager.doPayment(spec).isRedirect(), "doPayment debía redirigir a Banchile");
        assertEquals(EXTERNAL_PROCESSING_PAYMENT, reservationStatus(reservationId));
        var createSessionRequests = banchile.retrieveRecordedRequests(request().withMethod("POST").withPath("/api/session"));
        var lastBody = createSessionRequests[createSessionRequests.length - 1].getBodyAsString();
        referenceSentToBanchile.put(reservationId,
            JsonParser.parseString(lastBody).getAsJsonObject().getAsJsonObject("payment").get("reference").getAsString());
    }

    /**
     * Cancelación desde el admin, como las 4 reservas de producción: libera las entradas y deja
     * la reserva CANCELLED, sin tocar la transacción Banchile.
     */
    private void cancelFromAdmin(Event event, String reservationId) {
        var result = adminReservationManager.removeReservation(PurchaseContextType.event, event.getShortName(),
            reservationId, false, false, false, username);
        assertTrue(result.isSuccess(), "el admin no pudo cancelar la reserva");
        assertEquals(CANCELLED, reservationStatus(reservationId));
        assertTrue(ticketRepository.findTicketsInReservation(reservationId).isEmpty());
    }

    /**
     * Deja vencida la validez de la reserva (las de producción se crearon hace meses).
     */
    private void expire(String reservationId) {
        ticketReservationRepository.updateValidity(reservationId, DateUtils.addMinutes(new Date(), -40));
    }

    /**
     * Reserva confirmada a mano sin consultar Banchile: entradas ACQUIRED y reserva COMPLETE.
     */
    private void confirmManually(String reservationId) {
        ticketRepository.updateTicketsStatusWithReservationId(reservationId, Ticket.TicketStatus.ACQUIRED.name());
        ticketReservationRepository.updateReservationStatus(reservationId, COMPLETE.name());
    }

    /**
     * Reserva confirmada sin consultar Banchile: COMPLETE con las entradas ACQUIRED, como una
     * confirmación a mano, o en una etapa intermedia de la confirmación (FINALIZING u
     * OFFLINE_FINALIZING), con las entradas todavía PENDING.
     */
    private void markConfirmed(String reservationId, TicketReservationStatus confirmed) {
        if (confirmed == COMPLETE) {
            confirmManually(reservationId);
        } else {
            ticketReservationRepository.updateReservationStatus(reservationId, confirmed.name());
        }
    }

    private static Ticket.TicketStatus ticketsOf(TicketReservationStatus confirmed) {
        return confirmed == COMPLETE ? Ticket.TicketStatus.ACQUIRED : Ticket.TicketStatus.PENDING;
    }

    /**
     * Mueve la creación de la sesión hacia atrás: con el reloj fijo de los tests, es la forma de
     * tener una sesión creada hace {@code age}.
     */
    private void backdate(int requestId, Duration age) {
        var createdAt = ZonedDateTime.now(ClockProvider.clock()).minus(age).toOffsetDateTime();
        assertEquals(1, jdbc.update("update b_transaction set t_timestamp = :createdAt where gtw_payment_id = :requestId",
            Map.of("createdAt", createdAt, "requestId", String.valueOf(requestId))));
    }

    /**
     * La reserva vence mientras Banchile sigue PENDING y el stuck-check de alf.io la marca STUCK.
     */
    private void markAsStuck(String reservationId, int requestId) {
        expire(reservationId);
        banchileResponds(requestId, pending(requestId));
        ticketReservationManager.markExpiredInPaymentReservationAsStuck(new Date());
        assertEquals(STUCK, reservationStatus(reservationId));
        assertEquals(Transaction.Status.PENDING, banchileTransaction(reservationId).getStatus());
    }

    private static void banchileResponds(int requestId, String body) {
        var querySession = request().withMethod("POST").withPath("/api/session/" + requestId);
        banchile.clear(querySession);
        banchile.when(querySession).respond(json(body));
    }

    private static org.mockserver.model.HttpResponse json(String body) {
        return response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody(body);
    }

    // -------------------------------------------------------------------------
    // Helpers: respuestas de querySession
    // -------------------------------------------------------------------------

    private static String receipt(int requestId) {
        return "6145" + requestId;
    }

    private static String authorization(int requestId) {
        return "%06d".formatted(requestId % 1_000_000);
    }

    /**
     * {@code amount} de cada intento de pago con la forma de la respuesta real de Banchile (rq 34028):
     * el total que alf.io mandó en createSession (priceWithVAT en centavos: 2000 para los 20.00 CHF de
     * la reserva) en {@code from} y en {@code to}, y el factor de conversión.
     */
    private static final String SESSION_AMOUNT =
        "{\"from\": {\"total\": 2000, \"currency\": \"CHF\"}, \"to\": {\"total\": 2000, \"currency\": \"CHF\"}, \"factor\": 1}";

    /** internalReference del pago aprobado (o reembolsado): el ID con que se reembolsa por {@code /api/reverse}. */
    private static int internalReference(int requestId) {
        return 900000 + requestId;
    }

    private static int rejectedAttemptInternalReference(int requestId) {
        return 800000 + requestId;
    }

    private static String approved(int requestId) {
        return session(requestId, "APPROVED", "00", "La petición ha sido aprobada exitosamente",
            payment(requestId, "APPROVED", "00", "Aprobada", false));
    }

    private static String approvedThenRefunded(int requestId) {
        return session(requestId, "APPROVED", "00", "La petición ha sido aprobada exitosamente",
            payment(requestId, "APPROVED", "00", "Aprobada", true));
    }

    /**
     * Sesión que Banchile informa como REFUNDED: el comprador tuvo un intento rechazado antes del
     * pago que después se reembolsó, así que el primer intento de la lista no es el del pago.
     */
    private static String refundedSession(int requestId) {
        return session(requestId, "REFUNDED", "00", "La petición ha sido reembolsada",
            rejectedAttempt(requestId) + ", " + payment(requestId, "REFUNDED", "00", "Reembolsada", true));
    }

    private static String rejected(int requestId) {
        return """
            {"requestId": %d,
             "status": {"status": "REJECTED", "reason": "EX", "message": "La petición ha expirado", "date": "2026-05-26T12:10:00-04:00"},
             "payment": [%s]}
            """.formatted(requestId, rejectedAttempt(requestId));
    }

    private static String rejectedAttempt(int requestId) {
        return """
            {"status": {"status": "REJECTED", "reason": "05", "message": "Rechazada", "date": "2026-05-26T11:52:00-04:00"},
             "internalReference": %d, "paymentMethod": "visa", "paymentMethodName": "Visa",
             "issuerName": "BANCO DE PRUEBA", "amount": %s,
             "authorization": null, "reference": "REF%d", "receipt": null, "franchise": "visa",
             "refunded": false, "processorFields": [{"keyword": "lastDigits", "value": "0016", "displayOn": "none"}]}
            """.formatted(rejectedAttemptInternalReference(requestId), SESSION_AMOUNT, requestId);
    }

    private static String pending(int requestId) {
        return """
            {"requestId": %d,
             "status": {"status": "PENDING", "reason": "PC", "message": "La petición se encuentra activa", "date": "2026-05-25T11:45:00-04:00"}}
            """.formatted(requestId);
    }

    /**
     * Sesión sin estado final en Banchile: PENDING, o APPROVED_PARTIAL (pago parcial, que alf.io no soporta).
     */
    private static String withoutFinalStatus(int requestId, String status) {
        return """
            {"requestId": %d,
             "status": {"status": "%s", "reason": "PC", "message": "La petición se encuentra activa", "date": "2026-05-25T11:45:00-04:00"}}
            """.formatted(requestId, status);
    }

    private static String session(int requestId, String status, String reason, String message, String payment) {
        return """
            {"requestId": %d,
             "status": {"status": "%s", "reason": "%s", "message": "%s", "date": "2026-05-25T11:52:10-04:00"},
             "payment": [%s]}
            """.formatted(requestId, status, reason, message, payment);
    }

    private static String payment(int requestId, String status, String reason, String message, boolean refunded) {
        return """
            {"status": {"status": "%s", "reason": "%s", "message": "%s", "date": "2026-05-25T11:52:10-04:00"},
             "internalReference": %d, "paymentMethod": "visa", "paymentMethodName": "Visa", "issuerName": "BANCO DE PRUEBA",
             "amount": %s, "authorization": "%s", "reference": "REF%d",
             "receipt": "%s", "franchise": "visa", "refunded": %s,
             "processorFields": [{"keyword": "lastDigits", "value": "4242", "displayOn": "none"}]}
            """.formatted(status, reason, message, internalReference(requestId), SESSION_AMOUNT, authorization(requestId), requestId,
                receipt(requestId), refunded);
    }

    // -------------------------------------------------------------------------
    // Helpers: verificaciones
    // -------------------------------------------------------------------------

    private TicketReservationStatus reservationStatus(String reservationId) {
        return ticketReservationRepository.findOptionalStatusAndValidationById(reservationId)
            .map(TicketReservationStatusAndValidation::getStatus)
            .orElseThrow(() -> new AssertionError("la reserva " + reservationId + " no existe"));
    }

    private Transaction banchileTransaction(String reservationId) {
        return transactionRepository.loadOptionalByReservationId(reservationId)
            .orElseThrow(() -> new AssertionError("la reserva " + reservationId + " no tiene transacción"));
    }

    /** Estado y metadata de una transacción, también si está INVALID. */
    private record StoredTransaction(Transaction.Status status, Map<String, String> metadata) {}

    private StoredTransaction transactionByRequestId(int requestId) {
        var row = jdbc.queryForMap("select status, coalesce(metadata::text, '{}') as metadata from b_transaction where gtw_payment_id = :requestId",
            Map.of("requestId", String.valueOf(requestId)));
        Map<String, String> metadata = Json.GSON.fromJson((String) row.get("metadata"), new TypeToken<Map<String, String>>() {}.getType());
        return new StoredTransaction(Transaction.Status.valueOf(((String) row.get("status")).trim()), metadata);
    }

    /** Transacciones de la reserva en cualquier estado, también INVALID. */
    private int transactionsOf(String reservationId) {
        return Objects.requireNonNull(jdbc.queryForObject("select count(*) from b_transaction where reservation_id = :reservationId",
            Map.of("reservationId", reservationId), Integer.class));
    }

    /** Consultas de la sesión a Banchile (querySession) que recibió el Banchile falso. */
    private static int queriesTo(int requestId) {
        return banchile.retrieveRecordedRequests(request().withMethod("POST").withPath("/api/session/" + requestId)).length;
    }

    private void assertTicketsInStatus(String reservationId, Ticket.TicketStatus expected) {
        var tickets = ticketRepository.findTicketsInReservation(reservationId);
        assertEquals(2, tickets.size(), "la reserva debía conservar sus 2 entradas");
        assertTrue(tickets.stream().allMatch(t -> t.getStatus() == expected),
            () -> "se esperaban entradas " + expected + " y están " + tickets.stream().map(Ticket::getStatus).toList());
    }

    private List<Map<String, Object>> emailsFor(Event event) {
        return jdbc.queryForList("select recipient, subject, message from email_message where event_id = :eventId order by id",
            Map.of("eventId", event.getId()));
    }

    private List<Map<String, Object>> emailsTo(Event event, String recipient) {
        return emailsFor(event).stream().filter(m -> recipient.equals(m.get("recipient"))).toList();
    }

    private List<Map<String, Object>> alertsFor(Event event) {
        return emailsFor(event).stream()
            .filter(m -> ((String) m.get("message")).contains(ALERT_SENTENCE))
            .toList();
    }

    private Map<String, Object> singleAlert(Event event) {
        var alerts = alertsFor(event);
        assertEquals(1, alerts.size(), () -> "se esperaba UN correo de alerta y hay " + alerts.size() + ": " + emailsFor(event));
        return alerts.get(0);
    }

    private List<Map<String, Object>> refundedAlertsFor(Event event) {
        return emailsFor(event).stream()
            .filter(m -> ((String) m.get("message")).contains(REFUNDED_NOT_CONFIRMED_ALERT_SENTENCE))
            .toList();
    }

    private Map<String, Object> singleRefundedAlert(Event event) {
        var alerts = refundedAlertsFor(event);
        assertEquals(1, alerts.size(),
            () -> "se esperaba UNA alerta de pago reembolsado y hay " + alerts.size() + ": " + emailsFor(event));
        return alerts.get(0);
    }

    private List<Map<String, Object>> rejectedAlertsFor(Event event) {
        return emailsFor(event).stream()
            .filter(m -> ((String) m.get("message")).contains(REJECTED_ALERT_SENTENCE))
            .toList();
    }

    private Map<String, Object> singleRejectedAlert(Event event) {
        var alerts = rejectedAlertsFor(event);
        assertEquals(1, alerts.size(),
            () -> "se esperaba UNA alerta de reserva confirmada sin pago y hay " + alerts.size() + ": " + emailsFor(event));
        return alerts.get(0);
    }

    private List<Map<String, Object>> invalidSessionAlertsFor(Event event) {
        return emailsFor(event).stream()
            .filter(m -> ((String) m.get("message")).contains(INVALID_SESSION_ALERT_SENTENCE))
            .toList();
    }

    private Map<String, Object> singleInvalidSessionAlert(Event event) {
        var alerts = invalidSessionAlertsFor(event);
        assertEquals(1, alerts.size(),
            () -> "se esperaba UNA alerta de sesión invalidada pagada y hay " + alerts.size() + ": " + emailsFor(event));
        return alerts.get(0);
    }

    private int pendingFinalizationRetries() {
        return Objects.requireNonNull(jdbc.queryForObject("select count(*) from admin_job_queue where job_name = :jobName",
            Map.of("jobName", AdminJobExecutor.JobName.RETRY_RESERVATION_CONFIRMATION.name()), Integer.class));
    }

    /**
     * Junta los eventos de log de una clase mientras está abierto.
     *
     * <p>En los tests SLF4J puede quedar ligado a log4j2, el backend de alf.io, o a
     * java.util.logging, el proveedor que trae el jar de MockServer: con MockServer arrancado antes
     * que el contexto de Spring, los logs de esta clase salen por java.util.logging. Por eso escucha
     * en los dos, con un appender de log4j2 y un handler de java.util.logging en el logger de la
     * clase: cada evento llega por uno solo de los dos.
     */
    static final class LogCapture implements AutoCloseable {

        /** Un evento de log: el nivel (WARN o ERROR) y el mensaje ya formateado. */
        private record Captured(String level, String message) {}

        private final org.apache.logging.log4j.core.Logger log4jLogger;
        private final AbstractAppender appender;
        private final java.util.logging.Logger julLogger;
        private final java.util.logging.Handler handler;
        private final List<Captured> events = new CopyOnWriteArrayList<>();

        LogCapture(Class<?> type) {
            log4jLogger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(type);
            appender = new AbstractAppender("captura-" + type.getSimpleName(), null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    if (event.getLevel() == Level.WARN || event.getLevel() == Level.ERROR) {
                        events.add(new Captured(event.getLevel().name(), event.getMessage().getFormattedMessage()));
                    }
                }
            };
            appender.start();
            log4jLogger.addAppender(appender);
            julLogger = java.util.logging.Logger.getLogger(type.getName());
            handler = new java.util.logging.Handler() {
                @Override
                public void publish(java.util.logging.LogRecord record) {
                    if (record.getLevel() == java.util.logging.Level.WARNING) {
                        events.add(new Captured("WARN", record.getMessage()));
                    } else if (record.getLevel() == java.util.logging.Level.SEVERE) {
                        events.add(new Captured("ERROR", record.getMessage()));
                    }
                }

                @Override
                public void flush() {
                    // nada que vaciar: los eventos quedan en memoria
                }

                @Override
                public void close() {
                    // nada que cerrar
                }
            };
            julLogger.addHandler(handler);
        }

        /**
         * Los WARN capturados que mencionan {@code text}, ya formateados.
         */
        List<String> warningsAbout(String text) {
            return messagesAbout("WARN", text);
        }

        /**
         * Los ERROR capturados que mencionan {@code text}, ya formateados.
         */
        List<String> errorsAbout(String text) {
            return messagesAbout("ERROR", text);
        }

        private List<String> messagesAbout(String level, String text) {
            return events.stream()
                .filter(e -> e.level().equals(level))
                .map(Captured::message)
                .filter(m -> m.contains(text))
                .toList();
        }

        @Override
        public void close() {
            julLogger.removeHandler(handler);
            log4jLogger.removeAppender(appender);
            appender.stop();
        }
    }

    /**
     * Cliente Banchile con un tope corto para que el escenario de timeout no tarde 20 s.
     * Vive en {@code alfio.job}, paquete que ningún {@code @ComponentScan} recorre: solo lo usa este contexto.
     */
    @Configuration(proxyBeanMethods = false)
    static class ShortBanchileTimeoutConfiguration {

        @Bean
        @Primary
        BanchilePagosClient shortTimeoutBanchilePagosClient(HttpClient httpClient) {
            return new BanchilePagosClient(httpClient, BANCHILE_TIMEOUT);
        }
    }
}
