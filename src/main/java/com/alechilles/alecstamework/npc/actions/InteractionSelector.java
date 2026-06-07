package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.ArrayList;
import java.util.List;

/** Selects the first matching interaction entry, respecting cooldowns and requirements. */
final class InteractionSelector {
    private final ActionTameworkInteract owner;
    private final TameworkInteractRequirements requirements;
    private final InteractionCooldowns cooldowns;
    private final InteractionAlarmHelper alarmHelper;
    private final String harvestAlarmName;
    private final InteractionPromptPlanCache promptPlanCache = new InteractionPromptPlanCache();

    // Builds the selector with requirement, cooldown, and alarm helpers.
    InteractionSelector(ActionTameworkInteract owner,
                        TameworkInteractRequirements requirements,
                        InteractionCooldowns cooldowns,
                        InteractionAlarmHelper alarmHelper,
                        String harvestAlarmName) {
        this.owner = owner;
        this.requirements = requirements;
        this.cooldowns = cooldowns;
        this.alarmHelper = alarmHelper;
        this.harvestAlarmName = harvestAlarmName;
    }

    // Returns the first interaction entry that passes requirements and cooldown checks.
    ActionTameworkInteract.ResolvedInteraction selectInteraction(TwInteractionConfig config,
                                                                  Ref<EntityStore> npcRef,
                                                                  Role role,
                                                                  InfoProvider infoProvider,
                                                                  Store<EntityStore> store,
                                                                  Player player,
                                                                  InteractionContextSnapshot ctx) {
        return selectInteractionInternal(config, npcRef, role, infoProvider, store, player, ctx, false);
    }

    // Selects an interaction for prompts, preferring contextual and conditional entries.
    ActionTameworkInteract.ResolvedInteraction selectInteractionForPrompt(TwInteractionConfig config,
                                                                          Ref<EntityStore> npcRef,
                                                                          Role role,
                                                                          InfoProvider infoProvider,
                                                                          Store<EntityStore> store,
                                                                          Player player,
                                                                          InteractionContextSnapshot ctx) {
        InteractionEntry[] entries = config != null ? config.getInteractions() : null;
        String interactionConfigId = config != null ? config.getId() : null;
        if (entries == null || entries.length == 0) {
            return null;
        }
        InteractionPromptPlan promptPlan = promptPlanCache.getOrBuild(config, this::buildPromptPlan);
        ActionTameworkInteract.ResolvedInteraction[] blockedContext = new ActionTameworkInteract.ResolvedInteraction[1];
        ActionTameworkInteract.ResolvedInteraction contextual = findFirstPromptMatch(
                config,
                interactionConfigId,
                entries,
                promptPlan.contextualIndexes(),
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                blocked -> blockedContext[0] = blockedContext[0] == null ? blocked : blockedContext[0]
        );
        if (contextual != null) {
            return contextual;
        }
        ActionTameworkInteract.ResolvedInteraction conditional = findFirstPromptMatch(
                config,
                interactionConfigId,
                entries,
                promptPlan.conditionalIndexes(),
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                blocked -> {
                }
        );
        if (conditional != null) {
            return conditional;
        }
        ActionTameworkInteract.ResolvedInteraction generic = findFirstPromptMatch(
                config,
                interactionConfigId,
                entries,
                promptPlan.genericIndexes(),
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                blocked -> {
                }
        );
        return generic != null ? generic : blockedContext[0];
    }

    private ActionTameworkInteract.ResolvedInteraction findFirstPromptMatch(TwInteractionConfig config,
                                                                            String interactionConfigId,
                                                                            InteractionEntry[] entries,
                                                                            int[] indexes,
                                                                            Ref<EntityStore> npcRef,
                                                                            Role role,
                                                                            InfoProvider infoProvider,
                                                                            Store<EntityStore> store,
                                                                            Player player,
                                                                            InteractionContextSnapshot ctx,
                                                                            BlockedContextConsumer blockedContextConsumer) {
        if (indexes == null || indexes.length == 0) {
            return null;
        }
        for (int index : indexes) {
            if (index < 0 || index >= entries.length) {
                continue;
            }
            InteractionEntry entry = entries[index];
            if (entry == null || !entry.isEnabled()) {
                continue;
            }
            int cooldownSeconds = cooldowns.resolveCooldownSeconds(config, entry);
            String cooldownAlarmName = cooldownSeconds > 0
                    ? cooldowns.buildCooldownAlarmName(config, index)
                    : null;
            if (cooldownSeconds > 0
                    && (cooldownAlarmName == null || !cooldowns.isCooldownReady(npcRef, store, cooldownAlarmName))) {
                blockedContextConsumer.accept(new ActionTameworkInteract.ResolvedInteraction(
                        interactionConfigId,
                        entry,
                        index,
                        cooldownSeconds,
                        cooldownAlarmName,
                        true
                ));
                continue;
            }
            if (isHarvestAlarmBlocking(entry, npcRef, role, infoProvider, store, ctx, true)) {
                blockedContextConsumer.accept(new ActionTameworkInteract.ResolvedInteraction(
                        interactionConfigId,
                        entry,
                        index,
                        cooldownSeconds,
                        cooldownAlarmName,
                        true
                ));
                continue;
            }
            if (!requirements.requirementsMetForPrompt(
                    interactionConfigId,
                    index,
                    entry,
                    npcRef,
                    role,
                    infoProvider,
                    store,
                    player,
                    ctx
            )) {
                continue;
            }
            return new ActionTameworkInteract.ResolvedInteraction(
                    interactionConfigId,
                    entry,
                    index,
                    cooldownSeconds,
                    cooldownAlarmName
            );
        }
        return null;
    }

