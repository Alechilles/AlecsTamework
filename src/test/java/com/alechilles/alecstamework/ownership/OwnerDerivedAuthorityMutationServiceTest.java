package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Regression coverage for invalidating derived authority during canonical owner changes. */
class OwnerDerivedAuthorityMutationServiceTest {
    private static final UUID OLD_OWNER =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID NEW_OWNER =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void transferInvalidatesOldToolLinksAndRetargetsNameAuthority() {
        OwnerDerivedAuthorityMutationService.Snapshot projected =
                OwnerDerivedAuthorityMutationService.project(snapshot(), OLD_OWNER, NEW_OWNER);

        assertNull(projected.commandLinks());
        assertNotNull(projected.npcName());
        assertEquals(NEW_OWNER, projected.npcName().getOwnerId());
        assertEquals("Companion", projected.npcName().getName());
    }

    @Test
    void clearInvalidatesLinksAndClearsNameAuthority() {
        OwnerDerivedAuthorityMutationService.Snapshot projected =
                OwnerDerivedAuthorityMutationService.project(snapshot(), OLD_OWNER, null);

        assertNull(projected.commandLinks());
        assertNotNull(projected.npcName());
        assertNull(projected.npcName().getOwnerId());
    }

    @Test
    void repeatedClearAlsoSanitizesStaleDerivedAuthority() {
        OwnerDerivedAuthorityMutationService.Snapshot projected =
                OwnerDerivedAuthorityMutationService.project(snapshot(), null, null);

        assertNull(projected.commandLinks());
        assertNotNull(projected.npcName());
        assertNull(projected.npcName().getOwnerId());
    }

    @Test
    void sameOwnerLifecycleChangePreservesDerivedValues() {
        OwnerDerivedAuthorityMutationService.Snapshot original = snapshot();
        OwnerDerivedAuthorityMutationService.Snapshot projected =
                OwnerDerivedAuthorityMutationService.project(original, OLD_OWNER, OLD_OWNER);

        assertEquals(OLD_OWNER, projected.commandLinks().getOwnerId());
        assertEquals("CommandTool", projected.commandLinks().getToolIds()[0]);
        assertEquals(OLD_OWNER, projected.npcName().getOwnerId());
        assertSame(original.npcName().getSource(), projected.npcName().getSource());
    }

    @Test
    void firstCanonicalOwnershipPreservesLinksAlreadyOwnedByThatPlayer() {
        OwnerDerivedAuthorityMutationService.Snapshot original = snapshot();
        OwnerDerivedAuthorityMutationService.Snapshot projected =
                OwnerDerivedAuthorityMutationService.project(original, null, OLD_OWNER);

        assertNotNull(projected.commandLinks());
        assertEquals(OLD_OWNER, projected.commandLinks().getOwnerId());
        assertEquals("CommandTool", projected.commandLinks().getToolIds()[0]);
    }

    private static OwnerDerivedAuthorityMutationService.Snapshot snapshot() {
        return new OwnerDerivedAuthorityMutationService.Snapshot(
                new TameworkCommandLinksComponent(OLD_OWNER, new String[]{"CommandTool"}),
                new TameworkNpcNameComponent(
                        "Companion", OLD_OWNER, 42L, TameworkNpcNameComponent.NameSource.Player
                )
        );
    }
}
