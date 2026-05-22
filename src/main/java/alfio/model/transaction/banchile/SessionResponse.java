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

import java.util.List;

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
 * @param payment    Lista de intentos de pago. Banchile retorna ARRAY (no objeto único)
 *                   porque una sesión puede tener múltiples intentos (retries tras rechazo).
 *                   Para una sesión APPROVED, normalmente hay 1 elemento con status APPROVED.
 *                   En createSession suele venir null.
 */
public record SessionResponse(
    Status status,
    Integer requestId,
    String processUrl,
    List<PaymentDetails> payment
) {

    /**
     * Helper: el primer pago APPROVED de la lista, o null si no hay ninguno.
     */
    public PaymentDetails firstApprovedPayment() {
        if (payment == null) return null;
        return payment.stream()
            .filter(p -> p.status() != null && "APPROVED".equals(p.status().status()))
            .findFirst()
            .orElse(null);
    }

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
     * Detalles del pago (presente en respuestas con status APPROVED y también en algunos rechazos).
     *
     * <p>Banchile devuelve mucho más que reference/amount/receipt/status. Todos los campos
     * opcionales — cada uno puede venir {@code null} dependiendo del medio de pago, banco
     * emisor y estado del intento.
     *
     * @param reference         Referencia interna del comercio
     * @param amount            Monto aprobado
     * @param receipt           Número de recibo / voucher
     * @param status            Estado del pago
     * @param internalReference ID interno de Banchile usado para reembolsos (`/api/reverse`)
     * @param paymentMethod     Código del medio de pago (e.g., "VS", "MC", "DC")
     * @param paymentMethodName Nombre legible del medio de pago (e.g., "Visa Credito")
     * @param issuerName        Banco emisor de la tarjeta
     * @param authorization     Código CUS / código de autorización del banco
     * @param franchise         Franquicia de la marca (e.g., "VISA", "AMEX")
     * @param refunded          true si el intento fue revertido posteriormente
     * @param processorFields   Campos adicionales del procesador (lastDigits, BIN, etc.) — list of {keyword, value, displayOn}
     */
    public record PaymentDetails(
        String reference,
        CreateSessionRequest.Amount amount,
        String receipt,
        Status status,
        Integer internalReference,
        String paymentMethod,
        String paymentMethodName,
        String issuerName,
        String authorization,
        String franchise,
        Boolean refunded,
        java.util.List<ProcessorField> processorFields
    ) {
        /**
         * Constructor de compatibilidad para tests existentes (4 campos).
         * Todos los campos extendidos quedan null.
         */
        public PaymentDetails(String reference,
                              CreateSessionRequest.Amount amount,
                              String receipt,
                              Status status) {
            this(reference, amount, receipt, status,
                null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Item del array {@code processorFields[]} dentro de un {@link PaymentDetails}.
     *
     * <p>Banchile incluye aquí metadata operacional (lastDigits, bin, merchantCode, etc.).
     * El campo {@code value} puede ser string u objeto/array según el caso —
     * deserializado como {@link Object} para tolerar variabilidad.
     *
     * @param keyword   Identificador del campo (e.g., "lastDigits", "bin")
     * @param value     Valor (string o estructura)
     * @param displayOn Donde mostrarlo (e.g., "receipt", "approval")
     */
    public record ProcessorField(String keyword, Object value, String displayOn) {}
}
