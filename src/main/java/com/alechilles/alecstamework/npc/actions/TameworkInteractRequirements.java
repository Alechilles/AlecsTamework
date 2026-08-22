package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.avatarflight.AvatarFlightMountPreflight;

import com.alechilles.alecstamework.api.InteractionPresetDefinition;
import com.alechilles.alecstamework.api.InteractionRequirementContext;
import com.alechilles.alecstamework.api.InteractionRequirementSpec;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRuntime;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HarvestInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MountInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeCycleInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsEquippedRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInHandRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInInventoryRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.NpcHealthPercentRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MovementStateRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AlarmRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionContextRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.StringRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RequirementBucket;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RequirementGroup;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Tamework interact requirements. */
final class TameworkInteractRequirements {
    private final AvatarFlightMountPreflight avatarFlightMountPreflight = new AvatarFlightMountPreflight();
    private final ActionTameworkInteract owner;
    private final InteractionFeedHelper feedHelper;
    private final InteractionAlarmHelper alarmHelper;
    private final String harvestAlarmName;
    private final boolean interactionRequireOwnerDefault;
    @Nullable
    private final InteractionExtensionRuntime extensionRuntime;

    // Builds the requirement evaluator with shared helpers.
    TameworkInteractRequirements(ActionTameworkInteract owner,
                                 InteractionFeedHelper feedHelper,
                                 InteractionAlarmHelper alarmHelper,
                                 String harvestAlarmName,
                                 boolean interactionRequireOwnerDefault,
                                 @Nullable InteractionExtensionRuntime extensionRuntime) {
        this.owner = owner;
        this.feedHelper = feedHelper;
        this.alarmHelper = alarmHelper;
        this.harvestAlarmName = harvestAlarmName;
        this.interactionRequireOwnerDefault = interactionRequireOwnerDefault;
        this.extensionRuntime = extensionRuntime;
    }

    boolean requirementsMet(@Nullable String interactionConfigId,
                            int interactionIndex,
                            InteractionEntry entry,
                            Ref<EntityStore> npcRef,
                            Role role,
                            InfoProvider infoProvider,
                            Store<EntityStore> store,
                            Player player,
                            InteractionContextSnapshot ctx) {
        return requirementsMetInternal(
                interactionConfigId,
                interactionIndex,
                entry,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                false
        );
    }

    boolean requirementsMetForPrompt(@Nullable String interactionConfigId,
                                     int interactionIndex,
                                     InteractionEntry entry,
                                     Ref<EntityStore> npcRef,
                                     Role role,
                                     InfoProvider infoProvider,
                                     Store<EntityStore> store,
                                     Player player,
                                     InteractionContextSnapshot ctx) {
        return requirementsMetInternal(
                interactionConfigId,
                interactionIndex,
                entry,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                true
        );
    }

