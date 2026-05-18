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
package alfio.model.transaction.banchile;

/**
 * DTO para la respuesta de Banchile Pagos en createSession y querySession.
 *
 * <p>Estados posibles de {@code status.status}:
 * <ul>
 *   <li>{@code OK}              — Sesión creada exitosamente</li>
 *   <li>{@code PENDING}         — Pago pendiente</li>
 *   <li>{@code APPROVED}        — Pago aprobado</li>
 *   <li>{@code REJECTED}        — Pago rechazado</li>
 *   <li>{@code APPROVED_PARTIAL}  — Aprobación parcial</li>
 *   <li>{@code PARTIAL_EXPIRED}   — Expiración parcial</li>
 * </ul>
 *
 * @param status     Estado de la operación
 * @param requestId  ID numérico de la sesión (solo presente en createSession)
 * @param processUrl URL del checkout para redirigir al comprador (solo en createSession)
 * @param payment    Detalles del pago (presente cuando status = APPROVED)
 */
public record SessionResponse(
    Status status,
    Integer requestId,
    String processUrl,
    PaymentDetails payment
) {

    /**
     * Estado de la respuesta de Banchile.
     *
     * @param status  Código de estado, p.ej. "OK", "APPROVED", "REJECTED"
     * @param reason  Razón del estado (puede ser código numérico o string como "PC")
     * @param message Mensaje descriptivo
     * @param date    Fecha de la respuesta en ISO-8601
     */
    public record Status(String status, String reason, String message, String date) {}

    /**
     * Detalles del pago (presente en respuestas con status APPROVED).
     *
     * @param reference Referencia interna del comercio
     * @param amount    Monto aprobado
     * @param receipt   Número de recibo / voucher
     * @param status    Estado del pago
     */
    public record PaymentDetails(
        String reference,
        CreateSessionRequest.Amount amount,
        String receipt,
        Status status
    ) {}
}
