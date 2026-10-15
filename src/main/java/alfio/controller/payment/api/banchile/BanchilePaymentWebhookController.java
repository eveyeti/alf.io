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
package alfio.controller.payment.api.banchile;

import alfio.manager.payment.BanchileWebhookDispatcher;
import alfio.manager.payment.BanchileWebhookDispatcher.Notification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

import static alfio.manager.payment.BanchilePagosWebhookManager.WEBHOOK_URL_TEMPLATE;

/**
 * Endpoint público que recibe los webhooks de Banchile Pagos.
 *
 * <p>Banchile WebCheckout descarta el campo {@code notificationUrl} de la petición
 * createSession — la URL del webhook se registra UNA sola vez en el panel de comercios,
 * sin placeholders dinámicos. Por eso el endpoint es fijo y el reservationId se extrae
 * del campo {@code reference} del body (UUID sin guiones, 32 chars hex).
 *
 * <p>El body se lee crudo, sin los convertidores de mensajes de Spring y sin restringir el
 * Content-Type: en producción el primer convertidor es el de Jackson
 * ({@code MvcConfiguration#configureMessageConverters}), que intentaba convertir el objeto JSON en el
 * {@code String} (o en un {@code byte[]}) del parámetro y respondía 400 sin entrar al método.
 *
 * <p>Política de respuestas: el endpoint SIEMPRE responde 200 OK con un texto corto
 * ({@value #ACCEPTED} si dejó una verificación en cola, {@value #IGNORED} si no), incluso ante bodies
 * malformados, firmas inválidas o reservas no encontradas. Banchile no reintenta webhooks (doc
 * oficial), así que un 4xx/5xx solo perdería la notificación sin beneficio; el polling server-side y
 * el cron reconcilian el estado de cualquier forma. Los motivos se loguean como WARN.
 *
 * <p>El webhook no aplica el estado que trae: con la firma válida, {@link BanchileWebhookDispatcher}
 * dispara en segundo plano la misma verificación que el cron y el poll.
 *
 * <p>No necesita sesión ni token CSRF: {@code /api/payment/webhook/} está excluido de CSRF en
 * {@code AbstractFormBasedWebSecurity#configureCsrf}.
 */
