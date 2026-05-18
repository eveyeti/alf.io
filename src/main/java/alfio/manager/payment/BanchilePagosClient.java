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
import alfio.model.transaction.banchile.SessionResponse;
import alfio.util.HttpUtils;
import alfio.util.Json;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Cliente HTTP para la API de Banchile Pagos (PlacetoPay gateway).
 *
 * <p>Implementa el esquema de autenticación PlacetoPay:
 * <pre>
 *   tranKey_sig = Base64( SHA1( nonce_bytes ‖ seed.getBytes(UTF8) ‖ tranKey.getBytes(UTF8) ) )
 *   nonce_b64   = Base64( nonce_bytes )
 * </pre>
 *
 * <p>Endpoints soportados:
 * <ul>
 *   <li>POST /api/session         — createSession</li>
 *   <li>POST /api/session/{id}    — querySession</li>
 *   <li>POST /api/session/{id}/cancelation — cancelSession</li>
 * </ul>
 */
@Component
public class BanchilePagosClient {

    private static final Logger log = LoggerFactory.getLogger(BanchilePagosClient.class);

    private static final String SESSION_PATH = "/api/session";
    private static final int NONCE_BYTES = 16;

    private final HttpClient httpClient;

    public BanchilePagosClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // -------------------------------------------------------------------------
    // Auth
    // -------------------------------------------------------------------------

    /**
     * Construye el objeto {@link Auth} con el tranKey firmado a partir de vectores determinísticos.
     *
     * <p>Este método estático es el núcleo testeable de la firma: no genera aleatoriedad propia,
     * recibe {@code nonceBytes} y {@code seed} explícitamente.
     *
     * @param login      Identificador del comercio
     * @param tranKey    Secret tranKey del comercio (en texto plano)
     * @param nonceBytes 16 bytes aleatorios para el nonce
     * @param seed       Timestamp ISO-8601 UTC, p.ej. "2026-05-18T00:00:00Z"
     * @return Auth con tranKey firmado y nonce en Base64
     */
    public static Auth buildAuth(String login, String tranKey, byte[] nonceBytes, String seed) {
        try {
            // SHA-1 es requerido por el protocolo PlacetoPay (Banchile Pagos auth scheme).
            // No es una elección nuestra — el servidor rechaza cualquier otro algoritmo.
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1"); // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
            sha1.update(nonceBytes);
            sha1.update(seed.getBytes(UTF_8));
            sha1.update(tranKey.getBytes(UTF_8));
            byte[] digest = sha1.digest();

            String tranKeySigned = Base64.getEncoder().encodeToString(digest);
            String nonceb64 = Base64.getEncoder().encodeToString(nonceBytes);

            return new Auth(login, tranKeySigned, nonceb64, seed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 está garantizado en todo JDK — esto no ocurrirá en producción
            throw new IllegalStateException("SHA-1 no disponible en este JDK", e);
        }
    }

    /**
     * Genera el objeto {@link Auth} con nonce aleatorio y seed en el instante actual (UTC).
     *
     * @param login    Identificador del comercio
     * @param tranKey  Secret tranKey del comercio (en texto plano)
     * @return Auth con tranKey firmado y nonce en Base64
     */
    public static Auth buildAuth(String login, String tranKey) {
        byte[] nonceBytes = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonceBytes);
        String seed = Instant.now().toString(); // ISO-8601 UTC con sufijo "Z"
        return buildAuth(login, tranKey, nonceBytes, seed);
    }

    // -------------------------------------------------------------------------
    // HTTP operations
    // -------------------------------------------------------------------------

    /**
     * Crea una sesión de pago en Banchile Pagos.
     *
     * @param req     Payload completo de la request (auth ya incluido)
     * @param baseUrl URL base del ambiente, p.ej. "https://checkout.test.banchilepagos.cl"
     * @return SessionResponse con requestId y processUrl
     * @throws BanchilePagosException si el servidor responde con código de error
     */
    public SessionResponse createSession(CreateSessionRequest req, String baseUrl) {
        String url = baseUrl + SESSION_PATH;
        String body = Json.GSON.toJson(req);
        log.debug("createSession → POST {} | reference={}", url,
            req.payment() != null ? req.payment().reference() : "null");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header(HttpUtils.CONTENT_TYPE, HttpUtils.APPLICATION_JSON)
            .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
            .build();

        return execute(request, "createSession");
    }

    /**
     * Consulta el estado de una sesión de pago existente.
     *
     * @param requestId ID de la sesión devuelto por createSession
     * @param auth      Credenciales firmadas frescas (cada llamada requiere auth nueva)
     * @param baseUrl   URL base del ambiente
     * @return SessionResponse con el estado actualizado del pago
     * @throws BanchilePagosException si el servidor responde con código de error
     */
    public SessionResponse querySession(int requestId, Auth auth, String baseUrl) {
        String url = baseUrl + SESSION_PATH + "/" + requestId;
        String body = Json.GSON.toJson(Map.of("auth", auth));
        log.debug("querySession → POST {} | requestId={}", url, requestId);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header(HttpUtils.CONTENT_TYPE, HttpUtils.APPLICATION_JSON)
            .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
            .build();

        return execute(request, "querySession");
    }

