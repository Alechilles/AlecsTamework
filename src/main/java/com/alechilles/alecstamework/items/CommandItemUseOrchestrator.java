package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.joml.Vector3d;

/**
 * Coordinates one command-item use while delegating inventory repair, UI, and command effects.
 */
final class CommandItemUseOrchestrator {
    private static final String CYCLE_SELECTION_COMMAND_ID = "CycleSelection";
    private static final String OPEN_SELECTION_MENU_COMMAND_ID = "OpenSelectionMenu";

    private final CommandResolutionService resolutionService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandFeedbackService feedbackService;
    private final CommandRecipientService recipientService;
    private final CommandCanonicalRecordCommitGate canonicalRecordCommitGate;
    private final CommandRelocationDispatchService relocationDispatchService;
    private final CommandStepExecutionService stepExecutionService;
    private final LinkedRecordReconciler linkedRecordReconciler;
    private final SelectionMenuOpener selectionMenuOpener;
    private final DeferredLinkHandler deferredLinkHandler;
    private final BiFunction<Player, CommandEntry, String> commandLabelResolver;
    private final CommandTuning tuning;

    CommandItemUseOrchestrator(CommandResolutionService resolutionService,
                               CommandToolInventoryService toolInventoryService,
                               CommandLinkMutationService linkMutationService,
                               CommandFeedbackService feedbackService,
                               CommandRecipientService recipientService,
                               CommandCanonicalRecordCommitGate canonicalRecordCommitGate,
                               CommandRelocationDispatchService relocationDispatchService,
                               CommandStepExecutionService stepExecutionService,
                               LinkedRecordReconciler linkedRecordReconciler,
                               SelectionMenuOpener selectionMenuOpener,
                               DeferredLinkHandler deferredLinkHandler,
                               BiFunction<Player, CommandEntry, String> commandLabelResolver,
                               CommandTuning tuning) {
        this.resolutionService = resolutionService;
        this.toolInventoryService = toolInventoryService;
        this.linkMutationService = linkMutationService;
        this.feedbackService = feedbackService;
        this.recipientService = recipientService;
        this.canonicalRecordCommitGate = canonicalRecordCommitGate;
        this.relocationDispatchService = relocationDispatchService;
        this.stepExecutionService = stepExecutionService;
        this.linkedRecordReconciler = linkedRecordReconciler;
        this.selectionMenuOpener = selectionMenuOpener;
        this.deferredLinkHandler = deferredLinkHandler;
        this.commandLabelResolver = commandLabelResolver;
        this.tuning = tuning;
    }

    boolean handleUse(Player player,
                      ItemStack itemStack,
                      Ref<EntityStore> targetRef,
                      String configIdOverride,
                      String commandIdOverride) {
        return handleUse(player, itemStack, targetRef, configIdOverride, commandIdOverride, true);
    }

    /**
     * Dispatches an item command, optionally bypassing the primary-click link/unlink interception.
     * Fixed hotswap abilities still receive their aimed target, but must never change link membership.
     */
    boolean handleUse(Player player,
                      ItemStack itemStack,
                      Ref<EntityStore> targetRef,
                      String configIdOverride,
                      String commandIdOverride,
                      boolean allowLinkToggle) {
        CommandPreparedUse use = prepareUse(player, itemStack, configIdOverride);
        if (use == null) {
            return false;
        }
        Interception selection = interceptSelection(use, commandIdOverride);
        if (selection.handled()) {
            return selection.result();
        }
        Interception link = allowLinkToggle
                ? interceptLinkToggle(use, targetRef)
                : Interception.unhandled();
        if (link.handled()) {
            return link.result();
        }
        return executeCommand(use, targetRef, commandIdOverride);
    }

    private CommandPreparedUse prepareUse(Player player, ItemStack itemStack, String configIdOverride) {
        if (player == null || itemStack == null || itemStack.isEmpty()) {
            return null;
        }
        World world = player.getWorld();
        if (world == null || world.getEntityStore() == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return null;
        }
        TwCommandItemConfig config = resolutionService.resolveConfig(itemStack.getItemId(), configIdOverride);
        if (config == null || !config.isEnabled()) {
            return null;
        }
        return prepareTool(player, store, playerRef, config, itemStack);
    }

