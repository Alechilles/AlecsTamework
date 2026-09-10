package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.progression.SavedCompanionTalentRequest;
import com.alechilles.alecstamework.companion.progression.SavedCompanionTalentSnapshot;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionSettings;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.alechilles.alecstamework.ui.TameworkCompanionTalentsPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/** Uses the canonical restoration snapshot for dead/lost companion talent purchases. */
final class CommandSavedTalentPageService {
    private final PersistenceDomainFacades persistence;
    private final CommandToolInventoryService inventory;
    private final CommandTalentPageService renderer;

    CommandSavedTalentPageService(PersistenceDomainFacades persistence,
                                 CommandToolInventoryService inventory,
                                 CommandTalentPageService renderer) {
        this.persistence = persistence;
        this.inventory = inventory;
        this.renderer = renderer;
    }

    CompletionStage<CommandUiActionResult> open(CommandUiSessionImpl session, UUID rowId,
                                              Player player, String toolId, UUID npcId,
                                              BooleanSupplier authority) {
        if (player == null || !authority.getAsBoolean()) return completed(CommandUiActionResult.denied("Talent access unavailable"));
        UUID owner = player.getUuid();
        long openingGeneration = session.currentManagedGeneration();
        var profileId = new CommandOwnedPanelRecordSource(persistence.queries()::projectedProfileSnapshot)
                .profileForRow(owner, npcId);
        if (profileId.isEmpty()) return completed(CommandUiActionResult.notFound("Saved companion unavailable"));
        return persistence.queries().findProfile(profileId.get()).handle((read, failure) ->
                failure == null && read instanceof PersistenceReadResult.Found<CompanionProfileReadModel> found
                        ? found.value() : null).thenCompose(profile -> onWorld(owner, current -> {
            if (!session.isOpen() || session.currentManagedGeneration() != openingGeneration
                    || !authority.getAsBoolean() || !hasTool(current, toolId))
                return CommandUiActionResult.denied("Talent access unavailable");
            var snapshot = usable(owner, profile);
            if (snapshot == null) return CommandUiActionResult.notFound(text(current.getPlayerRef().getLanguage(), "unavailable"));
            State state = new State(owner, toolId, profile, snapshot);
            return CommandUiActionResult.presented(build(session, rowId, authority, current, state));
        }));
    }

    private com.alechilles.alecstamework.api.commandui.CommandUiTalentFlowView build(
            CommandUiSessionImpl session, UUID rowId, BooleanSupplier authority, Player player, State state) {
        var page = pageData(player.getPlayerRef().getLanguage(), state);
        int points = Math.max(0, CompanionLevelingService.resolveEarnedTalentPoints(
                state.snapshot.leveling().getLevel(), state.snapshot.leveling().getConfigId())
                - CompanionTalentService.reconcileAllocation(state.snapshot.talents(),
                        TwTalentConfig.resolveForRole(state.profile.identity().roleId())).getSpentPoints());
        return new CommandUiManagedTalentFlowService().build(session, rowId,
                state.profile.identity().profileId().toString(), CommandUiActionGateway.Route.GENERIC,
                new CommandUiManagedTalentFlowService.Snapshot(page, state.snapshot.leveling().getLevel(),
                        points, Map.of("saved", "true")),
                (kind, label, talentId, confirmation) -> {
                    var action = new CommandUiAction(kind, null, talentId, confirmation);
                    var handle = session.issueManaged(CommandUiActionGateway.Route.GENERIC, action,
                            ignored -> authority.getAsBoolean(),
                            (bound, ignored) -> mutate(session, rowId, authority, state,
                                    "RESET_TALENTS".equals(kind) ? SavedCompanionTalentRequest.Action.RESET
                                            : SavedCompanionTalentRequest.Action.PURCHASE, talentId),
                            CommandUiActionGateway.InputPolicy.NONE, 0, confirmation);
                    return new CommandUiActionView(kind, label, true, null, confirmation, handle);
                });
    }

    private TameworkCompanionTalentsPage.PageData pageData(String language, State state) {
        state.language = language;
        if (state.snapshot == null) return TameworkCompanionTalentsPage.PageData.empty();
        var saved = state.snapshot.fullState();
        var leveling = saved.leveling();
        TwLevelingConfig levels = TwLevelingConfig.resolveById(leveling.getConfigId());
        TwTalentConfig config = TwTalentConfig.resolveForRole(state.profile.identity().roleId());
        if (levels == null || config == null) return TameworkCompanionTalentsPage.PageData.empty();
        state.presentedConfigId = config.getId();
        state.presentedAllocationRevision = config.getAllocationRevision();
        var talents = CompanionTalentService.reconcileAllocation(saved.talents(), config);
        int level = leveling.getLevel();
        boolean max = level >= levels.getLevels().getMaxLevel();
        double nextDelta = max ? 0.0 : levels.getLevels().getBaseXp()
                * Math.pow(levels.getLevels().getGrowthFactor(), level - 1);
        var summary = new CompanionLevelingService.LevelingSnapshot(levels.getId(), level,
                leveling.getCurrentXp(), leveling.getTotalXp(), 0.0, nextDelta,
                levels.getLevels().getMaxLevel(), max);
        int points = Math.max(0, CompanionLevelingService.resolveEarnedTalentPoints(level, levels.getId())
                - talents.getSpentPoints());
        String name = state.profile.identity().displayName();
        if (name == null || name.isBlank()) name = state.profile.identity().roleId();
        return renderer.buildTalentPageData(language, name == null ? "Companion" : name,
                summary, points, config, talents);
    }