    /**
     * Cancela una sesión de pago activa.
     *
     * <p>TODO: confirmar URL exacta contra documentación oficial de Banchile.
     * La URL {@code /api/session/{id}/cancelation} es la esperada según spec,
     * pero no ha sido validada contra el sandbox.
     *
     * @param requestId ID de la sesión a cancelar
     * @param auth      Credenciales firmadas frescas
     * @param baseUrl   URL base del ambiente
     * @return SessionResponse con el resultado de la cancelación
     * @throws BanchilePagosException si el servidor responde con código de error
     */
    public SessionResponse cancelSession(int requestId, Auth auth, String baseUrl) {
        String url = baseUrl + SESSION_PATH + "/" + requestId + "/cancelation";
        // TODO: confirmar si se requiere locale en el body (body actual: {auth, locale:"es_CL"})
        String body = Json.GSON.toJson(Map.of("auth", auth, "locale", "es_CL"));
        log.debug("cancelSession → POST {} | requestId={}", url, requestId);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header(HttpUtils.CONTENT_TYPE, HttpUtils.APPLICATION_JSON)
            .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
            .build();

        return execute(request, "cancelSession");
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Ejecuta una request HTTP y parsea la respuesta como {@link SessionResponse}.
     * Lanza {@link BanchilePagosException} para respuestas no-2xx.
     *
     * @param request    Request HTTP pre-construida
     * @param operation  Nombre de la operación para logging
     * @return SessionResponse deserializado desde el JSON de respuesta
     */
    private SessionResponse execute(HttpRequest request, String operation) {
        try {
            HttpResponse<java.io.InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (HttpUtils.callSuccessful(response)) {
                try (var reader = new InputStreamReader(response.body(), UTF_8)) {
                    SessionResponse parsed = Json.GSON.fromJson(reader, SessionResponse.class);
                    log.debug("{} ← HTTP {} | status={}", operation, response.statusCode(),
                        parsed.status() != null ? parsed.status().status() : "null");
                    return parsed;
                }
            } else {
                // Intentar leer el cuerpo de error para el mensaje
                String errorBody = readErrorBody(response);
                log.warn("{} ← HTTP {} error | body={}", operation, response.statusCode(), errorBody);
                throw new BanchilePagosException(
                    "Banchile Pagos devolvió HTTP " + response.statusCode()
                        + " en " + operation + ": " + errorBody,
                    response.statusCode()
                );
            }
        } catch (BanchilePagosException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BanchilePagosException("Request interrumpida en " + operation, e);
        } catch (IOException e) {
            throw new BanchilePagosException("Error de I/O en " + operation, e);
        }
    }

    /**
     * Lee el cuerpo de una respuesta de error como String, extrayendo el mensaje de Banchile
     * si el JSON tiene el campo {@code status.message}.
     *
     * @param response Respuesta HTTP con error
     * @return Mensaje de error legible
     */
    private String readErrorBody(HttpResponse<java.io.InputStream> response) {
        try (var reader = new InputStreamReader(response.body(), UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("status")) {
                JsonObject status = json.getAsJsonObject("status");
                if (status.has("message")) {
                    return status.get("message").getAsString();
                }
            }
            return json.toString();
        } catch (Exception e) {
            log.trace("No se pudo leer el cuerpo del error", e);
            return "(no se pudo leer el body)";
        }
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /**
     * Excepción lanzada cuando Banchile Pagos devuelve un código de error HTTP (4xx / 5xx)
     * o cuando ocurre un error de comunicación.
     */
    public static class BanchilePagosException extends RuntimeException {

        private final int httpStatus;

        /**
         * Constructor para errores HTTP con código de estado.
         *
         * @param message    Mensaje descriptivo
         * @param httpStatus Código HTTP (p.ej. 400, 500)
         */
        public BanchilePagosException(String message, int httpStatus) {
            super(message);
            this.httpStatus = httpStatus;
        }

        /**
         * Constructor para errores de comunicación (sin código HTTP).
         *
         * @param message Mensaje descriptivo
         * @param cause   Causa raíz (IOException, InterruptedException, etc.)
         */
        public BanchilePagosException(String message, Throwable cause) {
            super(message, cause);
            this.httpStatus = -1;
        }

        /**
         * Retorna el código HTTP de la respuesta de error, o -1 si fue un error de comunicación.
         *
         * @return Código HTTP
         */
        public int getHttpStatus() {
            return httpStatus;
        }
    }
}
