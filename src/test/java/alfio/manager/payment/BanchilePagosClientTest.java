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

import alfio.model.transaction.banchile.CreateSessionRequest;
import alfio.model.transaction.banchile.CreateSessionRequest.Auth;
import alfio.model.transaction.banchile.CreateSessionRequest.Amount;
import alfio.model.transaction.banchile.CreateSessionRequest.Payment;
import alfio.model.transaction.banchile.CreateSessionRequest.Buyer;
import alfio.model.transaction.banchile.SessionResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.JsonBody;
import org.mockserver.model.MediaType;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios e integración HTTP del cliente Banchile Pagos.
 *
 * <p>Usa MockServer (ya disponible en el classpath del proyecto como
 * {@code org.mock-server:mockserver-netty-no-dependencies}) para simular
 * el servidor de Banchile sin hacer llamadas reales.
 */
class BanchilePagosClientTest {

    private static ClientAndServer mockServer;
    private static int mockPort;

    private BanchilePagosClient client;

    // Sandbox creds públicos (de la documentación de Banchile/PlacetoPay)
    private static final String LOGIN    = "ffb78b93826239e1aa85a515aa961bd9";
    private static final String TRAN_KEY = "U87nG0kcCsjb61Mj";

    @BeforeAll
    static void startMockServer() {
        mockServer = ClientAndServer.startClientAndServer(0); // puerto aleatorio
        mockPort = mockServer.getPort();
    }

    @AfterAll
    static void stopMockServer() {
        mockServer.stop();
    }

    @BeforeEach
    void setUp() {
        mockServer.reset(); // limpiar expectativas entre tests
        client = new BanchilePagosClient(HttpClient.newHttpClient());
    }

    // -------------------------------------------------------------------------
    // Test 1 — buildAuth con vector conocido (determinístico)
    // -------------------------------------------------------------------------

    /**
     * Verifica que buildAuth produce la firma SHA-1 correcta para un vector conocido.
     *
     * <p>Valor esperado calculado con:
     * <pre>
     *   python3 -c "import base64,hashlib;
     *     print(base64.b64encode(hashlib.sha1(
     *       bytes(range(16))
     *       + b'2026-05-18T00:00:00Z'
     *       + b'U87nG0kcCsjb61Mj').digest()).decode())"
     *   → al9lqLDZ1eZWOLEdBKhASz9d5zE=
     * </pre>
     */
    @Test
    void buildAuthProducesExpectedSignatureForKnownVector() {
        byte[] nonce = new byte[16];
        for (int i = 0; i < 16; i++) {
            nonce[i] = (byte) i;
        }
        String seed = "2026-05-18T00:00:00Z";

        Auth auth = BanchilePagosClient.buildAuth(LOGIN, TRAN_KEY, nonce, seed);

        assertEquals("al9lqLDZ1eZWOLEdBKhASz9d5zE=", auth.tranKey(),
            "tranKey firmado debe coincidir con SHA1(nonce||seed||tranKey) en Base64");
        assertEquals("AAECAwQFBgcICQoLDA0ODw==", auth.nonce(),
            "nonce debe ser Base64 de bytes [0..15]");
        assertEquals(seed, auth.seed());
        assertEquals(LOGIN, auth.login());
    }

    // -------------------------------------------------------------------------
    // Test 2 — createSession devuelve SessionResponse con requestId
    // -------------------------------------------------------------------------

    @Test
    void createSessionParsesSuccessResponse() {
        mockServer
            .when(
                HttpRequest.request()
                    .withMethod("POST")
                    .withPath("/api/session")
            )
            .respond(
                HttpResponse.response()
                    .withStatusCode(200)
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody("""
                        {
                          "status": {
                            "status": "OK",
                            "reason": "00",
                            "message": "La petición se ha procesado correctamente.",
                            "date": "2026-05-18T00:00:00-04:00"
                          },
                          "requestId": 123,
                          "processUrl": "https://checkout.test.banchilepagos.cl/spa/session/123"
                        }
                        """)
            );

        Auth auth = BanchilePagosClient.buildAuth(LOGIN, TRAN_KEY);
        CreateSessionRequest req = new CreateSessionRequest(
            auth,
            "es_CL",
            new Buyer("Test", "Smoke", "smoke@viaggio2027-dev.local", "11111111-1", "CLRUT", "+56900000000"),
            new Payment("REF-001", "Entrada evento test", new Amount("CLP", 10000L)),
            "https://example.com/return",
            "127.0.0.1",
            "BanchileTestClient/1.0",
            "2026-05-18T02:00:00Z"
        );

        String baseUrl = "http://localhost:" + mockPort;
        SessionResponse response = client.createSession(req, baseUrl);

        assertNotNull(response, "SessionResponse no debe ser null");
        assertEquals(123, response.requestId(), "requestId debe ser 123");
        assertEquals("https://checkout.test.banchilepagos.cl/spa/session/123", response.processUrl());
        assertNotNull(response.status(), "status no debe ser null");
        assertEquals("OK", response.status().status());
    }

