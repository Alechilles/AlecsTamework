package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.TranslationRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for custom linked-companion names surviving ordinary chunk unload. */
class CommandLinkedPanelUnloadedNameServiceTest {

    @Test
    void lastLiveCustomNameOverridesStaleCommandItemSpeciesName() {
        LinkedNpcRecord record = record("Chicken");
        CommandLinkedPanelUnloadedNameService service = service(
                new CommandLinkedPanelUnloadedNameService.NameSnapshot(
                        "Kaitlin", "Kaitlin", "Tamed_Chicken"
                )
        );

        assertEquals("Kaitlin", service.resolve(record));
    }

    @Test
    void lastLiveGenericNameOverridesAFormerCustomName() {
        LinkedNpcRecord record = record("Kaitlin");
        CommandLinkedPanelUnloadedNameService service = service(
                new CommandLinkedPanelUnloadedNameService.NameSnapshot(
                        null, "Tamed_Chicken", "Tamed_Chicken"
                )
        );

        assertEquals("Chicken", service.resolve(record));
    }

    @Test
    void cachedNameRemainsFallbackWhenNoLastLiveSnapshotExists() {
        LinkedNpcRecord record = record("Kaitlin");
        CommandLinkedPanelUnloadedNameService service = service(null);

        assertEquals("Kaitlin", service.resolve(record));
    }

    private static CommandLinkedPanelUnloadedNameService service(
            CommandLinkedPanelUnloadedNameService.NameSnapshot snapshot
    ) {
        TranslationRegistry translations = new TranslationRegistry();
        translations.put("npcRoles.Chicken.name", "Chicken");
        return new CommandLinkedPanelUnloadedNameService(
                new CommandNpcNameResolver(translations),
                ignored -> snapshot
        );
    }

    private static LinkedNpcRecord record(String cachedDisplayName) {
        return new LinkedNpcRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000601"),
                null,
                null,
                cachedDisplayName,
                "server.npcRoles.Chicken.name",
                "Tamed_Chicken"
        );
    }
}
