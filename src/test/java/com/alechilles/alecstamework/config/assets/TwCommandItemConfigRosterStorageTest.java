package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.exception.CodecException;
import java.lang.reflect.Field;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner-command-family config and registry contract tests. */
class TwCommandItemConfigRosterStorageTest {
    @Test
    void rejectsUnknownNonBlankRosterStorageInsteadOfSelectingLegacyStorage() {
        assertEquals(
                TwCommandItemConfig.RosterStorage.ItemMetadata,
                TwCommandItemConfig.RosterStorage.fromString(null)
        );
        assertEquals(
                TwCommandItemConfig.RosterStorage.ItemMetadata,
                TwCommandItemConfig.RosterStorage.fromString("  ")
        );
        assertThrows(
                CodecException.class,
                () -> TwCommandItemConfig.CODEC.decode(
                        BsonDocument.parse(
                                "{\"RosterStorage\":\"OwnerComandFamily\"}"
                        ),
                        new ExtraInfo()
                )
        );
    }

    @Test
    void defaultsPreserveReleasedItemMetadataBehavior() {
        TwCommandItemConfig config = new TwCommandItemConfig();

        assertNull(config.getCommandFamilyId());
        assertEquals(
                TwCommandItemConfig.RosterStorage.ItemMetadata,
                config.getRosterStorage()
        );
        assertTrue(config.isProjectRosterToItemMetadata());
        assertFalse(config.usesOwnerCommandFamilyRoster());
    }

    @Test
    void omittedRosterFieldsInheritWhileExplicitStorageWins()
            throws Exception {
        TwCommandItemConfig parent = ownerFamily("hydragon:dragon_horn");
        set(parent, "projectRosterToItemMetadata", false);
        TwCommandItemConfig child = new TwCommandItemConfig();

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertEquals(
                "hydragon:dragon_horn",
                child.getCommandFamilyId()
        );
        assertEquals(
                TwCommandItemConfig.RosterStorage.OwnerCommandFamily,
                child.getRosterStorage()
        );
        assertFalse(child.isProjectRosterToItemMetadata());

        TwCommandItemConfig explicit = new TwCommandItemConfig();
        explicit.inheritMissingTopLevelFrom(
                parent, Set.of("RosterStorage")
        );
        assertEquals(
                TwCommandItemConfig.RosterStorage.ItemMetadata,
                explicit.getRosterStorage()
        );
        assertEquals(
                "hydragon:dragon_horn",
                explicit.getCommandFamilyId()
        );
    }

    @Test
    void registryRejectsInvalidOwnerFamilyAuthority()
            throws Exception {
        TwCommandItemConfig missingFamily = new TwCommandItemConfig();
        set(
                missingFamily,
                "rosterStorage",
                TwCommandItemConfig.RosterStorage.OwnerCommandFamily
        );
        CommandItemRegistry registry = new CommandItemRegistry();
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.register("Horn", missingFamily)
        );

        TwCommandItemConfig ownerBypass = ownerFamily("dragons");
        set(ownerBypass, "requireOwner", Boolean.FALSE);
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.register("Horn", ownerBypass)
        );
    }

    @Test
    void registryIndexesExactConfigAndValidatesRoleAccess()
            throws Exception {
        TwCommandItemConfig config = ownerFamily(
                "hydragon:dragon_horn"
        );
        set(config, "id", "HyDragonDragonHorn");
        TwCommandItemConfig.AllowlistRoles roles =
                new TwCommandItemConfig.AllowlistRoles();
        set(roles, "allowlist", new String[]{"Tamed_Dragon_Fire"});
        set(config, "allowedRoles", roles);
        CommandItemRegistry registry = new CommandItemRegistry();

        registry.register(
                config.getId(), "HyDragon_Dragon_Horn", config
        );

        assertSame(config, registry.getByConfigId(
                "HyDragonDragonHorn"
        ));
        assertNull(registry.validateOwnerFamilyAccess(
                "hydragon:dragon_horn",
                "HyDragonDragonHorn",
                "HyDragon_Dragon_Horn",
                "Tamed_Dragon_Fire"
        ));
        assertEquals(
                "profile-role-not-allowed",
                registry.validateOwnerFamilyAccess(
                        "hydragon:dragon_horn",
                        "HyDragonDragonHorn",
                        "HyDragon_Dragon_Horn",
                        "Tamed_Dragon_Ice"
                )
        );
    }

    private static TwCommandItemConfig ownerFamily(String family)
            throws Exception {
        TwCommandItemConfig config = new TwCommandItemConfig();
        set(config, "commandFamilyId", family);
        set(
                config,
                "rosterStorage",
                TwCommandItemConfig.RosterStorage.OwnerCommandFamily
        );
        return config;
    }

    private static void set(Object target, String field, Object value)
            throws Exception {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }
}
