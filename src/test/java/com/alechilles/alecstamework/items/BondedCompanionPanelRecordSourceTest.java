package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionAvailability;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Profile-first identity and durable-card regression coverage. */
class BondedCompanionPanelRecordSourceTest {
    private static final UUID OWNER = UUID.fromString(
            "71000000-0000-0000-0000-000000000001");
    private static final UUID LIVE = UUID.fromString(
            "71000000-0000-0000-0000-000000000002");

    @Test
    void activeCardIdentityComesOnlyFromStableProfileId() {
        BondedCompanionProfileView active = BondedPanelTestFixtures.profile(
                "profile-7", 4L, BondedCompanionStateView.ACTIVE, LIVE,
                Map.of("healthPercent", "63.25", "level", "7",
                        "extension:hydragon:bond",
                        "{\"archetype\":\"storm\",\"ability\":\"dash\"}"));
        BondedCompanionPanelRecordSource source =
                new BondedCompanionPanelRecordSource(() ->
                        BondedPanelTestFixtures.api(List.of(active)));

        BondedCompanionPanelRecordSource.PanelRecord record =
                source.snapshotFor(OWNER, "hydragon:dragons")
                        .records().getFirst();

        assertEquals("profile-7", record.profile().profileId());
        assertEquals(4L, record.profile().revision());
        assertEquals(
                BondedCompanionPanelRecordSource.presentationUuid("profile-7"),
                record.presentationUuid());
        assertNotEquals(LIVE, record.presentationUuid());
    }

    @Test
    void duplicateProfilesCollapseByProfileAtTheNewestRevision() {
        BondedCompanionProfileView old = BondedPanelTestFixtures.profile(
                "profile-7", 3L, BondedCompanionStateView.STORED, null, Map.of());
        BondedCompanionProfileView current = BondedPanelTestFixtures.profile(
                "profile-7", 4L, BondedCompanionStateView.STORED, null, Map.of());
        BondedCompanionPanelRecordSource source =
                new BondedCompanionPanelRecordSource(() ->
                        BondedPanelTestFixtures.api(List.of(old, current)));

        var snapshot = source.snapshotFor(OWNER, "hydragon:dragons");

        assertEquals(1, snapshot.records().size());
        assertEquals(4L, snapshot.records().getFirst().profile().revision());
    }

    @Test
    void captureSummonStoreAndReviveKeepOneProfileKeyedDetailedCard() {
        AtomicReference<BondedCompanionProfileView> current =
                new AtomicReference<>();
        BondedCompanionPanelRecordSource source =
                new BondedCompanionPanelRecordSource(() ->
                        BondedPanelTestFixtures.api(List.of(current.get())));
        UUID expected = BondedCompanionPanelRecordSource
                .presentationUuid("profile-lifecycle");
        BondedCompanionStateView[] states = {
                BondedCompanionStateView.STORED,
                BondedCompanionStateView.ACTIVE,
                BondedCompanionStateView.STORED,
                BondedCompanionStateView.DEAD,
                BondedCompanionStateView.STORED
        };
        for (int index = 0; index < states.length; index++) {
            current.set(BondedPanelTestFixtures.profile(
                    "profile-lifecycle", index, states[index],
                    states[index] == BondedCompanionStateView.ACTIVE ? LIVE : null,
                    Map.of("healthPercent", "63.25", "level", "7",
                            "extension:hydragon:bond", "{\"ability\":\"dash\"}")));

            var record = source.snapshotFor(OWNER, "hydragon:dragons")
                    .records().getFirst();

            assertEquals(expected, record.presentationUuid());
            assertEquals("63.25", record.profile()
                    .snapshotPresentationData().get("healthPercent"));
            assertEquals("{\"ability\":\"dash\"}", record.profile()
                    .snapshotPresentationData().get("extension:hydragon:bond"));
        }
    }
}
