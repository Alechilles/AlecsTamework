package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.CommandOwner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TameworkCommandRootTest {

    @Test
    void rootPermissionDoesNotUseManifestDisplayName() {
        TameworkCommandRoot root = new TameworkCommandRoot();
        root.setOwner(new DisplayNameOwner());

        assertEquals(TameworkCommandRoot.ROOT_PERMISSION, root.getPermission());
        assertEquals(
                "tamework.command.tw.debug.set.owner",
                root.getSubCommands().get("debug").getSubCommands().get("set")
                        .getSubCommands().get("owner").getPermission()
        );
        assertEquals(
                TameworkConfigPermission.NODE,
                root.getSubCommands().get("config").getSubCommands().get("open").getPermission()
        );
        assertEquals(
                "tamework.command.tw.config.reload",
                root.getSubCommands().get("config").getSubCommands().get("reload").getPermission()
        );
        assertEquals(
                "tamework.command.tw.runtime.status",
                root.getSubCommands().get("runtime").getSubCommands().get("status").getPermission()
        );
        assertEquals(
                "tamework.command.tw.debug.set.level",
                root.getSubCommands().get("debug").getSubCommands().get("set")
                        .getSubCommands().get("level").getPermission()
        );
        assertEquals(
                "tamework.command.tw.debug.set.needs",
                root.getSubCommands().get("debug").getSubCommands().get("set")
                        .getSubCommands().get("needs").getPermission()
        );
        assertEquals(
                "tamework.command.tw.npc.spawn.tamed",
                root.getSubCommands().get("npc").getSubCommands().get("spawn")
                        .getSubCommands().get("tamed").getPermission()
        );
        var persistenceStatus = root.getSubCommands().get("debug")
                .getSubCommands().get("persistence").getSubCommands()
                .get("status");
        assertEquals(
                "tamework.command.tw.debug.persistence.status",
                persistenceStatus == null
                        ? "<missing>" : persistenceStatus.getPermission()
        );
        assertEquals(
                "tamework.command.tw.debug.persistence.reviveready",
                root.getSubCommands().get("debug").getSubCommands()
                        .get("persistence").getSubCommands()
                        .get("reviveready").getPermission()
        );
    }

    private static final class DisplayNameOwner implements CommandOwner {
        @Override
        public String getName() {
            return "Alechilles:Alec's Tamework!";
        }
    }
}