    InteractionPromptPlan buildPromptPlan(InteractionEntry[] entries) {
        if (entries == null || entries.length == 0) {
            return new InteractionPromptPlan(null, null, null);
        }
        List<Integer> contextual = new ArrayList<>();
        List<Integer> conditional = new ArrayList<>();
        List<Integer> generic = new ArrayList<>();
        for (int index = 0; index < entries.length; index++) {
            InteractionEntry entry = entries[index];
            if (entry == null || !entry.isEnabled()) {
                continue;
            }
            EntryPromptCategory category = classifyPromptEntry(entry);
            switch (category) {
                case CONTEXTUAL -> contextual.add(index);
                case CONDITIONAL -> conditional.add(index);
                case GENERIC -> generic.add(index);
                default -> {
                }
            }
        }
        return new InteractionPromptPlan(
                toIntArray(contextual),
                toIntArray(conditional),
                toIntArray(generic)
        );
    }

    private static int[] toIntArray(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return new int[0];
        }
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private EntryPromptCategory classifyPromptEntry(InteractionEntry entry) {
        if (isContextualEntry(entry)) {
            return EntryPromptCategory.CONTEXTUAL;
        }
        if (isConditionalEntry(entry)) {
            return EntryPromptCategory.CONDITIONAL;
        }
        return EntryPromptCategory.GENERIC;
    }

    private interface BlockedContextConsumer {
        void accept(ActionTameworkInteract.ResolvedInteraction blockedContext);
    }

    private enum EntryPromptCategory {
        CONTEXTUAL,
        CONDITIONAL,
        GENERIC
    }

    private ActionTameworkInteract.ResolvedInteraction selectInteractionInternal(TwInteractionConfig config,
                                                                                 Ref<EntityStore> npcRef,
                                                                                 Role role,
                                                                                 InfoProvider infoProvider,
                                                                                 Store<EntityStore> store,
                                                                                 Player player,
                                                                                 InteractionContextSnapshot ctx,
                                                                                 boolean contextualOnly) {
        InteractionEntry[] entries = config != null ? config.getInteractions() : null;
        String interactionConfigId = config != null ? config.getId() : null;
        if (entries == null || entries.length == 0) {
            return null;
        }
        ActionTameworkInteract.ResolvedInteraction blockedContext = null;
        for (int index = 0; index < entries.length; index++) {
            InteractionEntry entry = entries[index];
            if (entry == null || !entry.isEnabled()) {
                continue;
            }
            if (contextualOnly && !isContextualEntry(entry)) {
                continue;
            }
            int cooldownSeconds = cooldowns.resolveCooldownSeconds(config, entry);
            String cooldownAlarmName = cooldownSeconds > 0
                    ? cooldowns.buildCooldownAlarmName(config, index)
                    : null;
            if (cooldownSeconds > 0
                    && (cooldownAlarmName == null || !cooldowns.isCooldownReady(npcRef, store, cooldownAlarmName))) {
                if (isContextualEntry(entry) && blockedContext == null) {
                    blockedContext = new ActionTameworkInteract.ResolvedInteraction(
                            interactionConfigId,
                            entry,
                            index,
                            cooldownSeconds,
                            cooldownAlarmName,
                            true
                    );
                }
                continue;
            }
            if (isHarvestAlarmBlocking(entry, npcRef, role, infoProvider, store, ctx, false)) {
                if (isContextualEntry(entry) && blockedContext == null) {
                    blockedContext = new ActionTameworkInteract.ResolvedInteraction(
                            interactionConfigId,
                            entry,
                            index,
                            cooldownSeconds,
                            cooldownAlarmName,
                            true
                    );
                }
                continue;
            }
            if (requirements.requirementsMet(
                    interactionConfigId,
                    index,
                    entry,
                    npcRef,
                    role,
                    infoProvider,
                    store,
                    player,
                    ctx
            )) {
                return new ActionTameworkInteract.ResolvedInteraction(
                        interactionConfigId,
                        entry,
                        index,
                        cooldownSeconds,
                        cooldownAlarmName
                );
            }
        }
        return blockedContext;
    }

