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
import alfio.manager.system.ConfigurationLevel;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.testSupport.MaybeConfigurationBuilder;
import alfio.model.CustomerName;
import alfio.model.Event;
import alfio.model.PurchaseContext;
import alfio.model.TicketReservation;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.banchile.CreateSessionRequest;
import alfio.model.transaction.banchile.CreateSessionRequest.Buyer;
import alfio.model.transaction.banchile.SessionResponse;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.TestUtil;
import alfio.util.ClockProvider;
import alfio.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

import static alfio.model.system.ConfigurationKeys.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Lo que alf.io manda a Banchile en {@code createSession}: el comprador de la reserva, la IP y el
 * navegador reales del comprador, {@code skipResult} y una descripción que se entiende en el portal.
 *
 * <p>Ningún dato del comprador puede hacer fallar la creación de la sesión: si falta o no pasa la
 * validación, se manda lo de siempre (sin comprador, IP {@code 0.0.0.0}, user-agent
 * {@code alfio/banchile}). Los largos máximos son los de la referencia de la API de PlacetoPay
 * (docs.placetopay.dev, createSession): nombre y apellido 60, correo 80, user-agent 255 y
 * descripción 250.
 */
class BanchilePagosWebhookManagerCreateSessionTest {

    private static final String RESERVATION_ID = "4b8a1f2e-0c3d-4e5f-9a6b-7c8d9e0f1a2b";
    private static final String REFERENCE = "4b8a1f2e0c3d4e5f9a6b7c8d9e0f1a2b";
    private static final String SHORT_ID = "4B8A1F2E";
    private static final String BASE_URL = "https://checkout.test.banchilepagos.cl";
    private static final String IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
        + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

    private ConfigurationManager configurationManager;
    private BanchilePagosClient banchilePagosClient;
    private TicketReservationRepository ticketReservationRepository;
    private TransactionRepository transactionRepository;
    private Event event;
    private TicketReservation reservation;
    private BanchilePagosWebhookManager manager;

