package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.settings.NameFilterSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameFilterTest {
    private static final List<String> WHOLE = List.of("badword", "two words", "çé", "boob");
    private static final List<String> SEVERE = List.of("slurr");

    @Test
    void blocksWholeWordsIgnoringCaseAccentsAndPunctuation() {
        NameFilter filter = NameFilter.of(NameFilterSettings.DEFAULT, WHOLE, SEVERE);

        assertTrue(filter.isBlocked("Sir BadWord"));
        assertTrue(filter.isBlocked("Mr. Bádword-Jones"));
        assertTrue(filter.isBlocked("The Two  Words Cat"));
        assertTrue(filter.isBlocked("CE"));
    }

    @Test
    void doesNotBlockWholeWordEntriesInsideLongerWords() {
        NameFilter filter = NameFilter.of(NameFilterSettings.DEFAULT, WHOLE, SEVERE);

        assertFalse(filter.isBlocked("Badwordsworth"));
        assertFalse(filter.isBlocked("Two Wordsmiths"));
        assertFalse(filter.isBlocked("Biscuit"));
    }

    @Test
    void seesThroughLookalikesSpelledOutLettersAndStretchedLetters() {
        NameFilter filter = NameFilter.of(NameFilterSettings.DEFAULT, WHOLE, SEVERE);

        assertTrue(filter.isBlocked("B4dw0rd"));
        assertTrue(filter.isBlocked("b.a.d.w.o.r.d"));
        assertTrue(filter.isBlocked("b a d w o r d face"));
        assertTrue(filter.isBlocked("Baaadwooord"));
        assertTrue(filter.isBlocked("Boooob"));
    }

    @Test
    void stretchedMatchingKeepsAnEntrysOwnDoubleLetters() {
        NameFilter filter = NameFilter.of(NameFilterSettings.DEFAULT, WHOLE, SEVERE);

        assertFalse(filter.isBlocked("Bob"));
        assertFalse(filter.isBlocked("Bobb"));
        assertFalse(filter.isBlocked("Slur"));
    }

    @Test
    void severeEntriesMatchAnywhereInsideAWord() {
        NameFilter filter = NameFilter.of(NameFilterSettings.DEFAULT, WHOLE, SEVERE);

        assertTrue(filter.isBlocked("xXSlurrFaceXx"));
        assertTrue(filter.isBlocked("Big5lurrrr"));
        assertFalse(filter.isBlocked("Slu Rr"));
    }

    @Test
    void customListsAddToAndOverrideTheStockLists() {
        NameFilter filter = NameFilter.of(
                new NameFilterSettings(true, true, List.of("Biscuit", "*gravy*"),
                        List.of("BADWORD", "Slurrington")),
                WHOLE, SEVERE);

        assertTrue(filter.isBlocked("biscuit"));
        assertFalse(filter.isBlocked("Biscuits"));
        assertTrue(filter.isBlocked("Megagravyboat"));
        assertFalse(filter.isBlocked("Badword"));
        assertFalse(filter.isBlocked("Sir Slurrington"));
        assertTrue(filter.isBlocked("Slurrville"));
    }

    @Test
    void stockListAndFilterCanBeTurnedOff() {
        NameFilter customOnly = NameFilter.of(
                new NameFilterSettings(true, false, List.of("Biscuit"), List.of()), WHOLE, SEVERE);
        NameFilter disabled = NameFilter.of(
                new NameFilterSettings(false, true, List.of("Biscuit"), List.of()), WHOLE, SEVERE);

        assertFalse(customOnly.isBlocked("Badword"));
        assertFalse(customOnly.isBlocked("Slurrface"));
        assertTrue(customOnly.isBlocked("Biscuit"));
        assertFalse(disabled.isBlocked("Badword"));
        assertFalse(disabled.isBlocked("Biscuit"));
    }
}
