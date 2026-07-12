package com.alechilles.alecstamework.npc.actions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HarvestInteraction;
import com.alechilles.alecstamework.npc.alarms.TameworkAlarmService;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeCycleInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MountInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.ownership.LegacyTamedOwnershipBridge;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

/**
 * Updates the NPC interaction prompt based on the first matching Tamework interaction entry.
 */
public final class ActionTameworkInteractPrompt extends ActionTameworkInteract {
    private static final long PROMPT_SELECTION_CACHE_TTL_MS = 500L;
    private static final String HINT_GENERIC = "server.interactionHints.generic";
    private static final String HINT_HARVEST = "server.interactionHints.harvest";
    private static final String HINT_HARVEST_CONTEXT = "server.interactionHints.harvestContext";
    private static final String HINT_MOUNT = "server.interactionHints.mount";
    private static final String HINT_TAME = "server.interactionHints.tame";
    private static final String HINT_FEED = "server.interactionHints.feed";
    private static final String HINT_BREED = "server.interactionHints.breed";
    private static final String HINT_MODE_CYCLE = "server.interactionHints.modeCycle";
    private static final String HINT_CUSTOM = "server.interactionHints.custom";

    private final Map<UUID, PromptState> lastPrompts = new HashMap<>();
    private final Map<UUID, Long> lastDebugMs = new HashMap<>();
    private final Map<UUID, CachedPromptSelection> cachedPromptSelections = new HashMap<>();
    private long lastEarlyDebugMs;

    public ActionTameworkInteractPrompt(BuilderActionTameworkInteractPrompt builder, BuilderSupport support) {
        super(builder, support);
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || role == null || role.getStateSupport() == null) {
            maybeLogEarlyPromptDebug("invalid_npc_or_role", role, infoProvider);
            return false;
        }
        Ref<EntityStore> interactionTarget = resolveInteractionTarget(role, infoProvider);
        if (interactionTarget == null || !interactionTarget.isValid() || store == null) {
            maybeLogEarlyPromptDebug(
                    store == null ? "missing_store" : "no_interaction_target",
                    role,
                    infoProvider
            );
            return false;
        }
        Player player = store.getComponent(interactionTarget, Player.getComponentType());
        if (player == null) {
            maybeLogEarlyPromptDebug("interaction_target_not_player", role, infoProvider);
            return false;
        }

        UUID playerId = player.getUuid();
        if (playerId == null) {
            maybeLogEarlyPromptDebug("player_uuid_missing", role, infoProvider);
            return false;
        }

        long nowMs = System.currentTimeMillis();
        PromptSelectionFingerprint fingerprint = buildPromptSelectionFingerprint(player, role);
        CachedPromptSelection cachedSelection = cachedPromptSelections.get(playerId);

