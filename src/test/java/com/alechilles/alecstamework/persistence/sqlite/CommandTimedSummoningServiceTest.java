package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandTimedSummoningService;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end coordinator regressions for cap enforcement, expiry, and ambiguous storage. */
class CommandTimedSummoningServiceTest {
    @TempDir Path tempDir;

    @Test
    void expiryReleasesOneActiveSlotAndCooldownPreventsImmediateResummon() throws Exception {
        try (Fixture fixture = new Fixture(tempDir.resolve("service-expiry.sqlite"), 1)) {
            fixture.addStored("dragon-a", 100L, 50L, new long[] { 75L, 25L });
            fixture.addStored("dragon-b", 100L, 50L, new long[0]);

            CommandTimedSummoningService.ActionResult first = await(fixture.summon("dragon-a", "summon-a", 1_000L));
            assertEquals(CommandTimedSummoningService.Status.SUCCESS, first.status());
            assertEquals(1, fixture.population.active);

            CommandTimedSummoningService.ActionResult blocked = await(fixture.summon("dragon-b", "summon-b", 1_001L));
            assertEquals(CommandTimedSummoningService.Status.DENIED, blocked.status());
            assertEquals("max-active-per-owner", blocked.reason());

            assertEquals(1, await(fixture.service.tick(1_030L)).warned());
            assertEquals(0, await(fixture.service.tick(1_060L)).warned());
            assertEquals(1, await(fixture.service.tick(1_080L)).warned());
            CommandTimedSummoningService.TickResult expired = await(fixture.service.tick(1_101L));
            assertEquals(1, expired.stored());
            assertEquals(0, fixture.population.active);
            assertEquals(2, fixture.warningCount.get());

            CommandTimedSummoningService.ActionResult cooldown = await(fixture.summon("dragon-a", "summon-a-2", 1_120L));
            assertEquals(CommandTimedSummoningService.Status.COOLDOWN, cooldown.status());

            CommandTimedSummoningService.ActionResult second = await(fixture.summon("dragon-b", "summon-b-2", 1_120L));
            assertEquals(CommandTimedSummoningService.Status.SUCCESS, second.status());
            assertEquals(1, fixture.population.active);
            assertTrue(fixture.projections.frontPlans >= 2);
        }
    }

    @Test
    void ambiguousDespawnRemainsStoringAndCannotFreeMaxActiveSlot() throws Exception {
        try (Fixture fixture = new Fixture(tempDir.resolve("service-ambiguous.sqlite"), 1)) {
            fixture.addStored("dragon-a", 1_000L, 0L, new long[0]);
            fixture.addStored("dragon-b", 1_000L, 0L, new long[0]);
            assertEquals(CommandTimedSummoningService.Status.SUCCESS,
                    await(fixture.summon("dragon-a", "summon-a", 1_000L)).status());
            fixture.projections.storageOutcome = CommandTimedSummoningService.ProjectionOutcome.AMBIGUOUS;

            CommandTimedSummoningService.ActionResult dismissal = await(fixture.service.dismiss(
                    new CommandTimedSummoningService.DismissRequest(
                            fixture.owner, fixture.family, fixture.profile("dragon-a"), 1L,
                            null, "dismiss-a", 1_010L)));
            assertEquals(CommandTimedSummoningService.Status.RECOVERING, dismissal.status());
            CommandTimedSummonSessionRecord stored = fixture.repository.findSession(
                    fixture.owner, fixture.family, fixture.profile("dragon-a"));
            assertEquals(CommandTimedSummonSessionRecord.State.STORING, stored.state());
            assertTrue(stored.state().occupiesActiveCapacity());
            assertEquals(1, fixture.population.active);

            CommandTimedSummoningService.ActionResult blocked = await(fixture.summon(
                    "dragon-b", "summon-b", 1_020L));
            assertEquals(CommandTimedSummoningService.Status.DENIED, blocked.status());
            assertEquals("max-active-per-owner", blocked.reason());
        }
    }

