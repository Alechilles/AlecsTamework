package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.api.ClaimAccessDecisionView;
import com.alechilles.alecstamework.api.CommandLinkView;
import com.alechilles.alecstamework.api.CommandItemConfigView;
import com.alechilles.alecstamework.api.DamagePolicyDecisionView;
import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.InteractionEffectSpec;
import com.alechilles.alecstamework.api.InteractionConfigView;
import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.InteractionPresetDefinition;
import com.alechilles.alecstamework.api.InteractionRequirementSpec;
import com.alechilles.alecstamework.api.NameItemConfigView;
import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.OwnershipPolicyView;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.api.PolicyApi;
import com.alechilles.alecstamework.api.PopulationCapDecisionView;
import com.alechilles.alecstamework.api.ProgressionMutationResult;
import com.alechilles.alecstamework.api.ProgressionMutationStatus;
import com.alechilles.alecstamework.api.ProgressionView;
import com.alechilles.alecstamework.api.RoleScopedConfigView;
import com.alechilles.alecstamework.api.SpawnerConfigView;
import com.alechilles.alecstamework.api.PopulationGroupReconciliationView;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.TameworkConfigReadApi;
import com.alechilles.alecstamework.api.TraitEffectApi;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Executes live contract checks against Tamework's public integration API.
 */
public final class ApiSelfTestRunner {
    private static final String EXAMPLE_SPAWNER_ITEM_ID = "Spawner_Tamework_Example";
    private static final String EXAMPLE_SPAWNER_FILLED_ITEM_ID = "*Spawner_Tamework_Example_State_Filled";
    private static final String EXAMPLE_NAME_ITEM_ID = "Tamework_Nametag_Example";
    private static final String EXAMPLE_COMMAND_ITEM_ID = "Tamework_Command_Whistle_Example";

    public enum Suite {
        CORE,
        PROFILE,
        COMMAND_LINKS,
        CONFIGS,
        PROGRESSION,
        INTERACTION_EXTENSIONS,
        TRAIT_EFFECTS,
        POLICIES,
        DIAGNOSTICS,
        HYDRAGON_INTEGRATIONS,
        ALL;

        @Nonnull
        public static Suite parse(@Nonnull String raw) {
            return valueOf(raw.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        }
    }

    @Nonnull
    public ApiSelfTestRunReport run(@Nonnull ApiSelfTestContext context, @Nonnull Suite suite) {
        ArrayList<ApiSelfTestSuiteResult> suites = new ArrayList<>();
        if (suite == Suite.ALL || suite == Suite.CORE) {
            suites.add(runCore(context));
        }
        if (suite == Suite.ALL || suite == Suite.PROFILE) {
            suites.add(runProfile(context));
        }
        if (suite == Suite.ALL || suite == Suite.COMMAND_LINKS) {
            suites.add(runCommandLinks(context));
        }
        if (suite == Suite.ALL || suite == Suite.CONFIGS) {
            suites.add(runConfigs(context));
        }
        if (suite == Suite.ALL || suite == Suite.PROGRESSION) {
            suites.add(runProgression(context));
        }
        if (suite == Suite.ALL || suite == Suite.INTERACTION_EXTENSIONS) {
            suites.add(runInteractionExtensions(context));
        }
        if (suite == Suite.ALL || suite == Suite.TRAIT_EFFECTS) {
            suites.add(runTraitEffects(context));
        }
        if (suite == Suite.ALL || suite == Suite.POLICIES) {
            suites.add(runPolicies(context));
        }
        if (suite == Suite.ALL || suite == Suite.DIAGNOSTICS) {
            suites.add(runDiagnostics(context));
        }
        if (suite == Suite.ALL || suite == Suite.HYDRAGON_INTEGRATIONS) {
            suites.add(runHyDragonIntegrations(context));
        }
        return new ApiSelfTestRunReport(suites);
    }

    @Nonnull
    private ApiSelfTestSuiteResult runHyDragonIntegrations(@Nonnull ApiSelfTestContext context) {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        TameworkApi api = context.api();
        EnumSet<TameworkApiCapability> capabilities = api.getCapabilities();
        assertions.add(check(
                "capture policy capability ready",
                capabilities.contains(TameworkApiCapability.CAPTURE_POLICY),
                "capabilities=" + capabilities));
        assertions.add(check(
                "capture mechanics fixture resolves",
                api.configs().resolveSpawnerCaptureMechanicsForItemId(EXAMPLE_SPAWNER_ITEM_ID).isPresent(),
                "item=" + EXAMPLE_SPAWNER_ITEM_ID));
        assertions.add(check(
                "command-family roster capability ready",
                capabilities.contains(TameworkApiCapability.COMMAND_FAMILY_ROSTERS),
                "capabilities=" + capabilities));
        assertions.add(check(
                "timed command summoning capability ready",
                capabilities.contains(TameworkApiCapability.COMMAND_TIMED_SUMMONING),
                "capabilities=" + capabilities));
        assertions.add(check(
                "resolved capture consumption capability ready",
                capabilities.contains(TameworkApiCapability.CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION),
                "capabilities=" + capabilities));
        assertions.add(check(
                "capture tame-and-link capability ready",
                capabilities.contains(TameworkApiCapability.CAPTURE_TAME_AND_LINK),
                "capabilities=" + capabilities));

        PopulationGroupReconciliationView groupReadiness =
                api.policies().populationGroups().getReconciliationStatus();
        assertions.add(check(
                "population group capability ready",
                capabilities.contains(TameworkApiCapability.POPULATION_GROUPS)
                        && groupReadiness.readiness()
                        == PopulationGroupReconciliationView.Readiness.READY,
                "readiness=" + groupReadiness.readiness() + " reason=" + groupReadiness.reason()));
        assertions.add(check(
                "companion provisioning capability ready",
                capabilities.contains(TameworkApiCapability.COMPANION_PROVISIONING),
                "capabilities=" + capabilities));
        assertions.add(check(
                "profile data transactions capability ready",
                capabilities.contains(TameworkApiCapability.PROFILE_DATA_TRANSACTIONS),
                "capabilities=" + capabilities));
        assertions.addAll(HyDragonBehavioralSelfTestFixtures.run());
        return new ApiSelfTestSuiteResult("hydragon-integrations", assertions);
    }