    // Returns true when harvest alarm cooldown blocks a contextual harvest interaction.
    private boolean isHarvestAlarmBlocking(InteractionEntry entry,
                                           Ref<EntityStore> npcRef,
                                           Role role,
                                           InfoProvider infoProvider,
                                           Store<EntityStore> store,
                                           InteractionContextSnapshot ctx,
                                           boolean forPrompt) {
        if (!(entry instanceof TwInteractionConfig.HarvestInteraction)) {
            return false;
        }
        TwInteractionConfig.HarvestInteraction harvest = (TwInteractionConfig.HarvestInteraction) entry;
        boolean requireContext = harvest.getRequireHarvestInteractionContext() == null
                || harvest.getRequireHarvestInteractionContext();
        if (!requireContext
                || !(forPrompt
                ? owner.matchesHarvestContextForPrompt(role, infoProvider, ctx)
                : owner.matchesHarvestContext(role, infoProvider, ctx))) {
            return false;
        }
        boolean requireAlarm = harvest.getRequireHarvestAlarmReady() == null
                || harvest.getRequireHarvestAlarmReady();
        if (!requireAlarm) {
            return false;
        }
        return forPrompt
                ? !owner.isHarvestAlarmReady(npcRef, store, ctx)
                : !owner.isHarvestAlarmReady(npcRef, store, ctx);
    }

    // Returns true when the interaction is explicitly tied to contextual input.
    private boolean isContextualEntry(InteractionEntry entry) {
        if (entry == null) {
            return false;
        }
        if (entry instanceof TwInteractionConfig.HarvestInteraction) {
            Boolean requireContext = ((TwInteractionConfig.HarvestInteraction) entry).getRequireHarvestInteractionContext();
            if (requireContext == null || requireContext) {
                return true;
            }
        }
        TwInteractionConfig.RequirementGroup requires = entry.getRequires();
        if (requires == null) {
            return false;
        }
        return bucketHasContext(requires.getAll()) || bucketHasContext(requires.getAny());
    }

    // Checks if a requirement bucket includes contextual interaction requirements.
    private boolean bucketHasContext(TwInteractionConfig.RequirementBucket bucket) {
        if (bucket == null) {
            return false;
        }
        if (bucket.isHarvestInteractionContext()) {
            return true;
        }
        TwInteractionConfig.InteractionContextRequirement[] contexts = bucket.getInteractionContext();
        return contexts != null && contexts.length > 0;
    }

    private boolean isConditionalEntry(InteractionEntry entry) {
        if (entry == null) {
            return false;
        }
        if (entry instanceof TwInteractionConfig.TameInteraction
                || entry instanceof TwInteractionConfig.FeedInteraction
                || entry instanceof TwInteractionConfig.HarvestInteraction
                || entry instanceof TwInteractionConfig.MountInteraction
                || entry instanceof TwInteractionConfig.BreedInteraction) {
            return true;
        }
        if (entry instanceof TwInteractionConfig.ModeCycleInteraction) {
            TwInteractionConfig.ModeCycleInteraction mode = (TwInteractionConfig.ModeCycleInteraction) entry;
            if (mode.getRequireTamed() != null || mode.getRequireOwner() != null) {
                return true;
            }
        }
        TwInteractionConfig.RequirementGroup requires = entry.getRequires();
        return requires != null
                && (bucketHasRequirements(requires.getAll()) || bucketHasRequirements(requires.getAny()));
    }

    private boolean bucketHasRequirements(TwInteractionConfig.RequirementBucket bucket) {
        if (bucket == null) {
            return false;
        }
        if (bucket.isLovedItems()
                || bucket.isHarvestable()
                || bucket.isMountable()
                || bucket.isTamed()
                || bucket.isNotTamed()
                || bucket.isPlayerCrouching()
                || bucket.isPlayerIsOwner()
                || bucket.isHarvestAlarmReady()
                || bucket.isHarvestInteractionContext()) {
            return true;
        }
        return hasEntries(bucket.getItemsInHand())
                || hasEntries(bucket.getItemsInInventory())
                || hasEntries(bucket.getItemsEquipped())
                || hasEntries(bucket.getNpcHealthPercent())
                || hasEntries(bucket.getParameter())
                || hasEntries(bucket.getAlarmState())
                || hasEntries(bucket.getNpcState())
                || hasEntries(bucket.getPlayerMovementState())
                || hasEntries(bucket.getInteractionContext());
    }

    private boolean hasEntries(Object[] entries) {
        return entries != null && entries.length > 0;
    }
}