    @Test
    void leaseTickDoesNotRecoverAStorageOperationThatIsStillDespawning() throws Exception {
        try (Fixture fixture = new Fixture(tempDir.resolve("service-live-storage.sqlite"), 1)) {
            fixture.addStored("dragon-a", 1_000L, 0L, new long[0]);
            assertEquals(CommandTimedSummoningService.Status.SUCCESS,
                    await(fixture.summon("dragon-a", "summon-a", 1_000L)).status());
            CompletableFuture<CommandTimedSummoningService.ProjectionResult> despawn =
                    new CompletableFuture<>();
            fixture.projections.pendingStorage = despawn;
            fixture.projections.recoveryEvidence =
                    CommandTimedSummoningService.ProjectionEvidence.ABSENT;

            CompletionStage<CommandTimedSummoningService.ActionResult> dismissal =
                    fixture.service.dismiss(new CommandTimedSummoningService.DismissRequest(
                            fixture.owner, fixture.family, fixture.profile("dragon-a"), 1L,
                            null, "dismiss-a", 1_010L));
            fixture.projections.storageStarted.toCompletableFuture().get(5, TimeUnit.SECONDS);

            await(fixture.service.tick(1_011L));

            assertEquals(0, fixture.projections.inspections);
            assertEquals(CommandTimedSummonSessionRecord.State.STORING,
                    fixture.repository.findSession(
                            fixture.owner, fixture.family, fixture.profile("dragon-a")).state());
            assertEquals(1, fixture.population.active);

            despawn.complete(new CommandTimedSummoningService.ProjectionResult(
                    CommandTimedSummoningService.ProjectionOutcome.SUCCESS,
                    null, "stored"));
            assertEquals(CommandTimedSummoningService.Status.SUCCESS, await(dismissal).status());
            assertEquals(CommandTimedSummonSessionRecord.State.ROSTER_STORED,
                    fixture.repository.findSession(
                            fixture.owner, fixture.family, fixture.profile("dragon-a")).state());
            assertEquals(0, fixture.population.active);
        }
    }

    /** Regression: a failed world-removal callback must not leave STORING active forever. */
    @Test
    void leaseTickRecoversStaleStorageThatRetainedItsProjection() throws Exception {
        try (Fixture fixture = new Fixture(tempDir.resolve("service-stale-storage.sqlite"), 1)) {
            fixture.addStored("dragon-a", 1_000L, 0L, new long[0]);
            assertEquals(CommandTimedSummoningService.Status.SUCCESS,
                    await(fixture.summon("dragon-a", "summon-a", 1_000L)).status());
            fixture.projections.pendingStorage = new CompletableFuture<>();
            fixture.projections.recoveryEvidence =
                    CommandTimedSummoningService.ProjectionEvidence.PRESENT;

            fixture.service.dismiss(new CommandTimedSummoningService.DismissRequest(
                    fixture.owner, fixture.family, fixture.profile("dragon-a"), 1L,
                    null, "dismiss-a", 1_010L));
            fixture.projections.storageStarted.toCompletableFuture().get(5, TimeUnit.SECONDS);

            await(fixture.service.tick(
                    1_010L + 15_000L - 1L));
            assertEquals(0, fixture.projections.inspections);

            await(fixture.service.tick(
                    1_010L + 15_000L));

            assertEquals(1, fixture.projections.inspections);
            assertEquals(1, fixture.population.activeRecoveries);
            assertEquals(CommandTimedSummonSessionRecord.State.ACTIVE,
                    fixture.repository.findSession(
                            fixture.owner, fixture.family, fixture.profile("dragon-a")).state());
            assertEquals(1, fixture.population.active);
        }
    }