    @Nonnull
    private ApiSelfTestSuiteResult runCore(@Nonnull ApiSelfTestContext context) {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        TameworkApi api = context.api();
        assertions.add(pass("api available", "version=" + api.getApiVersion()));
        assertions.add(check(
                "api version present",
                api.getApiVersion() != null && !api.getApiVersion().isBlank(),
                "version=" + api.getApiVersion()
        ));

        EnumSet<TameworkApiCapability> expected = EnumSet.of(
                TameworkApiCapability.PROFILES,
                TameworkApiCapability.COMMAND_LINKS,
                TameworkApiCapability.PROGRESSION,
                TameworkApiCapability.PROGRESSION_MUTATIONS,
                TameworkApiCapability.POLICY,
                TameworkApiCapability.INTERACTION_EXTENSIONS,
                TameworkApiCapability.TRAIT_EFFECTS,
                TameworkApiCapability.PROFILE_DATA,
                TameworkApiCapability.EVENTS,
                TameworkApiCapability.COMPANION_XP_EVENTS,
                TameworkApiCapability.CONFIG_READ,
                TameworkApiCapability.DIAGNOSTICS,
                TameworkApiCapability.PERSISTENCE_RESILIENCE
        );
        EnumSet<TameworkApiCapability> capabilities = api.getCapabilities();
        assertions.add(check(
                "required capabilities advertised",
                capabilities.containsAll(expected),
                "capabilities=" + capabilities
        ));
        assertions.add(check(
                "global config available",
                api.configs().getGlobalConfig() != null,
                "global config read succeeded"
        ));
        assertions.add(check(
                "diagnostics available",
                api.diagnostics().getPersistenceDiagnostics() != null,
                "diagnostics read succeeded"
        ));
        return new ApiSelfTestSuiteResult("core", assertions);
    }

    @Nonnull
    private ApiSelfTestSuiteResult runProfile(@Nonnull ApiSelfTestContext context) {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        ApiSelfTestFixtureSet fixtureSet = requireFixtureSet(context, assertions, "profile");
        if (fixtureSet == null) {
            return new ApiSelfTestSuiteResult("profile", assertions);
        }
        for (ApiSelfTestFixtureRecord fixture : fixtureSet.fixtures().values()) {
            UUID npcUuid = fixture.npcUuid();
            Optional<String> resolvedProfileId = context.api().profiles().resolveProfileId(npcUuid);
            assertions.add(check(
                    fixture.fixtureKey() + " profile id resolves",
                    resolvedProfileId.isPresent(),
                    resolvedProfileId.orElse("<empty>")
            ));
            if (resolvedProfileId.isEmpty()) {
                continue;
            }
            Optional<NpcProfileView> byNpcUuid = context.api().profiles().getByNpcUuid(npcUuid);
            Optional<NpcProfileView> byProfileId = context.api().profiles().getByProfileId(resolvedProfileId.get());
            assertions.add(check(
                    fixture.fixtureKey() + " getByNpcUuid returns profile",
                    byNpcUuid.isPresent(),
                    byNpcUuid.map(view -> view.profileId() + " owner=" + view.ownerUuid()).orElse("<empty>")
            ));
            assertions.add(check(
                    fixture.fixtureKey() + " getByProfileId returns profile",
                    byProfileId.isPresent(),
                    byProfileId.map(view -> view.profileId() + " owner=" + view.ownerUuid()).orElse("<empty>")
            ));
            if (byNpcUuid.isEmpty() || byProfileId.isEmpty()) {
                continue;
            }
            NpcProfileView npcView = byNpcUuid.get();
            NpcProfileView profileView = byProfileId.get();
            assertions.add(check(
                    fixture.fixtureKey() + " profile lookups agree",
                    npcView.profileId().equals(profileView.profileId()),
                    npcView.profileId() + " vs " + profileView.profileId()
            ));
            assertions.add(check(
                    fixture.fixtureKey() + " role id matches fixture",
                    fixture.roleId().equalsIgnoreCase(npcView.roleId()),
                    "role=" + npcView.roleId()
            ));
            assertions.add(check(
                    fixture.fixtureKey() + " tamed flag present",
                    npcView.tamed(),
                    "tamed=" + npcView.tamed()
            ));
            assertions.add(check(
                    fixture.fixtureKey() + " tool ids include fixture tool",
                    npcView.toolIds().contains(fixtureSet.toolId()),
                    "toolIds=" + npcView.toolIds()
            ));
            assertions.add(check(
                    fixture.fixtureKey() + " lastUpdatedAtMs populated",
                    npcView.lastUpdatedAtMs() > 0L,
                    "lastUpdatedAtMs=" + npcView.lastUpdatedAtMs()
            ));
        }
        return new ApiSelfTestSuiteResult("profile", assertions);
    }

