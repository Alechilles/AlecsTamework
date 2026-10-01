package com.alechilles.alecstamework.api;

import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRegistry;
import com.alechilles.alecstamework.api.internal.IndexDiagnosticsApi;
import com.alechilles.alecstamework.api.internal.IndexNpcProfilesApi;
import com.alechilles.alecstamework.api.internal.IndexPopulationGroupApi;
import com.alechilles.alecstamework.api.internal.IndexProfileDataApi;
import com.alechilles.alecstamework.api.internal.IndexTameworkApi;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRegistry;
import com.alechilles.alecstamework.api.internal.PopulationGroupFixtures;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.api.internal.TraitEffectRegistry;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.companion.store.CompanionWriter;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls the real API the way the two reflective consumers do, so a renamed, removed or
 * unreachable method fails here instead of reading as "no data" in their mods.
 *
 * <p>The NPC Debug Inspector looks every method up on the object's own class
 * ({@code target.getClass().getMethod(...)}) and invokes it from another package, so a method
 * declared by a non-public implementation class is unreachable for it. Runeteria's
 * {@code HusbandryTameworkBridge} loads the {@code api} types by name and looks methods up on
 * those. This test lives outside {@code api.internal} so it has a consumer's access.</p>
 */
class ApiSurfaceCompatibilityTest {
    private static final String API_PACKAGE = "com.alechilles.alecstamework.api.";
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String GROUP = "test:flock";

    private final CompanionIndex index = new CompanionIndex(() -> 1_000L, (before, after) -> { });
    private final PopulationGroupConfigIndex groups = PopulationGroupFixtures.index(
            PopulationGroupFixtures.group("Flock", GROUP, "\"Sheep\"", 0, 0, "Global"));
    private final CompanionRecord record = CompanionRecord.builder(
                    UUID.randomUUID(), "Sheep", CompanionLocation.live("default", 1, 64, 1))
            .ownerUuid(OWNER).ownerName("Alec").currentNpcUuid(NPC)
            .toolIds(List.of("00000000-0000-0000-0000-0000000000c0")).build();
    private final String profileId = record.profileId().toString();
    private final Object api = buildApi();

    private Object buildApi() {
        index.insert(record);
        IndexNpcProfilesApi profiles = new IndexNpcProfilesApi(new CompanionQueries(index, new LoadedBodies<>()));
        return new IndexTameworkApi(
                profiles,
                new IndexProfileDataApi(index, owner -> CompletableFuture.completedFuture(null)),
                new IndexDiagnosticsApi(index, () -> new CompanionWriter.Status(0, 0, null, 0L), "Companions",
                        () -> 0L, () -> 0),
                new IndexPopulationGroupApi(index, () -> groups),
                new TameworkEventBus(null),
                null,
                new InteractionExtensionRegistry(null),
                new TraitEffectRegistry(null, profiles),
                new SimpleClaimsTamedDamagePolicy(),
                new CommandUiRegistry(),
                new CommandHudRegistry(),
                new ItemFeatureRegistry(),
                new CapturePolicyRegistry());
    }

    @AfterEach
    void closeApi() throws Exception {
        ((AutoCloseable) api).close();
    }

    // ---- NPC Debug Inspector (debug/NpcDebugTameworkApiIntegration.java) ----

    @Test
    void theInspectorReadsAProfileByItsBody() {
        Object profiles = inspector(api, "profiles");

        Object profile = present(inspector(profiles, "getByNpcUuid", types(UUID.class), NPC));
        assertEquals(profileId, present(inspector(profiles, "resolveProfileId", types(UUID.class), NPC)));
        present(inspector(profiles, "getByProfileId", types(String.class), profileId));
        assertInstanceOf(Optional.class,
                inspector(profiles, "getActiveSnapshot", types(String.class, String.class), profileId, "capture"));

        assertEquals(NPC, inspector(profile, "currentNpcUuid"));
        assertEquals(OWNER, inspector(profile, "ownerUuid"));
        assertEquals("Alec", inspector(profile, "ownerName"));
        assertEquals("Sheep", inspector(profile, "roleId"));
        assertEquals(Boolean.TRUE, inspector(profile, "tamed"));
        assertInstanceOf(Collection.class, inspector(profile, "toolIds"));
        assertInstanceOf(Collection.class, inspector(profile, "activeSnapshotTypes"));
        assertInstanceOf(Long.class, inspector(profile, "lastUpdatedAtMs"));
        resolves(profile, "displayName", "customName", "coopId", "coopSlot");
    }

