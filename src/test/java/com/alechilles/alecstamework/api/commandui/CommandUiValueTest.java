package com.alechilles.alecstamework.api.commandui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests immutable command UI value behavior visible to API consumers. */
class CommandUiValueTest {
    @Test
    void valueObjectCopiesMutableInput() {
        Map<String, CommandUiValue> source = new LinkedHashMap<>();
        source.put("ready", CommandUiValue.of(true));

        CommandUiValue value = CommandUiValue.object(source);
        source.clear();

        assertEquals(true, value.objectValue().get("ready").booleanValue());
    }

    @Test
    void valueListCopiesMutableInput() {
        List<CommandUiValue> source = new ArrayList<>();
        source.add(CommandUiValue.of(12L));

        CommandUiValue value = CommandUiValue.list(source);
        source.clear();

        assertEquals(List.of(CommandUiValue.of(12L)), value.listValue());
    }

    @Test
    void valueRejectsNullAndNonFiniteNumbers() {
        List<CommandUiValue> nullList = new ArrayList<>();
        nullList.add(null);
        Map<String, CommandUiValue> nullObject = new LinkedHashMap<>();
        nullObject.put("missing", null);

        assertThrows(NullPointerException.class, () -> CommandUiValue.of((String) null));
        assertThrows(NullPointerException.class, () -> CommandUiValue.list(nullList));
        assertThrows(NullPointerException.class, () -> CommandUiValue.object(nullObject));
        assertThrows(IllegalArgumentException.class, () -> CommandUiValue.of(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CommandUiValue.of(Double.POSITIVE_INFINITY));
    }

    @Test
    void valueRejectsBlankObjectKeys() {
        assertThrows(IllegalArgumentException.class, () -> CommandUiValue.object(
                Map.of("  ", CommandUiValue.of(true))));
    }
}