    @Nonnull
    private ApiSelfTestSuiteResult runCommandLinks(@Nonnull ApiSelfTestContext context) {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        ApiSelfTestFixtureSet fixtureSet = requireFixtureSet(context, assertions, "command-links");
        if (fixtureSet == null) {
            return new ApiSelfTestSuiteResult("command-links", assertions);
        }
        for (ApiSelfTestFixtureRecord fixture : fixtureSet.fixtures().values()) {
            Optional<CommandLinkView> view = context.api().commandLinks().getByNpcUuid(fixture.npcUuid());
            assertions.add(check(
                    fixture.fixtureKey() + " command links resolve",
                    view.isPresent(),
                    view.map(v -> "profile=" + v.profileId() + ", tools=" + v.toolIds()).orElse("<empty>")
            ));
            if (view.isEmpty()) {
                continue;
            }
            Optional<String> profileId = context.api().profiles().resolveProfileId(fixture.npcUuid());
            assertions.add(check(
                    fixture.fixtureKey() + " profile id resolves for command links",
                    profileId.isPresent(),
                    profileId.orElse("<empty>")
            ));
            CommandLinkView commandLinkView = view.get();
            assertions.add(check(
                    fixture.fixtureKey() + " linked tool id matches fixture",
                    commandLinkView.toolIds().contains(fixtureSet.toolId()),
                    "toolIds=" + commandLinkView.toolIds()
            ));
            assertions.add(check(
                    fixture.fixtureKey() + " has home position",
                    commandLinkView.hasHomePosition(),
                    describePosition(commandLinkView.homePosition())
            ));
            if (profileId.isEmpty()) {
                continue;
            }
            Optional<Vector3View> homePosition = context.api().commandLinks().getHomePosition(profileId.get());
            assertions.add(check(
                    fixture.fixtureKey() + " profile home position resolves",
                    homePosition.isPresent(),
                    describePosition(homePosition.orElse(null))
            ));
            assertions.add(check(
                    fixture.fixtureKey() + " listLinkedToolIds includes tool",
                    context.api().commandLinks().listLinkedToolIds(profileId.get()).contains(fixtureSet.toolId()),
                    "toolId=" + fixtureSet.toolId()
            ));
        }
        return new ApiSelfTestSuiteResult("command-links", assertions);
    }

    @Nonnull
    private ApiSelfTestSuiteResult runConfigs(@Nonnull ApiSelfTestContext context) {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        ApiSelfTestFixtureSet fixtureSet = requireFixtureSet(context, assertions, "configs");
        if (fixtureSet == null) {
            return new ApiSelfTestSuiteResult("configs", assertions);
        }
        ApiSelfTestFixtureRecord fixture = fixtureSet.getFixture(ApiSelfTestFixtureManager.FIXTURE_KEY_OWNED);
        if (fixture == null) {
            assertions.add(fail("owned fixture available", "missing " + ApiSelfTestFixtureManager.FIXTURE_KEY_OWNED));
            return new ApiSelfTestSuiteResult("configs", assertions);
        }
        String roleId = fixture.roleId();
        TameworkConfigReadApi configs = context.api().configs();
        Optional<InteractionConfigView> interaction = configs.resolveInteractionConfigForRole(roleId);
        assertions.add(check(
                "interaction config resolves",
                interaction.isPresent(),
                interaction.map(view -> view.id() + " priority=" + view.priority()).orElse("<empty>")
        ));
        assertions.add(checkRoleConfig("companion config resolves", configs.resolveCompanionConfigForRole(roleId)));
        assertions.add(checkRoleConfig("happiness config resolves", configs.resolveHappinessConfigForRole(roleId)));
        assertions.add(checkRoleConfig("needs config resolves", configs.resolveNeedsConfigForRole(roleId)));
        assertions.add(checkRoleConfig("breeding config resolves", configs.resolveBreedingConfigForRole(roleId)));
        assertions.add(checkRoleConfig("trait config resolves", configs.resolveTraitConfigForRole(roleId)));

        Optional<SpawnerConfigView> spawnerByEmptyItem = configs.resolveSpawnerConfigForItemId(EXAMPLE_SPAWNER_ITEM_ID);
        assertions.add(check(
                "spawner config resolves for empty item",
                spawnerByEmptyItem.isPresent(),
                spawnerByEmptyItem.map(view -> view.id() + " empty=" + view.emptyItemId()).orElse("<empty>")
        ));
        Optional<SpawnerConfigView> spawnerByFilledItem = configs.resolveSpawnerConfigForItemId(EXAMPLE_SPAWNER_FILLED_ITEM_ID);
        assertions.add(check(
                "spawner config resolves for filled item",
                spawnerByFilledItem.isPresent(),
                spawnerByFilledItem.map(view -> view.id() + " filled=" + view.filledItemId()).orElse("<empty>")
        ));
        if (spawnerByEmptyItem.isPresent()) {
            Optional<SpawnerConfigView> spawnerById = configs.getSpawnerConfigById(spawnerByEmptyItem.get().id());
            assertions.add(check(
                    "spawner config by-id round trip",
                    spawnerById.isPresent() && !spawnerById.get().detailsJson().isBlank(),
                    spawnerById.map(view -> view.id()).orElse("<empty>")
            ));
        }

        Optional<NameItemConfigView> nameItem = configs.resolveNameItemConfigForItemId(EXAMPLE_NAME_ITEM_ID);
        assertions.add(check(
                "name-item config resolves",
                nameItem.isPresent(),
                nameItem.map(view -> view.id() + " item=" + view.itemId()).orElse("<empty>")
        ));
        if (nameItem.isPresent()) {
            Optional<NameItemConfigView> nameItemById = configs.getNameItemConfigById(nameItem.get().id());
            assertions.add(check(
                    "name-item config by-id round trip",
                    nameItemById.isPresent() && !nameItemById.get().detailsJson().isBlank(),
                    nameItemById.map(NameItemConfigView::id).orElse("<empty>")
            ));
        }

        Optional<CommandItemConfigView> commandItem = configs.resolveCommandItemConfigForItemId(EXAMPLE_COMMAND_ITEM_ID);
        assertions.add(check(
                "command-item config resolves",
                commandItem.isPresent(),
                commandItem.map(view -> view.id() + " itemIds=" + view.itemIds()).orElse("<empty>")
        ));
        if (commandItem.isPresent()) {
            assertions.add(check(
                    "command-item config includes example item id",
                    commandItem.get().itemIds().contains(EXAMPLE_COMMAND_ITEM_ID),
                    "itemIds=" + commandItem.get().itemIds()
            ));
            Optional<CommandItemConfigView> commandItemById = configs.getCommandItemConfigById(commandItem.get().id());
            assertions.add(check(
                    "command-item config by-id round trip",
                    commandItemById.isPresent() && !commandItemById.get().detailsJson().isBlank(),
                    commandItemById.map(CommandItemConfigView::id).orElse("<empty>")
            ));
        }
        return new ApiSelfTestSuiteResult("configs", assertions);
    }