    @BeforeEach
    void setUp() {
        configurationManager = mock(ConfigurationManager.class);
        banchilePagosClient = mock(BanchilePagosClient.class);
        ticketReservationRepository = mock(TicketReservationRepository.class);
        transactionRepository = mock(TransactionRepository.class);

        event = mock(Event.class);
        when(event.getZoneId()).thenReturn(ZoneId.of("America/Santiago"));
        when(event.getConfigurationLevel()).thenReturn(ConfigurationLevel.organization(1));
        when(event.getCurrency()).thenReturn("CLP");
        when(event.getType()).thenReturn(PurchaseContext.PurchaseContextType.event);
        when(event.getPublicIdentifier()).thenReturn("bingo");
        when(event.getDisplayName()).thenReturn("Bingo Viaggio 2027");
        when(event.now(any(ClockProvider.class))).thenAnswer(inv -> ZonedDateTime.now(ZoneId.of("America/Santiago")));

        reservation = mock(TicketReservation.class);
        when(reservation.getId()).thenReturn(RESERVATION_ID);
        when(reservation.getFirstName()).thenReturn("María José");
        when(reservation.getLastName()).thenReturn("Núñez Peña");
        when(reservation.getEmail()).thenReturn("maria.nunez@example.com");
        when(ticketReservationRepository.findReservationById(RESERVATION_ID)).thenReturn(reservation);

        when(configurationManager.getFor(eq(BanchilePagosWebhookManager.ALL_OPTIONS), any())).thenReturn(Map.of(
            BANCHILE_ENABLED, MaybeConfigurationBuilder.existing(BANCHILE_ENABLED, "true"),
            BANCHILE_LOGIN, MaybeConfigurationBuilder.existing(BANCHILE_LOGIN, "ffb78b93826239e1aa85a515aa961bd9"),
            BANCHILE_TRANKEY, MaybeConfigurationBuilder.existing(BANCHILE_TRANKEY, "U87nG0kcCsjb61Mj"),
            BANCHILE_BASE_URL, MaybeConfigurationBuilder.existing(BANCHILE_BASE_URL, BASE_URL)));
        when(configurationManager.getFor(eq(ConfigurationKeys.BASE_URL), any()))
            .thenReturn(MaybeConfigurationBuilder.existing(ConfigurationKeys.BASE_URL, "https://eventos.viaggio2027.cl"));
        when(configurationManager.getShortReservationID(event, reservation)).thenReturn(SHORT_ID);
        when(banchilePagosClient.createSession(any(), eq(BASE_URL))).thenReturn(new SessionResponse(
            new SessionResponse.Status("OK", "PC", "La petición se ha procesado correctamente", "2026-11-28T18:00:00-03:00"),
            51001, "https://checkout.test.banchilepagos.cl/spa/session/51001/abc", null));

        manager = new BanchilePagosWebhookManager(configurationManager, banchilePagosClient, transactionRepository,
            ticketReservationRepository, TestUtil.clockProvider(), mock(NotificationManager.class), mock(OrganizationRepository.class));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    // -------------------------------------------------------------------------
    // Comprador
    // -------------------------------------------------------------------------

    @Test
    void sendsTheBuyerOfTheReservation() {
        assertEquals(new Buyer("María José", "Núñez Peña", "maria.nunez@example.com", null, null, null), createSession().buyer());
    }

    /**
     * Los nombres llegan en una línea, sin caracteres invisibles, en NFC (una "é" escrita como "e" más
     * la tilde combinada queda como un solo carácter) y cortados en 60 caracteres. El correo, sin
     * espacios en los extremos.
     */
    @Test
    void buyerNamesAreCleanedAndCutToSixtyCharacters() {
        when(reservation.getFirstName()).thenReturn("  José\t Luis​  ");
        when(reservation.getLastName()).thenReturn("Pérez" + " Soto".repeat(20));
        when(reservation.getEmail()).thenReturn("  Maria.Nunez+bingo@mail.example.cl ");

        var buyer = createSession().buyer();

        assertNotNull(buyer);
        assertEquals("José Luis", buyer.name());
        assertEquals(("Pérez" + " Soto".repeat(20)).substring(0, 60).strip(), buyer.surname());
        assertTrue(buyer.surname().length() <= 60, buyer.surname());
        assertEquals("Maria.Nunez+bingo@mail.example.cl", buyer.email());
    }

    @Test
    void namesWithApostrophesHyphensAndDotsAreKept() {
        when(reservation.getFirstName()).thenReturn("Anne-Marie");
        when(reservation.getLastName()).thenReturn("O'Higgins Jr.");

        var buyer = createSession().buyer();

        assertNotNull(buyer);
        assertEquals("Anne-Marie", buyer.name());
        assertEquals("O'Higgins Jr.", buyer.surname());
    }

    /**
     * Sin nombre, apellido o un correo válido la sesión se crea igual, sin comprador, como antes.
     */
    @ParameterizedTest(name = "[{index}] nombre=''{0}'' apellido=''{1}'' correo=''{2}''")
    @CsvSource(value = {
        "NULL, Núñez, maria@example.com",
        "María, NULL, maria@example.com",
        "'   ', Núñez, maria@example.com",
        "María, Núñez, NULL",
        "María, Núñez, '  '",
        "María, Núñez, no-es-un-correo",
        "María, Núñez, maria@example",
        "María, Núñez, maria nunez@example.com",
        "María, Núñez, maría@example.com",
        "María, Núñez, maria@ejemplo.c",
        "María, Núñez, .maria@example.com",
        "María, Núñez, maria..nunez@example.com",
        "María, Núñez, maria@-example.com",
        "R2D2, Núñez, maria@example.com",
        "María ❤, Núñez, maria@example.com",
        "María, <script>, maria@example.com"
    }, nullValues = "NULL")
    void sendsNoBuyerWhenADataIsMissingOrNotValid(String firstName, String lastName, String email) {
        when(reservation.getFirstName()).thenReturn(firstName);
        when(reservation.getLastName()).thenReturn(lastName);
        when(reservation.getEmail()).thenReturn(email);

        var request = createSession();

        assertNull(request.buyer());
        assertEquals(REFERENCE, request.payment().reference(), "la sesión se crea igual");
    }

    @Test
    void sendsNoBuyerWhenTheEmailIsLongerThanEightyCharacters() {
        // 81 caracteres: Banchile acepta hasta 80 y un correo cortado sería otro correo
        String email = "a".repeat(69) + "@example.com";
        assertEquals(81, email.length());
        when(reservation.getEmail()).thenReturn(email);

        assertNull(createSession().buyer());
    }

    @Test
    void acceptsAnEmailOfEightyCharacters() {
        String email = "a".repeat(68) + "@example.com";
        assertEquals(80, email.length());
        when(reservation.getEmail()).thenReturn(email);

        var buyer = createSession().buyer();

        assertNotNull(buyer);
        assertEquals(email, buyer.email());
    }

    // -------------------------------------------------------------------------
    // IP y navegador del comprador
    // -------------------------------------------------------------------------

    @Test
    void sendsTheClientIpAndUserAgentOfTheRequest() {
        onRequestFrom("203.0.113.7", IPHONE_SAFARI);

        var request = createSession();

        assertEquals("203.0.113.7", request.ipAddress());
        assertEquals(IPHONE_SAFARI, request.userAgent());
    }

    /**
     * Jetty, con {@code server.forward-headers-strategy=native}, entrega la IP del
     * {@code X-Forwarded-For} de Caddy; si es IPv6, entre corchetes. A Banchile va sin corchetes.
     */
    @ParameterizedTest
    @CsvSource({
        "[2001:db8::1], 2001:db8::1",
        "2001:db8::1, 2001:db8::1",
        "[::ffff:203.0.113.7], ::ffff:203.0.113.7",
        "[2800:150:11e:1a2b:0:0:0:7], 2800:150:11e:1a2b:0:0:0:7",
        "' 190.98.210.4 ', 190.98.210.4"
    })
    void sendsTheClientIpv6WithoutBrackets(String remoteAddr, String expected) {
        onRequestFrom(remoteAddr, IPHONE_SAFARI);

        assertEquals(expected, createSession().ipAddress());
    }

    /**
     * Solo una IP literal: nada que haya que resolver por DNS ni que no sea una dirección.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "localhost", "evil.example.com", "999.1.1.1", "01.2.3.4", "1.2.3",
        "203.0.113.7, 10.0.0.1", "203.0.113.7:4711", "fe80::1%eth0", "[2001:db8::1", "2001:db8::1::2", "::ffff:999.1.1.1",
        "12345::1", "1:2:3:4:5:6:7:8:9", "g::1", "[]"})
    void sendsTheCurrentIpWhenTheRemoteAddressIsNotAnIpLiteral(String remoteAddr) {
        onRequestFrom(remoteAddr, IPHONE_SAFARI);

        assertEquals("0.0.0.0", createSession().ipAddress());
    }

    @Test
    void sendsTheCurrentIpAndUserAgentWithoutARequest() {
        var request = createSession();

        assertEquals("0.0.0.0", request.ipAddress());
        assertEquals("alfio/banchile", request.userAgent());
    }

    @Test
    void userAgentWithoutControlCharactersAndCutTo255() {
        onRequestFrom("203.0.113.7", "Mozilla/5.0\r\nX-Injected: 1\t(test)\u0000 " + "x".repeat(300));

        var userAgent = createSession().userAgent();

        assertTrue(userAgent.startsWith("Mozilla/5.0 X-Injected: 1 (test) xxx"), userAgent);
        assertEquals(255, userAgent.length());
        assertTrue(userAgent.chars().noneMatch(Character::isISOControl), userAgent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\r\n\t"})
    void sendsTheCurrentUserAgentWhenTheHeaderIsBlank(String userAgent) {
        onRequestFrom("203.0.113.7", userAgent);

        assertEquals("alfio/banchile", createSession().userAgent());
    }

    @Test
    void sendsTheCurrentUserAgentWhenTheHeaderIsMissing() {
        onRequestFrom("203.0.113.7", null);

        assertEquals("alfio/banchile", createSession().userAgent());
    }

    // -------------------------------------------------------------------------
    // skipResult, descripción y referencia
    // -------------------------------------------------------------------------

    /**
     * Con {@code skipResult} Banchile vuelve directo al returnUrl en vez de mostrar su pantalla de
     * resultado (35 de 122 compradores con pago aprobado no volvían al sitio).
     */
    @Test
    void asksBanchileToSkipItsResultPage() {
        var request = createSession();

        assertEquals(Boolean.TRUE, request.skipResult());
        assertTrue(Json.GSON.toJson(request).contains("\"skipResult\":true"), Json.GSON.toJson(request));
    }

    @Test
    void descriptionIsTheEventNameAndTheShortReservationId() {
        when(event.getDisplayName()).thenReturn("Ya no te gusto, ¿verdad?");

        assertEquals("Ya no te gusto, ¿verdad? - reserva " + SHORT_ID, createSession().payment().description());
    }

    /**
     * Banchile admite hasta 250 caracteres alfanuméricos, acentuados y símbolos comunes: el resto
     * (emojis, saltos de línea) no llega, y un nombre largo se corta sin perder el ID de la reserva.
     */
    @Test
    void descriptionKeepsOnlyCommonCharactersAndIsCutTo250() {
        when(event.getDisplayName()).thenReturn("Bingo 🎉\n2026™ " + "muy largo ".repeat(40));

        var description = createSession().payment().description();

        assertTrue(description.startsWith("Bingo 2026 muy largo"), description);
        assertTrue(description.endsWith(" - reserva " + SHORT_ID), description);
        assertTrue(description.length() <= 250, description.length() + ": " + description);
    }

    @Test
    void descriptionWithoutAnEventNameIsTheShortReservationId() {
        when(event.getDisplayName()).thenReturn(" 🎉 ");

        assertEquals("Reserva " + SHORT_ID, createSession().payment().description());
    }

    @Test
    void referenceIsStillTheReservationIdWithoutDashes() {
        assertEquals(REFERENCE, createSession().payment().reference());
    }

    // -------------------------------------------------------------------------
    // Banchile rechaza los datos nuevos: la sesión de siempre
    // -------------------------------------------------------------------------

    /**
     * Si Banchile rechaza la request por inválida (HTTP 400, como "request_not_valid"), alf.io la
     * reintenta una vez como antes: sin comprador, IP 0.0.0.0, user-agent alfio/banchile, la
     * descripción "Reserva <referencia>" y sin skipResult, con credenciales nuevas. Un dato del
     * comprador que Banchile no acepte no puede impedir el pago.
     */
    @Test
    void retriesTheSessionAsBeforeWhenBanchileRejectsTheRequest() {
        onRequestFrom("203.0.113.7", IPHONE_SAFARI);
        when(reservation.getValidity()).thenReturn(minutesFromNow(25));
        when(banchilePagosClient.createSession(any(), eq(BASE_URL)))
            .thenThrow(new BanchilePagosClient.BanchilePagosException("Banchile Pagos devolvió HTTP 400 en createSession: request_not_valid", 400))
            .thenReturn(new SessionResponse(
                new SessionResponse.Status("OK", "PC", "La petición se ha procesado correctamente", "2026-11-28T18:00:00-03:00"),
                51009, "https://checkout.test.banchilepagos.cl/spa/session/51009/abc", null));

        var result = manager.doPayment(paymentSpecification());

        assertTrue(result.isRedirect(), "el comprador va a Banchile igual");
        assertEquals("https://checkout.test.banchilepagos.cl/spa/session/51009/abc", result.getRedirectUrl());
        var captor = ArgumentCaptor.forClass(CreateSessionRequest.class);
        verify(banchilePagosClient, times(2)).createSession(captor.capture(), eq(BASE_URL));
        var first = captor.getAllValues().get(0);
        var retry = captor.getAllValues().get(1);
        assertNotNull(first.buyer());
        assertNull(retry.buyer());
        assertEquals("0.0.0.0", retry.ipAddress());
        assertEquals("alfio/banchile", retry.userAgent());
        assertNull(retry.skipResult());
        assertEquals("Reserva " + REFERENCE, retry.payment().description());
        assertEquals(REFERENCE, retry.payment().reference());
        assertEquals(first.payment().amount(), retry.payment().amount());
        assertEquals(first.expiration(), retry.expiration());
        assertEquals(first.returnUrl(), retry.returnUrl());
        assertEquals(first.cancelUrl(), retry.cancelUrl());
        assertNotEquals(first.auth().nonce(), retry.auth().nonce(), "cada llamada a Banchile lleva credenciales nuevas");
        verify(ticketReservationRepository).updateValidity(eq(RESERVATION_ID), any(Date.class));
        verify(transactionRepository).insert(eq("51009"), eq("51009"), eq(RESERVATION_ID), any(), anyInt(), any(), any(),
            any(), anyLong(), anyLong(), any(), any());
    }

    /**
     * Solo un 400 se reintenta: un 400 no crea la sesión. Con un timeout la sesión puede haberse
     * creado, y un 401 o un 5xx no se arreglan sacando los datos del comprador.
     */
    @ParameterizedTest
    @ValueSource(ints = {401, 403, 500, 503, -1})
    void doesNotRetryOtherErrors(int httpStatus) {
        when(banchilePagosClient.createSession(any(), eq(BASE_URL)))
            .thenThrow(new BanchilePagosClient.BanchilePagosException("Banchile Pagos devolvió HTTP " + httpStatus, httpStatus));

        assertTrue(manager.doPayment(paymentSpecification()).isFailed());

        verify(banchilePagosClient, times(1)).createSession(any(), eq(BASE_URL));
        verify(ticketReservationRepository, never()).updateValidity(anyString(), any(Date.class));
    }

    @Test
    void failsWhenBanchileAlsoRejectsTheRetry() {
        when(banchilePagosClient.createSession(any(), eq(BASE_URL)))
            .thenThrow(new BanchilePagosClient.BanchilePagosException("Banchile Pagos devolvió HTTP 400 en createSession", 400));

        assertTrue(manager.doPayment(paymentSpecification()).isFailed());

        verify(banchilePagosClient, times(2)).createSession(any(), eq(BASE_URL));
        verify(ticketReservationRepository, never()).updateValidity(anyString(), any(Date.class));
        verify(ticketReservationRepository, never()).updateReservationStatus(anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // Validez de la reserva
    // -------------------------------------------------------------------------

    /**
     * La reserva vence 25 minutos después de creada y la sesión, 30 después de apretar "Pagar": sin
     * alargarla, cuando Banchile informa la sesión vencida la reserva ya venció y alf.io la anula. Al
     * crear la sesión, la validez pasa a 15 minutos después de su vencimiento (un ciclo del cron de 12
     * minutos más margen), calculado con el mismo vencimiento que se manda a Banchile.
     */
    @Test
    void extendsTheReservationValidityToFifteenMinutesAfterTheSessionExpiration() {
        when(reservation.getValidity()).thenReturn(minutesFromNow(25));
        var before = Instant.now();

        var request = createSession();

        var after = Instant.now();
        var sessionExpiration = Instant.parse(request.expiration());
        assertFalse(sessionExpiration.isBefore(before.plus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MILLIS)), request.expiration());
        assertFalse(sessionExpiration.isAfter(after.plus(Duration.ofMinutes(30))), request.expiration());
        verify(ticketReservationRepository).updateValidity(RESERVATION_ID, Date.from(sessionExpiration.plus(Duration.ofMinutes(15))));
    }

    /**
     * La validez se alarga, nunca se acorta: max(validez actual, vencimiento de la sesión + 15 min).
     */
    @Test
    void doesNotShortenALongerValidity() {
        when(reservation.getValidity()).thenReturn(minutesFromNow(120));

        createSession();

        verify(ticketReservationRepository, never()).updateValidity(anyString(), any(Date.class));
    }

    @Test
    void doesNotTouchTheValidityWhenBanchileDoesNotCreateTheSession() {
        when(reservation.getValidity()).thenReturn(minutesFromNow(25));
        when(banchilePagosClient.createSession(any(), eq(BASE_URL)))
            .thenThrow(new BanchilePagosClient.BanchilePagosException("Banchile Pagos devolvió HTTP 500 en createSession", 500));

        assertTrue(manager.doPayment(paymentSpecification()).isFailed());

        verify(ticketReservationRepository, never()).updateValidity(anyString(), any(Date.class));
        verify(ticketReservationRepository, never()).updateReservationStatus(anyString(), anyString());
    }

    @Test
    void doesNotTouchTheValidityWhenBanchileAnswersWithoutTheSession() {
        when(reservation.getValidity()).thenReturn(minutesFromNow(25));
        when(banchilePagosClient.createSession(any(), eq(BASE_URL))).thenReturn(new SessionResponse(
            new SessionResponse.Status("FAILED", "XN", "Error", "2026-11-28T18:00:00-03:00"), null, null, null));

        assertTrue(manager.doPayment(paymentSpecification()).isFailed());

        verify(ticketReservationRepository, never()).updateValidity(anyString(), any(Date.class));
    }

    // -------------------------------------------------------------------------
    // Tests del revisor (rama revision-v5-local): matan mutaciones que sobrevivían
    // -------------------------------------------------------------------------

    /**
     * Un emoji justo en el carácter 255 del user-agent no se parte: queda entero o no queda, nunca
     * un sustituto suelto (mata la mutación que corta por unidades UTF-16).
     */
    @Test
    void reviewerUserAgentCutDoesNotSplitAnEmojiAtTheLimit() {
        onRequestFrom("203.0.113.7", "x".repeat(254) + "😀" + "cola");

        var userAgent = createSession().userAgent();

        assertEquals("x".repeat(254) + "😀", userAgent);
        assertEquals(255, userAgent.codePointCount(0, userAgent.length()));
        for (int i = 0; i < userAgent.length(); i++) {
            char c = userAgent.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < userAgent.length() && Character.isLowSurrogate(userAgent.charAt(i + 1)), "sustituto suelto en " + i);
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(c), "sustituto suelto en " + i);
            }
        }
    }

    /**
     * Las IPv6 más largas (8 grupos de 4 dígitos, 39 caracteres; o con IPv4 embebida, 45) también
     * llegan a Banchile (mata la mutación que baja el tope de 45).
     */
    @ParameterizedTest
    @ValueSource(strings = {"2800:0150:011e:1a2b:0000:0000:0000:0007", "ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255"})
    void reviewerSendsTheLongestIpv6(String ip) {
        onRequestFrom("[" + ip + "]", IPHONE_SAFARI);

        assertEquals(ip, createSession().ipAddress());
    }

    /**
     * Un nombre de 71 letras sin espacios se corta en exactamente 60 (el test original corta justo
     * antes de un espacio, así que no distingue 60 de 61).
     */
    @Test
    void reviewerBuyerNameIsCutAtExactlySixtyCharacters() {
        when(reservation.getFirstName()).thenReturn("A" + "a".repeat(70));

        var buyer = createSession().buyer();

        assertNotNull(buyer);
        assertEquals("A" + "a".repeat(59), buyer.name());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void onRequestFrom(String remoteAddr, String userAgent) {
        var request = new MockHttpServletRequest("POST", "/api/v2/public/reservation/" + RESERVATION_ID);
        request.setRemoteAddr(remoteAddr);
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /**
     * {@link BanchilePagosWebhookManager#doPayment} con un Banchile que crea la sesión: devuelve lo
     * que alf.io le mandó.
     */
    private CreateSessionRequest createSession() {
        assertTrue(manager.doPayment(paymentSpecification()).isRedirect(), "doPayment debía redirigir a Banchile");
        var captor = ArgumentCaptor.forClass(CreateSessionRequest.class);
        verify(banchilePagosClient).createSession(captor.capture(), eq(BASE_URL));
        return captor.getValue();
    }

    private PaymentSpecification paymentSpecification() {
        return new PaymentSpecification(RESERVATION_ID, null, 24000, event, "maria.nunez@example.com",
            new CustomerName("María José Núñez Peña", "María José", "Núñez Peña", true));
    }

    private static Date minutesFromNow(int minutes) {
        return Date.from(Instant.now().plus(Duration.ofMinutes(minutes)));
    }
}
