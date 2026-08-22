package com.alechilles.alecstamework.api;

import com.alechilles.alecstamework.api.internal.BondedOnlyTameworkApi;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRegistry;
import com.alechilles.alecstamework.api.internal.TameworkApiImpl;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.api.internal.TraitEffectRegistry;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public behavior checks for the Tamework 0.11 contract surface. */
class TameworkApiV011ContractTest {
    private static final EnumSet<TameworkApiCapability> NEW_CAPABILITIES = EnumSet.of(
            TameworkApiCapability.ACTIVITY_FEED_V2,
            TameworkApiCapability.DURABLE_OUTPUT_OPERATIONS,
            TameworkApiCapability.NAMED_CAPACITY_RESERVATIONS,
            TameworkApiCapability.EXTERNAL_ADMISSION_PROVIDERS,
            TameworkApiCapability.REQUIRED_CONTENT_PROFILES
    );

    @Test
    void legacyDefaultsFailClosed() throws Exception {
        TameworkApi legacy = new LegacyApiImplementation();

        assertNewCapabilitiesAbsent(legacy);
        assertActivityFeedUnavailable(legacy);
        assertAdmissionDefaultsUnavailable(legacy.policies());
    }

    @Test
    void productionAndDegradedFacadesHoldBackNewCapabilitiesUntilReady() throws Exception {
        try (TameworkApiImpl base = newBaseApi()) {
            assertNewCapabilitiesAbsent(base);
            assertActivityFeedUnavailable(base);
            assertAdmissionDefaultsUnavailable(base.policies());
        }

        TameworkApi degraded = new BondedOnlyTameworkApi(
                BondedCompanionApi.unavailable());
        assertNewCapabilitiesAbsent(degraded);
        assertActivityFeedUnavailable(degraded);
    }

    @Test
    void unavailableActivityFacadeAcceptsSafeSubscriptions() {
        ActivityFeedApi api = ActivityFeedApi.unavailable();

        ActivityFeedStatus before = api.status("contract-consumer");
        ActivityFeedSubscription subscription = api.subscribe(
                "contract-consumer",
                ActivityFilter.forDomain(ActivityDomain.MANAGED_CARE_PRODUCTION),
                ignored -> { }
        );

        assertFalse(before.available());
        assertFalse(before.subscribed());
        assertEquals("contract-consumer", subscription.consumerId());
        subscription.close();
        subscription.close();
    }

    @Test
    void collectionIdentifiersAreCanonicalizedAtThePublicBoundary() {
        ManagedActivityView activity = activity(
                Set.of("  family:cow  "),
                Map.of("  Item_Milk  ", 2)
        );
        assertEquals(Set.of("family:cow"), activity.groupIds());
        assertEquals(Map.of("Item_Milk", 2), activity.itemQuantities());

        NeedSatisfiedActivityView need = new NeedSatisfiedActivityView(
                new ActivityHeader(UUID.randomUUID(), ActivityIds.NEED_SATISFIED, Instant.now()),
                UUID.randomUUID(),
                UUID.randomUUID(),
                " runeteria:husbandry ",
                Set.of(" family:cow "),
                " Cow_Role ",
                "runeteria:husbandry/feed",
                "hunger",
                "container",
                "Food_Wheat",
                20.0,
                40.0,
                20.0
        );
        assertEquals("runeteria:husbandry", need.profileId());
        assertEquals(Set.of("family:cow"), need.groupIds());
        assertEquals("Cow_Role", need.roleId());
        assertEquals("runeteria:husbandry/feed", need.mappedActivityId());

        PopulationAdmissionProviderRequest providerRequest =
                new PopulationAdmissionProviderRequest(
                        "provider:test",
                        1,
                        admissionRequest(),
                        "family:cow",
                        Set.of("  family:cow  ", "  group:all  "),
                        "  gate:cow  ",
                        1,
                        4L
                );
        assertEquals(
                Set.of("family:cow", "group:all"), providerRequest.groupIds());

        PopulationAdmissionProviderDecision decision =
                new PopulationAdmissionProviderDecision(
                        PopulationAdmissionProviderStatus.ALLOW,
                        "allowed",
                        Set.of(new PopulationDomainClaim("owned", 1, true, false)),
                        Map.of("  owned  ", 12),
                        8L,
                        4L
                );
        assertEquals(Map.of("owned", 12), decision.domainLimits());
    }

