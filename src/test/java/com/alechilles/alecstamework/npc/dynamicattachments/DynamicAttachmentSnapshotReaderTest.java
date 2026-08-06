package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicAttachmentSnapshotReaderTest {
    @Test
    void readReturnsEmptySnapshotForInvalidInput() {
        DynamicAttachmentNpcSnapshot snapshot = new DynamicAttachmentSnapshotReader(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ).read(null, null);

        assertNull(snapshot.getRoleId());
        assertNull(snapshot.getDisplayName());
        assertNull(snapshot.getOwnerPresent());
        assertNull(snapshot.getOwnerId());
        assertNull(snapshot.getOwnerName());
        assertNull(snapshot.getTamed());
        assertNull(snapshot.getGender());
        assertNull(snapshot.getLifeStage());
        assertNull(snapshot.getHappiness());
        assertTrue(snapshot.getNeeds().isEmpty());
        assertTrue(snapshot.getTraits().isEmpty());
        assertTrue(snapshot.getCommandStates().isEmpty());
    }

    @Test
    void readNeedsUsesHungerAndThirstKeys() {
        TameworkNeedsComponent needs = new TameworkNeedsComponent("default", 0.25, 0.75, 0.0, 10L, 20L);

        assertEquals(
                Map.of("hunger", 0.25, "thirst", 0.75),
                DynamicAttachmentSnapshotReader.readNeeds(needs)
        );
    }

    @Test
    void readTraitsSkipsNullEntriesAndBlankIds() {
        TameworkTraitsComponent traits = new TameworkTraitsComponent(
                "default",
                123L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("Brave", 2.0),
                        null,
                        new TameworkTraitsComponent.TraitValue(" ", 5.0),
                        new TameworkTraitsComponent.TraitValue("Patient", 1.5)
                }
        );

        assertEquals(
                Map.of("Brave", 2.0, "Patient", 1.5),
                DynamicAttachmentSnapshotReader.readTraits(traits)
        );
    }

    @Test
    void readCommandStatesIncludesHomeAndLinkedToolCount() {
        TameworkCommandLinksComponent links = new TameworkCommandLinksComponent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new String[] { "tool-a", "tool-b" }
        );
        links.setHasHome(true);

        assertEquals(
                Map.of("has_home", "true", "linked_tool_count", "2"),
                DynamicAttachmentSnapshotReader.readCommandStates(links)
        );
    }

    @Test
    void readCommandStatesTreatsMissingToolIdsAsZero() {
        TameworkCommandLinksComponent links = new TameworkCommandLinksComponent();

        assertEquals(
                Map.of("has_home", "false", "linked_tool_count", "0"),
                DynamicAttachmentSnapshotReader.readCommandStates(links)
        );
    }
}
