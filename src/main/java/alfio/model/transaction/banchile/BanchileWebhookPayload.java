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
 * DTO para los webhooks entrantes de Banchile Pagos.
 *
 * <p>Banchile notifica cambios de estado de sesión a la URL de webhook configurada.
 *
 * @param requestId ID de la sesión de pago
 * @param reference Referencia interna del comercio
 * @param status    Estado actual del pago
 * @param signature Firma del webhook para validar autenticidad (algoritmo por confirmar con doc)
 */
public record BanchileWebhookPayload(
    Integer requestId,
    String reference,
    SessionResponse.Status status,
    String signature
) {}