    @Nonnull
    private ApiSelfTestSuiteResult runProgression(@Nonnull ApiSelfTestContext context) {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        ApiSelfTestFixtureSet fixtureSet = requireFixtureSet(context, assertions, "progression");
        if (fixtureSet == null) {
            return new ApiSelfTestSuiteResult("progression", assertions);
        }
        ApiSelfTestFixtureRecord fixture = fixtureSet.getFixture(ApiSelfTestFixtureManager.FIXTURE_KEY_OWNED);
        if (fixture == null) {
            assertions.add(fail("owned fixture available", "missing " + ApiSelfTestFixtureManager.FIXTURE_KEY_OWNED));
            return new ApiSelfTestSuiteResult("progression", assertions);
        }
        bootstrapFixtureProgression(context, fixture);

        Optional<String> profileId = context.api().profiles().resolveProfileId(fixture.npcUuid());
        assertions.add(check(
                "progression fixture profile id resolves",
                profileId.isPresent(),
                profileId.orElse("<empty>")
        ));
        if (profileId.isEmpty()) {
            return new ApiSelfTestSuiteResult("progression", assertions);
        }
        String resolvedProfileId = profileId.get();
        Optional<ProgressionView> baselineProgression = context.api().progression().getByProfileId(resolvedProfileId);
        assertions.add(check(
                "progression available by profile id",
                baselineProgression.isPresent(),
                baselineProgression.map(this::describeProgression).orElse("<empty>")
        ));
        Optional<ProgressionView> byNpcUuid = context.api().progression().getByNpcUuid(fixture.npcUuid());
        assertions.add(check(
                "progression available by npc uuid",
                byNpcUuid.isPresent(),
                byNpcUuid.map(this::describeProgression).orElse("<empty>")
        ));
        if (baselineProgression.isEmpty()) {
            return new ApiSelfTestSuiteResult("progression", assertions);
        }
        ProgressionView baseline = baselineProgression.get();
        if (byNpcUuid.isPresent()) {
            assertions.add(check(
                    "progression lookups agree",
                    baseline.npcUuid().equals(byNpcUuid.get().npcUuid()),
                    baseline.npcUuid() + " vs " + byNpcUuid.get().npcUuid()
            ));
        }
        String progressionRoleId = firstNonBlank(baseline.roleId(), fixture.roleId());
        assertions.add(check(
                "progression role id available",
                !isBlank(progressionRoleId),
                progressionRoleId != null ? progressionRoleId : "<empty>"
        ));

        assertions.add(checkMutation(
                "setNeeds rejects missing hunger/thirst",
                context.api().progression().setNeeds(resolvedProfileId, null, null),
                ProgressionMutationStatus.INVALID_ARGUMENT
        ));
        assertions.add(checkMutation(
                "setHappiness rejects NaN",
                context.api().progression().setHappiness(resolvedProfileId, Double.NaN),
                ProgressionMutationStatus.INVALID_ARGUMENT
        ));
        assertions.add(checkMutation(
                "applyHappinessDelta rejects infinity",
                context.api().progression().applyHappinessDelta(resolvedProfileId, Double.POSITIVE_INFINITY),
                ProgressionMutationStatus.INVALID_ARGUMENT
        ));
        assertions.add(checkMutation(
                "setTraits rejects null map",
                context.api().progression().setTraits(resolvedProfileId, null),
                ProgressionMutationStatus.INVALID_ARGUMENT
        ));
        assertions.add(checkMutation(
                "setStoredAttachments rejects null map",
                context.api().progression().setStoredAttachments(resolvedProfileId, null),
                ProgressionMutationStatus.INVALID_ARGUMENT
        ));

        try {
            runProgressionMutationChecks(assertions, context, resolvedProfileId, progressionRoleId, baseline);
        } finally {
            restoreProgressionBaseline(assertions, context, resolvedProfileId, baseline);
        }
        return new ApiSelfTestSuiteResult("progression", assertions);
    }

    private void bootstrapFixtureProgression(@Nonnull ApiSelfTestContext context,
                                             @Nonnull ApiSelfTestFixtureRecord fixture) {
        Ref<EntityStore> npcRef = context.world().getEntityRef(fixture.npcUuid());
        if (npcRef == null || !npcRef.isValid()) {
            return;
        }
        CompanionProgressionBootstrapService.ensureProgressionComponents(
                npcRef,
                context.store(),
                fixture.roleId()
        );
    }

