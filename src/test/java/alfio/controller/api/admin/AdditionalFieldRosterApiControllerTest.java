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
package alfio.controller.api.admin;

import alfio.manager.AccessService;
import alfio.manager.system.ConfigurationLevel;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.testSupport.MaybeConfigurationBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.security.Principal;

import static alfio.model.system.ConfigurationKeys.ADDITIONAL_FIELD_ROSTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdditionalFieldRosterApiControllerTest {

    private static final int ORG_ID = 42;

    private ConfigurationManager configurationManager;
    private AdditionalFieldRosterApiController controller;
    private Principal principal;

    @BeforeEach
    void init() {
        configurationManager = mock(ConfigurationManager.class);
        AccessService accessService = mock(AccessService.class);
        principal = mock(Principal.class);
        controller = new AdditionalFieldRosterApiController(configurationManager, accessService);
    }

    @Test
    void returnsEmptyListWhenRosterNotConfigured() {
        when(configurationManager.getFor(eq(ADDITIONAL_FIELD_ROSTER), any(ConfigurationLevel.class)))
            .thenReturn(MaybeConfigurationBuilder.missing(ADDITIONAL_FIELD_ROSTER));

        var response = controller.getRoster(ORG_ID, principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void returnsParsedRosterWhenConfigured() {
        when(configurationManager.getFor(eq(ADDITIONAL_FIELD_ROSTER), any(ConfigurationLevel.class)))
            .thenReturn(MaybeConfigurationBuilder.existing(ADDITIONAL_FIELD_ROSTER, "[{\"value\":\"abc\",\"label\":\"4°B — Pérez, Juan\"}]"));

        var response = controller.getRoster(ORG_ID, principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("abc", response.getBody().get(0).value());
    }

    @Test
    void returnsBadRequestWhenRosterIsMalformed() {
        when(configurationManager.getFor(eq(ADDITIONAL_FIELD_ROSTER), any(ConfigurationLevel.class)))
            .thenReturn(MaybeConfigurationBuilder.existing(ADDITIONAL_FIELD_ROSTER, "{no es json valido"));

        var response = controller.getRoster(ORG_ID, principal);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