    // -------------------------------------------------------------------------
    // Test 3 — createSession con respuesta 400 lanza BanchilePagosException
    // -------------------------------------------------------------------------

    @Test
    void createSessionThrowsExceptionOn400() {
        mockServer
            .when(
                HttpRequest.request()
                    .withMethod("POST")
                    .withPath("/api/session")
            )
            .respond(
                HttpResponse.response()
                    .withStatusCode(400)
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody("""
                        {
                          "status": {
                            "status": "FAILED",
                            "reason": "request_not_valid",
                            "message": "El campo description debe tener al menos 4 caracteres.",
                            "date": "2026-05-18T00:00:00-04:00"
                          }
                        }
                        """)
            );

        Auth auth = BanchilePagosClient.buildAuth(LOGIN, TRAN_KEY);
        CreateSessionRequest req = new CreateSessionRequest(
            auth,
            "es_CL",
            new Buyer("Test", "Smoke", "smoke@viaggio2027-dev.local", null, null, null),
            new Payment("REF-002", "ab", new Amount("CLP", 5000L)), // description < 4 chars
            "https://example.com/return",
            "127.0.0.1",
            "BanchileTestClient/1.0",
            "2026-05-18T02:00:00Z"
        );

        String baseUrl = "http://localhost:" + mockPort;

        BanchilePagosClient.BanchilePagosException ex = assertThrows(
            BanchilePagosClient.BanchilePagosException.class,
            () -> client.createSession(req, baseUrl),
            "Debe lanzar BanchilePagosException para HTTP 400"
        );

        assertEquals(400, ex.getHttpStatus(), "httpStatus debe ser 400");
        assertTrue(ex.getMessage().contains("400"),
            "El mensaje de la excepción debe mencionar el código HTTP");
    }

    // -------------------------------------------------------------------------
    // Test 4 — querySession parsea respuesta APPROVED
    // -------------------------------------------------------------------------

    @Test
    void querySessionParsesApprovedResponse() {
        int requestId = 456;

        mockServer
            .when(
                HttpRequest.request()
                    .withMethod("POST")
                    .withPath("/api/session/" + requestId)
            )
            .respond(
                HttpResponse.response()
                    .withStatusCode(200)
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody("""
                        {
                          "requestId": 456,
                          "status": {
                            "status": "APPROVED",
                            "reason": "00",
                            "message": "Aprobada",
                            "date": "2026-05-18T01:00:00-04:00"
                          },
                          "payment": {
                            "reference": "REF-003",
                            "amount": { "currency": "CLP", "total": 10000 },
                            "receipt": "1234567890",
                            "status": {
                              "status": "APPROVED",
                              "reason": "00",
                              "message": "Aprobada",
                              "date": "2026-05-18T01:00:00-04:00"
                            }
                          }
                        }
                        """)
            );

        Auth auth = BanchilePagosClient.buildAuth(LOGIN, TRAN_KEY);
        String baseUrl = "http://localhost:" + mockPort;

        SessionResponse response = client.querySession(requestId, auth, baseUrl);

        assertNotNull(response, "SessionResponse no debe ser null");
        assertNotNull(response.status(), "status no debe ser null");
        assertEquals("APPROVED", response.status().status(),
            "status.status debe ser APPROVED");
        assertNotNull(response.payment(), "payment debe estar presente en respuesta APPROVED");
        assertEquals("REF-003", response.payment().reference());
    }
}