        InteractionContextSnapshot ctx = null;
        TwInteractionConfig config = null;
        ActionTameworkInteract.ResolvedInteraction resolved = null;
        PromptState prompt;
        if (cachedSelection != null
                && cachedSelection.matches(fingerprint, nowMs)) {
            config = cachedSelection.config();
            resolved = cachedSelection.resolvedInteraction();
            prompt = cachedSelection.promptState();
        } else {
            ctx = buildContextSnapshot(player, interactionTarget, role);
            config = resolveConfig(role, ctx);
            if (config != null && config.isEnabled()) {
                boolean adoptionPending = claimLegacyOwnershipForPrompt(npcRef, store, player);
                if (!adoptionPending) {
                    resolved = selectInteractionForPrompt(config, npcRef, role, infoProvider, store, player, ctx);
                }
            }
            if (resolved != null && resolved.entry instanceof HarvestInteraction) {
                HarvestInteraction harvest = (HarvestInteraction) resolved.entry;
                boolean requireAlarm = harvest.getRequireHarvestAlarmReady() == null || harvest.getRequireHarvestAlarmReady();
                // Prompt should only show when the harvest alarm is ready (unset or passed).
                if (requireAlarm && !isHarvestAlarmReady(npcRef, store, ctx)) {
                    resolved = null;
                }
            }

            prompt = resolvePromptState(resolved);
            cachedPromptSelections.put(
                    playerId,
                    new CachedPromptSelection(
                            fingerprint,
                            nowMs + PROMPT_SELECTION_CACHE_TTL_MS,
                            config,
                            resolved,
                            prompt
                    )
            );
        }
        PromptState previous = lastPrompts.get(playerId);
        boolean changed = !prompt.equals(previous);
        boolean interactable = prompt.interactable;
        if (ctx != null) {
            maybeLogPromptDebug(resolved, config, npcRef, role, infoProvider, store, player, ctx, prompt);
        }
        if (changed) {
            // Force a refresh when the prompt changes so the hint updates on the client.
            role.getStateSupport().setInteractable(npcRef, interactionTarget, false, null, false, store);
        }
        role.getStateSupport().setInteractable(
                npcRef,
                interactionTarget,
                interactable,
                prompt.hint,
                prompt.showPrompt,
                store
        );
        lastPrompts.put(playerId, prompt);
        return true;
    }

    private boolean claimLegacyOwnershipForPrompt(Ref<EntityStore> npcRef,
                                                  Store<EntityStore> store,
                                                  Player player) {
        LegacyTamedOwnershipBridge.ClaimResult ownerBridgeResult =
                LegacyTamedOwnershipBridge.claimForPlayerIfEligible(npcRef, store, player);
        if (ownerBridgeResult.isScheduled()) {
            logDebug("TameworkPrompt: legacy tamed ownership scheduled.");
            return true;
        }
        return false;
    }

    private PromptSelectionFingerprint buildPromptSelectionFingerprint(Player player, Role role) {
        ItemStack activeItem = PlayerInventoryAccess.getActiveHotbarItem(player);
        String activeItemId = activeItem != null && !activeItem.isEmpty() ? activeItem.getItemId() : null;
        int activeQuantity = activeItem != null && !activeItem.isEmpty() ? activeItem.getQuantity() : 0;
        int stateIndex = role != null && role.getStateSupport() != null ? role.getStateSupport().getStateIndex() : -1;
        int subStateIndex = role != null && role.getStateSupport() != null ? role.getStateSupport().getSubStateIndex() : -1;
        return new PromptSelectionFingerprint(activeItemId, activeQuantity, stateIndex, subStateIndex);
    }

    // Determines the prompt state (visibility + hint key) for the selected entry.
    private PromptState resolvePromptState(ActionTameworkInteract.ResolvedInteraction resolved) {
        if (resolved == null || resolved.blockedByCooldown || resolved.entry == null) {
            return PromptState.hidden(false);
        }
        InteractionEntry entry = resolved.entry;
        Boolean showOverride = entry.getShowPrompt();
        boolean showPrompt = showOverride == null || showOverride;
        if (!showPrompt) {
            return PromptState.hidden(true);
        }
        String hint = entry.getPromptHint();
        if (hint == null || hint.isBlank()) {
            hint = resolveDefaultHint(entry);
        }
        if (hint == null || hint.isBlank()) {
            return PromptState.hidden(true);
        }
        return new PromptState(hint, true, true);
    }

    // Selects a default hint key by interaction type.
    private String resolveDefaultHint(InteractionEntry entry) {
        if (entry instanceof HarvestInteraction) {
            HarvestInteraction harvest = (HarvestInteraction) entry;
            boolean requireContext = harvest.getRequireHarvestInteractionContext() == null
                    || harvest.getRequireHarvestInteractionContext();
            return requireContext ? HINT_HARVEST_CONTEXT : HINT_HARVEST;
        }
        if (entry instanceof MountInteraction) {
            return HINT_MOUNT;
        }
        if (entry instanceof TameInteraction) {
            return HINT_TAME;
        }
        if (entry instanceof FeedInteraction) {
            return HINT_FEED;
        }
        if (entry instanceof BreedInteraction) {
            return HINT_BREED;
        }
        if (entry instanceof ModeCycleInteraction) {
            return HINT_MODE_CYCLE;
        }
        if (entry instanceof CustomInteraction) {
            return HINT_CUSTOM;
        }
        return HINT_GENERIC;
    }

    // Simple value object for prompt hint/visibility.
    private static final class PromptState {
        final String hint;
        final boolean showPrompt;
        final boolean interactable;

        private PromptState(String hint, boolean showPrompt, boolean interactable) {
            this.hint = hint;
            this.showPrompt = showPrompt;
            this.interactable = interactable;
        }

        boolean isHidden() {
            return !showPrompt || hint == null || hint.isBlank();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PromptState)) {
                return false;
            }
            PromptState that = (PromptState) other;
            if (this.showPrompt != that.showPrompt) {
                return false;
            }
            if (this.interactable != that.interactable) {
                return false;
            }
            if (this.hint == null) {
                return that.hint == null;
            }
            return this.hint.equals(that.hint);
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(showPrompt);
            result = 31 * result + Boolean.hashCode(interactable);
            result = 31 * result + (hint == null ? 0 : hint.hashCode());
            return result;
        }

        static PromptState hidden(boolean interactable) {
            return new PromptState(null, false, interactable);
        }
    }

    private record PromptSelectionFingerprint(String activeItemId,
                                              int activeQuantity,
                                              int stateIndex,
                                              int subStateIndex) {
    }

    private record CachedPromptSelection(PromptSelectionFingerprint fingerprint,
                                         long expiresAtMs,
                                         TwInteractionConfig config,
                                         ActionTameworkInteract.ResolvedInteraction resolvedInteraction,
                                         PromptState promptState) {
        private boolean matches(PromptSelectionFingerprint fingerprint, long nowMs) {
            return this.fingerprint.equals(fingerprint) && nowMs < expiresAtMs;
        }
    }

    private void maybeLogPromptDebug(ActionTameworkInteract.ResolvedInteraction resolved,
                                     TwInteractionConfig config,
                                     Ref<EntityStore> npcRef,
                                     Role role,
                                     InfoProvider infoProvider,
                                     Store<EntityStore> store,
                                     Player player,
                                     InteractionContextSnapshot ctx,
                                     PromptState prompt) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugPromptEnabled() || instance.getLogger() == null) {
            return;
        }
        UUID playerId = ctx != null ? ctx.playerId : null;
        if (playerId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastDebugMs.get(playerId);
        if (last != null && now - last < 1000) {
            return;
        }
        lastDebugMs.put(playerId, now);

        String roleName = role != null ? role.getRoleName() : "<null>";
        String configId = config != null ? config.getId() : "<null>";
        String entryType = resolved != null && resolved.entry != null
                ? resolved.entry.getClass().getSimpleName()
                : "<none>";
        boolean showPrompt = !prompt.isHidden();
        boolean interactable = prompt.interactable;
        boolean tamed = isTamed(npcRef, store, ctx);
        boolean owner = isOwner(npcRef, store, player, ctx);
        boolean isMountable = resolveIsMountable(role, ctx);
        boolean crouching = isPlayerCrouching(role, infoProvider, store, ctx);
        String held = describeHeldItem(ctx);
        String noMatchSummary = resolved == null && config != null
                ? buildNoMatchSummary(config, npcRef, role, infoProvider, store, player, ctx)
                : "";
        TameworkAlarmService.Snapshot alarm = getHarvestAlarmSnapshot(npcRef, store, ctx);
        boolean requireAlarm = false;
        boolean requireContext = false;
        if (resolved != null && resolved.entry instanceof HarvestInteraction) {
            HarvestInteraction harvest = (HarvestInteraction) resolved.entry;
            requireAlarm = harvest.getRequireHarvestAlarmReady() == null || harvest.getRequireHarvestAlarmReady();
            requireContext = harvest.getRequireHarvestInteractionContext() == null || harvest.getRequireHarvestInteractionContext();
        }

        instance.getLogger().at(Level.INFO).log(
                "TameworkPrompt debug: role=%s config=%s entry=%s interactable=%s show=%s tamed=%s owner=%s " +
                        "held=%s isMountable=%s crouching=%s alarmName=%s " +
                        "validName=%s npcResolved=%s storeResolved=%s exists=%s nowAvailable=%s " +
                        "set=%s unset=%s active=%s passed=%s ready=%s requireAlarm=%s requireContext=%s noMatch=%s",
                roleName,
                configId,
                entryType,
                interactable,
                showPrompt,
                tamed,
                owner,
                held,
                isMountable,
                crouching,
                getHarvestAlarmName(),
                alarm.valid,
                alarm.valid,
                alarm.componentTypeAvailable,
                alarm.exists,
                true,
                alarm.exists,
                !alarm.exists,
                alarm.active,
                alarm.passed,
                alarm.ready,
                requireAlarm,
                requireContext,
                noMatchSummary
        );
    }

    private void maybeLogEarlyPromptDebug(String reason, Role role, InfoProvider infoProvider) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugPromptEnabled() || instance.getLogger() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastEarlyDebugMs < 1000) {
            return;
        }
        lastEarlyDebugMs = now;
        String roleName = role != null ? role.getRoleName() : "<null>";
        boolean hasStateTarget = role != null
                && role.getStateSupport() != null
                && role.getStateSupport().getInteractionIterationTarget() != null
                && role.getStateSupport().getInteractionIterationTarget().isValid();
        boolean hasPosition = infoProvider != null && infoProvider.hasPosition();
        String positionProvider = "<none>";
        if (hasPosition && infoProvider.getPositionProvider() != null) {
            positionProvider = infoProvider.getPositionProvider().getClass().getName();
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkPrompt debug: skipped role=%s reason=%s hasStateTarget=%s hasPosition=%s positionProvider=%s",
                roleName,
                reason,
                hasStateTarget,
                hasPosition,
                positionProvider
        );
    }
}