    private boolean requirementsMetInternal(@Nullable String interactionConfigId,
                                            int interactionIndex,
                                            InteractionEntry entry,
                                            Ref<EntityStore> npcRef,
                                            Role role,
                                            InfoProvider infoProvider,
                                            Store<EntityStore> store,
                                            Player player,
                                            InteractionContextSnapshot ctx,
                                            boolean forPrompt) {
        RequirementGroup requires = entry.getRequires();
        if (entry instanceof CustomInteraction customInteraction) {
            if (requires != null && !evaluateRequirementGroup(
                    interactionConfigId,
                    interactionIndex,
                    requires,
                    npcRef,
                    role,
                    infoProvider,
                    store,
                    player,
                    ctx,
                    forPrompt
            )) {
                return false;
            }
            return evaluateCustomPresetRequirements(
                    customInteraction,
                    interactionConfigId,
                    interactionIndex,
                    npcRef,
                    role,
                    infoProvider,
                    store,
                    player,
                    ctx,
                    forPrompt
            );
        }
        boolean baseRequirementsMet = false;
        if (entry instanceof TameInteraction) {
            baseRequirementsMet = meetsTameRequirements((TameInteraction) entry, npcRef, role, infoProvider, store, player, ctx);
        } else if (entry instanceof FeedInteraction) {
            baseRequirementsMet = meetsFeedRequirements((FeedInteraction) entry, npcRef, role, infoProvider, store, player, ctx);
        } else if (entry instanceof HarvestInteraction) {
            baseRequirementsMet = meetsHarvestRequirements((HarvestInteraction) entry, npcRef, role, infoProvider, store, player, ctx, forPrompt);
        } else if (entry instanceof MountInteraction) {
            baseRequirementsMet = meetsMountRequirements((MountInteraction) entry, npcRef, role, infoProvider, store, player, ctx);
        } else if (entry instanceof ModeCycleInteraction) {
            baseRequirementsMet = meetsModeCycleRequirements((ModeCycleInteraction) entry, npcRef, role, infoProvider, store, player, ctx);
        } else if (entry instanceof BreedInteraction) {
            baseRequirementsMet = meetsBreedRequirements((BreedInteraction) entry, npcRef, role, infoProvider, store, player, ctx);
        }
        if (!baseRequirementsMet) {
            return false;
        }
        return requires == null || evaluateRequirementGroup(
                interactionConfigId,
                interactionIndex,
                requires,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                forPrompt
        );
    }

    private boolean evaluateRequirementGroup(@Nullable String interactionConfigId,
                                             int interactionIndex,
                                             RequirementGroup group,
                                             Ref<EntityStore> npcRef,
                                             Role role,
                                             InfoProvider infoProvider,
                                             Store<EntityStore> store,
                                             Player player,
                                             InteractionContextSnapshot ctx,
                                             boolean forPrompt) {
        if (group == null) {
            return false;
        }
        if (!evaluateAllBucket(
                interactionConfigId,
                interactionIndex,
                group.getAll(),
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                forPrompt
        )) {
            return false;
        }
        RequirementBucket any = group.getAny();
        if (any == null || any.isEmpty()) {
            return true;
        }
        return evaluateAnyBucket(
                interactionConfigId,
                interactionIndex,
                any,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                forPrompt
        );
    }