    @Test
    void collectionIdentifiersRejectDuplicatesAfterCanonicalization() {
        assertThrows(IllegalArgumentException.class, () -> activity(
                Set.of("family:cow", " family:cow "),
                Map.of("Item_Milk", 1)
        ));
        assertThrows(IllegalArgumentException.class, () -> activity(
                Set.of("family:cow"),
                Map.of("Item_Milk", 1, " Item_Milk ", 2)
        ));
        assertThrows(IllegalArgumentException.class, () ->
                new PopulationAdmissionProviderRequest(
                        "provider:test",
                        1,
                        admissionRequest(),
                        "family:cow",
                        Set.of("family:cow", " family:cow "),
                        "gate:cow",
                        1,
                        4L
                ));
        assertThrows(IllegalArgumentException.class, () ->
                new PopulationAdmissionProviderDecision(
                        PopulationAdmissionProviderStatus.ALLOW,
                        "allowed",
                        Set.of(),
                        Map.of("owned", 1, " owned ", 2),
                        8L,
                        4L
                ));
    }

    @Test
    void degradedFacadeReportsV2VersionWithoutAdvertisingTheUnavailableFeed() {
        TameworkApi api = new BondedOnlyTameworkApi(
                BondedCompanionApi.unavailable());

        assertEquals("0.11.0", api.getApiVersion());
        assertFalse(api.getCapabilities().contains(
                TameworkApiCapability.ACTIVITY_FEED_V2));
        assertFalse(api.activities().status("contract-consumer").available());
    }

    private static void assertNewCapabilitiesAbsent(TameworkApi api) {
        assertTrue(api.getCapabilities().stream()
                .noneMatch(NEW_CAPABILITIES::contains));
    }

    private static void assertActivityFeedUnavailable(TameworkApi api) {
        ActivityFeedApi feed = api.activities();
        ActivityFeedStatus status = feed.status("contract-consumer");
        assertFalse(status.available());
        assertFalse(status.subscribed());
        ActivityFeedSubscription subscription = feed.subscribe(
                "contract-consumer",
                ActivityFilter.forDomain(ActivityDomain.MANAGED_CARE_PRODUCTION),
                ignored -> { }
        );
        assertEquals("contract-consumer", subscription.consumerId());
        subscription.close();
        subscription.close();
    }

    private static void assertAdmissionDefaultsUnavailable(PolicyApi policy)
            throws Exception {
        assertEquals(
                PopulationAdmissionDecision.Status.UNAVAILABLE,
                policy.populationAdmissions()
                        .tryAdmitV3(admissionRequest())
                        .toCompletableFuture()
                        .join()
                        .status()
        );
        AutoCloseable registration = policy.admissionProviders().register(
                "provider:test",
                1,
                request -> CompletableFuture.completedFuture(
                        PopulationAdmissionProviderDecision.unavailable(
                                "test-unavailable")
                )
        );
        registration.close();
        registration.close();
    }

    private static TameworkApiImpl newBaseApi() {
        return new TameworkApiImpl(
                new EmptyProfiles(),
                new EmptyProfileData(),
                new EmptyDiagnostics(),
                new TameworkEventBus(null),
                null,
                new InteractionExtensionRegistry(null),
                new TraitEffectRegistry(null, null),
                new SimpleClaimsTamedDamagePolicy()
        );
    }

    private static ManagedActivityView activity(
            Set<String> groups,
            Map<String, Integer> quantities
    ) {
        return new ManagedActivityView(
                new ActivityHeader(
                        UUID.randomUUID(),
                        1L,
                        ActivityIds.FEED,
                        Instant.EPOCH
                ),
                "profile:test",
                groups,
                java.util.List.of(new ActivityParticipantView(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "profile:test",
                        "role:cow"
                )),
                "tamework:feed",
                quantities,
                java.util.List.of(),
                null,
                null
        );
    }

