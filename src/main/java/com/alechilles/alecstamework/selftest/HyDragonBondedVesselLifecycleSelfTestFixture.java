package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.api.BondedVesselMode;
import com.alechilles.alecstamework.api.BondedVesselOperationResult;
import com.alechilles.alecstamework.api.BondedVesselProjectionStatus;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationView;
import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselTransition;
import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.api.BondedVesselTransitionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import com.alechilles.alecstamework.vessels.BondedVesselCoordinator;
import com.alechilles.alecstamework.vessels.BondedVesselEvidenceAuthority;
import com.alechilles.alecstamework.vessels.BondedVesselMutationAuthority;
import com.alechilles.alecstamework.vessels.BondedVesselTransitionPlanner;
import com.alechilles.alecstamework.vessels.SqliteBondedVesselJournal;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselInitialBindingService;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselLifecycleObserver;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Disposable SQLite fixtures for the complete bonded-vessel lifecycle. The database is created
 * under a fresh temporary directory and never reads or writes the live Tamework database,
 * inventory, profile store, player, or world.
 */
final class HyDragonBondedVesselLifecycleSelfTestFixture {
    private static final UUID OWNER_ID = UUID.fromString("81000000-0000-0000-0000-000000000008");
    private static final UUID BINDING_ID = UUID.fromString("82000000-0000-0000-0000-000000000008");
    private static final UUID ACTIVE_NPC_ID = UUID.fromString("83000000-0000-0000-0000-000000000008");
    private static final UUID LOST_BINDING_ID = UUID.fromString("84000000-0000-0000-0000-000000000008");
    private static final UUID LOST_NPC_ID = UUID.fromString("85000000-0000-0000-0000-000000000008");
    private static final String PROFILE_ID = "self-test-vessel-profile";
    private static final String LOST_PROFILE_ID = "self-test-lost-profile";
    private static final long NOW_MS = 90_000L;
    private static final Gson GSON = new Gson();
    private static final SpawnerVesselConfigView CONFIG = new SpawnerVesselConfigView(
            "HyDragon_SelfTest_Vessel", 1L, BondedVesselMode.BONDED,
            "HyDragon_SelfTest_Stone_Empty", "HyDragon_SelfTest_Stone_Stored",
            "HyDragon_SelfTest_Stone_Active", "HyDragon_SelfTest_Stone_Dead",
            "HyDragon_SelfTest_Stone_Lost", null, 0L, 12.0D,
            null, null, true, false);

    private HyDragonBondedVesselLifecycleSelfTestFixture() {
    }

    static List<ApiSelfTestAssertion> run() {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        Path temporaryRoot = null;
        try {
            temporaryRoot = Files.createTempDirectory("tamework-hydragon-selftest-");
            try (IsolatedDatabase database = new IsolatedDatabase(
                    temporaryRoot.resolve("bonded-vessels.sqlite"))) {
                database.insertProfile(PROFILE_ID, OWNER_ID);
                database.insertProfile(LOST_PROFILE_ID, OWNER_ID);
                Scenario scenario = new Scenario(database.repository);
                scenario.exerciseBindingAndTransitions(assertions);
                scenario.exerciseDeathAndLoss(assertions);
            }
        } catch (Exception failure) {
            assertions.add(new ApiSelfTestAssertion(
                    "isolated bonded vessel lifecycle fixtures execute",
                    false,
                    failure.getClass().getSimpleName() + ": isolated-fixture-failed"));
        } finally {
            deleteTemporaryRoot(temporaryRoot);
        }
        return List.copyOf(assertions);
    }

    private static final class Scenario {
        private final BondedVesselRepository repository;
        private final AtomicLong clock = new AtomicLong(NOW_MS);
        private final AtomicInteger applyCalls = new AtomicInteger();
        private final List<Object> events = new ArrayList<>();
        private final MutableEvidence evidence = new MutableEvidence();

        private Scenario(BondedVesselRepository repository) {
            this.repository = repository;
        }