    private boolean evaluateAllBucket(@Nullable String interactionConfigId,
                                      int interactionIndex,
                                      RequirementBucket bucket,
                                      Ref<EntityStore> npcRef,
                                      Role role,
                                      InfoProvider infoProvider,
                                      Store<EntityStore> store,
                                      Player player,
                                      InteractionContextSnapshot ctx,
                                      boolean forPrompt) {
        if (bucket == null) {
            return true;
        }
        if (bucket.isLovedItems()
                && !owner.isHeldItemInList(owner.resolveLovedItems(role, ctx), ctx)) {
            return false;
        }
        if (bucket.isHarvestable()
                && !owner.resolveIsHarvestable(role, ctx)) {
            return false;
        }
        if (bucket.isMountable()
                && !owner.resolveIsMountable(role, ctx)) {
            return false;
        }
        if (bucket.isTamed()
                && !owner.isTamed(npcRef, store, ctx)) {
            return false;
        }
        if (bucket.isNotTamed()
                && owner.isTamed(npcRef, store, ctx)) {
            return false;
        }
        if (bucket.isPlayerHandEmpty()
                && !owner.isPlayerHandEmpty(ctx)) {
            return false;
        }
        if (bucket.isPlayerCrouching()
                && !owner.isPlayerCrouching(role, infoProvider, store, ctx)) {
            return false;
        }
        if (bucket.isPlayerIsOwner()
                && !owner.isOwner(npcRef, store, player, ctx)) {
            return false;
        }
        if (bucket.isHarvestAlarmReady()
                && !owner.isHarvestAlarmReady(npcRef, store, ctx)) {
            return false;
        }
        if (bucket.isHarvestInteractionContext()
                && !matchesHarvestContext(role, infoProvider, ctx, forPrompt)) {
            return false;
        }
        if (hasRequirements(bucket.getItemsInHand())
                && !matchesAnyItemsInHand(bucket.getItemsInHand(), role, ctx)) {
            return false;
        }
        if (hasRequirements(bucket.getItemsInInventory())
                && !matchesAnyItemsInInventory(bucket.getItemsInInventory(), role, ctx)) {
            return false;
        }
        if (hasRequirements(bucket.getItemsEquipped())
                && !matchesAnyItemsEquipped(bucket.getItemsEquipped(), role, ctx)) {
            return false;
        }
        if (hasRequirements(bucket.getParameter())
                && !matchesAnyParameters(bucket.getParameter(), role)) {
            return false;
        }
        if (hasRequirements(bucket.getNpcHealthPercent())
                && !matchesAnyNpcHealthPercent(bucket.getNpcHealthPercent(), npcRef, store)) {
            return false;
        }
        if (hasRequirements(bucket.getAlarmState())
                && !matchesAnyAlarmState(bucket.getAlarmState(), npcRef, store, role, ctx)) {
            return false;
        }
        if (hasRequirements(bucket.getNpcState())
                && !matchesAnyNpcState(bucket.getNpcState(), role)) {
            return false;
        }
        if (hasRequirements(bucket.getPlayerMovementState())
                && !matchesAnyPlayerMovementState(bucket.getPlayerMovementState(), role, infoProvider, store, ctx)) {
            return false;
        }
        if (hasRequirements(bucket.getInteractionContext())
                && !matchesAnyInteractionContext(bucket.getInteractionContext(), role, infoProvider, ctx, forPrompt)) {
            return false;
        }
        if (hasRequirements(bucket.getCustom())
                && !matchesAnyCustomRequirements(
                bucket.getCustom(),
                interactionConfigId,
                interactionIndex,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                forPrompt
        )) {
            return false;
        }
        return true;
    }

    private boolean evaluateAnyBucket(@Nullable String interactionConfigId,
                                      int interactionIndex,
                                      RequirementBucket bucket,
                                      Ref<EntityStore> npcRef,
                                      Role role,
                                      InfoProvider infoProvider,
                                      Store<EntityStore> store,
                                      Player player,
                                      InteractionContextSnapshot ctx,
                                      boolean forPrompt) {
        if (bucket == null) {
            return false;
        }
        if (bucket.isLovedItems()
                && owner.isHeldItemInList(owner.resolveLovedItems(role, ctx), ctx)) {
            return true;
        }
        if (bucket.isHarvestable()
                && owner.resolveIsHarvestable(role, ctx)) {
            return true;
        }
        if (bucket.isMountable()
                && owner.resolveIsMountable(role, ctx)) {
            return true;
        }
        if (bucket.isTamed()
                && owner.isTamed(npcRef, store, ctx)) {
            return true;
        }
        if (bucket.isNotTamed()
                && !owner.isTamed(npcRef, store, ctx)) {
            return true;
        }
        if (bucket.isPlayerHandEmpty()
                && owner.isPlayerHandEmpty(ctx)) {
            return true;
        }
        if (bucket.isPlayerCrouching()
                && owner.isPlayerCrouching(role, infoProvider, store, ctx)) {
            return true;
        }
        if (bucket.isPlayerIsOwner()
                && owner.isOwner(npcRef, store, player, ctx)) {
            return true;
        }
        if (bucket.isHarvestAlarmReady()
                && owner.isHarvestAlarmReady(npcRef, store, ctx)) {
            return true;
        }
        if (bucket.isHarvestInteractionContext()
                && matchesHarvestContext(role, infoProvider, ctx, forPrompt)) {
            return true;
        }
        if (matchesAnyItemsInHand(bucket.getItemsInHand(), role, ctx)) {
            return true;
        }
        if (matchesAnyItemsInInventory(bucket.getItemsInInventory(), role, ctx)) {
            return true;
        }
        if (matchesAnyItemsEquipped(bucket.getItemsEquipped(), role, ctx)) {
            return true;
        }
        if (matchesAnyParameters(bucket.getParameter(), role)) {
            return true;
        }
        if (matchesAnyNpcHealthPercent(bucket.getNpcHealthPercent(), npcRef, store)) {
            return true;
        }
        if (matchesAnyAlarmState(bucket.getAlarmState(), npcRef, store, role, ctx)) {
            return true;
        }
        if (matchesAnyNpcState(bucket.getNpcState(), role)) {
            return true;
        }
        if (matchesAnyPlayerMovementState(bucket.getPlayerMovementState(), role, infoProvider, store, ctx)) {
            return true;
        }
        if (matchesAnyInteractionContext(bucket.getInteractionContext(), role, infoProvider, ctx, forPrompt)) {
            return true;
        }
        if (matchesAnyCustomRequirements(
                bucket.getCustom(),
                interactionConfigId,
                interactionIndex,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                forPrompt
        )) {
            return true;
        }
        return false;
    }

