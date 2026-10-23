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
package alfio.manager.i18n;

import alfio.util.CustomResourceBundleMessageSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La explicación de "procesando pago" cuando el resultado del pago con Banchile no llega, tal como la
 * recibe el navegador ({@code GET /api/v2/public/i18n/bundle/{lang}}), en los idiomas del sitio.
 * Los textos pasan por {@code MessageFormat}: el italiano duplica su apóstrofo.
 */
class BanchilePaymentInProgressTranslationsTest {

    private static final String PREFIX = "reservation.payment-processing.banchile.";

    private static Map<String, String> bundle(String lang) {
        var source = new CustomResourceBundleMessageSource();
        source.setBasenames("alfio.i18n.public", "alfio.i18n.admin");
        source.setDefaultEncoding(StandardCharsets.UTF_8.displayName());
        source.setFallbackToSystemLocale(false);
        source.setAlwaysUseMessageFormat(true);
        return new MessageSourceManager(source, null).getBundleAsMap("alfio.i18n.public", false, lang, MessageSourceManager.PUBLIC_FRONTEND);
    }

    @Test
    void spanish() {
        var bundle = bundle("es");
        assertEquals("Todavía no tenemos la respuesta de Banchile Pagos.", bundle.get(PREFIX + "title"));
        assertEquals("Si todavía estás pagando en Banchile, termina ahí: esta página se actualiza sola cuando llegue la respuesta.",
            bundle.get(PREFIX + "still-paying"));
        assertEquals("Si no vas a pagar, puedes cerrar esta página: tu reserva queda guardada y, cuando venza la sesión de pago, "
            + "te enviaremos un correo para que vuelvas a intentar el pago.", bundle.get(PREFIX + "not-paying"));
    }

    @Test
    void english() {
        var bundle = bundle("en");
        assertEquals("We have not received the answer from Banchile Pagos yet.", bundle.get(PREFIX + "title"));
        assertEquals("If you are still paying on Banchile, finish there: this page updates by itself when the answer arrives.",
            bundle.get(PREFIX + "still-paying"));
        assertEquals("If you are not going to pay, you can close this page: your reservation stays on hold and, when the payment "
            + "session expires, we will send you an email so you can try to pay again.", bundle.get(PREFIX + "not-paying"));
    }

    @Test
    void italian() {
        var bundle = bundle("it");
        assertEquals("Non abbiamo ancora ricevuto la risposta di Banchile Pagos.", bundle.get(PREFIX + "title"));
        assertEquals("Se stai ancora pagando su Banchile, completa il pagamento lì: questa pagina si aggiorna da sola quando arriva la risposta.",
            bundle.get(PREFIX + "still-paying"));
        assertEquals("Se non intendi pagare, puoi chiudere questa pagina: la tua prenotazione resta valida e, quando scade la sessione "
            + "di pagamento, ti invieremo un'email per riprovare a pagare.", bundle.get(PREFIX + "not-paying"));
    }
}
