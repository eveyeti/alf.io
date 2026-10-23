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
package alfio.controller.api.v2.model;

import alfio.controller.api.v2.model.ReservationInfo.PaymentDetailsInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Los detalles del pago Banchile que ve el comprador salen de {@code b_transaction.metadata}. El
 * aviso del resumen tras un pago fallido depende del estado de la sesión, y el código de
 * autorización solo se muestra si Banchile aprobó el pago: tras un rechazo de tarjeta, Banchile
 * manda {@code authorization: "000000"} en el intento rechazado, y ese valor no es una autorización.
 */
class PaymentDetailsInfoTest {

    @Test
    void aRejectedCardKeepsTheStatusAndTheBankMessageButNotTheAuthorization() {
        // el rechazo de tarjeta que vio el QA en el sandbox (15-oct): el intento trae "000000"
        var details = PaymentDetailsInfo.fromMetadata(metadata(
            "banchile_status", "REJECTED",
            "banchile_status_reason", "05",
            "banchile_status_message", "Fondos insuficientes",
            "banchile_authorization", "000000",
            "banchile_payment_method_name", "Visa",
            "banchile_pf_lastDigits", "0016"));

        assertNotNull(details);
        assertEquals("REJECTED", details.getStatus());
        assertEquals("05", details.getStatusReason());
        assertEquals("Fondos insuficientes", details.getStatusMessage());
        assertNull(details.getAuthorization(), "un intento rechazado no tiene código de autorización");
    }

    /**
     * QA del 15-oct en el sandbox: el banco rechazó la tarjeta y el comprador volvió al comercio, así
     * que la sesión se cerró con ?C. El último intento va aparte, para que el resumen muestre el
     * rechazo del banco y no "cancelaste"; su "000000" tampoco se expone.
     */
    @Test
    void theLastPaymentAttemptIsExposedWithoutItsAuthorization() {
        var details = PaymentDetailsInfo.fromMetadata(metadata(
            "banchile_status", "REJECTED",
            "banchile_status_reason", "?C",
            "banchile_status_message", "La petición ha sido cancelada por el usuario",
            "banchile_attempt_status", "REJECTED",
            "banchile_attempt_reason", "?2",
            "banchile_attempt_message", "Transacción declinada por políticas de control de riesgo",
            "banchile_authorization", "000000"));

        assertNotNull(details);
        assertEquals("?C", details.getStatusReason());
        assertEquals("REJECTED", details.getAttemptStatus());
        assertEquals("?2", details.getAttemptReason());
        assertEquals("Transacción declinada por políticas de control de riesgo", details.getAttemptMessage());
        assertNull(details.getAuthorization());
    }

    @Test
    void withoutPaymentAttemptsTheAttemptIsEmpty() {
        var details = PaymentDetailsInfo.fromMetadata(metadata(
            "banchile_status", "REJECTED",
            "banchile_status_reason", "EX",
            "banchile_status_message", "La petición ha expirado"));

        assertNotNull(details);
        assertNull(details.getAttemptStatus());
        assertNull(details.getAttemptReason());
        assertNull(details.getAttemptMessage());
    }

    @Test
    void aSessionCancelledByTheBuyerKeepsItsStatusAndReason() {
        var details = PaymentDetailsInfo.fromMetadata(metadata(
            "banchile_status", "REJECTED",
            "banchile_status_reason", "?C",
            "banchile_status_message", "La petición ha sido cancelada por el usuario"));

        assertNotNull(details);
        assertEquals("REJECTED", details.getStatus());
        assertEquals("?C", details.getStatusReason());
        assertNull(details.getAuthorization());
    }

    @Test
    void anApprovedPaymentKeepsItsAuthorization() {
        var details = PaymentDetailsInfo.fromMetadata(metadata(
            "banchile_status", "APPROVED",
            "banchile_status_reason", "00",
            "banchile_status_message", "La petición ha sido aprobada exitosamente",
            "banchile_authorization", "685559",
            "banchile_receipt", "614515011049"));

        assertNotNull(details);
        assertEquals("APPROVED", details.getStatus());
        assertEquals("685559", details.getAuthorization());
        assertEquals("614515011049", details.getReceipt());
    }

    @Test
    void anApprovedAndThenRefundedPaymentKeepsTheAuthorizationOfTheApproval() {
        var details = PaymentDetailsInfo.fromMetadata(metadata(
            "banchile_status", "REFUNDED",
            "banchile_authorization", "004393",
            "banchile_refunded", "true"));

        assertNotNull(details);
        assertEquals("REFUNDED", details.getStatus());
        assertEquals("004393", details.getAuthorization());
    }

    @Test
    void anExpiredPartialPaymentIsNotAnAuthorization() {
        var details = PaymentDetailsInfo.fromMetadata(metadata(
            "banchile_status", "PARTIAL_EXPIRED",
            "banchile_authorization", "123456"));

        assertNotNull(details);
        assertEquals("PARTIAL_EXPIRED", details.getStatus());
        assertNull(details.getAuthorization());
    }

    @Test
    void withoutTheSessionStatusTheAuthorizationIsNotShown() {
        var details = PaymentDetailsInfo.fromMetadata(metadata("banchile_authorization", "000000"));

        assertNotNull(details);
        assertNull(details.getStatus());
        assertNull(details.getAuthorization(), "sin el estado no se sabe si el pago se aprobó");
    }

    @Test
    void withoutBanchileMetadataThereAreNoDetails() {
        assertNull(PaymentDetailsInfo.fromMetadata(null));
        assertNull(PaymentDetailsInfo.fromMetadata(Map.of()));
        assertNull(PaymentDetailsInfo.fromMetadata(Map.of("stripe_charge", "ch_1")));
    }

    private static Map<String, String> metadata(String... keysAndValues) {
        var metadata = new HashMap<String, String>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            metadata.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return metadata;
    }
}