        private void exerciseBindingAndTransitions(List<ApiSelfTestAssertion> assertions)
                throws Exception {
            AtomicReference<BondedVesselInitialBindingService.SourceStatus> sourceStatus =
                    new AtomicReference<>(BondedVesselInitialBindingService.SourceStatus.INDETERMINATE);
            BondedVesselInitialBindingService.Request request = initialRequest(
                    PROFILE_ID, BINDING_ID, "primary");
            BondedVesselInitialBindingService firstRuntime = bindingService(sourceStatus);
            BondedVesselInitialBindingService.Result interrupted = firstRuntime.bind(request)
                    .toCompletableFuture().join();
            sourceStatus.set(BondedVesselInitialBindingService.SourceStatus.ALREADY_REPLACED);
            BondedVesselInitialBindingService.Result recovered = bindingService(sourceStatus)
                    .recover(request).toCompletableFuture().join();
            BondedVesselInitialBindingService.Result duplicate = bindingService(sourceStatus)
                    .bind(request).toCompletableFuture().join();
            BondedVesselBindingRecord bound = repository.findBinding(BINDING_ID.toString());
            assertions.add(new ApiSelfTestAssertion(
                    "isolated bonded vessel bind recovers one generation-one authority",
                    interrupted.status() == BondedVesselInitialBindingService.Status.INDETERMINATE
                            && recovered.status() == BondedVesselInitialBindingService.Status.COMMITTED
                            && duplicate.status() == BondedVesselInitialBindingService.Status.COMMITTED
                            && bound != null
                            && bound.generation() == 1L
                            && bound.lifecycleState() == BondedVesselBindingRecord.LifecycleState.STORED
                            && bound.activeOperationId() == null,
                    "interrupted=" + interrupted.status() + " recovered=" + recovered.status()
                            + " duplicate=" + duplicate.status() + " generation="
                            + (bound == null ? "missing" : bound.generation())));

            BondedVesselCoordinator coordinator = coordinator(ACTIVE_NPC_ID);
            BondedVesselOperationResult summoned = transition(
                    coordinator, PROFILE_ID, "summon-1", BondedVesselTransition.SUMMON);
            BondedVesselOperationResult stored = transition(
                    coordinator, PROFILE_ID, "store-1", BondedVesselTransition.STORE);
            BondedVesselBindingRecord storedBinding = repository.findBindingByProfile(PROFILE_ID);
            assertions.add(new ApiSelfTestAssertion(
                    "isolated bonded vessel summons and stores through one journal",
                    summoned.status() == BondedVesselOperationResult.Status.COMMITTED
                            && stored.status() == BondedVesselOperationResult.Status.COMMITTED
                            && storedBinding.generation() == 3L
                            && storedBinding.lifecycleState()
                            == BondedVesselBindingRecord.LifecycleState.STORED
                            && storedBinding.activeNpcUuid() == null,
                    "summon=" + summoned.status() + " store=" + stored.status()
                            + " generation=" + storedBinding.generation()));

            evidence.finalization.set(BondedVesselEvidenceAuthority.FinalizationStatus.INDETERMINATE);
            BondedVesselOperationResult applied = transition(
                    coordinator, PROFILE_ID, "summon-restart", BondedVesselTransition.SUMMON);
            evidence.finalization.set(BondedVesselEvidenceAuthority.FinalizationStatus.ALREADY_FINALIZED);
            BondedVesselCoordinator.RecoveryReport report = coordinator(ACTIVE_NPC_ID)
                    .recoverPending().toCompletableFuture().join();
            BondedVesselBindingRecord active = repository.findBindingByProfile(PROFILE_ID);
            assertions.add(new ApiSelfTestAssertion(
                    "isolated bonded vessel restart closes applied transition without reapplying",
                    applied.status() == BondedVesselOperationResult.Status.APPLIED
                            && report.scanned() == 1
                            && report.committed() == 1
                            && report.failed() == 0
                            && applyCalls.get() == 3
                            && active.lifecycleState()
                            == BondedVesselBindingRecord.LifecycleState.ACTIVE
                            && ACTIVE_NPC_ID.equals(active.activeNpcUuid()),
                    "applied=" + applied.status() + " recovered=" + report.committed()
                            + "/" + report.scanned() + " applyCalls=" + applyCalls.get()));
        }