    @Test
    void theInspectorReadsACommandLinkByItsBody() {
        Object link = present(inspector(inspector(api, "commandLinks"), "getByNpcUuid", types(UUID.class), NPC));

        assertEquals(profileId, inspector(link, "profileId"));
        assertEquals(NPC, inspector(link, "currentNpcUuid"));
        assertEquals(OWNER, inspector(link, "ownerUuid"));
        assertInstanceOf(Collection.class, inspector(link, "toolIds"));
        assertInstanceOf(Boolean.class, inspector(link, "hasHomePosition"));
        assertInstanceOf(Collection.class, inspector(link, "activeSnapshotTypes"));
        assertInstanceOf(Long.class, inspector(link, "lastUpdatedAtMs"));
        resolves(link, "homePosition", "lastKnownPosition");
    }

    @Test
    void theInspectorReadsOwnershipAndPolicyDecisions() {
        Object policies = inspector(api, "policies");

        Object ownership = present(inspector(policies, "getOwnershipByNpcUuid", types(UUID.class), NPC));
        assertEquals(profileId, inspector(ownership, "profileId"));
        assertEquals(OWNER, inspector(ownership, "ownerUuid"));
        assertEquals("Sheep", inspector(ownership, "roleId"));
        for (String flag : List.of("tamed", "inCoop", "blockOwnerDamage", "blockAllPlayerDamageIfOwned",
                "invulnerableIfOwned")) {
            assertInstanceOf(Boolean.class, inspector(ownership, flag), flag);
        }
        resolves(ownership, "ownerName", "coopId", "coopSlot");

        Object claim = inspector(policies, "evaluateClaimAccess", types(String.class, UUID.class), profileId, OWNER);
        assertInstanceOf(Boolean.class, inspector(claim, "available"));
        assertInstanceOf(Boolean.class, inspector(claim, "allowed"));
        assertNotNull(inspector(claim, "status"));
        resolves(claim, "reason", "claimPartyId", "claimChunkCount", "worldName", "position", "positionSource");

        Object damage = inspector(policies, "evaluateDamage", types(String.class, UUID.class), profileId, OWNER);
        assertInstanceOf(Boolean.class, inspector(damage, "allowed"));
        assertNotNull(inspector(damage, "status"));
        resolves(damage, "reason");

        Object population = inspector(policies, "evaluatePopulationCap", types(UUID.class), OWNER);
        assertInstanceOf(Boolean.class, inspector(population, "allowed"));
        assertInstanceOf(Boolean.class, inspector(population, "capEnabled"));
        resolves(population, "limit", "currentCount", "remainingHeadroom", "scope", "reason");
    }

    @Test
    void theInspectorReadsConfigs() {
        Object configs = inspector(api, "configs");

        Object global = inspector(configs, "getGlobalConfig");
        assertInstanceOf(Boolean.class, inspector(global, "enabled"));
        assertInstanceOf(Integer.class, inspector(global, "priority"));
        for (String section : List.of("ownershipProtection", "command", "population", "simpleClaims")) {
            assertNotNull(inspector(global, section), section);
        }
        for (String resolver : List.of("resolveCompanionConfigForRole", "resolveHappinessConfigForRole",
                "resolveNeedsConfigForRole", "resolveBreedingConfigForRole", "resolveTraitConfigForRole",
                "resolveInteractionConfigForRole")) {
            assertInstanceOf(Optional.class, inspector(configs, resolver, types(String.class), "Sheep"), resolver);
        }
    }

    @Test
    void theInspectorReadsTheVersionCapabilitiesAndPersistenceDiagnostics() {
        assertEquals("3.0.0", inspector(api, "getApiVersion"));
        assertTrue(((Collection<?>) inspector(api, "getCapabilities")).contains(TameworkApiCapability.DIAGNOSTICS));

        Object diagnostics = inspector(inspector(api, "diagnostics"), "getPersistenceDiagnostics");
        assertEquals("Companions", inspector(diagnostics, "databasePath"));
        for (String size : List.of("totalBytes", "sqliteBytes", "walBytes", "shmBytes")) {
            assertInstanceOf(Long.class, inspector(diagnostics, size), size);
        }
        Object queue = inspector(diagnostics, "queueMetrics");
        for (String count : List.of("queueDepth", "lastBatchSize", "maxBatchSize")) {
            assertInstanceOf(Integer.class, inspector(queue, count), count);
        }
        for (String total : List.of("batchesProcessed", "operationsProcessed", "retryAttempts", "failedBatches",
                "lastFailureAtMs")) {
            assertInstanceOf(Long.class, inspector(queue, total), total);
        }
        for (String average : List.of("averageBatchSize", "averageWriteMs", "lastBatchWriteMs")) {
            assertInstanceOf(Double.class, inspector(queue, average), average);
        }
        resolves(queue, "lastFailureReason");
        Object health = inspector(diagnostics, "health");
        assertEquals("HEALTHY", inspector(health, "status"));
        assertInstanceOf(Long.class, inspector(health, "lastFailureAtMs"));
        resolves(health, "reason");
    }