    private void runProgressionMutationChecks(@Nonnull List<ApiSelfTestAssertion> assertions,
                                              @Nonnull ApiSelfTestContext context,
                                              @Nonnull String profileId,
                                              @Nullable String roleId,
                                              @Nonnull ProgressionView baseline) {
        if (baseline.happiness() != null) {
            double min = baseline.happiness().min();
            double max = baseline.happiness().max();
            double target = clampDouble(baseline.happiness().value() + 1.0, min, max);
            assertions.add(checkMutation(
                    "setHappiness applies",
                    context.api().progression().setHappiness(profileId, target),
                    ProgressionMutationStatus.APPLIED
            ));
            assertions.add(checkMutation(
                    "applyHappinessDelta applies",
                    context.api().progression().applyHappinessDelta(profileId, -1.0),
                    ProgressionMutationStatus.APPLIED
            ));
        } else {
            assertions.add(fail("happiness baseline available", "No happiness snapshot on fixture progression."));
        }

        if (baseline.needs() != null) {
            assertions.add(checkMutation(
                    "setNeeds applies",
                    context.api().progression().setNeeds(profileId, baseline.needs().hunger(), baseline.needs().thirst()),
                    ProgressionMutationStatus.APPLIED
            ));
        } else {
            assertions.add(fail("needs baseline available", "No needs snapshot on fixture progression."));
        }

        boolean breedingConfigured = !isBlank(roleId)
                && context.api().configs().resolveBreedingConfigForRole(roleId).isPresent();
        assertions.add(check(
                "breeding config resolution expected for role",
                breedingConfigured || baseline.breeding() == null,
                "role=" + roleId + ", hasBaseline=" + (baseline.breeding() != null)
        ));
        ProgressionMutationStatus[] breedingAllowedStatuses = breedingConfigured
                ? new ProgressionMutationStatus[] { ProgressionMutationStatus.APPLIED }
                : new ProgressionMutationStatus[] { ProgressionMutationStatus.UNSUPPORTED };
        assertions.add(checkMutation(
                "setBreedingReady true returns expected status",
                context.api().progression().setBreedingReady(profileId, true),
                breedingAllowedStatuses
        ));
        assertions.add(checkMutation(
                "setBreedingReady false returns expected status",
                context.api().progression().setBreedingReady(profileId, false),
                breedingAllowedStatuses
        ));

        boolean traitConfigured = !isBlank(roleId)
                && context.api().configs().resolveTraitConfigForRole(roleId).isPresent();
        Map<String, Double> baselineTraits = collectTraitValues(baseline);
        ProgressionMutationResult rerollTraitsResult = context.api().progression().rerollTraits(profileId);
        assertions.add(checkMutation(
                "rerollTraits returns expected status",
                rerollTraitsResult,
                traitConfigured ? ProgressionMutationStatus.APPLIED : ProgressionMutationStatus.UNSUPPORTED
        ));
        if (traitConfigured) {
            Map<String, Double> traitValuesToReapply = !baselineTraits.isEmpty()
                    ? baselineTraits
                    : collectTraitValues(rerollTraitsResult.progression());
            assertions.add(check(
                    "trait values available for setTraits validation",
                    !traitValuesToReapply.isEmpty(),
                    "role=" + roleId + ", baselineTraits=" + baselineTraits.size()
            ));
            if (!traitValuesToReapply.isEmpty()) {
                assertions.add(checkMutation(
                        "setTraits applies",
                        context.api().progression().setTraits(profileId, traitValuesToReapply),
                        ProgressionMutationStatus.APPLIED
                ));
            }
        } else if (baseline.traits() != null && !baselineTraits.isEmpty()) {
            assertions.add(checkMutation(
                    "setTraits rejects unsupported trait path",
                    context.api().progression().setTraits(profileId, baselineTraits),
                    ProgressionMutationStatus.UNSUPPORTED
            ));
        }

        ProgressionMutationStatus lifeStageExpected =
                baseline.lifeStage() != null ? ProgressionMutationStatus.APPLIED : ProgressionMutationStatus.UNSUPPORTED;
        assertions.add(checkMutation(
                "refreshLifeStage returns expected status",
                context.api().progression().refreshLifeStage(profileId),
                lifeStageExpected
        ));

        Map<String, String> baselineAttachments = collectAttachmentSelections(baseline);
        boolean attachmentsAvailable = baseline.attachments() != null;
        ProgressionMutationResult setAttachmentsResult =
                context.api().progression().setStoredAttachments(profileId, baselineAttachments);
        assertions.add(checkMutation(
                "setStoredAttachments returns expected status",
                setAttachmentsResult,
                attachmentsAvailable ? ProgressionMutationStatus.APPLIED : ProgressionMutationStatus.UNSUPPORTED
        ));

        ProgressionMutationResult syncAttachmentsResult = context.api().progression().syncStoredAttachments(profileId);
        if (!attachmentsAvailable) {
            assertions.add(checkMutation(
                    "syncStoredAttachments returns unsupported when attachments missing",
                    syncAttachmentsResult,
                    ProgressionMutationStatus.UNSUPPORTED
            ));
            return;
        }
        if (setAttachmentsResult.status() != ProgressionMutationStatus.APPLIED) {
            assertions.add(fail(
                    "syncStoredAttachments precondition",
                    "setStoredAttachments did not apply: " + describeMutation(setAttachmentsResult)
            ));
            return;
        }
        ProgressionView attachmentAppliedProgression = setAttachmentsResult.progression();
        boolean hasStoredSelections = attachmentAppliedProgression != null
                && attachmentAppliedProgression.attachments() != null
                && !attachmentAppliedProgression.attachments().storedAttachmentIds().isEmpty();
        assertions.add(checkMutation(
                "syncStoredAttachments returns expected status",
                syncAttachmentsResult,
                hasStoredSelections ? ProgressionMutationStatus.APPLIED : ProgressionMutationStatus.UNSUPPORTED
        ));
    }