    @Test
    void startupConvergenceRepairsStoredSessionStillOccupyingActiveCapacity() throws Exception {
        try (Fixture fixture = new Fixture(tempDir.resolve("service-stored-convergence.sqlite"), 1)) {
            fixture.addStored("dragon-a", 1_000L, 0L, new long[0]);
            fixture.population.active = 1;

            CommandTimedSummoningService.RecoveryResult result =
                    await(fixture.service.convergeStoredPopulation());

            assertTrue(result.ready());
            assertEquals(1, result.converged());
            assertEquals(1, fixture.population.storedConvergences);
            assertEquals(0, fixture.population.active);
        }
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static final class Fixture implements AutoCloseable {
        private final HydragonPersistenceTestHarness harness;
        private final UUID owner = UUID.randomUUID();
        private final String family = "test:dragon_horn";
        private final java.util.Map<String, String> profiles = new java.util.HashMap<>();
        private final Set<String> roster = new HashSet<>();
        private final FakePopulation population;
        private final FakeProjection projections = new FakeProjection();
        private final AtomicInteger warningCount = new AtomicInteger();
        private final CommandTimedSummonRepository repository;
        private final CommandTimedSummoningService service;

        private Fixture(Path database, int maxActive) throws Exception {
            harness = new HydragonPersistenceTestHarness(database);
            repository = new CommandTimedSummonRepository(harness.connections, harness.queue);
            population = new FakePopulation(maxActive);
            service = new CommandTimedSummoningService(repository, harness.reads,
                    (ownerUuid, commandFamilyId, profileId) -> roster.contains(profileId),
                    population, projections,
                    (ownerUuid, profileId, remainingMs, thresholdMs) -> warningCount.incrementAndGet());
        }

        void addStored(String name, long durationMs, long cooldownMs, long[] warnings) throws Exception {
            String profile = harness.insertProfile(
                    owner, "TestDragon", CompanionLifecycleState.ROSTER_STORED.name(), "world", 1L);
            profiles.put(name, profile);
            roster.add(profile);
            insertMembership(harness, owner, family, profile);
            CommandTimedSummonPolicySnapshot policy =
                    new CommandTimedSummonPolicySnapshot(durationMs, cooldownMs, true, warnings);
            CommandTimedSummoningService.ActionResult registered = await(service.registerRosterStored(
                    new CommandTimedSummoningService.StoredRegistration(
                            owner, family, profile, "Dragon", 1L, policy, 900L)));
            assertEquals(CommandTimedSummoningService.Status.SUCCESS, registered.status());
        }

        CompletionStage<CommandTimedSummoningService.ActionResult> summon(String name, String key, long nowMs) {
            String profile = profile(name);
            CommandTimedSummonSessionRecord session;
            try {
                session = repository.findSession(owner, family, profile);
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return service.summon(new CommandTimedSummoningService.SummonRequest(
                    owner, family, profile, 1L, "TestDragon", "Dragon", 1L,
                    session.summonPolicy(), key, nowMs));
        }

        String profile(String name) { return profiles.get(name); }

        @Override public void close() { harness.close(); }
    }

    private static final class FakePopulation implements CommandTimedSummoningService.PopulationPort {
        private final int maxActive;
        private int pending;
        private int active;
        private int storedConvergences;
        private int activeRecoveries;
        private FakePopulation(int maxActive) { this.maxActive = maxActive; }

        public CompletionStage<CommandTimedSummoningService.PopulationReservation> reserveActive(
                CommandTimedSummoningService.PopulationContext context) {
            if (active + pending >= maxActive) {
                return CompletableFuture.completedFuture(new CommandTimedSummoningService.PopulationReservation(
                        false, null, "max-active-per-owner"));
            }
            pending++;
            return CompletableFuture.completedFuture(new CommandTimedSummoningService.PopulationReservation(
                    true, "population:" + context.idempotencyKey(), "reserved"));
        }
        public CommandTimedSummoningService.PopulationDecision claimActive(
                CommandTimedSummoningService.PopulationReservation reservation) {
            return CommandTimedSummoningService.PopulationDecision.accepted("claimed");
        }
        public CompletionStage<CommandTimedSummoningService.PopulationDecision> commitActive(
                CommandTimedSummoningService.PopulationReservation reservation,
                CommandTimedSummoningService.PopulationContext context) {
            pending--;
            active++;
            return accepted("active");
        }
        public CompletionStage<CommandTimedSummoningService.PopulationDecision> cancel(
                CommandTimedSummoningService.PopulationReservation reservation) {
            if (pending > 0) pending--;
            return accepted("canceled");
        }
        public CompletionStage<CommandTimedSummoningService.PopulationDecision> beginStoring(
                CommandTimedSummoningService.PopulationContext context) { return accepted("storing"); }
        public CompletionStage<CommandTimedSummoningService.PopulationDecision> commitRosterStored(
                CommandTimedSummoningService.PopulationContext context) {
            active--;
            return accepted("stored");
        }
        public CompletionStage<CommandTimedSummoningService.PopulationDecision> rollbackStoring(
                CommandTimedSummoningService.PopulationContext context) { return accepted("active"); }
        public CompletionStage<CommandTimedSummoningService.PopulationDecision> convergeRosterStored(
                CommandTimedSummoningService.PopulationContext context) {
            storedConvergences++;
            if (active > 0) active--;
            return accepted("stored-converged");
        }
        public CompletionStage<CommandTimedSummoningService.PopulationDecision> recoverActive(
                CommandTimedSummoningService.PopulationContext context,
                String populationOperationId) {
            activeRecoveries++;
            active = Math.max(1, active);
            return accepted("active-recovered");
        }
        private CompletionStage<CommandTimedSummoningService.PopulationDecision> accepted(String reason) {
            return CompletableFuture.completedFuture(
                    CommandTimedSummoningService.PopulationDecision.accepted(reason));
        }
    }

    private static final class FakeProjection implements CommandTimedSummoningService.ProjectionPort {
        private int frontPlans;
        private int inspections;
        private CompletableFuture<Void> storageStarted = new CompletableFuture<>();
        private CompletionStage<CommandTimedSummoningService.ProjectionResult> pendingStorage;
        private CommandTimedSummoningService.ProjectionEvidence recoveryEvidence =
                CommandTimedSummoningService.ProjectionEvidence.AMBIGUOUS;
        private CommandTimedSummoningService.ProjectionOutcome spawnOutcome =
                CommandTimedSummoningService.ProjectionOutcome.SUCCESS;
        private CommandTimedSummoningService.ProjectionOutcome storageOutcome =
                CommandTimedSummoningService.ProjectionOutcome.SUCCESS;
        public CompletionStage<CommandTimedSummoningService.SpawnPlan> planSpawnInFront(
                UUID ownerUuid, String profileId) {
            frontPlans++;
            return CompletableFuture.completedFuture(new CommandTimedSummoningService.SpawnPlan(
                    true, "world", 0, 0, "front-placement"));
        }
        public CompletionStage<CommandTimedSummoningService.ProjectionResult> spawn(
                CommandTimedSummoningService.SpawnPlan plan,
                CommandTimedSummoningService.PopulationContext context,
                CommandTimedSummoningService.PopulationReservation reservation,
                String sessionId) {
            return CompletableFuture.completedFuture(new CommandTimedSummoningService.ProjectionResult(
                    spawnOutcome,
                    spawnOutcome == CommandTimedSummoningService.ProjectionOutcome.SUCCESS
                            ? UUID.randomUUID() : null,
                    spawnOutcome.name().toLowerCase()));
        }
        public CompletionStage<CommandTimedSummoningService.ProjectionResult> snapshotAndDespawn(
                CommandTimedSummoningService.PopulationContext context, String sessionId) {
            storageStarted.complete(null);
            if (pendingStorage != null) return pendingStorage;
            return CompletableFuture.completedFuture(new CommandTimedSummoningService.ProjectionResult(
                    storageOutcome, context.projectionNpcUuid(), storageOutcome.name().toLowerCase()));
        }
        public CompletionStage<CommandTimedSummoningService.ProjectionEvidence> inspect(
                CommandTimedSummoningService.PopulationContext context, String sessionId) {
            inspections++;
            return CompletableFuture.completedFuture(recoveryEvidence);
        }
    }

    private static void insertMembership(HydragonPersistenceTestHarness harness,
                                         UUID owner, String family, String profile) throws Exception {
        try (Connection connection = harness.connections.openConnection()) {
            try (PreparedStatement roster = connection.prepareStatement("""
                    INSERT INTO command_family_rosters
                        (owner_uuid, command_family_id, row_revision, created_at_ms, updated_at_ms)
                    VALUES (?, ?, 1, 1, 1)
                    ON CONFLICT(owner_uuid, command_family_id) DO NOTHING
                    """)) {
                roster.setString(1, owner.toString());
                roster.setString(2, family);
                roster.executeUpdate();
            }
            try (PreparedStatement membership = connection.prepareStatement("""
                    INSERT INTO command_family_roster_memberships
                        (owner_uuid, command_family_id, profile_id, role_id, profile_revision,
                         command_state, created_at_ms, updated_at_ms)
                    VALUES (?, ?, ?, 'TestDragon', 1, 'ROSTER_STORED', 1, 1)
                    """)) {
                membership.setString(1, owner.toString());
                membership.setString(2, family);
                membership.setString(3, profile);
                membership.executeUpdate();
            }
        }
    }
}
