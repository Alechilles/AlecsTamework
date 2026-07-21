package com.alechilles.alecstamework.compat.v08;

import com.alechilles.alecstamework.api.*;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Frozen API 0.8 fixture source. This source is intentionally outside Maven's test source roots;
 * the compatibility test loads the class files compiled against c0f83f20 from test resources.
 */
public final class Pre09ApiBinaryFixture {
    private Pre09ApiBinaryFixture() {
    }

    public static TameworkApi legacyApi() {
        return new LegacyTameworkApi();
    }

    /** Old invokeinterface call sites that must still resolve when linked to API 0.9. */
    public static boolean invokeLegacyCallSites(TameworkApi api) {
        api.getApiVersion();
        api.getCapabilities();
        api.profiles();
        api.commandLinks();
        api.progression();
        api.traitEffects();
        api.profileData();
        api.events();
        api.diagnostics();

        PolicyApi policies = api.policies();
        policies.getOwnershipByProfileId("profile");
        policies.getOwnershipByNpcUuid(new UUID(0L, 1L));
        policies.isOwner("profile", new UUID(0L, 2L));
        policies.evaluateClaimAccess("profile", null);
        policies.evaluateDamage("profile", null);
        policies.evaluatePopulationCap((UUID) null);
        policies.populationAdmissions().cleanupExpired();

        InteractionExtensionApi interactions = api.interactionExtensions();
        interactions.registerRequirement("fixture", null);
        interactions.registerEffect("fixture", null);
        interactions.registerPreset(null);
        interactions.getPreset("fixture");
        interactions.listRequirementIds();
        interactions.listEffectIds();
        interactions.listPresetIds();

        TameworkConfigReadApi configs = api.configs();
        configs.getGlobalConfig();
        configs.getInteractionConfigById("fixture");
        configs.resolveInteractionConfigForRole("fixture");
        configs.getSpawnerConfigById("fixture");
        configs.resolveSpawnerConfigForItemId("fixture");
        return true;
    }

    public static final class LegacyTameworkApi implements TameworkApi {
        private final PolicyApi policies = new LegacyPolicyApi();
        private final InteractionExtensionApi interactions = new LegacyInteractionExtensionApi();
        private final TameworkConfigReadApi configs = new LegacyConfigReadApi();

        @Override
        public String getApiVersion() {
            return "0.8.0";
        }

        @Override
        public EnumSet<TameworkApiCapability> getCapabilities() {
            return EnumSet.noneOf(TameworkApiCapability.class);
        }

        @Override public NpcProfilesApi profiles() { return null; }
        @Override public CommandLinksApi commandLinks() { return null; }
        @Override public ProgressionApi progression() { return null; }
        @Override public PolicyApi policies() { return policies; }
        @Override public InteractionExtensionApi interactionExtensions() { return interactions; }
        @Override public TraitEffectApi traitEffects() { return null; }
        @Override public ProfileDataApi profileData() { return null; }
        @Override public TameworkEventsApi events() { return null; }
        @Override public TameworkConfigReadApi configs() { return configs; }
        @Override public DiagnosticsApi diagnostics() { return null; }
    }

    public static final class LegacyPolicyApi implements PolicyApi {
        @Override public Optional<OwnershipPolicyView> getOwnershipByProfileId(String profileId) { return Optional.empty(); }
        @Override public Optional<OwnershipPolicyView> getOwnershipByNpcUuid(UUID npcUuid) { return Optional.empty(); }
        @Override public boolean isOwner(String profileId, UUID playerUuid) { return false; }
        @Override public ClaimAccessDecisionView evaluateClaimAccess(String profileId, UUID playerUuid) { return null; }
        @Override public DamagePolicyDecisionView evaluateDamage(String profileId, UUID attackerPlayerUuid) { return null; }
        @Override public PopulationCapDecisionView evaluatePopulationCap(UUID ownerUuid) { return null; }
        @Override public PopulationAdmissionApi populationAdmissions() { return new LegacyPopulationAdmissionApi(); }
    }

    public static final class LegacyInteractionExtensionApi implements InteractionExtensionApi {
        private static final AutoCloseable NOOP = () -> { };

        @Override public AutoCloseable registerRequirement(String id, InteractionRequirementHandler handler) { return NOOP; }
        @Override public AutoCloseable registerEffect(String id, InteractionEffectHandler handler) { return NOOP; }
        @Override public AutoCloseable registerPreset(InteractionPresetDefinition preset) { return NOOP; }
        @Override public Optional<InteractionPresetDefinition> getPreset(String id) { return Optional.empty(); }
        @Override public Set<String> listRequirementIds() { return Set.of(); }
        @Override public Set<String> listEffectIds() { return Set.of(); }
        @Override public Set<String> listPresetIds() { return Set.of(); }
    }

    public static final class LegacyPopulationAdmissionApi implements PopulationAdmissionApi {
        @Override
        public CompletionStage<PopulationAdmissionDecision> tryAdmit(PopulationAdmissionRequest request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<PopulationBatchAdmissionDecision> tryAdmitBatch(PopulationBatchAdmissionRequest request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public PopulationAdmissionDecision claimForApply(PopulationAdmissionToken token) { return null; }
        @Override public CompletionStage<PopulationAdmissionDecision> commit(PopulationAdmissionToken token) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<PopulationAdmissionDecision> cancel(PopulationAdmissionToken token) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Integer> cleanupExpired() { return CompletableFuture.completedFuture(0); }
    }

    public static final class LegacyConfigReadApi implements TameworkConfigReadApi {
        @Override public GlobalConfigView getGlobalConfig() { return null; }
        @Override public Optional<InteractionConfigView> getInteractionConfigById(String id) { return Optional.empty(); }
        @Override public Optional<InteractionConfigView> resolveInteractionConfigForRole(String roleId) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> getCompanionConfigById(String id) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> resolveCompanionConfigForRole(String roleId) { return Optional.empty(); }
        @Override public Optional<SpawnerConfigView> getSpawnerConfigById(String id) { return Optional.empty(); }
        @Override public Optional<SpawnerConfigView> resolveSpawnerConfigForItemId(String itemId) { return Optional.empty(); }
        @Override public Optional<NameItemConfigView> getNameItemConfigById(String id) { return Optional.empty(); }
        @Override public Optional<NameItemConfigView> resolveNameItemConfigForItemId(String itemId) { return Optional.empty(); }
        @Override public Optional<CommandItemConfigView> getCommandItemConfigById(String id) { return Optional.empty(); }
        @Override public Optional<CommandItemConfigView> resolveCommandItemConfigForItemId(String itemId) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> getHappinessConfigById(String id) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> resolveHappinessConfigForRole(String roleId) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> getNeedsConfigById(String id) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> resolveNeedsConfigForRole(String roleId) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> getBreedingConfigById(String id) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> resolveBreedingConfigForRole(String roleId) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> getLevelingConfigById(String id) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> resolveLevelingConfigForRole(String roleId) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> getTraitConfigById(String id) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> resolveTraitConfigForRole(String roleId) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> getTalentConfigById(String id) { return Optional.empty(); }
        @Override public Optional<RoleScopedConfigView> resolveTalentConfigForRole(String roleId) { return Optional.empty(); }
    }
}
