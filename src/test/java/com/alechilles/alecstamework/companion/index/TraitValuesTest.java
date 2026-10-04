package com.alechilles.alecstamework.companion.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraitValuesTest {
    @Test
    void keepsTheSourceOrderAndIgnoresLaterChangesToTheSource() {
        Map<String, Double> source = new LinkedHashMap<>();
        source.put("Swift", 2.0);
        source.put("Brave", 1.0);
        source.put("Hardy", 3.0);

        Map<String, Double> traits = TraitValues.copyOf(source);
        source.put("Late", 4.0);
        source.remove("Swift");

        // Owner files list traits in this order; a reordered copy would rewrite every file.
        assertEquals(List.of("Swift", "Brave", "Hardy"), List.copyOf(traits.keySet()));
        assertEquals(List.of(2.0, 1.0, 3.0), List.copyOf(traits.values()));
    }
}
