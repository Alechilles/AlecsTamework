package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.progression.SavedCompanionTalentDefinition;
import com.alechilles.alecstamework.companion.progression.SavedCompanionTalentRequest;
import com.alechilles.alecstamework.companion.progression.SavedCompanionTalentSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.DeathSnapshotV2Codec;
import com.alechilles.alecstamework.items.persistence.DeathSnapshotV2Payload;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupAction;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupCoordinator;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior coverage for offline generic companion talent mutations. */
class SqliteSavedCompanionTalentOperationsTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER = OwnerId.parse(
            "20000000-0000-0000-0000-000000000001");
    private static final String ROLE = "test_role";
    private static final String CONFIG = "saved-talents";

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteSavedCompanionTalentOperations operations;
    private CompanionSnapshot original;
    private Object previousTalentAssetStore;

    @BeforeEach
    void setUp() throws Exception {
        installTalentConfig();
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -1_000L).initialize();
        seedDeadProfile();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        SavedCompanionTalentDefinition.INSTANCE)),
                units, readyAdmission());
        operations = new SqliteSavedCompanionTalentOperations(
                new SqliteDatabaseOperationCoordinator(
                        engine,
                        new SqliteOperationEvidenceReader(reads),
                        new ProjectionCoordinator(
                                new SqliteProjectionGateway(reads, units),
                                ProjectionRetryPolicy.DEFAULT,
                                () -> -250L),
                        () -> -250L),
                List.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (writer != null) writer.shutdown(Duration.ofSeconds(5));
        if (reads != null) reads.shutdown(Duration.ofSeconds(5));
        staticField(TwTalentConfig.class, "ASSET_STORE")
                .set(null, previousTalentAssetStore);
        TwTalentConfig.clearRoleCache();
    }

    @Test
    void purchaseUpdatesTheFullStateRestorationSourceAndReplaysIdempotently()
            throws Exception {
        SavedCompanionTalentRequest request = request(
                SavedCompanionTalentRequest.Action.PURCHASE, "base", original);
        OperationId operationId = OperationId.parse(
                "30000000-0000-0000-0000-000000000001");

        OperationWorkflowResult first = operations.submit(
                operationId, new IdempotencyKey("saved-talent-purchase"), request)
                .completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, first.status());
        assertTrue(SqliteSavedCompanionTalentOperations.isApplied(first));

        CompanionSnapshot changed = currentSnapshot();
        TameworkTalentsComponent talents = SavedCompanionTalentSnapshot
                .decode(changed).fullState().talents();
        assertNotEquals(original.snapshotId(), changed.snapshotId());
        assertEquals(1, talents.getSpentPoints());
        assertArrayEquals(new String[] {"base"},
                talents.getPurchasedTalentIds());
        DeathSnapshotV2Payload death = new DeathSnapshotV2Codec().decode(
                changed.payloadJson());
        assertEquals(-500L, death.diedAtMs());
        assertEquals(-100L, death.respawnAvailableAtMs());

        OperationWorkflowResult replay = operations.submit(
                operationId, new IdempotencyKey("saved-talent-purchase"), request)
                .completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertTrue(SqliteSavedCompanionTalentOperations.isApplied(replay));
        assertEquals(changed.snapshotId(), currentSnapshot().snapshotId());
    }

    @Test
    void staleSecondClickCannotSpendAgainAndResetPersists() throws Exception {
        var purchase = request(SavedCompanionTalentRequest.Action.PURCHASE, "base", original);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, operations.submit(
                OperationId.create(), new IdempotencyKey("first-click"), purchase)
                .completion().toCompletableFuture().get(10, TimeUnit.SECONDS).status());
        var purchased = currentSnapshot();
        assertRejected(purchase, "30000000-0000-0000-0000-000000000008");
        assertEquals(purchased.snapshotId(), currentSnapshot().snapshotId());
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, operations.submit(
                OperationId.create(), new IdempotencyKey("reset-click"),
                request(SavedCompanionTalentRequest.Action.RESET, null, purchased))
                .completion().toCompletableFuture().get(10, TimeUnit.SECONDS).status());
        var reset = SavedCompanionTalentSnapshot.decode(currentSnapshot()).talents();
        assertEquals(0, reset.getSpentPoints());
        assertArrayEquals(new String[0], reset.getPurchasedTalentIds());
    }

    @Test
    void rejectsWrongOwnerAndStaleSnapshotEvidence() throws Exception {
        SavedCompanionTalentRequest wrongOwner = new SavedCompanionTalentRequest(
                PROFILE, OwnerId.parse("20000000-0000-0000-0000-000000000002"),
                LifecycleRevision.INITIAL, original.snapshotId(),
                original.payloadHash(), SavedCompanionTalentRequest.Action.PURCHASE,
                "base", CONFIG, 0L, -300L);
        assertRejected(wrongOwner, "30000000-0000-0000-0000-000000000002");

        SavedCompanionTalentRequest stale = new SavedCompanionTalentRequest(
                PROFILE, OWNER, LifecycleRevision.INITIAL, original.snapshotId(),
                Sha256Hash.ofUtf8("other"),
                SavedCompanionTalentRequest.Action.PURCHASE,
                "base", CONFIG, 0L, -300L);
        assertRejected(stale, "30000000-0000-0000-0000-000000000003");
        assertEquals(original.snapshotId(), currentSnapshot().snapshotId());

        OperationWorkflowResult valid = operations.submit(
                OperationId.parse("30000000-0000-0000-0000-000000000006"),
                new IdempotencyKey("saved-talent-after-denial"),
                request(SavedCompanionTalentRequest.Action.PURCHASE,
                        "base", original)
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, valid.status());
        assertTrue(SqliteSavedCompanionTalentOperations.isApplied(valid));
    }

    @Test
    void rejectsTreeRevisionChangedAfterThePageWasPresented() throws Exception {
        var purchase = request(SavedCompanionTalentRequest.Action.PURCHASE, "base", original);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, operations.submit(
                OperationId.create(), new IdempotencyKey("before-reload"), purchase)
                .completion().toCompletableFuture().get(10, TimeUnit.SECONDS).status());
        var purchased = currentSnapshot();
        var staleClick = request(SavedCompanionTalentRequest.Action.PURCHASE, "advanced", purchased);
        TwTalentConfig.resolveForRole(ROLE).setAllocationRevision(1L);
        assertRejected(staleClick, "30000000-0000-0000-0000-000000000009");
        assertEquals(purchased.snapshotId(), currentSnapshot().snapshotId());
        assertArrayEquals(new String[] {"base"}, SavedCompanionTalentSnapshot
                .decode(currentSnapshot()).talents().getPurchasedTalentIds());
    }

    @Test
    void rejectsOverspendAndUnmetPrerequisite() throws Exception {
        assertRejected(request(SavedCompanionTalentRequest.Action.PURCHASE,
                "expensive", original),
                "30000000-0000-0000-0000-000000000004");
        assertRejected(request(SavedCompanionTalentRequest.Action.PURCHASE,
                "advanced", original),
                "30000000-0000-0000-0000-000000000005");
        assertEquals(original.snapshotId(), currentSnapshot().snapshotId());
    }

    private void assertRejected(SavedCompanionTalentRequest request,
                                String operationId) throws Exception {
        OperationWorkflowResult result = operations.submit(
                OperationId.parse(operationId), new IdempotencyKey(operationId), request)
                .completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertFalse(SqliteSavedCompanionTalentOperations.isApplied(result));
        OperationWorkflowResult replay = operations.submit(
                OperationId.parse(operationId), new IdempotencyKey(operationId), request)
                .completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertFalse(SqliteSavedCompanionTalentOperations.isApplied(replay));
    }

    private SavedCompanionTalentRequest request(
            SavedCompanionTalentRequest.Action action,
            String talentId,
            CompanionSnapshot snapshot
    ) {
        return new SavedCompanionTalentRequest(PROFILE, OWNER,
                LifecycleRevision.INITIAL, snapshot.snapshotId(),
                snapshot.payloadHash(), action, talentId, CONFIG, 0L, -300L);
    }

    private PersistenceStartupCoordinator readyAdmission() {
        EnumMap<PersistenceStartupNode, PersistenceStartupAction> actions =
                new EnumMap<>(PersistenceStartupNode.class);
        for (PersistenceStartupNode node : PersistenceStartupNode.values()) {
            actions.put(node, () -> CompletableFuture.completedFuture(
                    PersistenceStartupAction.Result.COMPLETE));
        }
        PersistenceStartupCoordinator admission = new PersistenceStartupCoordinator(
                PublicPersistenceFeatureRegistry.create(), Map.copyOf(actions));
        admission.advance().toCompletableFuture().join();
        return admission;
    }

    private void seedDeadProfile() throws Exception {
        DeathSnapshotV2Payload death = DeathSnapshotV2Payload.capture(
                fullState(), -500L, -100L,
                DeathSnapshotV2Payload.DeathCauseKind.ENVIRONMENT, "Lava");
        String payload = new DeathSnapshotV2Codec().encode(death);
        original = new CompanionSnapshot(
                SnapshotId.parse("40000000-0000-0000-0000-000000000001"),
                PROFILE, TameworkSnapshotCodecs.DEATH, 2, payload,
                Sha256Hash.ofUtf8(payload), LifecycleRevision.INITIAL,
                true, -500L);
        try (var connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.identities().createProfile(new CompanionIdentity(
                    PROFILE, "Companion", ROLE, null, null, "world",
                    -1_000L, -1_000L, -1_000L, 0L));
            transaction.lifecycles().create(new CompanionLifecycle(PROFILE,
                    OWNER, LifecycleState.DEAD_REVIVABLE,
                    LifecycleLocation.none(), LifecycleRevision.INITIAL,
                    null, -500L, ReconciliationGeneration.INITIAL, null));
            transaction.snapshots().replaceCurrent(original);
            connection.commit();
        }
    }

    private CompanionSnapshot currentSnapshot() throws Exception {
        try (var connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection).findCurrent(
                    PROFILE, TameworkSnapshotCodecs.DEATH).orElseThrow();
        }
    }

    private CoopResidentStateSnapshot fullState() {
        return new CoopResidentStateSnapshot(
                UUID.fromString("50000000-0000-0000-0000-000000000001"),
                null, -1, ROLE, null, null, null, null, null, null, null,
                new TameworkLevelingComponent(null, 2, 0.0, 0.0), null, null,
                null, null, 20.0, 20.0, 1.0, -700L, null);
    }

    private void installTalentConfig() throws Exception {
        previousTalentAssetStore = staticField(TwTalentConfig.class,
                "ASSET_STORE").get(null);
        TwTalentConfig config = talentConfig();
        staticField(TwTalentConfig.class, "ASSET_STORE").set(null,
                new TestTalentAssetStore(new DefaultAssetMap<>(Map.of(
                        CONFIG, config))));
        TwTalentConfig.clearRoleCache();
    }

    private static TwTalentConfig talentConfig() throws Exception {
        Constructor<TwTalentConfig> constructor =
                TwTalentConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwTalentConfig config = constructor.newInstance();
        setField(config, "id", CONFIG);
        setField(config, "enabled", true);
        setField(config, "allocationRevision", 1L);
        setField(config, "roleIds", new String[] {ROLE});
        setField(config, "talents", new TwTalentConfig.TalentDefinition[] {
                talent("base", 1, 1, new String[0]),
                talent("advanced", 1, 1, new String[] {"base"}),
                talent("expensive", 3, 1, new String[0])
        });
        return config;
    }

    private static TwTalentConfig.TalentDefinition talent(
            String id, int cost, int minLevel, String[] prerequisites
    ) throws Exception {
        Constructor<TwTalentConfig.TalentDefinition> constructor =
                TwTalentConfig.TalentDefinition.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwTalentConfig.TalentDefinition talent = constructor.newInstance();
        setField(talent, "id", id);
        setField(talent, "pointCost", cost);
        setField(talent, "minLevel", minLevel);
        setField(talent, "requiresTalentIds", prerequisites);
        return talent;
    }

    private static Field staticField(Class<?> type, String name)
            throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class TestTalentAssetStore extends AssetStore<String,
            TwTalentConfig, DefaultAssetMap<String, TwTalentConfig>> {
        private TestTalentAssetStore(DefaultAssetMap<String, TwTalentConfig> map) {
            super(new Builder(map));
        }

        @Override protected com.hypixel.hytale.event.IEventBus getEventBus() {
            return null;
        }
        @Override public void addFileMonitor(String pack, Path path) { }
        @Override public void removeFileMonitor(Path path) { }
        @Override protected void handleRemoveOrUpdate(Set<String> removed,
                Map<String, TwTalentConfig> changed, AssetUpdateQuery query) { }

        private static final class Builder extends AssetStore.Builder<String,
                TwTalentConfig, DefaultAssetMap<String, TwTalentConfig>, Builder> {
            private final DefaultAssetMap<String, TwTalentConfig> map;
            private Builder(DefaultAssetMap<String, TwTalentConfig> map) {
                super(String.class, TwTalentConfig.class, map);
                this.map = map;
                setPath("Tamework/Talents");
                setCodec(TwTalentConfig.CODEC);
                setKeyFunction(TwTalentConfig::getId);
            }
            @Override public AssetStore<String, TwTalentConfig,
                    DefaultAssetMap<String, TwTalentConfig>> build() {
                return new TestTalentAssetStore(map);
            }
        }
    }
}
