package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

/** Selects the first matching interaction entry, respecting cooldowns and requirements. */
final class InteractionSelector {
    private final ActionTameworkInteract owner;
    private final TameworkInteractRequirements requirements;
    private final InteractionCooldowns cooldowns;
    private final InteractionAlarmHelper alarmHelper;
    private final String harvestAlarmName;

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
        if (entries == null || entries.length == 0) {
            return null;
        }
        ActionTameworkInteract.ResolvedInteraction best = null;
        int bestScore = -1;
        for (int index = 0; index < entries.length; index++) {
            InteractionEntry entry = entries[index];
            if (entry == null || !entry.isEnabled()) {
                continue;
            }
            boolean contextual = isContextualEntry(entry);
            int score = contextual ? 100 : (isConditionalEntry(entry) ? 50 : 0);
            if (score < bestScore) {
                continue;
            }
            int cooldownSeconds = cooldowns.resolveCooldownSeconds(config, entry);
            String cooldownAlarmName = cooldownSeconds > 0
                    ? cooldowns.buildCooldownAlarmName(config, index)
                    : null;
            if (cooldownSeconds > 0
                    && (cooldownAlarmName == null || !cooldowns.isCooldownReady(npcRef, store, cooldownAlarmName))) {
                if (contextual && score >= bestScore) {
                    bestScore = score;
                    best = new ActionTameworkInteract.ResolvedInteraction(
                            entry,
                            index,
                            cooldownSeconds,
                            cooldownAlarmName,
                            true
                    );
                }
                continue;
            }
            if (isHarvestAlarmBlocking(entry, npcRef, role, infoProvider, store, ctx)) {
                if (contextual && score >= bestScore) {
                    bestScore = score;
                    best = new ActionTameworkInteract.ResolvedInteraction(
                            entry,
                            index,
                            cooldownSeconds,
                            cooldownAlarmName,
                            true
                    );
                }
                continue;
            }
            if (!requirements.requirementsMet(entry, npcRef, role, infoProvider, store, player, ctx)) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                best = new ActionTameworkInteract.ResolvedInteraction(entry, index, cooldownSeconds, cooldownAlarmName);
            }
        }
        return best;
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
        if (entries == null || entries.length == 0) {
            return null;
        }
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
                if (isContextualEntry(entry)) {
                    return new ActionTameworkInteract.ResolvedInteraction(
                            entry,
                            index,
                            cooldownSeconds,
                            cooldownAlarmName,
                            true
                    );
                }
                continue;
            }
            if (isHarvestAlarmBlocking(entry, npcRef, role, infoProvider, store, ctx)) {
                return new ActionTameworkInteract.ResolvedInteraction(
                        entry,
                        index,
                        cooldownSeconds,
                        cooldownAlarmName,
                        true
                );
            }
            if (requirements.requirementsMet(entry, npcRef, role, infoProvider, store, player, ctx)) {
                return new ActionTameworkInteract.ResolvedInteraction(entry, index, cooldownSeconds, cooldownAlarmName);
            }
        }
        return null;
    }

    // Returns true when harvest alarm cooldown blocks a contextual harvest interaction.
    private boolean isHarvestAlarmBlocking(InteractionEntry entry,
                                           Ref<EntityStore> npcRef,
                                           Role role,
                                           InfoProvider infoProvider,
                                           Store<EntityStore> store,
                                           InteractionContextSnapshot ctx) {
        if (!(entry instanceof TwInteractionConfig.HarvestInteraction)) {
            return false;
        }
        TwInteractionConfig.HarvestInteraction harvest = (TwInteractionConfig.HarvestInteraction) entry;
        boolean requireContext = harvest.getRequireHarvestInteractionContext() == null
                || harvest.getRequireHarvestInteractionContext();
        if (!requireContext || !owner.matchesHarvestContext(role, infoProvider, ctx)) {
            return false;
        }
        boolean requireAlarm = harvest.getRequireHarvestAlarmReady() == null
                || harvest.getRequireHarvestAlarmReady();
        return requireAlarm
                && !alarmHelper.isAlarmReady(npcRef, store, harvestAlarmName);
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