        private void exerciseDeathAndLoss(List<ApiSelfTestAssertion> assertions) throws Exception {
            BondedVesselLifecycleObserver deathObserver = lifecycleObserver(evidence);
            BondedVesselBindingRecord active = repository.findBindingByProfile(PROFILE_ID);
            evidence.finalization.set(BondedVesselEvidenceAuthority.FinalizationStatus.INDETERMINATE);
            BondedVesselLifecycleObserver.Observation death =
                    new BondedVesselLifecycleObserver.Observation(
                            PROFILE_ID, ACTIVE_NPC_ID, active.expectedProfileRevision() + 1L,
                            BondedVesselState.DEAD, "self-test-death", "population-death");
            BondedVesselLifecycleObserver.Result firstDeath = deathObserver.observe(death)
                    .toCompletableFuture().join();
            BondedVesselLifecycleObserver.Result duplicateDeath = deathObserver.observe(death)
                    .toCompletableFuture().join();
            BondedVesselBindingRecord dead = repository.findBindingByProfile(PROFILE_ID);
            assertions.add(new ApiSelfTestAssertion(
                    "isolated bonded vessel death is duplicate-safe with offline item",
                    firstDeath.status() == BondedVesselLifecycleObserver.Status.COMMITTED
                            && duplicateDeath.status() == BondedVesselLifecycleObserver.Status.IDEMPOTENT
                            && dead.lifecycleState() == BondedVesselBindingRecord.LifecycleState.DEAD
                            && dead.itemProjectionStatus()
                            == BondedVesselBindingRecord.ItemProjectionStatus.MISSING
                            && dead.activeNpcUuid() == null,
                    "first=" + firstDeath.status() + " duplicate=" + duplicateDeath.status()
                            + " projection=" + dead.itemProjectionStatus()));

            bindImmediately(LOST_PROFILE_ID, LOST_BINDING_ID, "lost");
            BondedVesselCoordinator lostCoordinator = coordinator(LOST_NPC_ID);
            transition(lostCoordinator, LOST_PROFILE_ID, "summon-lost", BondedVesselTransition.SUMMON);
            BondedVesselBindingRecord activeLost = repository.findBindingByProfile(LOST_PROFILE_ID);
            evidence.finalization.set(BondedVesselEvidenceAuthority.FinalizationStatus.FINALIZED);
            BondedVesselLifecycleObserver.Result lost = lifecycleObserver(evidence).observe(
                    new BondedVesselLifecycleObserver.Observation(
                            LOST_PROFILE_ID, LOST_NPC_ID,
                            activeLost.expectedProfileRevision() + 1L,
                            BondedVesselState.LOST, "self-test-lost", "population-lost"))
                    .toCompletableFuture().join();
            BondedVesselBindingRecord lostBinding = repository.findBindingByProfile(LOST_PROFILE_ID);
            assertions.add(new ApiSelfTestAssertion(
                    "isolated bonded vessel lost transition preserves exact item evidence",
                    lost.status() == BondedVesselLifecycleObserver.Status.COMMITTED
                            && lostBinding.lifecycleState()
                            == BondedVesselBindingRecord.LifecycleState.LOST
                            && lostBinding.itemProjectionStatus()
                            == BondedVesselBindingRecord.ItemProjectionStatus.PRESENT
                            && lostBinding.activeNpcUuid() == null,
                    "status=" + lost.status() + " projection="
                            + lostBinding.itemProjectionStatus()));
        }

        private BondedVesselInitialBindingService bindingService(
                AtomicReference<BondedVesselInitialBindingService.SourceStatus> sourceStatus) {
            return new BondedVesselInitialBindingService(
                    repository,
                    request -> CompletableFuture.completedFuture(
                            new BondedVesselInitialBindingService.SourceFinalization(
                                    sourceStatus.get(), "self-test-source-state")),
                    events::add,
                    Runnable::run,
                    clock::getAndIncrement);
        }

        private void bindImmediately(String profileId, UUID bindingId, String key) {
            AtomicReference<BondedVesselInitialBindingService.SourceStatus> source =
                    new AtomicReference<>(BondedVesselInitialBindingService.SourceStatus.REPLACED);
            BondedVesselInitialBindingService.Result result = bindingService(source)
                    .bind(initialRequest(profileId, bindingId, key)).toCompletableFuture().join();
            if (result.status() != BondedVesselInitialBindingService.Status.COMMITTED) {
                throw new IllegalStateException("self-test initial binding failed: " + result.reason());
            }
        }