    private void restoreProgressionBaseline(@Nonnull List<ApiSelfTestAssertion> assertions,
                                            @Nonnull ApiSelfTestContext context,
                                            @Nonnull String profileId,
                                            @Nonnull ProgressionView baseline) {
        if (baseline.happiness() != null) {
            assertions.add(checkMutation(
                    "restore baseline happiness",
                    context.api().progression().setHappiness(profileId, baseline.happiness().value()),
                    ProgressionMutationStatus.APPLIED
            ));
        }
        if (baseline.needs() != null) {
            assertions.add(checkMutation(
                    "restore baseline needs",
                    context.api().progression().setNeeds(profileId, baseline.needs().hunger(), baseline.needs().thirst()),
                    ProgressionMutationStatus.APPLIED
            ));
        }
        if (baseline.breeding() != null) {
            assertions.add(checkMutation(
                    "restore baseline breeding ready flag",
                    context.api().progression().setBreedingReady(profileId, baseline.breeding().readyFlag()),
                    ProgressionMutationStatus.APPLIED
            ));
        }
        Map<String, Double> baselineTraits = collectTraitValues(baseline);
        if (baseline.traits() != null && !baselineTraits.isEmpty()) {
            assertions.add(checkMutation(
                    "restore baseline traits",
                    context.api().progression().setTraits(profileId, baselineTraits),
                    ProgressionMutationStatus.APPLIED
            ));
        }
        Map<String, String> baselineAttachments = collectAttachmentSelections(baseline);
        if (baseline.attachments() != null) {
            assertions.add(checkMutation(
                    "restore baseline stored attachments",
                    context.api().progression().setStoredAttachments(profileId, baselineAttachments),
                    ProgressionMutationStatus.APPLIED
            ));
            if (!baselineAttachments.isEmpty()) {
                assertions.add(checkMutation(
                        "restore baseline attachment sync",
                        context.api().progression().syncStoredAttachments(profileId),
                        ProgressionMutationStatus.APPLIED,
                        ProgressionMutationStatus.UNSUPPORTED
                ));
            }
        }
    }

