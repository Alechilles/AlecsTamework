package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Characterizes stable role-parameter routing across every mount implementation. */
class InteractionMountModeTest {
    @Test
    void preservesExistingAndAvatarModeMappings() {
        assertEquals(InteractionMountMode.NATIVE, InteractionMountMode.parse(""));
        assertEquals(InteractionMountMode.TAMEWORK_RIDE, InteractionMountMode.parse("TameworkRide"));
        assertEquals(InteractionMountMode.TAMEWORK_MOUNTED_GLIDE,
                InteractionMountMode.parse("TameworkMountedGlide"));
        assertEquals(InteractionMountMode.TAMEWORK_AVATAR_FLIGHT,
                InteractionMountMode.parse("TameworkAvatarFlight"));
    }

    @Test
    void unknownModeUsesDocumentedNativeCompatibilityFallback() {
        assertEquals(InteractionMountMode.NATIVE, InteractionMountMode.parse("TypoMode"));
        assertFalse(InteractionMountMode.isKnown("TypoMode"));
        assertTrue(InteractionMountMode.isKnown("native"));
    }

    @Test
    void dispatcherInvokesExactlyTheParsedModeHandler() {
        StringBuilder calls = new StringBuilder();
        InteractionMountModeDispatcher dispatcher = new InteractionMountModeDispatcher(
                request -> calls.append('N').length() > 0,
                request -> calls.append('R').length() > 0,
                request -> calls.append('G').length() > 0,
                request -> calls.append('A').length() > 0
        );

        dispatcher.dispatch(InteractionMountMode.NATIVE, null);
        dispatcher.dispatch(InteractionMountMode.TAMEWORK_RIDE, null);
        dispatcher.dispatch(InteractionMountMode.TAMEWORK_MOUNTED_GLIDE, null);
        dispatcher.dispatch(InteractionMountMode.TAMEWORK_AVATAR_FLIGHT, null);

        assertEquals("NRGA", calls.toString());
    }
}
