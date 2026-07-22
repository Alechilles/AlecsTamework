package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwCommandItemConfigRosterStorageTest {
    @Test
    void defaultsPreserveItemMetadataCompatibility() {
        TwCommandItemConfig config = new TwCommandItemConfig();
        assertNull(config.getCommandFamilyId());
        assertEquals(TwCommandItemConfig.RosterStorage.ItemMetadata, config.getRosterStorage());
        assertTrue(config.isProjectRosterToItemMetadata());
    }

    @Test
    void omittedRosterFieldsInheritAndExplicitStorageWins() throws Exception {
        TwCommandItemConfig parent = new TwCommandItemConfig();
        set(parent, "commandFamilyId", "dragons");
        set(parent, "rosterStorage", TwCommandItemConfig.RosterStorage.OwnerCommandFamily);
        set(parent, "projectRosterToItemMetadata", false);
        TwCommandItemConfig child = new TwCommandItemConfig();

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertEquals("dragons", child.getCommandFamilyId());
        assertEquals(TwCommandItemConfig.RosterStorage.OwnerCommandFamily, child.getRosterStorage());
        assertEquals(false, child.isProjectRosterToItemMetadata());

        TwCommandItemConfig explicit = new TwCommandItemConfig();
        explicit.inheritMissingTopLevelFrom(parent, Set.of("RosterStorage"));
        assertEquals(TwCommandItemConfig.RosterStorage.ItemMetadata, explicit.getRosterStorage());
        assertEquals("dragons", explicit.getCommandFamilyId());
    }

    @Test
    void ownerFamilyStorageRejectsMissingFamilyAndOwnerBypass() throws Exception {
        TwCommandItemConfig missingFamily = new TwCommandItemConfig();
        set(missingFamily, "rosterStorage", TwCommandItemConfig.RosterStorage.OwnerCommandFamily);
        assertThrows(IllegalArgumentException.class,
                () -> new CommandItemRegistry().register("horn", missingFamily));

        TwCommandItemConfig ownerBypass = new TwCommandItemConfig();
        set(ownerBypass, "rosterStorage", TwCommandItemConfig.RosterStorage.OwnerCommandFamily);
        set(ownerBypass, "commandFamilyId", "dragons");
        set(ownerBypass, "requireOwner", Boolean.FALSE);
        assertThrows(IllegalArgumentException.class,
                () -> new CommandItemRegistry().register("horn", ownerBypass));
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }
}
