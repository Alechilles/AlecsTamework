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
}
