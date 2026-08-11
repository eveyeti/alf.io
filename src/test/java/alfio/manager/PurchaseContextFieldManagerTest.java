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
package alfio.manager;

import alfio.manager.i18n.MessageSourceManager;
import alfio.model.modification.EventModification;
import alfio.repository.AdditionalServiceRepository;
import alfio.repository.PurchaseContextFieldRepository;
import alfio.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Regression test for the bug where the "select:searchable" field type (searchable dropdown,
 * used to render a roster of "alumnos") silently dropped its restrictedValues on save, because
 * {@link alfio.model.api.v1.admin.AdditionalInfoRequest#WITH_RESTRICTED_VALUES} did not include
 * its code.
 */
class PurchaseContextFieldManagerTest {

    private static final List<String> RESTRICTED_VALUES = List.of("Juan Perez", "Maria Lopez");

    private PurchaseContextFieldRepository purchaseContextFieldRepository;
    private PurchaseContextFieldManager purchaseContextFieldManager;

    @BeforeEach
    void init() {
        purchaseContextFieldRepository = mock(PurchaseContextFieldRepository.class);
        purchaseContextFieldManager = new PurchaseContextFieldManager(
            purchaseContextFieldRepository,
            mock(AdditionalServiceRepository.class),
            mock(MessageSourceManager.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"select", "select:searchable", "checkbox", "radio"})
    void typesWithOptionsPersistRestrictedValues(String type) {
        purchaseContextFieldManager.updateAdditionalField(1L, updateRequestFor(type, false), 1);

        ArgumentCaptor<String> restrictedValuesCaptor = ArgumentCaptor.forClass(String.class);
        verify(purchaseContextFieldRepository)
            .updateField(eq(1L), eq(true), eq(true), restrictedValuesCaptor.capture(), any(), any(), eq(false));

        assertEquals(Json.GSON.toJson(RESTRICTED_VALUES), restrictedValuesCaptor.getValue(),
            () -> "restrictedValues must be persisted for type '" + type + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"input:text", "input:tel", "textarea", "country", "vat:eu", "input:dateOfBirth"})
    void freeTextTypesDoNotPersistRestrictedValues(String type) {
        purchaseContextFieldManager.updateAdditionalField(1L, updateRequestFor(type, false), 1);

        ArgumentCaptor<String> restrictedValuesCaptor = ArgumentCaptor.forClass(String.class);
        verify(purchaseContextFieldRepository)
            .updateField(eq(1L), eq(true), eq(true), restrictedValuesCaptor.capture(), any(), any(), eq(false));

        assertNull(restrictedValuesCaptor.getValue(),
            () -> "restrictedValues must NOT be persisted for type '" + type + "'");
    }

    /**
     * Regression test for the bug where editing an existing field never persisted
     * askOnlyFirstTicket: the checkbox rendered the real value in edit mode (so it
     * looked "live"), but FieldRepository#updateField's SQL never wrote the column,
     * so toggling it and saving was a silent no-op. It only worked when creating a
     * new field (insertConfiguration already bound it).
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void editingFieldPersistsAskOnlyFirstTicket(boolean askOnlyFirstTicket) {
        purchaseContextFieldManager.updateAdditionalField(1L, updateRequestFor("input:text", askOnlyFirstTicket), 1);

        verify(purchaseContextFieldRepository)
            .updateField(eq(1L), eq(true), eq(true), any(), any(), any(), eq(askOnlyFirstTicket));
    }

    private static EventModification.UpdateAdditionalField updateRequestFor(String type, boolean askOnlyFirstTicket) {
        return new EventModification.UpdateAdditionalField(
            type,
            true,
            false,
            RESTRICTED_VALUES,
            List.of(),
            Map.of(),
            List.of(),
            askOnlyFirstTicket);
    }
}
