package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningChangedEvent;
import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.api.CommandTimedSummoningResult;
import com.alechilles.alecstamework.api.CommandTimedSummoningView;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonProjectionView;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for automatic timed-summon storage at owner lifecycle boundaries. */
class TimedSummonOwnerLifecycleServiceTest {
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final ProfileId ACTIVE = ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileId OPTED_OUT = ProfileId.parse("20000000-0000-0000-0000-000000000002");

    @Test
    void logoutStoresOnlyActiveLeasesThatOptIntoLogoutStorage() {
        RecordingApi api = new RecordingApi();
        TimedSummonOwnerLifecycleService service = new TimedSummonOwnerLifecycleService(
                () -> api,
                () -> Map.of(
                        ACTIVE, active(ACTIVE, true),
                        OPTED_OUT, active(OPTED_OUT, false)
                )
        );

        int submitted = service.onOwnerLogout(OWNER);

        assertEquals(1, submitted);
        assertEquals(1, api.dismissals.size());
        CommandTimedSummoningRequest request = api.dismissals.getFirst();
        assertEquals(OWNER, request.ownerUuid());
        assertEquals(ACTIVE.toString(), request.profileId());
        assertTrue(request.idempotencyKey().contains("owner-logout"));
    }

    @Test
    void deathStoresActiveLeasesEvenWhenLogoutStorageIsDisabled() {
        RecordingApi api = new RecordingApi();
        TimedSummonOwnerLifecycleService service = new TimedSummonOwnerLifecycleService(
                () -> api,
                () -> Map.of(OPTED_OUT, active(OPTED_OUT, false))
        );

        int submitted = service.onOwnerDeath(OWNER);

        assertEquals(1, submitted);
        assertEquals(OPTED_OUT.toString(), api.dismissals.getFirst().profileId());
        assertTrue(api.dismissals.getFirst().idempotencyKey().contains("owner-death"));
    }

    @Test
    void staleAvatarFlightRecoveryStoresAnActiveLeaseAfterRestart() {
        RecordingApi api = new RecordingApi();
        TimedSummonOwnerLifecycleService service = new TimedSummonOwnerLifecycleService(
                () -> api,
                () -> Map.of(OPTED_OUT, active(OPTED_OUT, false))
        );

        int submitted = service.onStaleAvatarFlightRecovery(OWNER);

        assertEquals(1, submitted);
        assertTrue(api.dismissals.getFirst().idempotencyKey().contains("avatar-flight-restart"));
    }

    private static TimedSummonProjectionView active(ProfileId profile, boolean autoStoreOnLogout) {
        OwnerId owner = new OwnerId(OWNER);
        CommandFamilyKey family = new CommandFamilyKey(owner, "test:horn");
        CommandRosterSlotId slot = CommandRosterSlotId.parse(
                profile.equals(ACTIVE)
                        ? "30000000-0000-0000-0000-000000000001"
                        : "30000000-0000-0000-0000-000000000002"
        );
        TimedSummonLease lease = new TimedSummonLease(
                profile,
                7,
                new TimedSummonSessionId(UUID.nameUUIDFromBytes(profile.toString().getBytes())),
                null,
                null,
                new TimedSummonPolicy("test:policy", 1L, 0L, 0L, autoStoreOnLogout, List.of()),
                java.util.Set.of(),
                -1L,
                -2L,
                -1L
        );
        CommandRosterMembership membership = new CommandRosterMembership(
                slot, family, profile, 3L, null, false, null, -2L, -1L
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                profile,
                owner,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        NpcAlias.parse("40000000-0000-0000-0000-000000000001").toString(),
                        "world-a"
                ),
                LifecycleRevision.INITIAL,
                null,
                -1L,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
        return new TimedSummonProjectionView(lease, membership, lifecycle);
    }

    private static final class RecordingApi implements CommandTimedSummoningApi {
        private final List<CommandTimedSummoningRequest> dismissals = new ArrayList<>();

        @Override public Optional<CommandTimedSummoningView> get(CommandTimedSummoningRequest identity) {
            return Optional.empty();
        }

        @Override public CompletionStage<CommandTimedSummoningResult> summon(CommandTimedSummoningRequest request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<CommandTimedSummoningResult> dismiss(CommandTimedSummoningRequest request) {
            dismissals.add(request);
            return CompletableFuture.completedFuture(null);
        }

        @Override public AutoCloseable subscribe(Consumer<CommandTimedSummoningChangedEvent> listener) {
            return () -> { };
        }
    }
}