    private boolean matchesAnyItemsInHand(ItemsInHandRequirement[] requirements,
                                          Role role,
                                          InteractionContextSnapshot ctx) {
        if (!hasRequirements(requirements)) {
            return false;
        }
        for (ItemsInHandRequirement requirement : requirements) {
            if (requirement != null && owner.matchesItemsInHand(requirement, role, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyItemsInInventory(ItemsInInventoryRequirement[] requirements,
                                               Role role,
                                               InteractionContextSnapshot ctx) {
        if (!hasRequirements(requirements)) {
            return false;
        }
        for (ItemsInInventoryRequirement requirement : requirements) {
            if (requirement != null && owner.matchesItemsInInventory(requirement, role, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyItemsEquipped(ItemsEquippedRequirement[] requirements,
                                            Role role,
                                            InteractionContextSnapshot ctx) {
        if (!hasRequirements(requirements)) {
            return false;
        }
        for (ItemsEquippedRequirement requirement : requirements) {
            if (requirement != null && owner.matchesItemsEquipped(requirement, role, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyParameters(ParamRequirement[] requirements,
                                         Role role) {
        if (!hasRequirements(requirements)) {
            return false;
        }
        for (ParamRequirement requirement : requirements) {
            if (requirement != null && owner.matchesParamRequirement(requirement, role)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyNpcHealthPercent(NpcHealthPercentRequirement[] requirements,
                                               Ref<EntityStore> npcRef,
                                               Store<EntityStore> store) {
        if (!hasRequirements(requirements)) {
            return false;
        }
        for (NpcHealthPercentRequirement requirement : requirements) {
            if (requirement != null && owner.matchesNpcHealthPercent(requirement, npcRef, store)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyAlarmState(AlarmRequirement[] requirements,
                                         Ref<EntityStore> npcRef,
                                         Store<EntityStore> store,
                                         Role role,
                                         InteractionContextSnapshot ctx) {
        if (!hasRequirements(requirements)) {
            return false;
        }
        for (AlarmRequirement requirement : requirements) {
            if (requirement != null && owner.matchesAlarmState(requirement, npcRef, store, role, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyNpcState(StringRequirement[] requirements,
                                       Role role) {
        if (!hasRequirements(requirements)) {
            return false;
        }
        for (StringRequirement requirement : requirements) {
            if (requirement != null && owner.matchesNpcState(requirement, role)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyPlayerMovementState(MovementStateRequirement[] requirements,
                                                  Role role,
                                                  InfoProvider infoProvider,
                                                  Store<EntityStore> store,
                                                  InteractionContextSnapshot ctx) {
        if (!hasRequirements(requirements)) {
            return false;
        }
        for (MovementStateRequirement requirement : requirements) {
            if (requirement != null && owner.matchesMovementState(requirement, role, infoProvider, store, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyInteractionContext(InteractionContextRequirement[] requirements,
                                                 Role role,
                                                 InfoProvider infoProvider,
                                                 InteractionContextSnapshot ctx,
                                                 boolean forPrompt) {
        if (!hasRequirements(requirements)) {
            return false;
        }
        for (InteractionContextRequirement requirement : requirements) {
            if (requirement != null && matchesInteractionContext(requirement, role, infoProvider, ctx, forPrompt)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyCustomRequirements(CustomRequirement[] requirements,
                                                 @Nullable String interactionConfigId,
                                                 int interactionIndex,
                                                 Ref<EntityStore> npcRef,
                                                 Role role,
                                                 InfoProvider infoProvider,
                                                 Store<EntityStore> store,
                                                 Player player,
                                                 InteractionContextSnapshot ctx,
                                                 boolean forPrompt) {
        if (!hasRequirements(requirements)) {
            return false;
        }
        for (CustomRequirement requirement : requirements) {
            if (requirement != null && evaluateCustomRequirement(
                    requirement,
                    interactionConfigId,
                    interactionIndex,
                    npcRef,
                    role,
                    infoProvider,
                    store,
                    player,
                    ctx,
                    forPrompt
            )) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRequirements(Object[] requirements) {
        return requirements != null && requirements.length > 0;
    }

    private boolean meetsTameRequirements(TameInteraction interaction,
                                          Ref<EntityStore> npcRef,
                                          Role role,
                                          InfoProvider infoProvider,
                                          Store<EntityStore> store,
                                          Player player,
                                          InteractionContextSnapshot ctx) {
        if (owner.isTamed(npcRef, store, ctx)) {
            return false;
        }
        return matchesPresetItems(
                interaction.getUseLovedItems(),
                interaction.getItemsInHand(),
                interaction.getItemsParam(),
                role,
                player,
                ctx,
                true
        );
    }

    private boolean meetsFeedRequirements(FeedInteraction interaction,
                                          Ref<EntityStore> npcRef,
                                          Role role,
                                          InfoProvider infoProvider,
                                          Store<EntityStore> store,
                                          Player player,
                                          InteractionContextSnapshot ctx) {
        if (!owner.isTamed(npcRef, store, ctx)) {
            return false;
        }
        InteractionRequiredItems resolved = owner.resolveFeedRequirementItems(interaction, role, ctx);
        if (resolved == null || !resolved.requiresItems()) {
            return true;
        }
        return owner.isHeldItemInList(resolved.getItems(), ctx);
    }

    private boolean meetsHarvestRequirements(HarvestInteraction interaction,
                                             Ref<EntityStore> npcRef,
                                             Role role,
                                             InfoProvider infoProvider,
                                             Store<EntityStore> store,
                                             Player player,
                                             InteractionContextSnapshot ctx,
                                             boolean forPrompt) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        boolean requireHarvestable = optionOrDefault(interaction.getRequireHarvestable(), true);
        boolean requireAlarm = optionOrDefault(interaction.getRequireHarvestAlarmReady(), true);
        boolean requireContext = optionOrDefault(interaction.getRequireHarvestInteractionContext(), true);
        if (requireTamed && !owner.isTamed(npcRef, store, ctx)) {
            return false;
        }
        if (requireHarvestable && !owner.resolveIsHarvestable(role, ctx)) {
            return false;
        }
        if (requireAlarm
                && !owner.isHarvestAlarmReady(npcRef, store, ctx)) {
            return false;
        }
        if (requireContext && !matchesHarvestContext(role, infoProvider, ctx, forPrompt)) {
            return false;
        }
        return true;
    }

    private boolean meetsMountRequirements(MountInteraction interaction,
                                           Ref<EntityStore> npcRef,
                                           Role role,
                                           InfoProvider infoProvider,
                                           Store<EntityStore> store,
                                           Player player,
                                           InteractionContextSnapshot ctx) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        boolean requireOwner = resolveInteractionRequireOwner(interaction.getRequireOwner(), interactionRequireOwnerDefault);
        boolean requireMountable = optionOrDefault(interaction.getRequireMountable(), true);
        boolean requireCrouching = optionOrDefault(interaction.getRequireCrouching(), true);
        if (requireTamed && !owner.isTamed(npcRef, store, ctx)) {
            return false;
        }
        if (requireOwner && !owner.isOwner(npcRef, store, player, ctx)) {
            return false;
        }
        if (requireMountable && !owner.resolveIsMountable(role, ctx)) {
            return false;
        }
        if (requireCrouching && !owner.isPlayerCrouching(role, infoProvider, store, ctx)) {
            return false;
        }
        String mountMode = owner.getRoleStringParam(role, "MountMode");
        if (InteractionMountMode.parse(mountMode) == InteractionMountMode.TAMEWORK_AVATAR_FLIGHT) {
            Ref<EntityStore> playerRef = owner.resolveInteractionTarget(role, infoProvider);
            String configId = owner.getRoleStringParam(role, InteractionMountEffects.AVATAR_FLIGHT_CONFIG_PARAM);
            if (!avatarFlightMountPreflight.canStart(store, npcRef, playerRef, role, configId)) {
                return false;
            }
        }
        return true;
    }

    private boolean meetsModeCycleRequirements(ModeCycleInteraction interaction,
                                               Ref<EntityStore> npcRef,
                                               Role role,
                                               InfoProvider infoProvider,
                                               Store<EntityStore> store,
                                               Player player,
                                               InteractionContextSnapshot ctx) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        boolean requireOwner = resolveInteractionRequireOwner(interaction.getRequireOwner(), interactionRequireOwnerDefault);
        if (requireTamed && !owner.isTamed(npcRef, store, ctx)) {
            return false;
        }
        if (requireOwner && !owner.isOwner(npcRef, store, player, ctx)) {
            return false;
        }
        return true;
    }

    private boolean meetsBreedRequirements(BreedInteraction interaction,
                                           Ref<EntityStore> npcRef,
                                           Role role,
                                           InfoProvider infoProvider,
                                           Store<EntityStore> store,
                                           Player player,
                                           InteractionContextSnapshot ctx) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        if (requireTamed && !owner.isTamed(npcRef, store, ctx)) {
            return false;
        }
        // Keep the action selectable so execution can explain the cooldown.
        return true;
    }

    private boolean evaluateCustomPresetRequirements(CustomInteraction interaction,
                                                     @Nullable String interactionConfigId,
                                                     int interactionIndex,
                                                     Ref<EntityStore> npcRef,
                                                     Role role,
                                                     InfoProvider infoProvider,
                                                     Store<EntityStore> store,
                                                     Player player,
                                                     InteractionContextSnapshot ctx,
                                                     boolean forPrompt) {
        if (interaction == null || interaction.getPresetId() == null || interaction.getPresetId().isBlank()) {
            return true;
        }
        if (extensionRuntime == null) {
            return false;
        }
        Optional<InteractionPresetDefinition> preset = extensionRuntime.resolvePreset(interaction.getPresetId());
        if (preset.isEmpty()) {
            return false;
        }
        InteractionRequirementContext runtimeContext = buildRequirementContext(
                interactionConfigId,
                interactionIndex,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                forPrompt
        );
        if (runtimeContext == null) {
            return false;
        }
        for (InteractionRequirementSpec requirementSpec : preset.get().requirements()) {
            if (!extensionRuntime.evaluateRequirement(requirementSpec, runtimeContext)) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateCustomRequirement(CustomRequirement requirement,
                                              @Nullable String interactionConfigId,
                                              int interactionIndex,
                                              Ref<EntityStore> npcRef,
                                              Role role,
                                              InfoProvider infoProvider,
                                              Store<EntityStore> store,
                                              Player player,
                                              InteractionContextSnapshot ctx,
                                              boolean forPrompt) {
        if (requirement == null || extensionRuntime == null) {
            return false;
        }
        InteractionRequirementContext runtimeContext = buildRequirementContext(
                interactionConfigId,
                interactionIndex,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                forPrompt
        );
        if (runtimeContext == null) {
            return false;
        }
        InteractionRequirementSpec spec = new InteractionRequirementSpec(
                requirement.getId(),
                requirement.getParam(),
                toValues(requirement.getValues()),
                requirement.getJsonPayload()
        );
        return extensionRuntime.evaluateRequirement(spec, runtimeContext);
    }

    @Nullable
    private InteractionRequirementContext buildRequirementContext(@Nullable String interactionConfigId,
                                                                  int interactionIndex,
                                                                  Ref<EntityStore> npcRef,
                                                                  Role role,
                                                                  InfoProvider infoProvider,
                                                                  Store<EntityStore> store,
                                                                  Player player,
                                                                  InteractionContextSnapshot ctx,
                                                                  boolean forPrompt) {
        if (npcRef == null || role == null || store == null) {
            return null;
        }
        return new InteractionRequirementContext(
                interactionConfigId,
                interactionIndex,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx != null ? ctx.playerId : null,
                ctx != null ? ctx.activeItemId : null,
                forPrompt
        );
    }

    private List<String> toValues(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private boolean matchesPresetItems(Boolean useLovedItemsFlag,
                                       String[] explicitItems,
                                       String paramName,
                                       Role role,
                                       Player player,
                                       InteractionContextSnapshot ctx,
                                       boolean defaultUseLovedItems) {
        InteractionRequiredItems resolved = resolvePresetItemsInHand(
                optionOrDefault(useLovedItemsFlag, defaultUseLovedItems),
                explicitItems,
                paramName,
                role,
                ctx
        );
        if (!resolved.requiresItems()) {
            return true;
        }
        return owner.isHeldItemInList(resolved.getItems(), ctx);
    }

    private InteractionRequiredItems resolvePresetItemsInHand(boolean useLovedItems,
                                                              String[] explicitItems,
                                                              String paramName,
                                                              Role role,
                                                              InteractionContextSnapshot ctx) {
        String[] paramItems = owner.resolveItemsParam(role, ctx, paramName);
        if (hasItems(paramItems)) {
            return new InteractionRequiredItems(paramItems, true);
        }
        if (hasItems(explicitItems)) {
            return new InteractionRequiredItems(explicitItems, true);
        }
        if (useLovedItems) {
            return new InteractionRequiredItems(owner.resolveLovedItems(role, ctx), true);
        }
        return new InteractionRequiredItems(new String[0], false);
    }

    private boolean hasItems(String[] items) {
        if (items == null || items.length == 0) {
            return false;
        }
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean optionOrDefault(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    static boolean resolveInteractionRequireOwner(@Nullable Boolean ignoredEntryRequireOwner, boolean globalRequireOwner) {
        // /tw settings global ownership requirement always wins for interaction checks.
        return TameworkRuntimeSettings.interactionRequiresOwner(globalRequireOwner);
    }

    private boolean matchesHarvestContext(Role role,
                                          InfoProvider infoProvider,
                                          InteractionContextSnapshot ctx,
                                          boolean forPrompt) {
        return forPrompt
                ? owner.matchesHarvestContextForPrompt(role, infoProvider, ctx)
                : owner.matchesHarvestContext(role, infoProvider, ctx);
    }

    private boolean matchesInteractionContext(com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionContextRequirement requirement,
                                              Role role,
                                              InfoProvider infoProvider,
                                              InteractionContextSnapshot ctx,
                                              boolean forPrompt) {
        return forPrompt
                ? owner.matchesInteractionContextForPrompt(requirement, role, infoProvider, ctx)
                : owner.matchesInteractionContext(requirement, role, infoProvider, ctx);
    }
}
