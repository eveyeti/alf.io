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

import alfio.manager.PurchaseContextManager;
import alfio.manager.TicketReservationManager;
import alfio.model.transaction.PaymentContext;
import alfio.model.transaction.PaymentProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static alfio.manager.payment.BanchilePagosWebhookManager.WEBHOOK_URL_TEMPLATE;

/**
 * Endpoint público que recibe los webhooks de Banchile Pagos.
 *
 * <p>Banchile WebCheckout descarta el campo {@code notificationUrl} de la petición
 * createSession — la URL del webhook se registra UNA sola vez en el panel de comercios,
 * sin placeholders dinámicos. Por eso el endpoint es fijo y el reservationId se extrae
 * del campo {@code reference} del body (UUID sin guiones, 32 chars hex).
 *
 * <p>Política de respuestas: el endpoint SIEMPRE responde 200 OK, incluso ante bodies
 * malformados o reservas no encontradas. Banchile no reintenta webhooks (doc oficial),
 * así que un 4xx/5xx solo perdería la notificación sin beneficio. El polling
 * server-side reconcilia el estado de cualquier forma. Los errores se loguean como warn.
 */
@RestController
@AllArgsConstructor
public class BanchilePaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BanchilePaymentWebhookController.class);

    private final TicketReservationManager ticketReservationManager;
    private final PurchaseContextManager purchaseContextManager;
    private final ObjectMapper objectMapper;

    /**
     * Endpoint GET para health-check / verificación de la URL en el panel de comercios.
     * Banchile durante onboarding y certificación verifica que la URL existe.
     */
    @GetMapping(WEBHOOK_URL_TEMPLATE)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    @PostMapping(WEBHOOK_URL_TEMPLATE)
    public ResponseEntity<String> receivePaymentConfirmation(@RequestBody(required = false) String body) {
        if (body == null || body.isBlank()) {
            log.warn("Webhook Banchile con body vacío — respondiendo 200 (probable ping de verificación)");
            return ResponseEntity.ok("empty body ignored");
        }

        String reservationId;
        try {
            JsonNode root = objectMapper.readTree(body);
            String reference = root.path("reference").asText(null);
            reservationId = reservationIdFromReference(reference);
        } catch (Exception e) {
            log.warn("Webhook Banchile con body malformado: {}", e.getMessage());
            return ResponseEntity.ok("malformed body ignored");
        }

        if (reservationId == null) {
            log.warn("Webhook Banchile sin reference válida en el body");
            return ResponseEntity.ok("missing reference ignored");
        }

        final String resId = reservationId;
        return purchaseContextManager.findByReservationId(resId)
            .map(purchaseContext -> {
                var result = ticketReservationManager.processTransactionWebhook(
                    body,
                    null,
                    PaymentProxy.BANCHILE,
                    Map.of("reservationId", resId),
                    new PaymentContext(purchaseContext, resId)
                );
                if (result.isError()) {
                    log.warn("Webhook Banchile error procesando reserva {}: {}", resId, result.getReason());
                    return ResponseEntity.ok("processing error: " + result.getReason());
                }
                return ResponseEntity.ok(result.isSuccessful() ? "OK" : result.getReason());
            })
            .orElseGet(() -> {
                log.warn("Webhook Banchile para reservation desconocida: {}", resId);
                return ResponseEntity.ok("reservation not found ignored");
            });
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
