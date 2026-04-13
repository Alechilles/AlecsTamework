package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AlarmRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionContextRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsEquippedRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInHandRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInInventoryRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MovementStateRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.NpcHealthPercentRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

/** Groups selection helpers used to evaluate requirements and pick interactions. */
final class InteractionSelection {
    private final InteractionItemRequirementResolver itemRequirements;
    private final InteractionMatchHelpers matchHelpers;
    private final InteractionParamMatcher paramMatcher;
    private final InteractionOwnershipHelper ownershipHelper;
    private final TameworkInteractRequirements requirements;
    private final InteractionSelector selector;
    private final InteractionDiagnostics diagnostics;

    InteractionSelection(InteractionItemRequirementResolver itemRequirements,
                         InteractionMatchHelpers matchHelpers,
                         InteractionParamMatcher paramMatcher,
                         InteractionOwnershipHelper ownershipHelper,
                         TameworkInteractRequirements requirements,
                         InteractionSelector selector,
                         InteractionDiagnostics diagnostics) {
        this.itemRequirements = itemRequirements;
        this.matchHelpers = matchHelpers;
        this.paramMatcher = paramMatcher;
        this.ownershipHelper = ownershipHelper;
        this.requirements = requirements;
        this.selector = selector;
        this.diagnostics = diagnostics;
    }

    // Selects the next interaction entry using the configured selector.
    ActionTameworkInteract.ResolvedInteraction selectInteraction(TwInteractionConfig config,
                                                                 Ref<EntityStore> npcRef,
                                                                 Role role,
                                                                 InfoProvider infoProvider,
                                                                 Store<EntityStore> store,
                                                                 Player player,
                                                                 InteractionContextSnapshot ctx) {
        return selector.selectInteraction(config, npcRef, role, infoProvider, store, player, ctx);
    }

    // Selects an interaction for prompts, preferring contextual and conditional entries.
    ActionTameworkInteract.ResolvedInteraction selectInteractionForPrompt(TwInteractionConfig config,
                                                                          Ref<EntityStore> npcRef,
                                                                          Role role,
                                                                          InfoProvider infoProvider,
                                                                          Store<EntityStore> store,
                                                                          Player player,
                                                                          InteractionContextSnapshot ctx) {
        return selector.selectInteractionForPrompt(config, npcRef, role, infoProvider, store, player, ctx);
    }

    // Returns true if the held item matches the requirement list.
    boolean matchesItemsInHand(ItemsInHandRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        return itemRequirements.matchesItemsInHand(requirement, role, ctx);
    }

    // Returns true if inventory contains the required items.
    boolean matchesItemsInInventory(ItemsInInventoryRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        return itemRequirements.matchesItemsInInventory(requirement, role, ctx);
    }

    // Returns true if equipped slots contain the required items.
    boolean matchesItemsEquipped(ItemsEquippedRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        return itemRequirements.matchesItemsEquipped(requirement, role, ctx);
    }

    // Checks whether the held item is in the list.
    boolean isHeldItemInList(String[] items, InteractionContextSnapshot ctx) {
        return itemRequirements.isHeldItemInList(items, ctx);
    }

    // Returns true when the player has no active held item.
    boolean isPlayerHandEmpty(InteractionContextSnapshot ctx) {
        return itemRequirements.isPlayerHandEmpty(ctx);
    }

    // Resolves items from a role param for requirements.
    String[] resolveItemsParam(Role role, InteractionContextSnapshot ctx, String itemsParam) {
        return itemRequirements.resolveItemsParam(role, ctx, itemsParam);
    }

    // Checks whether a context string matches for the interacting player.
    boolean matchesInteractionContext(String context, Role role, InfoProvider infoProvider, boolean allowBlank) {
        return matchHelpers.matchesInteractionContext(context, role, infoProvider, allowBlank);
    }

    // Checks whether a context string matches for prompt display.
    boolean matchesInteractionContextForPrompt(String context,
                                               Role role,
                                               InfoProvider infoProvider,
                                               InteractionContextSnapshot ctx,
                                               boolean allowBlank) {
        return matchHelpers.matchesInteractionContextForPrompt(context, role, infoProvider, ctx, allowBlank);
    }