    private CompletionStage<CommandUiActionResult> mutate(CommandUiSessionImpl session, UUID rowId,
            BooleanSupplier authority, State state, SavedCompanionTalentRequest.Action action, String talentId) {
        if (state.pending) return completed(CommandUiActionResult.conflict(text(state.language, "saving")));
        long flowGeneration = session.currentManagedGeneration();
        // The action gateway invokes this callback on the owning world thread.
        var profileId = state.profile.identity().profileId();
        var snapshot = state.snapshot.snapshot();
        OperationId operationId = OperationId.create();
        var request = new SavedCompanionTalentRequest(profileId, new OwnerId(state.owner),
                state.profile.lifecycle().revision(), snapshot.snapshotId(), snapshot.payloadHash(),
                action, talentId, state.presentedConfigId, state.presentedAllocationRevision, System.currentTimeMillis());
        state.pending = true;
        try {
            var submitted = persistence.operations().updateSavedTalents(operationId,
                    new IdempotencyKey("saved-talents:" + operationId), request);
            return submitted.completion().handle((result, failure) -> failure == null && com.alechilles.alecstamework.companion.progression.SavedCompanionTalentOutcome.isApplied(result)).thenCompose(applied ->
                    persistence.queries().findProfile(profileId).handle((read, failure) ->
                            failure == null && read instanceof PersistenceReadResult.Found<CompanionProfileReadModel> found
                                    ? found.value() : null).thenCompose(refreshed -> onWorld(state.owner, current -> {
                        state.pending = false;
                        if (!session.isOpen() || session.currentManagedGeneration() != flowGeneration
                                || !authority.getAsBoolean() || !hasTool(current, state.toolId))
                            return CommandUiActionResult.denied("Talent access unavailable");
                        var next = usable(state.owner, refreshed);
                        if (next == null) return CommandUiActionResult.notFound(text(state.language, "unavailable"));
                        state.profile = refreshed;
                        state.snapshot = next;
                        return CommandUiActionResult.updated(text(state.language, applied ? "saved" : "changed"),
                                build(session, rowId, authority, current, state));
                    })));
        } catch (RuntimeException unavailable) {
            state.pending = false;
            return completed(CommandUiActionResult.failed(text(state.language, "unavailable")));
        }
    }

    private SavedCompanionTalentSnapshot usable(UUID owner, CompanionProfileReadModel profile) {
        if (profile == null || !new OwnerId(owner).equals(profile.lifecycle().ownerId())
                || profile.lifecycle().activeOperationId() != null
                || !CompanionProgressionSettings.isTalentsEnabled()
                || !CompanionProgressionSettings.isLevelingEnabled()) return null;
        var snapshot = SavedCompanionTalentSnapshot.find(profile);
        if (snapshot == null || snapshot.talents() == null || snapshot.leveling() == null) return null;
        var config = TwTalentConfig.resolveForRole(profile.identity().roleId());
        var levels = TwLevelingConfig.resolveById(snapshot.leveling().getConfigId());
        return config != null && config.isEnabled() && levels != null && levels.isEnabled() ? snapshot : null;
    }

    private boolean hasTool(Player player, String toolId) {
        var tool = inventory.findToolStack(player, toolId);
        return tool != null && !tool.isEmpty();
    }

    private static CompletionStage<CommandUiActionResult> onWorld(UUID owner, Function<Player, CommandUiActionResult> action) {
        CompletableFuture<CommandUiActionResult> result = new CompletableFuture<>();
        boolean queued = CommandUiCurrentWorldDispatcher.production().dispatch(owner, new CommandUiHostPage.WorldOperation() {
            @Override public void run(Ref<EntityStore> ref, Store<EntityStore> store) {
                try {
                    Player player = ref == null || !ref.isValid() ? null : store.getComponent(ref, Player.getComponentType());
                    if (player == null) unavailable();
                    else result.complete(action.apply(player));
                } catch (RuntimeException failure) {
                    result.complete(CommandUiActionResult.failed("Saved talent action failed"));
                }
            }
            @Override public void unavailable() {
                result.complete(CommandUiActionResult.notFound("Player is unavailable"));
            }
        });
        if (!queued) result.complete(CommandUiActionResult.notFound("Player is unavailable"));
        return result;
    }

    private static CompletionStage<CommandUiActionResult> completed(CommandUiActionResult result) {
        return CompletableFuture.completedFuture(result);
    }

    private static String text(String language, String key) {
        return LocalizedText.resolve(language, "tamework.ui.talents.saved." + key);
    }

    /** All session state is read and changed only on the player's owning world thread. */
    private static final class State {
        final UUID owner;
        final String toolId;
        CompanionProfileReadModel profile;
        SavedCompanionTalentSnapshot snapshot;
        String language;
        String presentedConfigId;
        long presentedAllocationRevision;
        boolean pending;
        State(UUID owner, String toolId, CompanionProfileReadModel profile, SavedCompanionTalentSnapshot snapshot) {
            this.owner = owner;
            this.toolId = toolId;
            this.profile = profile;
            this.snapshot = snapshot;
        }
    }
}