    private CommandPreparedUse prepareTool(Player player,
                                           Store<EntityStore> store,
                                           Ref<EntityStore> playerRef,
                                           TwCommandItemConfig config,
                                           ItemStack itemStack) {
        CommandToolInventoryService.ToolResolution tool = toolInventoryService.ensureToolId(itemStack);
        if (tool.toolId == null || tool.toolId.isBlank()) {
            return null;
        }
        ItemStack working = usesGenericLinkedRecords(config)
                ? linkedRecordReconciler.reconcile(
                        player, store, config, tool.stack, tool.toolId)
                : tool.stack;
        boolean changed = tool.changed || working != tool.stack;
        return new CommandPreparedUse(
                player, playerRef, store, config, tool.toolId, working, changed, toolInventoryService
        );
    }

    private Interception interceptSelection(CommandPreparedUse use, String commandIdOverride) {
        if (matchesCommand(commandIdOverride, OPEN_SELECTION_MENU_COMMAND_ID)) {
            use.flushHeldItem();
            boolean opened = selectionMenuOpener.open(
                    use.player, use.store, use.config, use.workingItem, use.toolId
            );
            if (!opened) {
                feedbackService.showWarningKey(
                        use.player, "tamework.ui.notifications.command.selection.noChoices");
            }
            return Interception.handled(opened);
        }
        if (!matchesCommand(commandIdOverride, CYCLE_SELECTION_COMMAND_ID)) {
            return Interception.unhandled();
        }
        return cycleSelection(use);
    }

    private Interception cycleSelection(CommandPreparedUse use) {
        CommandSelectionResult selection = cycleSelectedCommand(use.config, use.workingItem);
        if (selection.changed) {
            use.replaceWorkingItem(selection.stack);
        }
        use.flushHeldItem();
        if (selection.command == null) {
            feedbackService.showWarningKey(
                    use.player, "tamework.ui.notifications.command.shared.noConfiguredCommand");
            return Interception.handled(false);
        }
        feedbackService.showDefaultKey(
                use.player,
                "tamework.ui.notifications.command.selection.selected",
                commandLabelResolver.apply(use.player, selection.command)
        );
        return Interception.handled(true);
    }

    private Interception interceptLinkToggle(CommandPreparedUse use, Ref<EntityStore> targetRef) {
        if (!usesGenericLinkedRecords(use.config)
                || targetRef == null || !use.config.isLinkEnabled()
                || !use.config.isLinkUseTogglesMembership()) {
            return Interception.unhandled();
        }
        LinkToggleResult link = linkMutationService.tryToggleLink(
                use.player,
                use.store,
                targetRef,
                use.toolId,
                use.config,
                use.workingItem,
                (player, store, target) -> deferredLinkHandler.handle(
                        player, store, target, use.toolId, use.config)
        );
        if (link.pending) {
            use.flushHeldItem();
            return Interception.handled(true);
        }
        if (!link.toggled) {
            return Interception.unhandled();
        }
        applyLinkResult(use, link);
        return Interception.handled(true);
    }

    private void applyLinkResult(CommandPreparedUse use, LinkToggleResult link) {
        if (link.updatedItem != null) {
            use.replaceWorkingItem(link.updatedItem);
        }
        use.flushHeldItem();
        if (link.linked && !link.active) {
            feedbackService.showSuccessKey(
                    use.player, "tamework.ui.notifications.command.link.successInactive", link.npcName);
            return;
        }
        feedbackService.showSuccessKey(
                use.player,
                link.linked
                        ? "tamework.ui.notifications.command.link.success"
                        : "tamework.ui.notifications.command.link.unlinked",
                link.npcName
        );
    }

    private boolean executeCommand(CommandPreparedUse use,
                                   Ref<EntityStore> targetRef,
                                   String commandIdOverride) {
        int cooldownMs = Math.max(0, use.config.getCooldownSeconds()) * 1000;
        if (isCooldownActive(use.workingItem, cooldownMs)) {
            use.flushHeldItem();
            feedbackService.showWarningKey(
                    use.player, "tamework.ui.notifications.command.shared.cooldown");
            return false;
        }
        CommandEntry command =
                resolutionService.resolveCommand(use.config, commandIdOverride, use.workingItem);
        if (command == null) {
            use.flushHeldItem();
            feedbackService.showWarningKey(
                    use.player, "tamework.ui.notifications.command.shared.noConfiguredCommand");
            return false;
        }
        return dispatchResolvedCommand(use, targetRef, command, cooldownMs);
    }

