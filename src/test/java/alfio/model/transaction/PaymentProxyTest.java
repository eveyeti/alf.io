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
package alfio.model.transaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaymentProxyTest {

    @Test
    void banchileValueOfDoesNotThrow() {
        assertDoesNotThrow(() -> PaymentProxy.valueOf("BANCHILE"));
    }

    @Test
    void banchileIsVisible() {
        // BANCHILE should appear in availableProxies() (i.e. visible=true)
        assertTrue(
            PaymentProxy.availableProxies().contains(PaymentProxy.BANCHILE),
            "BANCHILE should be listed in availableProxies()"
        );
    }

    @Test
    void banchilePaymentMethodIsCreditCard() {
        assertEquals(PaymentMethod.CREDIT_CARD, PaymentProxy.BANCHILE.getPaymentMethod());
    }

    @Test
    void banchileSafeValueOf() {
        var result = PaymentProxy.safeValueOf("BANCHILE");
        assertTrue(result.isPresent(), "safeValueOf(\"BANCHILE\") should return a non-empty Optional");
        assertEquals(PaymentProxy.BANCHILE, result.get());
    }
}
