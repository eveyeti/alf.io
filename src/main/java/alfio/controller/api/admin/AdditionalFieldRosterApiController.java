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
import alfio.util.Json;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

import static alfio.model.system.ConfigurationKeys.ADDITIONAL_FIELD_ROSTER;

@RestController
@RequestMapping("/admin/api")
public class AdditionalFieldRosterApiController {

    private static final Logger log = LoggerFactory.getLogger(AdditionalFieldRosterApiController.class);

    public record RosterEntry(String value, String label) {}

    private final ConfigurationManager configurationManager;
    private final AccessService accessService;

    public AdditionalFieldRosterApiController(ConfigurationManager configurationManager, AccessService accessService) {
        this.configurationManager = configurationManager;
        this.accessService = accessService;
    }

    @GetMapping("/organization/{orgId}/additional-field-roster")
    public ResponseEntity<List<RosterEntry>> getRoster(@PathVariable int orgId, Principal principal) {
        accessService.checkOrganizationOwnership(principal, orgId);
        var raw = configurationManager
            .getFor(ADDITIONAL_FIELD_ROSTER, ConfigurationLevel.organization(orgId))
            .getValueOrNull();
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        try {
            List<RosterEntry> roster = Json.GSON.fromJson(raw, new TypeToken<List<RosterEntry>>(){}.getType());
            return ResponseEntity.ok(roster == null ? List.of() : roster);
        } catch (Exception e) {
            log.warn("ADDITIONAL_FIELD_ROSTER de la organización {} no es un JSON válido: {}", orgId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}
