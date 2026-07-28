package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionLeaseView;
import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionAvailability;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.npc.components
        .TameworkProjectionIdentityComponent;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exact-authority coverage for bonded Horn command recipients. */
class BondedCompanionCommandRecipientSourceTest {
    private static final UUID OWNER = uuid(1);
    private static final UUID OTHER_OWNER = uuid(2);
    private static final UUID LIVE = uuid(11);
    private static final UUID OTHER_LIVE = uuid(12);
    private static final String ROSTER = "hydragon:bonded";
    private static final String WORLD = "world-a";
    private static final long NOW = -1_000L;

    private TestEntityComponentStore store;

    @BeforeEach
    void setUp() {
        TestEntityStore entityStore = new TestEntityStore(null);
        store = new TestEntityComponentStore(entityStore);
        entityStore.store = store;
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void selectsOnlyExactActiveOwnerRosterWorldLeaseWithoutGenericLinks() {
        BondedCompanionProfileView selected = active(
                "profile-selected", OWNER, ROSTER, LIVE, WORLD,
                "lease-selected", 0L);
        List<BondedCompanionProfileView> profiles = List.of(
                selected,
                stored("stored", OWNER, ROSTER),
                dead("dead", OWNER, ROSTER),
                active("wrong-owner", OTHER_OWNER, ROSTER, uuid(21), WORLD,
                        "lease-owner", 0L),
                active("wrong-roster", OWNER, "other:roster", uuid(22), WORLD,
                        "lease-roster", 0L),
                active("wrong-world", OWNER, ROSTER, uuid(23), "world-b",
                        "lease-world", 0L));
        BondedCompanionCommandRecipientSource source = source(profiles);
        var projection = projection(selected, new Vector3d(3, 0, 0), "NordicDrake");

        List<Candidate> recipients = source.select(
                request(config(), 100D, 25),
                npcUuid -> LIVE.equals(npcUuid) ? projection : null);

        assertEquals(1, recipients.size());
        assertEquals("profile-selected", recipients.getFirst().profileId);
        assertEquals(LIVE, recipients.getFirst().npc.getUuid());
    }

    @Test
    void rejectsExpiredAmbiguousAndMarkerMismatchedAuthority() {
        BondedCompanionProfileView expired = active(
                "expired", OWNER, ROSTER, LIVE, WORLD,
                "lease-expired", NOW);
        BondedCompanionCommandRecipientSource expiredSource = source(List.of(expired));
        assertTrue(expiredSource.select(
                request(config(), -1D, 25),
                ignored -> projection(expired, new Vector3d(), "NordicDrake")
        ).isEmpty());

        BondedCompanionProfileView first = active(
                "first", OWNER, ROSTER, LIVE, WORLD, "lease-first", 0L);
        BondedCompanionProfileView duplicate = active(
                "duplicate", OWNER, ROSTER, LIVE, WORLD,
                "lease-duplicate", 0L);
        assertTrue(source(List.of(first, duplicate)).select(
                request(config(), -1D, 25),
                ignored -> projection(first, new Vector3d(), "NordicDrake")
        ).isEmpty());

        BondedCompanionCommandRecipientSource exactSource = source(List.of(first));
        assertTrue(exactSource.select(
                request(config(), -1D, 25),
                ignored -> projection(first, new Vector3d(), "NordicDrake",
                        TameworkProjectionIdentityComponent.bondedCompanion(
                                first.profileId(), "wrong-token"))
        ).isEmpty());
        assertTrue(exactSource.select(
                request(config(), -1D, 25),
                ignored -> projection(first, new Vector3d(), "NordicDrake",
                        new TameworkProjectionIdentityComponent(
                                first.profileId(), "lease-first",
                                TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                                null, null, 0L))
        ).isEmpty());
        assertTrue(exactSource.select(
                request(config(), -1D, 25),
                ignored -> projection(first, new Vector3d(), "NordicDrake",
                        TameworkProjectionIdentityComponent.bondedCompanion(
                                "wrong-profile", "lease-first"))
        ).isEmpty());
        var wrongUuid = projection(first, new Vector3d(), "NordicDrake");
        wrongUuid.npc().setLegacyUUID(uuid(99));
        assertTrue(exactSource.select(
                request(config(), -1D, 25), ignored -> wrongUuid).isEmpty());

        BondedCompanionProfileView missingUuid = active(
                "missing-uuid", OWNER, ROSTER, null, WORLD,
                "lease-missing", 0L);
        assertTrue(source(List.of(missingUuid)).select(
                request(config(), -1D, 25), ignored -> wrongUuid).isEmpty());
    }

    /** Regression: one durable lease must never authorize two physical projections. */
    @Test
    void rejectsExpectedProjectionWhenAnotherEntityCarriesTheSameExactMarker() {
        BondedCompanionProfileView profile = active(
                "profile-duplicate", OWNER, ROSTER, LIVE, WORLD,
                "lease-duplicate", 0L);
        var expected = projection(profile, new Vector3d(), "NordicDrake");
        var duplicate = projection(profile, new Vector3d(2, 0, 0), "NordicDrake");
        duplicate.npc().setLegacyUUID(OTHER_LIVE);
        List<BondedCompanionCommandRecipientSource.LoadedProjection> physical =
                List.of(expected, duplicate);

        List<Candidate> recipients = source(List.of(profile)).select(
                request(config(), -1D, 25),
                uuid -> LIVE.equals(uuid) ? expected : null,
                (profileId, leaseToken) -> physical.stream()
                        .filter(projection -> projection.marker().matches(
                                TameworkProjectionIdentityComponent
                                        .KIND_BONDED_COMPANION,
                                leaseToken, profileId))
                        .count() == 1L);

        assertTrue(recipients.isEmpty());
    }

    @Test
    void appliesRoleRadiusMaxTargetsAndStableOrderingAfterAuthority() {
        BondedCompanionProfileView near = active(
                "profile-b", OWNER, ROSTER, LIVE, WORLD, "lease-b", 0L);
        BondedCompanionProfileView tied = active(
                "profile-a", OWNER, ROSTER, OTHER_LIVE, WORLD,
                "lease-a", 0L);
        BondedCompanionProfileView denied = active(
                "profile-denied", OWNER, ROSTER, uuid(13), WORLD,
                "lease-denied", 0L);
        BondedCompanionProfileView far = active(
                "profile-far", OWNER, ROSTER, uuid(14), WORLD,
                "lease-far", 0L);
        Map<UUID, BondedCompanionCommandRecipientSource.LoadedProjection> live = Map.of(
                LIVE, projection(near, new Vector3d(4, 0, 0), "NordicDrake"),
                OTHER_LIVE, projection(tied, new Vector3d(-4, 0, 0), "NordicDrake"),
                uuid(13), projection(denied, new Vector3d(1, 0, 0), "Chicken"),
                uuid(14), projection(far, new Vector3d(20, 0, 0), "NordicDrake"));
        BondedCompanionCommandRecipientSource source = source(
                List.of(near, tied, denied, far));

        List<Candidate> recipients = source.select(
                request(config("NordicDrake"), 25D, 1), live::get);

        assertEquals(1, recipients.size());
        assertEquals("profile-a", recipients.getFirst().profileId);
    }

    @Test
    void productionApiSourceFailsClosedForUnavailableFailedOrPendingReads() {
        var projection = projection(active(
                "profile", OWNER, ROSTER, LIVE, WORLD, "lease", 0L),
                new Vector3d(), "NordicDrake");
        assertTrue(BondedCompanionCommandRecipientSource.production(
                BondedCompanionApi::unavailable,
                new CommandLinkPolicyService()).select(
                request(config(), -1D, 25), ignored -> projection).isEmpty());

        CompletableFuture<BondedCompanionResult<List<BondedCompanionProfileView>>>
                pending = new CompletableFuture<>();
        assertTrue(BondedCompanionCommandRecipientSource.production(
                () -> api(pending), new CommandLinkPolicyService()).select(
                request(config(), -1D, 25), ignored -> projection).isEmpty());

        CompletableFuture<BondedCompanionResult<List<BondedCompanionProfileView>>>
                failed = CompletableFuture.failedFuture(
                        new IllegalStateException("database unavailable"));
        assertTrue(BondedCompanionCommandRecipientSource.production(
                () -> api(failed), new CommandLinkPolicyService()).select(
                request(config(), -1D, 25), ignored -> projection).isEmpty());

        BondedCompanionProfileView profile = active(
                "profile", OWNER, ROSTER, LIVE, WORLD, "lease", 0L);
        CompletableFuture<BondedCompanionResult<List<BondedCompanionProfileView>>>
                successful = CompletableFuture.completedFuture(
                        new BondedCompanionResult<>(
                                BondedCompanionResultCode.SUCCESS,
                                List.of(profile), null));
        BondedCompanionCommandRecipientSource production =
                BondedCompanionCommandRecipientSource.production(
                        () -> api(successful), new CommandLinkPolicyService());
        assertEquals(1, production.select(
                request(config(), -1D, 25), ignored -> projection).size());
        assertTrue(production.select(
                request(config(), -1D, 25), ignored -> {
                    throw new IllegalStateException("world read failed");
                }).isEmpty());
    }

    private BondedCompanionCommandRecipientSource source(
            List<BondedCompanionProfileView> profiles) {
        return new BondedCompanionCommandRecipientSource(
                (owner, roster) -> profiles,
                new CommandLinkPolicyService(),
                () -> NOW);
    }

    private BondedCompanionCommandRecipientSource.Request request(
            TwCommandItemConfig config, double radiusSq, int maxTargets) {
        return new BondedCompanionCommandRecipientSource.Request(
                OWNER, ROSTER, WORLD, config, new Vector3d(), radiusSq,
                maxTargets);
    }

    private BondedCompanionCommandRecipientSource.LoadedProjection projection(
            BondedCompanionProfileView profile, Vector3d position,
            String roleId) {
        return projection(profile, position, roleId,
                TameworkProjectionIdentityComponent.bondedCompanion(
                        profile.profileId(), profile.activeLease().leaseToken()));
    }

    private BondedCompanionCommandRecipientSource.LoadedProjection projection(
            BondedCompanionProfileView profile, Vector3d position,
            String roleId, TameworkProjectionIdentityComponent marker) {
        Ref<EntityStore> ref = store.createReference();
        NPCEntity npc = new NPCEntity();
        npc.setLegacyUUID(profile.activeLease().liveNpcUuid());
        return new BondedCompanionCommandRecipientSource.LoadedProjection(
                ref, npc, marker, position, roleId);
    }

    private static BondedCompanionProfileView active(
            String profileId, UUID owner, String roster, UUID liveUuid,
            String world, String token, long expiresAt) {
        return profile(profileId, owner, roster, BondedCompanionStateView.ACTIVE,
                new BondedCompanionLeaseView(
                        token, liveUuid, world, -2_000L, expiresAt));
    }

    private static BondedCompanionProfileView stored(
            String profileId, UUID owner, String roster) {
        return profile(profileId, owner, roster,
                BondedCompanionStateView.STORED, null);
    }

    private static BondedCompanionProfileView dead(
            String profileId, UUID owner, String roster) {
        return profile(profileId, owner, roster,
                BondedCompanionStateView.DEAD, null);
    }

    private static BondedCompanionProfileView profile(
            String profileId, UUID owner, String roster,
            BondedCompanionStateView state, BondedCompanionLeaseView lease) {
        return new BondedCompanionProfileView(
                profileId, owner, roster, "dragons", "NordicDrake",
                profileId, "Dragon", null, 1L, state,
                state == BondedCompanionStateView.STORED,
                state == BondedCompanionStateView.ACTIVE,
                state == BondedCompanionStateView.DEAD,
                Map.of(), lease, 0L, null);
    }

    private static TwCommandItemConfig config(String... roles) {
        String allowed = roles.length == 0 ? "" : """
                ,"AllowedRoles":{"Mode":"Allowlist","Allowlist":["%s"]}
                """.formatted(roles[0]);
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("""
                {
                  "RosterStorage":"BondedCompanions",
                  "BondedRosterId":"hydragon:bonded"%s
                }
                """.formatted(allowed)), new ExtraInfo());
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static BondedCompanionApi api(
            CompletableFuture<BondedCompanionResult<
                    List<BondedCompanionProfileView>>> listed) {
        return (BondedCompanionApi) Proxy.newProxyInstance(
                BondedCompanionApi.class.getClassLoader(),
                new Class<?>[]{BondedCompanionApi.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "availability" -> BondedCompanionAvailability.availableNow();
                    case "list" -> listed;
                    case "subscribe" -> (AutoCloseable) () -> { };
                    default -> CompletableFuture.completedFuture(
                            new BondedCompanionResult<>(
                                    BondedCompanionResultCode.UNAVAILABLE,
                                    null, "not-used"));
                });
    }

    private static final class TestEntityStore extends EntityStore {
        private TestEntityComponentStore store;

        private TestEntityStore(World world) {
            super(world);
        }

        @Override
        public TestEntityComponentStore getStore() {
            return store;
        }
    }
}
