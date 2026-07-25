package com.alechilles.alecstamework.persistence.authoring.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.authoring.ReplacementFeatureLiveEvidenceSource;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class HytaleReplacementFeatureLiveEvidenceSourceTest {
    private static final UUID OWNER =
            UUID.fromString("b73bd8a9-ec7f-4ea3-99bc-fcb5b93be4cb");
    private static final ProfileId PROFILE = new ProfileId(
            UUID.fromString("d79bcceb-f18a-49e7-86b5-e6bdbed1b578")
    );
    private static final NpcAlias ALIAS = new NpcAlias(
            UUID.fromString("4d180c7e-91c8-4829-8942-485c3d3a1fe1")
    );

    @Test
    void routesAllFourFreezesThroughTheOwnerWorldExecutor() {
        RecordingFreezer freezer = new RecordingFreezer(false);
        RecordingWorlds worlds = new RecordingWorlds(
                true, "flatworld", false
        );
        HytaleReplacementFeatureLiveEvidenceSource source =
                new HytaleReplacementFeatureLiveEvidenceSource(
                        worlds, freezer
                );

        assertSame(
                freezer.roster,
                source.freezeRosterAccess(
                        rosterIntent()
                ).toCompletableFuture().join()
        );
        assertSame(
                freezer.timed,
                source.freezeTimedWorld(
                        timedIntent()
                ).toCompletableFuture().join()
        );
        assertSame(
                freezer.provisioning,
                source.freezeProvisioningWorld(
                        provisioningIntent("flatworld")
                ).toCompletableFuture().join()
        );
        assertSame(
                freezer.paid,
                source.freezePaidInventory(
                        paidIntent()
                ).toCompletableFuture().join()
        );
        assertEquals(
                Arrays.asList(null, null, "flatworld", null),
                worlds.expectedWorlds
        );
        assertEquals(List.of("roster", "timed", "provisioning", "paid"),
                freezer.calls);
    }

    @Test
    void offlineOwnerFailsClosedForEveryFreeze() {
        RecordingFreezer freezer = new RecordingFreezer(false);
        HytaleReplacementFeatureLiveEvidenceSource source =
                new HytaleReplacementFeatureLiveEvidenceSource(
                        new RecordingWorlds(false, null, false),
                        freezer
                );

        assertNull(source.freezeRosterAccess(
                rosterIntent()
        ).toCompletableFuture().join());
        assertNull(source.freezeTimedWorld(
                timedIntent()
        ).toCompletableFuture().join());
        assertNull(source.freezeProvisioningWorld(
                provisioningIntent("flatworld")
        ).toCompletableFuture().join());
        assertNull(source.freezePaidInventory(
                paidIntent()
        ).toCompletableFuture().join());
        assertEquals(List.of(), freezer.calls);
    }

    @Test
    void provisioningInTheWrongWorldFailsBeforeEvidenceCapture() {
        RecordingFreezer freezer = new RecordingFreezer(false);
        HytaleReplacementFeatureLiveEvidenceSource source =
                new HytaleReplacementFeatureLiveEvidenceSource(
                        new RecordingWorlds(true, "hub", false),
                        freezer
                );

        assertNull(source.freezeProvisioningWorld(
                provisioningIntent("flatworld")
        ).toCompletableFuture().join());
        assertEquals(List.of(), freezer.calls);
    }

    @Test
    void missingOrFailedWorldEvidenceFailsClosed() {
        RecordingFreezer missing = new RecordingFreezer(true);
        HytaleReplacementFeatureLiveEvidenceSource missingSource =
                new HytaleReplacementFeatureLiveEvidenceSource(
                        new RecordingWorlds(true, "flatworld", false),
                        missing
                );
        assertNull(missingSource.freezeRosterAccess(
                rosterIntent()
        ).toCompletableFuture().join());
        assertNull(missingSource.freezeTimedWorld(
                timedIntent()
        ).toCompletableFuture().join());
        assertNull(missingSource.freezeProvisioningWorld(
                provisioningIntent("flatworld")
        ).toCompletableFuture().join());
        assertNull(missingSource.freezePaidInventory(
                paidIntent()
        ).toCompletableFuture().join());

        HytaleReplacementFeatureLiveEvidenceSource failedSource =
                new HytaleReplacementFeatureLiveEvidenceSource(
                        new RecordingWorlds(true, "flatworld", true),
                        new RecordingFreezer(false)
                );
        assertNull(failedSource.freezePaidInventory(
                paidIntent()
        ).toCompletableFuture().join());
    }

    private CommandFamilyRosterMutationRequest rosterRequest() {
        return new CommandFamilyRosterMutationRequest(
                "test",
                "roster",
                null,
                OWNER,
                "dragon",
                PROFILE.toString(),
                "Dragon_Command",
                "Dragon_Horn",
                CommandFamilyRosterMemberState.ACTIVE,
                null,
                true,
                null,
                0,
                0
        );
    }

    private ReplacementFeatureLiveEvidenceSource.RosterAccessIntent
    rosterIntent() {
        return new ReplacementFeatureLiveEvidenceSource.RosterAccessIntent(
                rosterRequest(),
                CommandRosterMembershipRequest.Action.UPSERT,
                "miniwyvern"
        );
    }

    private ReplacementFeatureLiveEvidenceSource.TimedWorldIntent
    timedIntent() {
        return new ReplacementFeatureLiveEvidenceSource.TimedWorldIntent(
                new CommandTimedSummoningRequest(
                        OWNER, "dragon", PROFILE.toString(), "summon"
                ),
                TimedSummonTransitionRequest.Action.START,
                profile(),
                ALIAS,
                null
        );
    }

    private ReplacementFeatureLiveEvidenceSource.ProvisioningWorldIntent
    provisioningIntent(String world) {
        return new ReplacementFeatureLiveEvidenceSource
                .ProvisioningWorldIntent(
                new ProvisioningOrigin("hydragons", "miniwyvern"),
                OWNER,
                world,
                "miniwyvern",
                null,
                null,
                null
        );
    }

    private ReplacementFeatureLiveEvidenceSource.PaidInventoryIntent
    paidIntent() {
        return new ReplacementFeatureLiveEvidenceSource.PaidInventoryIntent(
                OWNER, profile(), List.of(), null, true
        );
    }

    private CompanionProfileReadModel profile() {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Test",
                "miniwyvern",
                null,
                null,
                "flatworld",
                1,
                1,
                1,
                0
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                new OwnerId(OWNER),
                LifecycleState.ROSTER_STORED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.COMMAND_ROSTER, "slot"
                ),
                LifecycleRevision.INITIAL,
                null,
                1,
                ReconciliationGeneration.INITIAL,
                null,
                "flatworld"
        );
        return new CompanionProfileReadModel(
                identity, null, lifecycle, List.of(), List.of(), null
        );
    }

    private CompanionSnapshot snapshot() {
        String json = "{}";
        return new CompanionSnapshot(
                SnapshotId.create(),
                PROFILE,
                TimedSummonTransitionRequest.SNAPSHOT_KIND,
                1,
                json,
                Sha256Hash.ofUtf8(json),
                LifecycleRevision.INITIAL,
                true,
                1
        );
    }

    private final class RecordingFreezer
            implements FeatureWorldEvidenceFreezer {
        private final boolean missing;
        private final List<String> calls = new ArrayList<>();
        private final ReplacementFeatureLiveEvidenceSource.RosterAccess
                roster = new ReplacementFeatureLiveEvidenceSource
                .RosterAccess(
                OWNER, "dragon", "Dragon_Command", "Dragon_Horn",
                new CommandRosterSlotId(UUID.randomUUID()), 1
        );
        private final ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence
                timed = new ReplacementFeatureLiveEvidenceSource
                .TimedWorldEvidence(
                OWNER, "flatworld", ALIAS, null, snapshot(), 1
        );
        private final ReplacementFeatureLiveEvidenceSource
                .ProvisioningWorldEvidence provisioning =
                new ReplacementFeatureLiveEvidenceSource
                        .ProvisioningWorldEvidence(
                        OWNER, "Tester", "{}", null, null, null, 1
                );
        private final ReplacementFeatureLiveEvidenceSource
                .PaidInventoryEvidence paid =
                new ReplacementFeatureLiveEvidenceSource
                        .PaidInventoryEvidence(
                        OWNER, List.of(), List.of(), null, 1
                );

        private RecordingFreezer(boolean missing) {
            this.missing = missing;
        }

        @Override
        public ReplacementFeatureLiveEvidenceSource.RosterAccess
        freezeRoster(
                HytaleOwnerWorldAccess access,
                ReplacementFeatureLiveEvidenceSource.RosterAccessIntent intent
        ) {
            calls.add("roster");
            return missing ? null : roster;
        }

        @Override
        public ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence
        freezeTimed(
                HytaleOwnerWorldAccess access,
                ReplacementFeatureLiveEvidenceSource.TimedWorldIntent intent
        ) {
            calls.add("timed");
            return missing ? null : timed;
        }

        @Override
        public ReplacementFeatureLiveEvidenceSource
                .ProvisioningWorldEvidence freezeProvisioning(
                HytaleOwnerWorldAccess access,
                ReplacementFeatureLiveEvidenceSource
                        .ProvisioningWorldIntent intent
        ) {
            calls.add("provisioning");
            return missing ? null : provisioning;
        }

        @Override
        public ReplacementFeatureLiveEvidenceSource.PaidInventoryEvidence
        freezePaid(
                HytaleOwnerWorldAccess access,
                ReplacementFeatureLiveEvidenceSource.PaidInventoryIntent intent
        ) {
            calls.add("paid");
            return missing ? null : paid;
        }
    }

    private static final class RecordingWorlds
            implements OwnerWorldSnapshotExecutor {
        private final boolean online;
        private final String currentWorld;
        private final boolean fail;
        private final List<String> expectedWorlds = new ArrayList<>();

        private RecordingWorlds(
                boolean online,
                String currentWorld,
                boolean fail
        ) {
            this.online = online;
            this.currentWorld = currentWorld;
            this.fail = fail;
        }

        @Override
        public <T> CompletionStage<T> read(
                UUID ownerUuid,
                String expectedWorldKey,
                WorldSnapshotRead<T> read
        ) {
            expectedWorlds.add(expectedWorldKey);
            if (fail) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("world read failed")
                );
            }
            if (!online || expectedWorldKey != null
                    && !expectedWorldKey.equals(currentWorld)) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.completedFuture(read.read(null));
        }
    }
}