        private BondedVesselCoordinator coordinator(UUID activeNpcId) {
            BondedVesselTransitionPlanner planner = (binding, request, now) -> {
                boolean summon = request.transition() == BondedVesselTransition.SUMMON;
                return new BondedVesselTransitionPlanner.Plan(
                        summon ? BondedVesselState.ACTIVE : BondedVesselState.STORED,
                        BondedVesselProjectionStatus.PRESENT,
                        summon ? CONFIG.activeItemId() : CONFIG.storedItemId(),
                        "self-test-replacement-" + request.idempotencyKey(),
                        0L,
                        "{\"fixture\":true}");
            };
            BondedVesselMutationAuthority mutation = (operation, binding, recovery) -> {
                applyCalls.incrementAndGet();
                boolean summon = operation.action() == BondedVesselOperationRecord.Action.SUMMON;
                BondedVesselSourceItemEvidence itemEvidence = new BondedVesselSourceItemEvidence(
                        operation.targetItemId(), "self-test-holder", "self-test-container", 1,
                        operation.candidateGeneration(), operation.replacementFingerprint());
                return CompletableFuture.completedFuture(new BondedVesselMutationAuthority.ApplyOutcome(
                        BondedVesselMutationAuthority.Status.APPLIED,
                        "self-test-transition-applied",
                        operation.expectedProfileRevision() + 1L,
                        summon ? activeNpcId : null,
                        summon ? new BondedVesselBindingRecord.PhysicalLocation(
                                "self-test-world", 0, 0) : null,
                        GSON.toJson(itemEvidence)));
            };
            return new BondedVesselCoordinator(
                    new SqliteBondedVesselJournal(repository), planner, evidence, mutation,
                    events::add, Runnable::run, clock::getAndIncrement,
                    () -> 1_000_000L, 30_000L, 16);
        }

        private BondedVesselOperationResult transition(
                BondedVesselCoordinator coordinator,
                String profileId,
                String key,
                BondedVesselTransition transition) throws Exception {
            BondedVesselBindingRecord binding = repository.findBindingByProfile(profileId);
            BondedVesselSourceItemEvidence source = GSON.fromJson(
                    binding.itemEvidenceJson(), BondedVesselSourceItemEvidence.class);
            BondedVesselTransitionContext context = new BondedVesselTransitionContext(
                    binding.lastItemId(), source.holderEvidenceId(), source.containerPath(),
                    source.inventorySlot(), source.inventoryRevision(), source.itemFingerprint(),
                    transition == BondedVesselTransition.STORE ? binding.activeNpcUuid() : null,
                    transition == BondedVesselTransition.SUMMON
                            ? new PopulationAdmissionLocation("self-test-world", 0, 0) : null);
            BondedVesselTransitionRequest request = new BondedVesselTransitionRequest(
                    "tamework-self-test", key, OWNER_ID,
                    UUID.fromString(binding.bindingId()), binding.generation(),
                    binding.expectedProfileRevision(), transition, context);
            BondedVesselOperationResult prepared = coordinator.prepareTransition(request)
                    .toCompletableFuture().join();
            if (prepared.status() != BondedVesselOperationResult.Status.RESERVED
                    || coordinator.claimForApply(prepared.token()).status()
                    != BondedVesselOperationResult.Status.APPLYING) {
                return prepared;
            }
            return coordinator.commit(prepared.token()).toCompletableFuture().join();
        }

        private BondedVesselLifecycleObserver lifecycleObserver(
                BondedVesselEvidenceAuthority selectedEvidence) {
            return new BondedVesselLifecycleObserver(
                    repository,
                    (configId, revision) -> CONFIG.configId().equals(configId)
                            && CONFIG.configRevision() == revision
                            ? Optional.of(CONFIG) : Optional.empty(),
                    selectedEvidence,
                    events::add,
                    Runnable::run,
                    clock::getAndIncrement);
        }
    }

