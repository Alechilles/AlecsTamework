package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ensures every API list view is rebuilt from its durable profile snapshot. */
class BondedCompanionViewFactoryTest {
    @Test
    void capturedSnapshotPopulatesImmediatePanelFieldsWithoutALiveNpc() {
        UUID owner = UUID.fromString(
                "72000000-0000-0000-0000-000000000001");
        TameworkLifeStageComponent life = new TameworkLifeStageComponent();
        life.setGender("Female");
        BondedCompanionSnapshot snapshot = BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshot(
                        UUID.fromString("72000000-0000-0000-0000-000000000002"),
                        null, -1, "Bonded_Miniwyvern_Storm", null, null, null,
                        new TameworkNpcNameComponent(
                                "Nimbus", owner, 1L,
                                TameworkNpcNameComponent.NameSource.Player),
                        null, null, null,
                        new TameworkLevelingComponent(
                                "level", 7, 20D, 50D, 1L),
                        null, null, life, null, 63.25D, 1L),
                Map.of("hydragon:bond",
                        "{\"archetype\":\"storm\",\"ability\":\"dash\"}"));
        String encoded = new BondedCompanionSnapshotCodec().encode(snapshot);
        BondedCompanionRecord.Profile profile = new BondedCompanionRecord.Profile(
                "profile-7", owner, "hydragon:dragons", "hydragon:dragon",
                "Bonded_Miniwyvern_Storm", BondedCompanionState.STORED, 4L,
                BondedCompanionPayload.of(encoded.getBytes(StandardCharsets.UTF_8)),
                1L, 1L, Map.of(), "Nimbus", "Miniwyvern", "Female",
                null, 0L, 0L, null, null);

        var view = new BondedCompanionViewFactory().view(profile, null);

        assertEquals("Nimbus", view.displayName());
        assertEquals("Miniwyvern", view.species());
        assertEquals("Female", view.gender());
        assertEquals("63.25", view.snapshotPresentationData().get("healthPercent"));
        assertEquals("7", view.snapshotPresentationData().get("level"));
        assertEquals(
                "{\"archetype\":\"storm\",\"ability\":\"dash\"}",
                view.snapshotPresentationData().get("extension:hydragon:bond"));
        assertEquals("Miniwyvern Storm",
                view.snapshotPresentationData().get("rolePresentation"));
    }
}
