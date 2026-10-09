package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.settings.NameFilterSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameFilterTest {
    private static final List<String> STOCK = List.of("badword", "two words", "çé");

    @Test
    void blocksWholeWordsIgnoringCaseAccentsAndPunctuation() {
        NameFilter filter = NameFilter.of(NameFilterSettings.DEFAULT, STOCK);

        assertTrue(filter.isBlocked("Sir BadWord"));
        assertTrue(filter.isBlocked("Mr. Bádword-Jones"));
        assertTrue(filter.isBlocked("b.a.d.w.o.r.d"));
        assertTrue(filter.isBlocked("The Two  Words Cat"));
        assertTrue(filter.isBlocked("CE"));
    }

    @Test
    void doesNotBlockEntriesInsideLongerWords() {
        NameFilter filter = NameFilter.of(NameFilterSettings.DEFAULT, STOCK);

        assertFalse(filter.isBlocked("Badwordsworth"));
        assertFalse(filter.isBlocked("Two Wordsmiths"));
        assertFalse(filter.isBlocked("Biscuit"));
    }

    @Test
    void customListsAddToAndOverrideTheStockList() {
        NameFilter filter = NameFilter.of(
                new NameFilterSettings(true, true, List.of("Biscuit"), List.of("BADWORD")), STOCK);

        assertTrue(filter.isBlocked("biscuit"));
        assertFalse(filter.isBlocked("Badword"));
        assertTrue(filter.isBlocked("two words"));
    }

    @Test
    void stockListAndFilterCanBeTurnedOff() {
        NameFilter customOnly = NameFilter.of(
                new NameFilterSettings(true, false, List.of("Biscuit"), List.of()), STOCK);
        NameFilter disabled = NameFilter.of(
                new NameFilterSettings(false, true, List.of("Biscuit"), List.of()), STOCK);

        assertFalse(customOnly.isBlocked("Badword"));
        assertTrue(customOnly.isBlocked("Biscuit"));
        assertFalse(disabled.isBlocked("Badword"));
        assertFalse(disabled.isBlocked("Biscuit"));
    }
}