    // Checks whether a context requirement matches.
    boolean matchesInteractionContext(InteractionContextRequirement requirement,
                                      Role role,
                                      InfoProvider infoProvider,
                                      InteractionContextSnapshot ctx) {
        return matchHelpers.matchesInteractionContext(requirement, role, infoProvider, ctx);
    }

    // Checks whether a context requirement matches for prompt display.
    boolean matchesInteractionContextForPrompt(InteractionContextRequirement requirement,
                                               Role role,
                                               InfoProvider infoProvider,
                                               InteractionContextSnapshot ctx) {
        return matchHelpers.matchesInteractionContextForPrompt(requirement, role, infoProvider, ctx);
    }

    // Checks movement state requirements against the player.
    boolean matchesMovementState(MovementStateRequirement requirement,
                                 Role role,
                                 InfoProvider infoProvider,
                                 Store<EntityStore> store,
                                 InteractionContextSnapshot ctx) {
        return matchHelpers.matchesMovementState(requirement, role, infoProvider, store, ctx);
    }

    // Returns true if the player is crouching.
    boolean isPlayerCrouching(Role role,
                              InfoProvider infoProvider,
                              Store<EntityStore> store,
                              InteractionContextSnapshot ctx) {
        return matchHelpers.isPlayerCrouching(role, infoProvider, store, ctx);
    }

    // Checks an alarm state requirement.
    boolean matchesAlarmState(AlarmRequirement requirement,
                              Ref<EntityStore> npcRef,
                              Store<EntityStore> store,
                              Role role,
                              InteractionContextSnapshot ctx) {
        return matchHelpers.matchesAlarmState(requirement, npcRef, store, role, ctx);
    }

    // Checks a param requirement against role scopes.
    boolean matchesParamRequirement(ParamRequirement requirement, Role role) {
        return paramMatcher.matchesParamRequirement(requirement, role);
    }

    // Checks NPC health percent requirements against the interaction target NPC.
    boolean matchesNpcHealthPercent(NpcHealthPercentRequirement requirement,
                                    Ref<EntityStore> npcRef,
                                    Store<EntityStore> store) {
        return matchHelpers.matchesNpcHealthPercent(requirement, npcRef, store);
    }

    // Returns true if the NPC is tamed.
    boolean isTamed(Ref<EntityStore> npcRef,
                    Store<EntityStore> store,
                    InteractionContextSnapshot ctx) {
        return ownershipHelper.isTamed(npcRef, store, ctx);
    }

    // Returns true if the player is the owner.
    boolean isOwner(Ref<EntityStore> npcRef,
                    Store<EntityStore> store,
                    Player player,
                    InteractionContextSnapshot ctx) {
        return ownershipHelper.isOwner(npcRef, store, player, ctx);
    }

    // Notifies the player if ownership prevents interaction.
    void maybeNotifyOwnerDenied(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player) {
        ownershipHelper.maybeNotifyOwnerDenied(npcRef, store, player);
    }

    // Logs a warning-level message for unsupported interactions.
    void logUnsupported(String message) {
        diagnostics.logUnsupported(message);
    }

    // Logs a debug message if logging is enabled.
    void logDebug(String message) {
        diagnostics.logDebug(message);
    }

    // Describes the held item for diagnostics.
    String describeHeldItem(InteractionContextSnapshot ctx) {
        return diagnostics.describeHeldItem(ctx);
    }

    // Builds a summary explaining why no interactions matched.
    String buildNoMatchSummary(TwInteractionConfig config,
                               Ref<EntityStore> npcRef,
                               Role role,
                               InfoProvider infoProvider,
                               Store<EntityStore> store,
                               Player player,
                               InteractionContextSnapshot ctx) {
        return diagnostics.buildNoMatchSummary(config, npcRef, role, infoProvider, store, player, ctx);
    }

    // Returns whether requirements are satisfied for the entry.
    boolean requirementsMet(TwInteractionConfig.InteractionEntry entry,
                            Ref<EntityStore> npcRef,
                            Role role,
                            InfoProvider infoProvider,
                            Store<EntityStore> store,
                            Player player,
                            InteractionContextSnapshot ctx) {
        return requirements.requirementsMet(
                null,
                -1,
                entry,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx
        );
    }
}
