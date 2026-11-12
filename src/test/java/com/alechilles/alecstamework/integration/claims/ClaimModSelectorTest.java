package com.alechilles.alecstamework.integration.claims;

import com.alechilles.alecstamework.settings.ClaimModChoice;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which claim mod the claim integration reads for each choice and set of installed mods. */
class ClaimModSelectorTest {
    private static final Predicate<ClaimModChoice> NONE = mod -> false;
    private static final Predicate<ClaimModChoice> SIMPLE_CLAIMS_ONLY = Set.of(ClaimModChoice.SIMPLE_CLAIMS)::contains;
    private static final Predicate<ClaimModChoice> QUESTLINES_ONLY = Set.of(ClaimModChoice.QUESTLINES_CLAIMS)::contains;
    private static final Predicate<ClaimModChoice> BOTH =
            Set.of(ClaimModChoice.SIMPLE_CLAIMS, ClaimModChoice.QUESTLINES_CLAIMS)::contains;

    @Test
    void automaticPrefersSimpleClaimsThenQuestLines() {
        assertEquals(ClaimModChoice.SIMPLE_CLAIMS, ClaimModSelector.select(ClaimModChoice.AUTOMATIC, SIMPLE_CLAIMS_ONLY));
        assertEquals(ClaimModChoice.SIMPLE_CLAIMS, ClaimModSelector.select(ClaimModChoice.AUTOMATIC, BOTH));
        assertEquals(ClaimModChoice.QUESTLINES_CLAIMS, ClaimModSelector.select(ClaimModChoice.AUTOMATIC, QUESTLINES_ONLY));
    }

    /** With neither mod, the SimpleClaims path reports "not installed" exactly as before the choice existed. */
    @Test
    void automaticWithNoClaimModFallsBackToTheSimpleClaimsUnavailablePath() {
        assertEquals(ClaimModChoice.SIMPLE_CLAIMS, ClaimModSelector.select(ClaimModChoice.AUTOMATIC, NONE));
        assertEquals(ClaimModChoice.SIMPLE_CLAIMS, ClaimModSelector.select(null, NONE));
    }

    /** An explicit choice is kept even when that mod is absent, so its checks report it unavailable. */
    @Test
    void explicitChoiceIsKeptWhetherOrNotThatModIsInstalled() {
        assertEquals(ClaimModChoice.QUESTLINES_CLAIMS, ClaimModSelector.select(ClaimModChoice.QUESTLINES_CLAIMS, BOTH));
        assertEquals(ClaimModChoice.QUESTLINES_CLAIMS,
                ClaimModSelector.select(ClaimModChoice.QUESTLINES_CLAIMS, SIMPLE_CLAIMS_ONLY));
        assertEquals(ClaimModChoice.SIMPLE_CLAIMS, ClaimModSelector.select(ClaimModChoice.SIMPLE_CLAIMS, QUESTLINES_ONLY));
    }
}
