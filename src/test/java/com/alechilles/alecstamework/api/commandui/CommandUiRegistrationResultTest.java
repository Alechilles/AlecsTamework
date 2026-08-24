package com.alechilles.alecstamework.api.commandui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests observable invariants of shared command UI registration results. */
class CommandUiRegistrationResultTest {
    @Test
    void registeredResultUsesTheHandleIdentity() {
        CommandUiRegistration handle = new CommandUiRegistration() {
            @Override
            public String id() {
                return "Runeteria:Renderer";
            }

            @Override
            public long generation() {
                return 7L;
            }

            @Override
            public boolean active() {
                return true;
            }

            @Override
            public void close() {
            }
        };

        CommandUiRegistrationResult result = CommandUiRegistrationResult.registered(handle);

        assertEquals(CommandUiRegistrationResult.Status.REGISTERED, result.status());
        assertTrue(result.registered());
        assertSame(handle, result.registration());
        assertEquals("runeteria:renderer", result.id());
    }

    @Test
    void nonSuccessResultsDoNotExposeRegistrationHandles() {
        CommandUiRegistrationResult conflict =
                CommandUiRegistrationResult.conflict("Runeteria:Renderer");
        CommandUiRegistrationResult invalid =
                CommandUiRegistrationResult.invalid("Runeteria:Renderer", "bad input");
        CommandUiRegistrationResult unavailable =
                CommandUiRegistrationResult.unavailable("Runeteria:Renderer");

        assertFalse(conflict.registered());
        assertFalse(invalid.registered());
        assertFalse(unavailable.registered());
        assertNull(conflict.registration());
        assertNull(invalid.registration());
        assertNull(unavailable.registration());
        assertEquals("runeteria:renderer", conflict.id());
        assertEquals("runeteria:renderer", invalid.id());
        assertEquals("runeteria:renderer", unavailable.id());
    }
}