    private boolean dispatchResolvedCommand(CommandPreparedUse use,
                                            Ref<EntityStore> targetRef,
                                            CommandEntry command,
                                            int cooldownMs) {
        Context context = createContext(use, targetRef, command);
        List<Candidate> recipients = recipientService.queryRecipients(context);
        List<LinkedNpcRecord> unloaded = recipientService.queryUnloadedLinkedRecords(context, recipients);
        if (!commitCanonicalItemIfNeeded(use, context)) {
            return false;
        }
        if (recipients.isEmpty() && unloaded.isEmpty()) {
            use.flushHeldItem();
            feedbackService.showWarningKey(
                    use.player, "tamework.ui.notifications.command.execution.noRecipients");
            return false;
        }
        return dispatchRecipients(use, context, recipients, unloaded, cooldownMs);
    }

    private Context createContext(CommandPreparedUse use,
                                  Ref<EntityStore> targetRef,
                                  CommandEntry command) {
        Ref<EntityStore> commandTarget = resolutionService.resolveCommandTarget(
                use.playerRef, use.store, use.config, command, targetRef
        );
        Vector3d raycastPosition = resolutionService.resolveRaycastPosition(
                use.playerRef, use.store, use.config, command
        );
        TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.EffectiveSettings.fromGlobal(
                TwGlobalConfig.resolveActive()
        );
        return new Context(
                use.player, use.playerRef, use.store, use.config, command,
                use.workingItem.getItemId(), use.toolId, commandTarget, raycastPosition,
                use.workingItem,
                TameworkRuntimeSettings.blockAllPlayerDamageIfOwned(
                        settings.isBlockAllPlayerDamageIfOwned()),
                TameworkRuntimeSettings.invulnerableIfOwned(settings.isInvulnerableIfOwned()),
                positive(settings.getReturnHomeTeleportDistance(), tuning.returnHomeTeleportDistance()),
                positive(settings.getReturnHomePathDistanceBeforeTeleport(),
                        tuning.returnHomePathDistanceBeforeTeleport()),
                positive(settings.getReturnHomeTeleportDelayMs(), tuning.returnHomeTeleportDelayMs()),
                positive(settings.getRecallSafeSpawnDistance(), tuning.recallSafeSpawnDistance()),
                positive(settings.getRecallForceRelocateDistance(), tuning.recallForceRelocateDistance())
        );
    }

    private boolean commitCanonicalItemIfNeeded(CommandPreparedUse use, Context context) {
        if (!context.itemChanged || context.workingItem == use.workingItem) {
            return true;
        }
        ItemStack canonicalStack = context.workingItem;
        boolean committed = canonicalRecordCommitGate.commitBeforeAction(
                true, () -> toolInventoryService.updateHeldItem(use.player, canonicalStack)
        );
        if (!committed) {
            feedbackService.showWarningKey(
                    use.player, "tamework.ui.notifications.command.shared.itemNotFound");
            return false;
        }
        use.acceptCommittedItem(canonicalStack);
        context.itemChanged = false;
        return true;
    }

    private boolean dispatchRecipients(CommandPreparedUse use,
                                       Context context,
                                       List<Candidate> recipients,
                                       List<LinkedNpcRecord> unloaded,
                                       int cooldownMs) {
        LoadedDispatch loaded = executeLoadedRecipients(context, recipients);
        refreshLinkedPositions(use, context, recipients, loaded.appliedCommandStates());
        int queued = relocationDispatchService.queueRelocationsForUnloaded(context, unloaded).queued();
        use.synchronizeFrom(context);
        if (loaded.affected() <= 0 && queued <= 0) {
            use.flushHeldItem();
            feedbackService.showWarningKey(
                    use.player, "tamework.ui.notifications.command.execution.none");
            return false;
        }
        applyCooldown(use, context, cooldownMs);
        use.flushHeldItem();
        emitExecutionFeedback(context, loaded.affected(), queued);
        return true;
    }

    private LoadedDispatch executeLoadedRecipients(Context context, List<Candidate> recipients) {
        int affected = 0;
        Map<UUID, String> appliedStates = new HashMap<>();
        for (Candidate candidate : recipients) {
            relocationDispatchService.maybeRelocateLoadedRecallCandidate(context, candidate);
            StepResult result = stepExecutionService.executeCommand(context, candidate);
            if (result.applied) {
                affected++;
            }
            recordAppliedState(appliedStates, candidate, result);
            if (result.abortAll) {
                break;
            }
        }
        return new LoadedDispatch(affected, appliedStates);
    }

