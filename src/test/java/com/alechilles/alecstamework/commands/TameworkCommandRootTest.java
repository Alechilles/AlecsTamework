package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.CommandOwner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TameworkCommandRootTest {

    @Test
    void rootPermissionDoesNotUseManifestDisplayName() {
        TameworkCommandRoot root = new TameworkCommandRoot();
        root.setOwner(new DisplayNameOwner());

        assertEquals(TameworkCommandRoot.ROOT_PERMISSION, root.getPermission());
        assertEquals(
                "tamework.command.tw.debug.set.owner",
                command(root, "debug", "set", "owner").getPermission()
        );
        assertEquals(
                TameworkConfigPermission.NODE,
                command(root, "config", "open").getPermission()
        );
        assertEquals(
                "tamework.command.tw.config.reload",
                command(root, "config", "reload").getPermission()
        );
        assertEquals(
                "tamework.command.tw.runtime.status",
                command(root, "runtime", "status").getPermission()
        );
        assertEquals(
                "tamework.command.tw.debug.set.level",
                command(root, "debug", "set", "level").getPermission()
        );
        assertEquals(
                "tamework.command.tw.debug.set.needs",
                root.getSubCommands().get("debug").getSubCommands().get("set")
                        .getSubCommands().get("needs").getPermission()
        );
        assertEquals(
                "tamework.command.tw.npc.spawn.tamed",
                command(root, "npc", "spawn", "tamed").getPermission()
        );
        var debugDb = command(root, "debug", "persistence", "debugdb");
        assertNotNull(debugDb, "replacement persistence diagnostics must remain registered");
        assertEquals(
                "tamework.command.tw.debug.persistence.debugdb",
                debugDb.getPermission()
        );
        assertEquals(
                "tamework.command.tw.debug.persistence.reviveready",
                command(root, "debug", "persistence", "reviveready").getPermission()
        );
        assertFalse(
                root.getSubCommands().containsKey("persistencecircuit"),
                "the unreleased persistence circuit command must not be restored"
        );
    }

    private static com.hypixel.hytale.server.core.command.system.AbstractCommand command(
            TameworkCommandRoot root,
            String... path
    ) {
        com.hypixel.hytale.server.core.command.system.AbstractCommand current = root;
        for (String part : path) {
            current = current.getSubCommands().get(part);
            assertNotNull(current, "Missing command path segment: " + part);
        }
        return current;
    }

    private static final class DisplayNameOwner implements CommandOwner {
        @Override
        public String getName() {
            return "Alechilles:Alec's Tamework!";
        }
    }
}
