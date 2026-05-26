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
                "tamework.command.tw.setowner",
                root.getSubCommands().get("setowner").getPermission()
        );
        assertEquals(TameworkConfigPermission.NODE, root.getSubCommands().get("config").getPermission());
        assertEquals(
                "tamework.command.tw.reloadconfig",
                root.getSubCommands().get("reloadconfig").getPermission()
        );
    }

    private static final class DisplayNameOwner implements CommandOwner {
        @Override
        public String getName() {
            return "Alechilles:Alec's Tamework!";
        }
    }
}
