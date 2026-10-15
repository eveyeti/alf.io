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
import alfio.manager.TicketReservationManager;
import alfio.manager.system.ConfigurationManager;
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
import org.mockserver.model.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static alfio.model.TicketReservation.TicketReservationStatus.EXTERNAL_PROCESSING_PAYMENT;
import static alfio.model.TicketReservation.TicketReservationStatus.PENDING;
import static alfio.model.system.ConfigurationKeys.*;
import static alfio.test.util.IntegrationTestUtil.AVAILABLE_SEATS;
import static alfio.test.util.IntegrationTestUtil.initEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La sesión que alf.io crea en Banchile cuando el comprador aprieta "Pagar", contra un Banchile
 * falso local (MockServer).
 *
 * <p>El comprador entra por el controller ({@code POST /api/v2/public/reservation/{id}}, con
 * MockMvc): así se prueba que {@code doPayment} corre en el hilo del request y saca de ahí la IP y
 * el navegador del comprador.
 */
@AlfioIntegrationTest
@ContextConfiguration(classes = {
    DataSourceConfiguration.class,
    TestConfiguration.class,
    ControllerConfiguration.class,
    BanchileReconciliationIntegrationTest.ShortBanchileTimeoutConfiguration.class
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