    @Nonnull
    private ApiSelfTestSuiteResult runInteractionExtensions(@Nonnull ApiSelfTestContext context) {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        InteractionExtensionApi extensions = context.api().interactionExtensions();
        if (extensions == null) {
            assertions.add(fail("interaction extension api available", "<null>"));
            return new ApiSelfTestSuiteResult("interaction-extensions", assertions);
        }

        String idSuffix = UUID.randomUUID().toString().replace("-", "");
        String requirementId = "selftest.requirement." + idSuffix;
        String effectId = "selftest.effect." + idSuffix;
        String presetId = "selftest.preset." + idSuffix;

        AutoCloseable requirementRegistration = null;
        AutoCloseable effectRegistration = null;
        AutoCloseable presetRegistration = null;
        try {
            requirementRegistration = extensions.registerRequirement(requirementId, (requirementContext, spec) -> true);
            effectRegistration = extensions.registerEffect(effectId, (effectContext, spec) -> true);
            presetRegistration = extensions.registerPreset(new InteractionPresetDefinition(
                    presetId,
                    List.of(new InteractionRequirementSpec(requirementId, "flag", List.of("x"), null)),
                    List.of(new InteractionEffectSpec(effectId, "mode", List.of("follow"), null))
            ));

            assertions.add(check(
                    "registered requirement id is listed",
                    extensions.listRequirementIds().contains(requirementId),
                    "requirements=" + extensions.listRequirementIds()
            ));
            assertions.add(check(
                    "registered effect id is listed",
                    extensions.listEffectIds().contains(effectId),
                    "effects=" + extensions.listEffectIds()
            ));
            assertions.add(check(
                    "registered preset id is listed",
                    extensions.listPresetIds().contains(presetId),
                    "presets=" + extensions.listPresetIds()
            ));
            assertions.add(check(
                    "registered preset resolves",
                    extensions.getPreset(presetId.toUpperCase(Locale.ROOT)).isPresent(),
                    extensions.getPreset(presetId).map(InteractionPresetDefinition::id).orElse("<empty>")
            ));
        } catch (Exception ex) {
            assertions.add(fail("interaction extension registration", ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        } finally {
            closeQuietly(presetRegistration);
            closeQuietly(effectRegistration);
            closeQuietly(requirementRegistration);
        }

        assertions.add(check(
                "requirement unregistered on close",
                !extensions.listRequirementIds().contains(requirementId),
                "requirements=" + extensions.listRequirementIds()
        ));
        assertions.add(check(
                "effect unregistered on close",
                !extensions.listEffectIds().contains(effectId),
                "effects=" + extensions.listEffectIds()
        ));
        assertions.add(check(
                "preset unregistered on close",
                !extensions.getPreset(presetId).isPresent(),
                "presets=" + extensions.listPresetIds()
        ));

        boolean invalidRequirementRejected = false;
        try {
            extensions.registerRequirement("   ", (requirementContext, spec) -> true);
        } catch (IllegalArgumentException expected) {
            invalidRequirementRejected = true;
        }
        assertions.add(check("blank requirement id rejected", invalidRequirementRejected, Boolean.toString(invalidRequirementRejected)));

        boolean invalidEffectRejected = false;
        try {
            extensions.registerEffect("", (effectContext, spec) -> true);
        } catch (IllegalArgumentException expected) {
            invalidEffectRejected = true;
        }
        assertions.add(check("blank effect id rejected", invalidEffectRejected, Boolean.toString(invalidEffectRejected)));

        boolean invalidPresetRejected = false;
        try {
            extensions.registerPreset(new InteractionPresetDefinition(" ", List.of(), List.of()));
        } catch (IllegalArgumentException expected) {
            invalidPresetRejected = true;
        }
        assertions.add(check("blank preset id rejected", invalidPresetRejected, Boolean.toString(invalidPresetRejected)));
        return new ApiSelfTestSuiteResult("interaction-extensions", assertions);
    }

    @Nonnull
    private ApiSelfTestSuiteResult runTraitEffects(@Nonnull ApiSelfTestContext context) {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        TraitEffectApi traitEffects = context.api().traitEffects();
        if (traitEffects == null) {
            assertions.add(fail("trait effect api available", "<null>"));
            return new ApiSelfTestSuiteResult("trait-effects", assertions);
        }

        String idSuffix = UUID.randomUUID().toString().replace("-", "");
        String effectKey = "selftest.trait.effect." + idSuffix;
        AutoCloseable registration = null;
        try {
            registration = traitEffects.registerEffectKey(effectKey.toUpperCase(Locale.ROOT), traitContext -> true);
            assertions.add(check(
                    "registered trait effect key is listed",
                    traitEffects.listEffectKeys().contains(effectKey),
                    "effects=" + traitEffects.listEffectKeys()
            ));
        } catch (Exception ex) {
            assertions.add(fail("trait effect registration", ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        } finally {
            closeQuietly(registration);
        }

        assertions.add(check(
                "trait effect key unregistered on close",
                !traitEffects.listEffectKeys().contains(effectKey),
                "effects=" + traitEffects.listEffectKeys()
        ));

        boolean invalidEffectRejected = false;
        try {
            traitEffects.registerEffectKey(" ", traitContext -> true);
        } catch (IllegalArgumentException expected) {
            invalidEffectRejected = true;
        }
        assertions.add(check("blank trait effect key rejected", invalidEffectRejected, Boolean.toString(invalidEffectRejected)));
        return new ApiSelfTestSuiteResult("trait-effects", assertions);
    }

    @Nonnull
    private ApiSelfTestSuiteResult runPolicies(@Nonnull ApiSelfTestContext context) {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        ApiSelfTestFixtureSet fixtureSet = requireFixtureSet(context, assertions, "policies");
        if (fixtureSet == null) {
            return new ApiSelfTestSuiteResult("policies", assertions);
        }
        PolicyApi policyApi = context.api().policies();
        UUID playerUuid = context.player().getUuid();
        ApiSelfTestFixtureRecord owned = fixtureSet.getFixture(ApiSelfTestFixtureManager.FIXTURE_KEY_OWNED);
        ApiSelfTestFixtureRecord stranger = fixtureSet.getFixture(ApiSelfTestFixtureManager.FIXTURE_KEY_STRANGER);
        if (owned == null || stranger == null) {
            assertions.add(fail("fixtures available", "owned=" + owned + ", stranger=" + stranger));
            return new ApiSelfTestSuiteResult("policies", assertions);
        }
        runPolicyChecksForFixture(assertions, policyApi, fixtureSet, owned, playerUuid, true);
        runPolicyChecksForFixture(assertions, policyApi, fixtureSet, stranger, playerUuid, false);
        PopulationCapDecisionView population = policyApi.evaluatePopulationCap(playerUuid);
        assertions.add(check(
                "population cap decision coherent",
                population.limit() >= 0 && population.currentCount() >= 0,
                "limit=" + population.limit() + ", current=" + population.currentCount()
        ));
        return new ApiSelfTestSuiteResult("policies", assertions);
    }

    @Nonnull
    private ApiSelfTestSuiteResult runDiagnostics(@Nonnull ApiSelfTestContext context) {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        DiagnosticsApi diagnosticsApi = context.api().diagnostics();
        PersistenceDiagnosticsView diagnostics = diagnosticsApi.getPersistenceDiagnostics();
        assertions.add(check(
                "persistence diagnostics returned",
                diagnostics != null,
                diagnostics == null ? "<null>" : diagnostics.databasePath()
        ));
        if (diagnostics != null) {
            assertions.add(check(
                    "database path present",
                    diagnostics.databasePath() != null && !diagnostics.databasePath().isBlank(),
                    diagnostics.databasePath()
            ));
            assertions.add(check(
                    "health status present",
                    diagnostics.health() != null
                            && diagnostics.health().status() != null
                            && !diagnostics.health().status().isBlank(),
                    diagnostics.health() != null ? diagnostics.health().status() : "<null>"
            ));
            assertions.add(check(
                    "queue metrics readable",
                    diagnostics.queueMetrics() != null
                            && diagnostics.queueMetrics().maxBatchSize() >= 0
                            && diagnostics.queueMetrics().queueDepth() >= 0,
                    diagnostics.queueMetrics() == null
                            ? "<null>"
                            : "queueDepth="
                            + diagnostics.queueMetrics().queueDepth()
                            + ", maxBatch="
                            + diagnostics.queueMetrics().maxBatchSize()
            ));
        }
        return new ApiSelfTestSuiteResult("diagnostics", assertions);
    }

    private void runPolicyChecksForFixture(@Nonnull List<ApiSelfTestAssertion> assertions,
                                           @Nonnull PolicyApi policyApi,
                                           @Nonnull ApiSelfTestFixtureSet fixtureSet,
                                           @Nonnull ApiSelfTestFixtureRecord fixture,
                                           @Nonnull UUID playerUuid,
                                           boolean shouldBeOwner) {
        Optional<String> profileId = Optional.ofNullable(policyApi.getOwnershipByNpcUuid(fixture.npcUuid())
                .map(OwnershipPolicyView::profileId)
                .orElse(null));
        assertions.add(check(
                fixture.fixtureKey() + " ownership resolves",
                profileId.isPresent(),
                profileId.orElse("<empty>")
        ));
        if (profileId.isEmpty()) {
            return;
        }
        Optional<OwnershipPolicyView> ownership = policyApi.getOwnershipByProfileId(profileId.get());
        assertions.add(check(
                fixture.fixtureKey() + " ownership view present",
                ownership.isPresent(),
                ownership.map(view -> "owner=" + view.ownerUuid()).orElse("<empty>")
        ));
        if (ownership.isPresent()) {
            assertions.add(check(
                    fixture.fixtureKey() + " isOwner matches expectation",
                    policyApi.isOwner(profileId.get(), playerUuid) == shouldBeOwner,
                    "isOwner=" + policyApi.isOwner(profileId.get(), playerUuid)
            ));
            assertions.add(check(
                    fixture.fixtureKey() + " ownership owner matches expectation",
                    shouldBeOwner == playerUuid.equals(ownership.get().ownerUuid()),
                    "ownerUuid=" + ownership.get().ownerUuid()
            ));
        }
        ClaimAccessDecisionView claimAccess = policyApi.evaluateClaimAccess(profileId.get(), playerUuid);
        assertions.add(check(
                fixture.fixtureKey() + " claim access coherent",
                claimAccess.status() != null && (!claimAccess.available() || claimAccess.status() != ClaimAccessDecisionView.Status.UNAVAILABLE || !claimAccess.allowed()),
                "status=" + claimAccess.status() + ", allowed=" + claimAccess.allowed()
        ));
        DamagePolicyDecisionView damage = policyApi.evaluateDamage(profileId.get(), playerUuid);
        assertions.add(check(
                fixture.fixtureKey() + " damage decision coherent",
                profileId.get().equals(damage.profileId()) && damage.status() != null && damage.reason() != null,
                "status=" + damage.status() + ", reason=" + damage.reason()
        ));
        assertions.add(check(
                fixture.fixtureKey() + " damage ownership matches fixture",
                damage.ownership() != null && fixtureSet.getFixture(fixture.fixtureKey()) != null,
                damage.ownership() == null ? "<null>" : "owner=" + damage.ownership().ownerUuid()
        ));
    }

    @Nonnull
    private ApiSelfTestAssertion checkRoleConfig(@Nonnull String name,
                                                 @Nonnull Optional<RoleScopedConfigView> view) {
        return check(
                name,
                view.isPresent() && view.get().detailsJson() != null && !view.get().detailsJson().isBlank(),
                view.map(value -> value.id() + " roles=" + value.roleIds()).orElse("<empty>")
        );
    }

    @Nonnull
    private ApiSelfTestFixtureSet requireFixtureSet(@Nonnull ApiSelfTestContext context,
                                                    @Nonnull List<ApiSelfTestAssertion> assertions,
                                                    @Nonnull String suiteName) {
        ApiSelfTestFixtureSet fixtureSet = context.fixtureSet();
        if (fixtureSet == null) {
            assertions.add(fail(suiteName + " fixtures available", "Run /tw api test prepare first."));
        }
        return fixtureSet;
    }

    @Nonnull
    private ApiSelfTestAssertion pass(@Nonnull String name, @Nonnull String detail) {
        return new ApiSelfTestAssertion(name, true, detail);
    }

    @Nonnull
    private ApiSelfTestAssertion fail(@Nonnull String name, @Nonnull String detail) {
        return new ApiSelfTestAssertion(name, false, detail);
    }

    @Nonnull
    private ApiSelfTestAssertion check(@Nonnull String name, boolean passed, @Nonnull String detail) {
        return new ApiSelfTestAssertion(name, passed, detail);
    }

    @Nonnull
    private ApiSelfTestAssertion checkMutation(@Nonnull String name,
                                               @Nonnull ProgressionMutationResult result,
                                               @Nonnull ProgressionMutationStatus... allowedStatuses) {
        for (ProgressionMutationStatus allowedStatus : allowedStatuses) {
            if (result.status() == allowedStatus) {
                return pass(name, describeMutation(result));
            }
        }
        return fail(name, describeMutation(result));
    }

    @Nonnull
    private String describeMutation(@Nonnull ProgressionMutationResult result) {
        return result.status() + ": " + result.message();
    }

    @Nonnull
    private String describeProgression(@Nonnull ProgressionView progression) {
        return "npc="
                + progression.npcUuid()
                + ", role="
                + progression.roleId()
                + ", happiness="
                + (progression.happiness() != null ? progression.happiness().value() : "<none>");
    }

    @Nonnull
    private Map<String, Double> collectTraitValues(@Nonnull ProgressionView progression) {
        if (progression.traits() == null || progression.traits().values().isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Double> values = new LinkedHashMap<>();
        for (ProgressionView.TraitValueView traitValue : progression.traits().values()) {
            values.put(traitValue.id(), traitValue.value());
        }
        return Map.copyOf(values);
    }

    @Nonnull
    private Map<String, String> collectAttachmentSelections(@Nonnull ProgressionView progression) {
        if (progression.attachments() == null) {
            return Map.of();
        }
        if (!progression.attachments().storedAttachmentIds().isEmpty()) {
            return Map.copyOf(new LinkedHashMap<>(progression.attachments().storedAttachmentIds()));
        }
        if (!progression.attachments().currentAttachmentIds().isEmpty()) {
            return Map.copyOf(new LinkedHashMap<>(progression.attachments().currentAttachmentIds()));
        }
        return Map.of();
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    @Nullable
    private String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (!isBlank(first)) {
            return first;
        }
        if (!isBlank(second)) {
            return second;
        }
        return null;
    }

    private void closeQuietly(@Nullable AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best-effort cleanup for in-game self-test registration handles.
        }
    }

    @Nonnull
    private String describePosition(Vector3View position) {
        if (position == null) {
            return "<null>";
        }
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", position.x(), position.y(), position.z());
    }
}
