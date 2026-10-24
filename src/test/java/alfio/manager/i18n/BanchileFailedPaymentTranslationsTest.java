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
 * Los textos del aviso del resumen tras un pago Banchile fallido, tal como los recibe el navegador
 * ({@code GET /api/v2/public/i18n/bundle/{lang}}): pasan por {@code MessageFormat}, así que un
 * apóstrofo sin duplicar o una llave suelta los rompen. Los idiomas del sitio son español, inglés e
 * italiano (los eventos tienen {@code locales} = 259).
 */
class BanchileFailedPaymentTranslationsTest {

    private static final String PREFIX = "reservation-page.payment.banchile.failed-attempt.";

    /** El message source de la aplicación ({@code DataSourceConfiguration#messageSourceManager}). */
    private static MessageSourceManager messageSourceManager() {
        var source = new CustomResourceBundleMessageSource();
        source.setBasenames("alfio.i18n.public", "alfio.i18n.admin");
        source.setDefaultEncoding(StandardCharsets.UTF_8.displayName());
        source.setFallbackToSystemLocale(false);
        source.setAlwaysUseMessageFormat(true);
        return new MessageSourceManager(source, null);
    }

    private static Map<String, String> bundle(String lang) {
        return messageSourceManager().getBundleAsMap("alfio.i18n.public", false, lang, MessageSourceManager.PUBLIC_FRONTEND);
    }

    @Test
    void spanishWithTuteoAndWithoutTechnicalCodes() {
        var bundle = bundle("es");
        assertEquals("Tu banco no aprobó el pago y no se hizo ningún cargo. Tu reserva sigue guardada: puedes intentarlo de nuevo, "
            + "con la misma tarjeta o con otra.", bundle.get(PREFIX + "rejected"));
        assertEquals("Cancelaste el pago y no se hizo ningún cargo. Tu reserva sigue guardada: puedes volver a pagar antes de que venza.",
            bundle.get(PREFIX + "cancelled"));
        assertEquals("Se acabó el tiempo para pagar en Banchile y no se hizo ningún cargo. Tu reserva sigue guardada: puedes intentarlo de nuevo.",
            bundle.get(PREFIX + "expired"));
        assertEquals("Detalle del banco: {{0}}", bundle.get(PREFIX + "bank-detail"));
        assertEquals("El pago quedó incompleto y se acabó el tiempo para terminarlo. Tu reserva sigue guardada: puedes intentarlo de nuevo.",
            bundle.get(PREFIX + "partial-expired"));
    }

    @Test
    void english() {
        var bundle = bundle("en");
        assertEquals("Your bank did not approve the payment and you have not been charged. Your reservation is still on hold: "
            + "you can try again, with the same card or a different one.", bundle.get(PREFIX + "rejected"));
        assertEquals("You cancelled the payment and you have not been charged. Your reservation is still on hold: "
            + "you can pay again before it expires.", bundle.get(PREFIX + "cancelled"));
        assertEquals("The time to pay on Banchile ran out and you have not been charged. Your reservation is still on hold: "
            + "you can try again.", bundle.get(PREFIX + "expired"));
        assertEquals("Message from the bank: {{0}}", bundle.get(PREFIX + "bank-detail"));
        assertEquals("The payment was left incomplete and the time to finish it ran out. Your reservation is still on hold: "
            + "you can try again.", bundle.get(PREFIX + "partial-expired"));
    }

    @Test
    void italianKeepsItsApostrophes() {
        var bundle = bundle("it");
        assertEquals("La tua banca non ha approvato il pagamento e non ti è stato addebitato nulla. La tua prenotazione è ancora valida: "
            + "puoi riprovare, con la stessa carta o con un'altra.", bundle.get(PREFIX + "rejected"));
        assertEquals("Hai annullato il pagamento e non ti è stato addebitato nulla. La tua prenotazione è ancora valida: "
            + "puoi pagare di nuovo prima che scada.", bundle.get(PREFIX + "cancelled"));
        assertEquals("Il tempo per pagare su Banchile è scaduto e non ti è stato addebitato nulla. La tua prenotazione è ancora valida: "
            + "puoi riprovare.", bundle.get(PREFIX + "expired"));
        assertEquals("Messaggio della banca: {{0}}", bundle.get(PREFIX + "bank-detail"));
        assertEquals("Il pagamento è rimasto incompleto ed è scaduto il tempo per completarlo. La tua prenotazione è ancora valida: "
            + "puoi riprovare.", bundle.get(PREFIX + "partial-expired"));
    }

    @Test
    void theBankDetailIsFormattedWithTheBankMessage() {
        var messageSource = messageSourceManager().getRootMessageSource(false);
        assertEquals("Detalle del banco: Fondos insuficientes", messageSource.getMessage(PREFIX + "bank-detail",
            new Object[]{"Fondos insuficientes"}, java.util.Locale.forLanguageTag("es")));
    }
}
