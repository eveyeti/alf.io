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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BanchilePaymentWebhookControllerTest {

    @Test
    void reconstruyeUuidDesdeReferenceValida() {
        String reference = "98ad798f51ab47669c34a3fee0c8a075";
        assertEquals(
            "98ad798f-51ab-4766-9c34-a3fee0c8a075",
            BanchilePaymentWebhookController.reservationIdFromReference(reference)
        );
    }

    @Test
    void aceptaHexEnMayusculasYMinusculas() {
        String reference = "ABCD1234EF567890ABCDEF1234567890";
        assertEquals(
            "ABCD1234-EF56-7890-ABCD-EF1234567890",
            BanchilePaymentWebhookController.reservationIdFromReference(reference)
        );
    }

    @Test
    void devuelveNullSiReferenceEsNull() {
        assertNull(BanchilePaymentWebhookController.reservationIdFromReference(null));
    }

    @Test
    void devuelveNullSiReferenceNoTieneLongitudCorrecta() {
        assertNull(BanchilePaymentWebhookController.reservationIdFromReference(""));
        assertNull(BanchilePaymentWebhookController.reservationIdFromReference("98ad798f51ab"));
        assertNull(BanchilePaymentWebhookController.reservationIdFromReference("98ad798f51ab47669c34a3fee0c8a07500")); // 34 chars
    }

    @Test
    void devuelveNullSiReferenceContieneNonHex() {
        assertNull(BanchilePaymentWebhookController.reservationIdFromReference("98ad798f51ab47669c34a3fee0c8a07X")); // 'X' al final
        assertNull(BanchilePaymentWebhookController.reservationIdFromReference("98ad798f-51ab-4766-9c34-a3fee0c8a075")); // ya tiene guiones
    }
}
