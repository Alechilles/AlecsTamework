package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the structured debug command tree and its native completion-visible arguments. */
class TameworkDebugCommandTest {
    @Test
    void needsMutationsUseTheStandardNpcSelectorSurface() {
        TameworkCommandRoot root = new TameworkCommandRoot();
        AbstractCommand needs = root.getSubCommands().get("debug")
                .getSubCommands().get("set")
                .getSubCommands().get("needs");

        assertEquals(2, needs.getRequiredArguments().size());
        assertTrue(needs.getOptionalArguments().containsKey("world"));
        assertTrue(needs.getOptionalArguments().containsKey("entity"));
        assertTrue(needs.getOptionalArguments().containsKey("angle"));
        assertTrue(needs.getOptionalArguments().containsKey("range"));
        assertTrue(needs.getOptionalArguments().containsKey("roles"));
        assertTrue(needs.getOptionalArguments().containsKey("nearest"));
        assertTrue(needs.getOptionalArguments().containsKey("ray"));
        assertTrue(needs.getOptionalArguments().containsKey("cone"));
        assertTrue(needs.getOptionalArguments().containsKey("coneall"));
        assertTrue(needs.getOptionalArguments().containsKey("sphere"));
    }

    @Test
    void spawnTamedUsesTypedRoleAndSpawnSwitches() {
        TameworkCommandRoot root = new TameworkCommandRoot();
        AbstractCommand spawnTamed = root.getSubCommands().get("spawntamed");

        assertEquals(1, spawnTamed.getRequiredArguments().size());
        assertTrue(spawnTamed.getOptionalArguments().containsKey("count"));
        assertTrue(spawnTamed.getOptionalArguments().containsKey("radius"));
        assertTrue(spawnTamed.getOptionalArguments().containsKey("attachment"));
    }
}