    @Test
    void theInspectorSubscribesToEventsByClassName() throws Exception {
        Object events = inspector(api, "events");

        for (String event : List.of("NpcProfileChangedEvent", "NpcCapturedEvent", "NpcDeathRecordedEvent",
                "NpcLostRecordedEvent", "ConfigReloadedEvent")) {
            Object subscription = inspector(events, "subscribe", types(Class.class, Consumer.class),
                    Class.forName(API_PACKAGE + event), (Consumer<Object>) ignored -> { });
            assertInstanceOf(AutoCloseable.class, subscription, event);
        }
    }

    // ---- Runeteria Rune_Professions (husbandry/bridge/HusbandryTameworkBridge.java) ----

    @Test
    void runeteriaReadsTheVersionCapabilitiesAndReadiness() {
        assertEquals("3.0.0", runeteria("TameworkApi", api, "getApiVersion"));
        Object capabilities = runeteria("TameworkApi", api, "getCapabilities");
        assertTrue(((Iterable<?>) capabilities).iterator().next() instanceof Enum<?>);
        assertTrue(((Collection<?>) capabilities).containsAll(Set.of(
                TameworkApiCapability.POPULATION_GROUPS,
                TameworkApiCapability.DURABLE_POPULATION_GROUP_COUNTS,
                TameworkApiCapability.DURABLE_DEPLOYABLE_POPULATION_COUNTS)));

        Object feed = runeteria("TameworkApi", api, "activities");
        Object status = runeteria("ActivityFeedApi", feed, "status", types(String.class),
                "runeteria:rune_professions_husbandry");
        assertEquals(Boolean.TRUE, runeteria("ActivityFeedStatus", status, "available"));
        assertEquals(Boolean.FALSE, runeteria("ActivityFeedStatus", status, "subscribed"));

        Object outcomes = runeteria("TameworkApi", api, "husbandryOutcomes");
        assertEquals(Boolean.TRUE, runeteria("HusbandryOutcomeApi", outcomes, "available"));
    }

    @Test
    void runeteriaReadsGroupMembershipAndDeployedCounts() {
        Object populationGroups = runeteria("TameworkApi", api, "populationGroups");

        Object count = runeteria("PopulationGroupApi", populationGroups, "getDurableDeployableCount",
                types(UUID.class, Set.class), OWNER, Set.of(GROUP));
        assertEquals(OptionalLong.of(1L), count);

        Object resolved = runeteria("PopulationGroupApi", populationGroups, "resolveForRole",
                types(String.class), "Sheep");
        Object definition = ((Iterable<?>) resolved).iterator().next();
        assertEquals(GROUP, runeteria("PopulationGroupDefinitionView", definition, "groupId"));
    }

    // ---- Served by later tasks; add their reflective names here when they land ----
    // Runeteria: requiredContentProfiles().status(String) with available, providerId and
    //   providerContractVersion; policies().admissionProviders().register(String, int, provider);
    //   activities().subscribe(String, ActivityFilter, ActivityConsumer) through a proxy;
    //   husbandryOutcomes().register(provider) and interactionExtensions().registerRequirement
    //   through proxies; the admission request unwrap request.admission().request().request().

    /** The Inspector's lookup: the method comes from the object's own class. */
    private static Object inspector(Object target, String method) {
        return inspector(target, method, types());
    }

    private static Object inspector(Object target, String method, Class<?>[] parameterTypes, Object... args) {
        assertNotNull(target, "no object to call " + method + " on");
        try {
            return target.getClass().getMethod(method, parameterTypes).invoke(target, args);
        } catch (ReflectiveOperationException unreachable) {
            throw new AssertionError(target.getClass().getName() + "." + method
                    + " cannot be called by reflection from another mod", unreachable);
        }
    }

    /** For accessors that may return null: the method must still be reachable. */
    private static void resolves(Object target, String... accessors) {
        for (String accessor : accessors) {
            inspector(target, accessor);
        }
    }

    /** Runeteria's lookup: the method comes from the public API type, loaded by name. */
    private static Object runeteria(String apiType, Object target, String method) {
        return runeteria(apiType, target, method, types());
    }

    private static Object runeteria(String apiType, Object target, String method, Class<?>[] parameterTypes,
                                    Object... args) {
        assertNotNull(target, "no object to call " + method + " on");
        try {
            return Class.forName(API_PACKAGE + apiType).getMethod(method, parameterTypes).invoke(target, args);
        } catch (ReflectiveOperationException unreachable) {
            throw new AssertionError(apiType + "." + method + " cannot be called by reflection", unreachable);
        }
    }

    private static Object present(Object optional) {
        return assertInstanceOf(Optional.class, optional).orElseThrow();
    }

    private static Class<?>[] types(Class<?>... types) {
        return types;
    }
}
