package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.localization.TranslationRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandNpcNameResolverTest {

    @Test
    void resolvesCachedRoleIdViaServerNpcRoleTranslationKey() {
        TranslationRegistry registry = new TranslationRegistry();
        registry.put("server.npcRole.cat_pet.name", "Cat");

        CommandNpcNameResolver resolver = new CommandNpcNameResolver(registry);
        LinkedNpcRecord record = new LinkedNpcRecord(UUID.randomUUID(), null, null, null, null, "cat_pet");

        assertEquals("Cat", resolver.resolveCachedUnloadedDisplayName(record));
    }

    @Test
    void resolvesSnapshotDisplayNameWhenStoredNameMatchesRawRoleId() {
        TranslationRegistry registry = new TranslationRegistry();
        registry.put("server.npcRole.cat_pet.name", "Cat");

        CommandNpcNameResolver resolver = new CommandNpcNameResolver(registry);

        assertEquals("Cat", resolver.resolveSnapshotDisplayName("cat_pet", "cat_pet"));
    }

    @Test
    void resolvesGenericCachedSpeciesNameToSpecificRoleName() {
        TranslationRegistry registry = new TranslationRegistry();
        registry.put("npcRoles.Cat.name", "Cat");
        registry.put("npcRoles.Cat_Pet.name", "Cat");
        registry.put("npcRoles.Cat_Longhair_Pet.name", "Longhair Cat");

        CommandNpcNameResolver resolver = new CommandNpcNameResolver(registry);
        LinkedNpcRecord record = new LinkedNpcRecord(
                UUID.randomUUID(),
                null,
                null,
                "Cat",
                "server.npcRoles.Cat_Longhair_Pet.name",
                "Cat_Longhair_Pet"
        );

        assertEquals("Longhair Cat", resolver.resolveCachedUnloadedDisplayName(record));
    }

    @Test
    void resolvesGenericCachedSpeciesNameWhenCachedRoleIdIsGenericButNameKeyIsSpecific() {
        TranslationRegistry registry = new TranslationRegistry();
        registry.put("npcRoles.Cat.name", "Cat");
        registry.put("npcRoles.Cat_Pet.name", "Cat");
        registry.put("npcRoles.Cat_Bobtail_Pet.name", "Bobtail Cat");

        CommandNpcNameResolver resolver = new CommandNpcNameResolver(registry);
        LinkedNpcRecord record = new LinkedNpcRecord(
                UUID.randomUUID(),
                null,
                null,
                "Cat",
                "server.npcRoles.Cat_Bobtail_Pet.name",
                "Cat"
        );

        assertEquals("Bobtail Cat", resolver.resolveCachedUnloadedDisplayName(record));
    }

    @Test
    void resolvesSingularRoleKeyAgainstPluralNpcRolesLanguageEntry() {
        TranslationRegistry registry = new TranslationRegistry();
        registry.put("npcRoles.Cat.name", "Cat");
        registry.put("npcRoles.Cat_Pet.name", "Cat");
        registry.put("npcRoles.Cat_Shorthair_Pet.name", "Shorthair Cat");

        CommandNpcNameResolver resolver = new CommandNpcNameResolver(registry);
        LinkedNpcRecord record = new LinkedNpcRecord(
                UUID.randomUUID(),
                null,
                null,
                "Cat",
                "server.npcRole.Cat_Shorthair_Pet.name",
                "Cat_Shorthair_Pet"
        );

        assertEquals("Shorthair Cat", resolver.resolveCachedUnloadedDisplayName(record));
    }

    @Test
    void preservesCustomCachedDisplayNameOverSpecificRoleName() {
        TranslationRegistry registry = new TranslationRegistry();
        registry.put("npcRoles.Cat.name", "Cat");
        registry.put("npcRoles.Cat_Longhair_Pet.name", "Longhair Cat");

        CommandNpcNameResolver resolver = new CommandNpcNameResolver(registry);
        LinkedNpcRecord record = new LinkedNpcRecord(
                UUID.randomUUID(),
                null,
                null,
                "Mittens",
                "server.npcRoles.Cat_Longhair_Pet.name",
                "Cat_Longhair_Pet"
        );

        assertEquals("Mittens", resolver.resolveCachedUnloadedDisplayName(record));
    }

    @Test
    void resolvesRawTamedSnapshotThroughCachedRoleNameKey() {
        TranslationRegistry registry = new TranslationRegistry();
        registry.put("npcRoles.Bison.name", "Bison");

        CommandNpcNameResolver resolver = new CommandNpcNameResolver(registry);

        assertEquals(
                "Bison",
                resolver.resolveSnapshotDisplayName(
                        "Tamed_Bison",
                        "server.npcRoles.Bison.name",
                        "Tamed_Bison"
                )
        );
    }

    @Test
    void resolvesLowercaseTamedRoleIdBeforeFallingBackToRawId() {
        TranslationRegistry registry = new TranslationRegistry();
        registry.put("npcRoles.Chicken.name", "Chicken");

        CommandNpcNameResolver resolver = new CommandNpcNameResolver(registry);

        assertEquals(
                "Chicken",
                resolver.resolveSnapshotDisplayName(null, null, "tamed_chicken")
        );
    }

    @Test
    void resolvesDormantSpeciesLabelFromLowercaseTamedRoleId() {
        TranslationRegistry registry = new TranslationRegistry();
        registry.put("npcRoles.Chicken.name", "Chicken");

        CommandNpcNameResolver resolver = new CommandNpcNameResolver(registry);

        assertEquals(
                "Chicken",
                resolver.resolveRoleDisplayName("tamed_chicken", null)
        );
    }

    @Test
    void humanizesDormantSpeciesWhenTranslationIsUnavailable() {
        CommandNpcNameResolver resolver = new CommandNpcNameResolver(
                new TranslationRegistry()
        );

        assertEquals(
                "Chicken",
                resolver.resolveRoleDisplayName("tamed_chicken", null)
        );
    }

    @Test
    void humanizesLowercaseSnapshotRoleWhenTranslationIsUnavailable() {
        CommandNpcNameResolver resolver = new CommandNpcNameResolver(
                new TranslationRegistry()
        );

        assertEquals(
                "Chicken",
                resolver.resolveSnapshotDisplayName(
                        null, null, "tamed_chicken"
                )
        );
    }
}
