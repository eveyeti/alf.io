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
package alfio.model.system;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationKeysTest {

    private static final List<String> BANCHILE_KEY_NAMES = List.of(
        "BANCHILE_ENABLED",
        "BANCHILE_LOGIN",
        "BANCHILE_TRANKEY",
        "BANCHILE_WEBHOOK_SECRET",
        "BANCHILE_BASE_URL"
    );

    @Test
    void banchileKeysCanBeLookedUpByName() {
        for (String keyName : BANCHILE_KEY_NAMES) {
            var key = ConfigurationKeys.safeValueOf(keyName);
            assertNotEquals(ConfigurationKeys.NOT_RECOGNIZED, key,
                keyName + " should be a recognized ConfigurationKeys entry");
        }
    }

    @Test
    void banchileKeysCategoryIsPaymentBanchile() {
        for (String keyName : BANCHILE_KEY_NAMES) {
            var key = ConfigurationKeys.safeValueOf(keyName);
            assertEquals(ConfigurationKeys.SettingCategory.PAYMENT_BANCHILE, key.getCategory(),
                keyName + " should belong to PAYMENT_BANCHILE category");
        }
    }

    @Test
    void banchileSecretKeysUseTextComponentType() {
        // BANCHILE_TRANKEY and BANCHILE_WEBHOOK_SECRET must use TEXT component type
        // (same pattern as MOLLIE_API_KEY, STRIPE_SECRET_KEY — no PASSWORD type exists)
        var tranKey = ConfigurationKeys.safeValueOf("BANCHILE_TRANKEY");
        var webhookSecret = ConfigurationKeys.safeValueOf("BANCHILE_WEBHOOK_SECRET");
        assertEquals(ComponentType.TEXT, tranKey.getComponentType(),
            "BANCHILE_TRANKEY should use ComponentType.TEXT");
        assertEquals(ComponentType.TEXT, webhookSecret.getComponentType(),
            "BANCHILE_WEBHOOK_SECRET should use ComponentType.TEXT");
    }

    @Test
    void validateAllBooleansHaveDefaultValue() {
        var pattern = Pattern.compile(".*?\\(.*?default.*?(true|false).*?\\).*?");
        Arrays.stream(ConfigurationKeys.values())
            .filter(ConfigurationKeys::isBooleanComponentType)
            .forEach(confKey -> {
                assertNotNull(confKey.getDefaultValue(), confKey.name());
                assertTrue(confKey.getDefaultValue().equals("true") || confKey.getDefaultValue().equals("false"), confKey.name() + " default value must be either 'true' or 'false'");
                var matcher = pattern.matcher(confKey.getDescription());
                if(matcher.matches()) {
                    assertEquals(matcher.group(1), confKey.getDefaultValue(), confKey.name() + ": description says \"default "+matcher.group(1)+"\", but default value is "+ confKey.getDefaultValue());
                }
            });
    }

}