    private static BondedVesselInitialBindingService.Request initialRequest(
            String profileId, UUID bindingId, String key) {
        UUID operationId = UUID.nameUUIDFromBytes(
                ("tamework-self-test-initial:" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BondedVesselSourceItemEvidence targetEvidence = new BondedVesselSourceItemEvidence(
                CONFIG.storedItemId(), "self-test-holder", "self-test-container", 1,
                1L, "self-test-stored-" + key);
        return new BondedVesselInitialBindingService.Request(
                operationId, bindingId, "tamework-self-test", "initial-bind:" + key,
                null, profileId, OWNER_ID, 1L, CONFIG.configId(), CONFIG.configRevision(),
                CONFIG.emptyItemId(), CONFIG.storedItemId(), "self-test-empty-" + key,
                targetEvidence.itemFingerprint(), "{\"fixture\":true}",
                GSON.toJson(targetEvidence), "{\"mode\":\"BONDED\"}", null);
    }

    private static final class MutableEvidence implements BondedVesselEvidenceAuthority {
        private final AtomicReference<FinalizationStatus> finalization =
                new AtomicReference<>(FinalizationStatus.FINALIZED);

        @Override
        public CompletionStage<SourceObservation> observe(BondedVesselTransitionContext expected) {
            return CompletableFuture.completedFuture(new SourceObservation(
                    Status.EXACT, "self-test-source-exact", expected.sourceHolderEvidenceId(),
                    expected.sourceContainerPath(), expected.sourceInventorySlot(),
                    expected.sourceInventoryRevision(), expected.sourceItemId(),
                    expected.sourceItemFingerprint()));
        }

        @Override
        public CompletionStage<SourceFinalization> finalizeSource(
                BondedVesselOperationRecord operation,
                BondedVesselTransitionContext expected) {
            BondedVesselSourceItemEvidence replacement = new BondedVesselSourceItemEvidence(
                    operation.targetItemId(), expected.sourceHolderEvidenceId(),
                    expected.sourceContainerPath(), expected.sourceInventorySlot(),
                    operation.candidateGeneration(), operation.replacementFingerprint());
            return CompletableFuture.completedFuture(new SourceFinalization(
                    finalization.get(), "self-test-source-finalized",
                    operation.replacementFingerprint(), GSON.toJson(replacement)));
        }

        @Override
        public BondedVesselProjectionValidationView validateProjection(
                BondedVesselBindingRecord binding,
                BondedVesselProjectionValidationRequest request) {
            return BondedVesselProjectionValidationView.unavailable(request.bindingId());
        }
    }

    private static final class IsolatedDatabase implements AutoCloseable {
        private final SqliteConnectionManager connections;
        private final PersistenceWriteQueue queue;
        private final BondedVesselRepository repository;

        private IsolatedDatabase(Path databasePath) throws Exception {
            connections = new SqliteConnectionManager(databasePath);
            try (Connection connection = connections.openConnection()) {
                connection.setAutoCommit(false);
                new SqliteSchemaMigrator().migrate(connection);
                connection.commit();
            }
            queue = new PersistenceWriteQueue(connections, new PersistenceHealthService(), null);
            repository = new BondedVesselRepository(connections, queue);
        }

        private void insertProfile(String profileId, UUID ownerUuid) throws Exception {
            try (Connection connection = connections.openConnection();
                 PreparedStatement profile = connection.prepareStatement("""
                         INSERT INTO npc_profiles (
                             profile_id, owner_uuid, role_id, last_world_name,
                             created_at_ms, updated_at_ms, last_active_at_ms
                         ) VALUES (?, ?, 'HyDragon_SelfTest_Dragon', 'self-test-world', 1, 1, 1)
                         """);
                 PreparedStatement population = connection.prepareStatement("""
                         INSERT INTO companion_population_state (
                             profile_id, ownership_world_name, lifecycle_state,
                             revision, source, created_at_ms, updated_at_ms
                         ) VALUES (?, 'self-test-world', 'CAPTURED', 1, 'self-test', 1, 1)
                         """)) {
                profile.setString(1, profileId);
                profile.setString(2, ownerUuid.toString());
                profile.executeUpdate();
                population.setString(1, profileId);
                population.executeUpdate();
            }
        }

        @Override
        public void close() {
            queue.close();
        }
    }

    private static void deleteTemporaryRoot(Path root) {
        if (root == null || root.getFileName() == null
                || !root.getFileName().toString().startsWith("tamework-hydragon-selftest-")) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            root.toFile().deleteOnExit();
        }
    }
}