    private static PopulationAdmissionRequestV3 admissionRequest() {
        PopulationAdmissionRequest request = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(
                        null,
                        "provisional-profile",
                        "contract-request"
                ),
                null,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                UUID.randomUUID(),
                null,
                new PopulationAdmissionLocation("contract-world", 0, 0),
                PopulationAdmissionOperation.NEW_OWNERSHIP,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        return new PopulationAdmissionRequestV3(
                new PopulationAdmissionRequestV2(
                        request, "contract-role", "contract-world"),
                "runeteria:husbandry"
        );
    }

    private static final class LegacyApiImplementation implements TameworkApi {
        @Override public String getApiVersion() { return "0.9.0"; }
        @Override public EnumSet<TameworkApiCapability> getCapabilities() {
            return EnumSet.noneOf(TameworkApiCapability.class);
        }
        @Override public NpcProfilesApi profiles() { return null; }
        @Override public CommandLinksApi commandLinks() { return null; }
        @Override public ProgressionApi progression() { return null; }
        @Override public PolicyApi policies() { return new LegacyPolicyImplementation(); }
        @Override public InteractionExtensionApi interactionExtensions() { return null; }
        @Override public TraitEffectApi traitEffects() { return null; }
        @Override public ProfileDataApi profileData() { return null; }
        @Override public TameworkEventsApi events() { return null; }
        @Override public TameworkConfigReadApi configs() { return null; }
        @Override public DiagnosticsApi diagnostics() { return null; }
    }

    private static final class LegacyPolicyImplementation implements PolicyApi {
        @Override public Optional<OwnershipPolicyView> getOwnershipByProfileId(
                String profileId) { return Optional.empty(); }
        @Override public Optional<OwnershipPolicyView> getOwnershipByNpcUuid(
                UUID npcUuid) { return Optional.empty(); }
        @Override public boolean isOwner(String profileId, UUID playerUuid) {
            return false;
        }
        @Override public ClaimAccessDecisionView evaluateClaimAccess(
                String profileId, UUID playerUuid) { return null; }
        @Override public DamagePolicyDecisionView evaluateDamage(
                String profileId, UUID attackerPlayerUuid) { return null; }
        @Override public PopulationCapDecisionView evaluatePopulationCap(
                UUID ownerUuid) { return null; }
    }

    private static final class EmptyProfiles implements NpcProfilesApi {
        @Override public Optional<String> resolveProfileId(UUID npcUuid) {
            return Optional.empty();
        }
        @Override public Optional<NpcProfileView> getByProfileId(String profileId) {
            return Optional.empty();
        }
        @Override public Optional<NpcProfileView> getByNpcUuid(UUID npcUuid) {
            return Optional.empty();
        }
        @Override public Optional<String> getActiveSnapshot(
                String profileId, String snapshotType) { return Optional.empty(); }
        @Override public Set<String> listActiveSnapshotTypes(String profileId) {
            return Set.of();
        }
    }

    private static final class EmptyProfileData implements ProfileDataApi {
        @Override public Optional<String> get(
                String profileId, String namespace, String key) {
            return Optional.empty();
        }
        @Override public Map<String, String> list(
                String profileId, String namespace) { return Map.of(); }
        @Override public boolean put(
                String profileId, String namespace, String key, String jsonPayload) {
            return false;
        }
        @Override public boolean delete(
                String profileId, String namespace, String key) { return false; }
    }

    private static final class EmptyDiagnostics implements DiagnosticsApi {
        @Override public PersistenceDiagnosticsView getPersistenceDiagnostics() {
            return new PersistenceDiagnosticsView(
                    "test",
                    0L,
                    0L,
                    0L,
                    0L,
                    new PersistenceDiagnosticsView.QueueMetricsView(
                            0,
                            0,
                            0,
                            0L,
                            0L,
                            0L,
                            0L,
                            0.0,
                            0.0,
                            0.0,
                            null,
                            0L
                    ),
                    new PersistenceDiagnosticsView.HealthView(
                            "UNAVAILABLE", null, 0L)
            );
        }
    }
}
