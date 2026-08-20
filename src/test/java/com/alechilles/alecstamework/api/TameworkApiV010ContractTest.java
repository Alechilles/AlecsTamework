package com.alechilles.alecstamework.api;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-only compatibility checks for the Tamework 0.10 contract surface. */
class TameworkApiV010ContractTest {
    private static final EnumSet<TameworkApiCapability> REQUIRED_CAPABILITIES = EnumSet.of(
            TameworkApiCapability.SUCCESSFUL_ACTIVITY_FEED,
            TameworkApiCapability.DURABLE_OUTPUT_OPERATIONS,
            TameworkApiCapability.NAMED_CAPACITY_RESERVATIONS,
            TameworkApiCapability.EXTERNAL_ADMISSION_PROVIDERS,
            TameworkApiCapability.REQUIRED_CONTENT_PROFILES
    );

    @Test
    void publicContractKeepsFiveCapabilitiesAndFailClosedDefaults() throws Exception {
        TameworkApi api = new ContractApi();

        assertEquals("0.10.0", api.getApiVersion());
        assertTrue(api.getCapabilities().containsAll(REQUIRED_CAPABILITIES));

        ActivityFeedApi feed = api.activities();
        assertNotNull(feed);
        assertFalse(feed.status("contract-consumer").available());
        ActivityFeedSubscription subscription = feed.subscribe(
                "contract-consumer",
                activity -> CompletableFuture.completedFuture(ActivityConsumeResult.APPLIED)
        );
        assertEquals("contract-consumer", subscription.consumerId());
        subscription.close();
        subscription.close();
        assertFalse(feed.status("contract-consumer").subscribed());

        AutoCloseable registration = api.policies().admissionProviders().register(
                "runeteria:husbandry",
                1,
                request -> CompletableFuture.completedFuture(
                        PopulationAdmissionProviderDecision.unavailable("test-unavailable")
                )
        );
        registration.close();
        registration.close();

        PopulationAdmissionDecision decision = api.policies()
                .populationAdmissions()
                .tryAdmitV3(admissionRequest())
                .toCompletableFuture()
                .join();
        assertEquals(PopulationAdmissionDecision.Status.UNAVAILABLE, decision.status());
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
                new PopulationAdmissionRequestV2(request, "contract-role", "contract-world"),
                "runeteria:husbandry"
        );
    }

    private static final class ContractApi implements TameworkApi {
        @Override
        public String getApiVersion() {
            return "0.10.0";
        }

        @Override
        public EnumSet<TameworkApiCapability> getCapabilities() {
            return REQUIRED_CAPABILITIES.clone();
        }

        @Override
        public NpcProfilesApi profiles() {
            return null;
        }

        @Override
        public CommandLinksApi commandLinks() {
            return null;
        }

        @Override
        public ProgressionApi progression() {
            return null;
        }

        @Override
        public PolicyApi policies() {
            return new ContractPolicy();
        }

        @Override
        public InteractionExtensionApi interactionExtensions() {
            return null;
        }

        @Override
        public TraitEffectApi traitEffects() {
            return null;
        }

        @Override
        public ProfileDataApi profileData() {
            return null;
        }

        @Override
        public TameworkEventsApi events() {
            return null;
        }

        @Override
        public TameworkConfigReadApi configs() {
            return null;
        }

        @Override
        public DiagnosticsApi diagnostics() {
            return null;
        }
    }

    private static final class ContractPolicy implements PolicyApi {
        @Override
        public Optional<OwnershipPolicyView> getOwnershipByProfileId(String profileId) {
            return Optional.empty();
        }

        @Override
        public Optional<OwnershipPolicyView> getOwnershipByNpcUuid(UUID npcUuid) {
            return Optional.empty();
        }

        @Override
        public boolean isOwner(String profileId, UUID playerUuid) {
            return false;
        }

        @Override
        public ClaimAccessDecisionView evaluateClaimAccess(String profileId, UUID playerUuid) {
            return null;
        }

        @Override
        public DamagePolicyDecisionView evaluateDamage(String profileId, UUID attackerPlayerUuid) {
            return null;
        }

        @Override
        public PopulationCapDecisionView evaluatePopulationCap(UUID ownerUuid) {
            return null;
        }
    }
}
