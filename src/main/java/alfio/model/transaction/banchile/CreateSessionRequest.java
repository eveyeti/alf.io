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
 * DTO para el body de POST /api/session de Banchile Pagos.
 *
 * @param auth            Credenciales firmadas (Banchile auth scheme)
 * @param locale          Locale del checkout, p.ej. "es_CL"
 * @param buyer           Datos opcionales del comprador
 * @param payment         Datos del pago (referencia, descripción, monto)
 * @param returnUrl       URL de retorno tras el pago (browser redirect)
 * @param cancelUrl       URL a la que vuelve el usuario si cancela en pantalla Banchile
 * @param notificationUrl URL del webhook server-to-server. Banchile envía POST con el resultado
 *                        cuando la sesión llega a estado final. Documentado en
 *                        Transacción Completa / Notificaciones — aplica también a WebCheckout.
 * @param ipAddress       IP del comprador
 * @param userAgent       User-Agent del comprador
 * @param expiration      Vencimiento de la sesión en ISO-8601, p.ej. "2026-05-18T12:00:00Z"
 */
public record CreateSessionRequest(
    Auth auth,
    String locale,
    Buyer buyer,
    Payment payment,
    String returnUrl,
    String cancelUrl,
    String notificationUrl,
    String ipAddress,
    String userAgent,
    String expiration
) {

    /**
     * Credenciales firmadas según el esquema PlacetoPay.
     *
     * @param login    Identificador del comercio
     * @param tranKey  Firma Base64(SHA1(nonce || seed || secretTranKey))
     * @param nonce    Nonce aleatorio en Base64
     * @param seed     Timestamp ISO-8601 UTC, p.ej. "2026-05-18T00:00:00Z"
     */
    public record Auth(String login, String tranKey, String nonce, String seed) {}

    /**
     * Datos del comprador (todos opcionales según doc Banchile, pero email y name son habituales).
     *
     * @param name         Nombre
     * @param surname      Apellido
     * @param email        Correo electrónico
     * @param document     Número de documento (p.ej. RUT)
     * @param documentType Tipo de documento, p.ej. "CLRUT"
     * @param mobile       Teléfono móvil, p.ej. "+56900000000"
     */
    public record Buyer(String name, String surname, String email,
                        String document, String documentType, String mobile) {}

    /**
     * Monto del pago.
     *
     * @param currency Código ISO-4217, p.ej. "CLP"
     * @param total    Monto entero (CLP no usa decimales)
     */
    public record Amount(String currency, long total) {}

    /**
     * Información del pago.
     *
     * @param reference   Referencia interna única del comercio
     * @param description Descripción del pago (mínimo 4 caracteres)
     * @param amount      Monto
     */
    public record Payment(String reference, String description, Amount amount) {}
}