    private void recordAppliedState(Map<UUID, String> appliedStates,
                                    Candidate candidate,
                                    StepResult result) {
        if (result.appliedState == null || candidate == null || candidate.npc == null
                || candidate.npc.getUuid() == null) {
            return;
        }
        String cachedState = result.appliedState.cachedValue();
        if (cachedState != null) {
            appliedStates.put(candidate.npc.getUuid(), cachedState);
        }
    }

    private void refreshLinkedPositions(CommandPreparedUse use,
                                        Context context,
                                        List<Candidate> recipients,
                                        Map<UUID, String> appliedStates) {
        if (!usesGenericLinkedRecords(context.config)) {
            return;
        }
        ItemStack refreshed = linkMutationService.refreshLinkedNpcPositions(
                context.workingItem, recipients, use.store, appliedStates
        );
        if (refreshed != context.workingItem) {
            context.workingItem = refreshed;
            context.itemChanged = true;
            use.replaceWorkingItem(refreshed);
        }
    }

    private void applyCooldown(CommandPreparedUse use, Context context, int cooldownMs) {
        if (cooldownMs <= 0) {
            return;
        }
        ItemStack cooled = use.workingItem.withMetadata(
                TameworkMetadataKeys.COMMAND_COOLDOWN_UNTIL,
                Codec.LONG,
                System.currentTimeMillis() + cooldownMs
        );
        use.replaceWorkingItem(cooled);
        context.workingItem = cooled;
        context.itemChanged = true;
    }

    private void emitExecutionFeedback(Context context, int affected, int queued) {
        feedbackService.emitCommandExecutionFeedback(
                context.player,
                context.playerRef,
                context.store,
                context.command,
                affected,
                queued,
                command -> commandLabelResolver.apply(context.player, command)
        );
    }

    private CommandSelectionResult cycleSelectedCommand(TwCommandItemConfig config, ItemStack stack) {
        String selectedId = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING
        );
        CommandEntry next = config.findNextCommand(selectedId);
        if (next == null || next.getId() == null || next.getId().isBlank()) {
            return CommandSelectionResult.none(stack);
        }
        boolean changed = !sameCommandId(next.getId(), selectedId);
        ItemStack selected = changed
                ? stack.withMetadata(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING, next.getId())
                : stack;
        return new CommandSelectionResult(selected, next, changed);
    }

    private boolean matchesCommand(String candidate, String expected) {
        return candidate != null && !candidate.isBlank()
                && expected.equalsIgnoreCase(candidate.trim());
    }

    private boolean sameCommandId(String left, String right) {
        return left != null && right != null && !left.isBlank() && !right.isBlank()
                && left.trim().equalsIgnoreCase(right.trim());
    }

    private boolean isCooldownActive(ItemStack stack, int cooldownMs) {
        if (cooldownMs <= 0) {
            return false;
        }
        Long until = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.COMMAND_COOLDOWN_UNTIL, Codec.LONG
        );
        return until != null && until > System.currentTimeMillis();
    }

    private double positive(double configured, double fallback) {
        return configured > 0.0 ? configured : fallback;
    }

    private long positive(long configured, long fallback) {
        return configured > 0L ? configured : fallback;
    }

    private boolean usesGenericLinkedRecords(TwCommandItemConfig config) {
        return config != null && !config.usesBondedCompanionRoster();
    }

    @FunctionalInterface
    interface LinkedRecordReconciler {
        ItemStack reconcile(Player player,
                            Store<EntityStore> store,
                            TwCommandItemConfig config,
                            ItemStack stack,
                            String toolId);
    }

    @FunctionalInterface
    interface SelectionMenuOpener {
        boolean open(Player player,
                     Store<EntityStore> store,
                     TwCommandItemConfig config,
                     ItemStack stack,
                     String toolId);
    }

    @FunctionalInterface
    interface DeferredLinkHandler {
        void handle(Player player,
                    Store<EntityStore> store,
                    Ref<EntityStore> targetRef,
                    String toolId,
                    TwCommandItemConfig config);
    }

    record CommandTuning(double returnHomeTeleportDistance,
                         double returnHomePathDistanceBeforeTeleport,
                         long returnHomeTeleportDelayMs,
                         double recallSafeSpawnDistance,
                         double recallForceRelocateDistance) {
    }

    private record Interception(boolean handled, boolean result) {
        private static Interception handled(boolean result) {
            return new Interception(true, result);
        }

        private static Interception unhandled() {
            return new Interception(false, false);
        }
    }

    private record LoadedDispatch(int affected, Map<UUID, String> appliedCommandStates) {
    }

}
