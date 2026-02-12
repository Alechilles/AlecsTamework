package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedItem;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HarvestInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MountInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeCycleInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RequirementBucket;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RequirementGroup;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

final class TameworkInteractRequirements {
    private final ActionTameworkInteract owner;

    TameworkInteractRequirements(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    boolean requirementsMet(InteractionEntry entry,
                            Ref<EntityStore> npcRef,
                            Role role,
                            InfoProvider infoProvider,
                            Store<EntityStore> store,
                            Player player) {
        RequirementGroup requires = entry.getRequires();
        if (entry instanceof CustomInteraction) {
            return requires == null || evaluateRequirementGroup(requires, npcRef, role, infoProvider, store, player);
        }
        boolean baseRequirementsMet = false;
        if (entry instanceof TameInteraction) {
            baseRequirementsMet = meetsTameRequirements((TameInteraction) entry, npcRef, role, infoProvider, store, player);
        } else if (entry instanceof FeedInteraction) {
            baseRequirementsMet = meetsFeedRequirements((FeedInteraction) entry, npcRef, role, infoProvider, store, player);
        } else if (entry instanceof HarvestInteraction) {
            baseRequirementsMet = meetsHarvestRequirements((HarvestInteraction) entry, npcRef, role, infoProvider, store, player);
        } else if (entry instanceof MountInteraction) {
            baseRequirementsMet = meetsMountRequirements((MountInteraction) entry, npcRef, role, infoProvider, store, player);
        } else if (entry instanceof ModeCycleInteraction) {
            baseRequirementsMet = meetsModeCycleRequirements((ModeCycleInteraction) entry, npcRef, role, infoProvider, store, player);
        } else if (entry instanceof BreedInteraction) {
            baseRequirementsMet = meetsBreedRequirements((BreedInteraction) entry, npcRef, role, infoProvider, store, player);
        }
        if (!baseRequirementsMet) {
            return false;
        }
        return requires == null || evaluateRequirementGroup(requires, npcRef, role, infoProvider, store, player);
    }

    private boolean evaluateRequirementGroup(RequirementGroup group,
                                             Ref<EntityStore> npcRef,
                                             Role role,
                                             InfoProvider infoProvider,
                                             Store<EntityStore> store,
                                             Player player) {
        if (group == null) {
            return false;
        }
        if (!evaluateAllBucket(group.getAll(), npcRef, role, infoProvider, store, player)) {
            return false;
        }
        RequirementBucket any = group.getAny();
        if (any == null || any.isEmpty()) {
            return true;
        }
        return evaluateAnyBucket(any, npcRef, role, infoProvider, store, player);
    }

    private boolean evaluateAllBucket(RequirementBucket bucket,
                                      Ref<EntityStore> npcRef,
                                      Role role,
                                      InfoProvider infoProvider,
                                      Store<EntityStore> store,
                                      Player player) {
        if (bucket == null) {
            return true;
        }
        if (bucket.isLovedItems()
                && !owner.isHeldItemInList(owner.resolveLovedItems(role), player)) {
            return false;
        }
        if (bucket.isHarvestable()
                && !owner.resolveIsHarvestable(role)) {
            return false;
        }
        if (bucket.isMountable()
                && !owner.resolveIsMountable(role)) {
            return false;
        }
        if (bucket.isTamed()
                && !owner.isTamed(npcRef, store)) {
            return false;
        }
        if (bucket.isNotTamed()
                && owner.isTamed(npcRef, store)) {
            return false;
        }
        if (bucket.isPlayerCrouching()
                && !owner.isPlayerCrouching(role, infoProvider, store, player)) {
            return false;
        }
        if (bucket.isPlayerIsOwner()
                && !owner.isOwner(npcRef, store, player)) {
            return false;
        }
        if (bucket.isHarvestAlarmReady()
                && !owner.isAlarmReady(npcRef, store, ActionTameworkInteract.DEFAULT_HARVEST_ALARM)) {
            return false;
        }
        if (bucket.isHarvestInteractionContext()
                && !owner.matchesHarvestContext(npcRef, role, infoProvider, store)) {
            return false;
        }
        if (!requireAnyMatch(bucket.getItemsInHand(),
                requirement -> owner.matchesItemsInHand(requirement, role, player))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getItemsInInventory(),
                requirement -> owner.matchesItemsInInventory(requirement, role, player))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getItemsEquipped(),
                requirement -> owner.matchesItemsEquipped(requirement, player))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getParameter(),
                requirement -> owner.matchesParamRequirement(requirement, role))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getAlarmState(),
                requirement -> owner.matchesAlarmState(requirement, npcRef, store))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getNpcState(),
                requirement -> owner.matchesNpcState(requirement, role))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getPlayerMovementState(),
                requirement -> owner.matchesMovementState(requirement, role, infoProvider, store))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getInteractionContext(),
                requirement -> owner.matchesInteractionContext(requirement, npcRef, role, infoProvider, store))) {
            return false;
        }
        return true;
    }

    private boolean evaluateAnyBucket(RequirementBucket bucket,
                                      Ref<EntityStore> npcRef,
                                      Role role,
                                      InfoProvider infoProvider,
                                      Store<EntityStore> store,
                                      Player player) {
        if (bucket == null) {
            return false;
        }
        if (bucket.isLovedItems()
                && owner.isHeldItemInList(owner.resolveLovedItems(role), player)) {
            return true;
        }
        if (bucket.isHarvestable()
                && owner.resolveIsHarvestable(role)) {
            return true;
        }
        if (bucket.isMountable()
                && owner.resolveIsMountable(role)) {
            return true;
        }
        if (bucket.isTamed()
                && owner.isTamed(npcRef, store)) {
            return true;
        }
        if (bucket.isNotTamed()
                && !owner.isTamed(npcRef, store)) {
            return true;
        }
        if (bucket.isPlayerCrouching()
                && owner.isPlayerCrouching(role, infoProvider, store, player)) {
            return true;
        }
        if (bucket.isPlayerIsOwner()
                && owner.isOwner(npcRef, store, player)) {
            return true;
        }
        if (bucket.isHarvestAlarmReady()
                && owner.isAlarmReady(npcRef, store, ActionTameworkInteract.DEFAULT_HARVEST_ALARM)) {
            return true;
        }
        if (bucket.isHarvestInteractionContext()
                && owner.matchesHarvestContext(npcRef, role, infoProvider, store)) {
            return true;
        }
        if (anyMatch(bucket.getItemsInHand(),
                requirement -> owner.matchesItemsInHand(requirement, role, player))) {
            return true;
        }
        if (anyMatch(bucket.getItemsInInventory(),
                requirement -> owner.matchesItemsInInventory(requirement, role, player))) {
            return true;
        }
        if (anyMatch(bucket.getItemsEquipped(),
                requirement -> owner.matchesItemsEquipped(requirement, player))) {
            return true;
        }
        if (anyMatch(bucket.getParameter(),
                requirement -> owner.matchesParamRequirement(requirement, role))) {
            return true;
        }
        if (anyMatch(bucket.getAlarmState(),
                requirement -> owner.matchesAlarmState(requirement, npcRef, store))) {
            return true;
        }
        if (anyMatch(bucket.getNpcState(),
                requirement -> owner.matchesNpcState(requirement, role))) {
            return true;
        }
        if (anyMatch(bucket.getPlayerMovementState(),
                requirement -> owner.matchesMovementState(requirement, role, infoProvider, store))) {
            return true;
        }
        if (anyMatch(bucket.getInteractionContext(),
                requirement -> owner.matchesInteractionContext(requirement, npcRef, role, infoProvider, store))) {
            return true;
        }
        return false;
    }

    private <T> boolean requireAnyMatch(T[] requirements, Predicate<T> matcher) {
        if (requirements == null || requirements.length == 0) {
            return true;
        }
        for (T requirement : requirements) {
            if (requirement != null && matcher.test(requirement)) {
                return true;
            }
        }
        return false;
    }

    private <T> boolean anyMatch(T[] requirements, Predicate<T> matcher) {
        if (requirements == null || requirements.length == 0) {
            return false;
        }
        for (T requirement : requirements) {
            if (requirement != null && matcher.test(requirement)) {
                return true;
            }
        }
        return false;
    }

    private boolean meetsTameRequirements(TameInteraction interaction,
                                          Ref<EntityStore> npcRef,
                                          Role role,
                                          InfoProvider infoProvider,
                                          Store<EntityStore> store,
                                          Player player) {
        if (owner.isTamed(npcRef, store)) {
            return false;
        }
        return matchesPresetItems(
                interaction.getUseLovedItems(),
                interaction.getItemsInHand(),
                interaction.getItemsParam(),
                role,
                player,
                true
        );
    }

    private boolean meetsFeedRequirements(FeedInteraction interaction,
                                          Ref<EntityStore> npcRef,
                                          Role role,
                                          InfoProvider infoProvider,
                                          Store<EntityStore> store,
                                          Player player) {
        if (!owner.isTamed(npcRef, store)) {
            return false;
        }
        String[] explicitItems = resolveFeedItemIds(interaction);
        return matchesPresetItems(
                interaction.getUseLovedItems(),
                explicitItems,
                interaction.getItemsParam(),
                role,
                player,
                true
        );
    }

    private boolean meetsHarvestRequirements(HarvestInteraction interaction,
                                             Ref<EntityStore> npcRef,
                                             Role role,
                                             InfoProvider infoProvider,
                                             Store<EntityStore> store,
                                             Player player) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        boolean requireHarvestable = optionOrDefault(interaction.getRequireHarvestable(), true);
        boolean requireAlarm = optionOrDefault(interaction.getRequireHarvestAlarmReady(), true);
        boolean requireContext = optionOrDefault(interaction.getRequireHarvestInteractionContext(), true);
        if (requireTamed && !owner.isTamed(npcRef, store)) {
            return false;
        }
        if (requireHarvestable && !owner.resolveIsHarvestable(role)) {
            return false;
        }
        if (requireAlarm && !owner.isAlarmReady(npcRef, store, ActionTameworkInteract.DEFAULT_HARVEST_ALARM)) {
            return false;
        }
        if (requireContext && !owner.matchesHarvestContext(npcRef, role, infoProvider, store)) {
            return false;
        }
        return true;
    }

    private boolean meetsMountRequirements(MountInteraction interaction,
                                           Ref<EntityStore> npcRef,
                                           Role role,
                                           InfoProvider infoProvider,
                                           Store<EntityStore> store,
                                           Player player) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        boolean requireOwner = optionOrDefault(interaction.getRequireOwner(), true);
        boolean requireMountable = optionOrDefault(interaction.getRequireMountable(), true);
        boolean requireCrouching = optionOrDefault(interaction.getRequireCrouching(), true);
        if (requireTamed && !owner.isTamed(npcRef, store)) {
            return false;
        }
        if (requireOwner && !owner.isOwner(npcRef, store, player)) {
            return false;
        }
        if (requireMountable && !owner.resolveIsMountable(role)) {
            return false;
        }
        if (requireCrouching && !owner.isPlayerCrouching(role, infoProvider, store, player)) {
            return false;
        }
        return true;
    }

    private boolean meetsModeCycleRequirements(ModeCycleInteraction interaction,
                                               Ref<EntityStore> npcRef,
                                               Role role,
                                               InfoProvider infoProvider,
                                               Store<EntityStore> store,
                                               Player player) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        boolean requireOwner = optionOrDefault(interaction.getRequireOwner(), true);
        if (requireTamed && !owner.isTamed(npcRef, store)) {
            return false;
        }
        if (requireOwner && !owner.isOwner(npcRef, store, player)) {
            return false;
        }
        return true;
    }

    private boolean meetsBreedRequirements(BreedInteraction interaction,
                                           Ref<EntityStore> npcRef,
                                           Role role,
                                           InfoProvider infoProvider,
                                           Store<EntityStore> store,
                                           Player player) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        if (requireTamed && !owner.isTamed(npcRef, store)) {
            return false;
        }
        return true;
    }

    private boolean matchesPresetItems(Boolean useLovedItemsFlag,
                                       String[] explicitItems,
                                       String paramName,
                                       Role role,
                                       Player player,
                                       boolean defaultUseLovedItems) {
        boolean useLovedItems = optionOrDefault(useLovedItemsFlag, defaultUseLovedItems);
        boolean hasExplicitItems = explicitItems != null && explicitItems.length > 0;
        boolean hasParam = paramName != null && !paramName.isBlank();
        boolean requiresItems = useLovedItems || hasExplicitItems || hasParam;
        if (!requiresItems) {
            return true;
        }
        String[] resolvedItems = resolvePresetItemsInHand(useLovedItems, explicitItems, paramName, role);
        return owner.isHeldItemInList(resolvedItems, player);
    }

    private String[] resolvePresetItemsInHand(boolean useLovedItems,
                                              String[] explicitItems,
                                              String paramName,
                                              Role role) {
        Set<String> merged = new HashSet<>();
        if (useLovedItems) {
            String[] loved = owner.resolveLovedItems(role);
            if (loved != null) {
                merged.addAll(Arrays.asList(loved));
            }
        }
        if (explicitItems != null) {
            for (String item : explicitItems) {
                if (item != null && !item.isBlank()) {
                    merged.add(item);
                }
            }
        }
        if (paramName != null && !paramName.isBlank()) {
            String[] paramItems = owner.getRoleStringArrayParam(role, paramName);
            if (paramItems != null) {
                merged.addAll(Arrays.asList(paramItems));
            }
        }
        return merged.toArray(new String[0]);
    }

    private String[] resolveFeedItemIds(FeedInteraction interaction) {
        if (interaction == null) {
            return new String[0];
        }
        FeedItem[] feedItems = interaction.getItemsInHand();
        if (feedItems == null || feedItems.length == 0) {
            return new String[0];
        }
        Set<String> ids = new HashSet<>();
        for (FeedItem feedItem : feedItems) {
            if (feedItem == null) {
                continue;
            }
            String item = feedItem.getItem();
            if (item != null && !item.isBlank()) {
                ids.add(item);
            }
        }
        return ids.toArray(new String[0]);
    }

    private boolean optionOrDefault(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }
}
