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

    @Test
    void durableProfileNameSurvivesRestartWithoutALiveSnapshot() {
        LinkedNpcRecord record = record("Chicken");
        CommandLinkedPanelUnloadedNameService service = new CommandLinkedPanelUnloadedNameService(
                resolver(),
                ignored -> null,
                ignored -> new CommandLinkedPanelUnloadedNameService.NameSnapshot(
                        "Kaitlin", "Kaitlin", "Tamed_Chicken"
                )
        );

        assertEquals("Kaitlin", service.resolve(record));
    }

    @Test
    void durableProfileLookupIsMemoizedAcrossPanelRefreshes() {
        LinkedNpcRecord record = record("Chicken");
        int[] lookups = {0};
        CommandLinkedPanelUnloadedNameService service = new CommandLinkedPanelUnloadedNameService(
                resolver(),
                ignored -> null,
                ignored -> {
                    lookups[0]++;
                    return new CommandLinkedPanelUnloadedNameService.NameSnapshot(
                            "Kaitlin", "Kaitlin", "Tamed_Chicken"
                    );
                }
        );

        assertEquals("Kaitlin", service.resolve(record));
        assertEquals("Kaitlin", service.resolve(record));
        assertEquals(1, lookups[0]);
    }

    private static CommandLinkedPanelUnloadedNameService service(
            CommandLinkedPanelUnloadedNameService.NameSnapshot snapshot
    ) {
        return new CommandLinkedPanelUnloadedNameService(
                resolver(),
                ignored -> snapshot
        );
    }

    private static CommandNpcNameResolver resolver() {
        TranslationRegistry translations = new TranslationRegistry();
        translations.put("npcRoles.Chicken.name", "Chicken");
        return new CommandNpcNameResolver(translations);
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