@RestController
@AllArgsConstructor
public class BanchilePaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BanchilePaymentWebhookController.class);

    /** Respuesta cuando la notificación dejó una verificación en cola. */
    static final String ACCEPTED = "accepted";
    /** Respuesta en cualquier otro caso. */
    static final String IGNORED = "ignored";
    /** Tope del body: una notificación de Banchile pesa unos cientos de bytes. */
    static final int MAX_BODY_BYTES = 16 * 1024;
    /** requestId que llega como texto: un entero positivo sin ceros a la izquierda, para que se firme igual. */
    private static final Pattern REQUEST_ID_TEXT = Pattern.compile("[1-9]\\d{0,9}");

    private final BanchileWebhookDispatcher banchileWebhookDispatcher;
    private final ObjectMapper objectMapper;

    /**
     * Endpoint GET para health-check / verificación de la URL en el panel de comercios.
     * Banchile durante onboarding y certificación verifica que la URL existe.
     */
    @GetMapping(WEBHOOK_URL_TEMPLATE)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /**
     * Recibe una notificación de Banchile: lee el body crudo (a lo sumo {@value #MAX_BODY_BYTES}
     * bytes), lo interpreta y se lo pasa a {@link BanchileWebhookDispatcher}, que valida la firma y
     * dispara la verificación en segundo plano.
     *
     * @param request la request HTTP, de la que se lee el body sin pasar por los convertidores
     * @return siempre 200, en texto plano: {@value #ACCEPTED} o {@value #IGNORED}
     */
    @PostMapping(WEBHOOK_URL_TEMPLATE)
    public ResponseEntity<String> receivePaymentConfirmation(HttpServletRequest request) {
        try {
            var notification = readBody(request).flatMap(this::parse);
            if (notification.isEmpty()) {
                return answer(IGNORED);
            }
            var dispatch = banchileWebhookDispatcher.dispatch(notification.get());
            return answer(dispatch.outcome().isAccepted() ? ACCEPTED : IGNORED);
        } catch (IOException e) {
            log.warn("[banchile-webhook] no se pudo leer el body de la notificación ({}): se responde 200 sin hacer nada",
                e.getClass().getSimpleName());
            return answer(IGNORED);
        } catch (RuntimeException e) {
            log.error("[banchile-webhook] error inesperado procesando la notificación: se responde 200 sin hacer nada", e);
            return answer(IGNORED);
        }
    }

    private static ResponseEntity<String> answer(String text) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(text);
    }

    /**
     * Lee el body crudo, con el tope de {@value #MAX_BODY_BYTES} bytes.
     *
     * @return el body, o vacío si supera el tope
     */
    private static Optional<byte[]> readBody(HttpServletRequest request) throws IOException {
        try (var in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                log.warn("[banchile-webhook] notificación con un body de más de {} bytes: se ignora", MAX_BODY_BYTES);
                return Optional.empty();
            }
            return Optional.of(body);
        }
    }

    /**
     * Interpreta la notificación: {@code requestId} numérico, {@code reference} de 32 caracteres hex
     * y {@code status.status} y {@code status.date} presentes. La firma se valida después, en el
     * dispatcher. Los valores del body no van al log: solo el motivo, el requestId y la reserva, una
     * vez validados.
     *
     * @return la notificación, o vacío si no tiene la forma esperada
     */
    private Optional<Notification> parse(byte[] body) {
        if (new String(body, StandardCharsets.UTF_8).isBlank()) {
            log.warn("[banchile-webhook] notificación con el body vacío (¿un ping de verificación?): se ignora");
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            var location = e.getLocation();
            log.warn("[banchile-webhook] el body no es JSON válido ({}{}): se ignora", e.getClass().getSimpleName(),
                location != null ? ", línea " + location.getLineNr() + " columna " + location.getColumnNr() : "");
            return Optional.empty();
        } catch (IOException e) {
            log.warn("[banchile-webhook] no se pudo leer el body como JSON ({}): se ignora", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            log.warn("[banchile-webhook] el body no es un objeto JSON ({} bytes): se ignora", body.length);
            return Optional.empty();
        }
        var requestId = requestId(root.get("requestId"));
        if (requestId.isEmpty()) {
            log.warn("[banchile-webhook] notificación sin un requestId numérico: se ignora");
            return Optional.empty();
        }
        String reservationId = reservationIdFromReference(text(root.get("reference")));
        if (reservationId == null) {
            log.warn("[banchile-webhook] notificación requestId={} sin una reference de 32 caracteres hex: se ignora", requestId.getAsInt());
            return Optional.empty();
        }
        JsonNode status = root.get("status");
        String statusValue = status != null && status.isObject() ? text(status.get("status")) : null;
        String date = status != null && status.isObject() ? text(status.get("date")) : null;
        if (statusValue == null || statusValue.isBlank() || date == null || date.isBlank()) {
            log.warn("[banchile-webhook] notificación requestId={} de la reserva {} sin status.status o status.date: se ignora",
                requestId.getAsInt(), reservationId);
            return Optional.empty();
        }
        return Optional.of(new Notification(requestId.getAsInt(), reservationId, statusValue, date, text(root.get("signature"))));
    }

    /**
     * El requestId como entero positivo: un número entero de JSON, o un texto con solo dígitos.
     */
    private static OptionalInt requestId(JsonNode node) {
        if (node == null) {
            return OptionalInt.empty();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToInt() && node.intValue() > 0 ? OptionalInt.of(node.intValue()) : OptionalInt.empty();
        }
        if (node.isTextual() && REQUEST_ID_TEXT.matcher(node.asText()).matches()) {
            long value = Long.parseLong(node.asText());
            return value <= Integer.MAX_VALUE ? OptionalInt.of((int) value) : OptionalInt.empty();
        }
        return OptionalInt.empty();
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /**
     * Reconstruye el UUID con guiones a partir del campo {@code reference} del webhook.
     * Banchile rechaza UUIDs con guiones en {@code payment.reference}, así que el fork
     * envía {@code reservationId.replace("-", "")} (32 chars hex). Aquí hacemos la
     * operación inversa.
     *
     * @return UUID con formato 8-4-4-4-12, o {@code null} si el reference no es válido
     */
    static String reservationIdFromReference(String reference) {
        if (reference == null || reference.length() != 32 || !reference.matches("[0-9a-fA-F]{32}")) {
            return null;
        }
        return reference.substring(0, 8) + "-"
            + reference.substring(8, 12) + "-"
            + reference.substring(12, 16) + "-"
            + reference.substring(16, 20) + "-"
            + reference.substring(20, 32);
    }
}
