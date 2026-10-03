package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionTalentActionRequest.Action;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiTalentFlowView;
import com.alechilles.alecstamework.companion.bonded.BondedTalentUpdates;
import com.alechilles.alecstamework.companion.bonded.BondedTalentUpdates.Status;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionSettings;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.alechilles.alecstamework.ui.TameworkCompanionTalentsPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The talent page of an owner's dead or lost ordinary companion, which has no body: the page
 * reads the companion's stored snapshot, and a purchase or reset patches that snapshot
 * ({@link BondedTalentUpdates#updateStored}), so a revive or recovery brings it back. Serves the
 * native panel's Talents button ({@link #openPage}) and the managed command UI's talent row
 * ({@link #openManaged}).
 *
 * <p>Threads: clicks arrive on the owner's world thread. The snapshot read, its decode and the
 * change start on another thread ({@link SavedTalents}), never on a world thread. Results come
 * back on the owner's current world thread ({@link CommandUiHostPage.WorldDispatcher}), where the
 * player is resolved again and the tool (and, for the managed flow, the session) is checked
 * before anything is shown. The page model ({@link View}) is immutable; an open page keeps the
 * latest one and is read and changed on that thread only.</p>
 */
final class CommandSavedTalentPageService {
    private static final String SAVED = "tamework.ui.talents.saved.";

    private final SavedTalents talents;
    private final CommandOwnedPanelRecordSource rows;
    private final CommandToolInventoryService inventory;
    private final Function<ItemStack, List<LinkedNpcRecord>> linkedRecords;
    private final CommandFeedbackService feedback;
    private final CommandTalentPageService renderer;
    private final CommandUiManagedTalentFlowService flows = new CommandUiManagedTalentFlowService();
    private final CommandUiHostPage.WorldDispatcher dispatcher = CommandUiCurrentWorldDispatcher.production();

    /** @param linkedRecords the companions a command item links, read from its stack */
    CommandSavedTalentPageService(@Nonnull CompanionQueries companions, @Nonnull BondedTalentUpdates updates,
                                  @Nonnull CommandToolInventoryService inventory,
                                  @Nonnull Function<ItemStack, List<LinkedNpcRecord>> linkedRecords,
                                  @Nonnull CommandFeedbackService feedback,
                                  @Nonnull CommandTalentPageService renderer) {
        this.talents = new SavedTalents(companions::get, updates);
        this.rows = new CommandOwnedPanelRecordSource(companions);
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.linkedRecords = Objects.requireNonNull(linkedRecords, "linkedRecords");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /** Opens the native talent page for the card {@code rowId}. Owner's world thread. */
    void openPage(@Nonnull Player player, @Nonnull String toolId, @Nonnull UUID rowId, @Nonnull Runnable back) {
        UUID owner = player.getUuid();
        UUID profileId = profileFor(player, owner, toolId, rowId);
        if (owner == null || profileId == null) {
            feedback.showWarningKey(player, "tamework.ui.talents.noUsableData");
            return;
        }
        talents.load(owner, profileId).whenComplete((view, failure) -> dispatcher.dispatch(owner, (ref, store) -> {
            Player current = currentPlayer(ref, store);
            if (current == null || !hasTool(current, toolId)) {
                return;
            }
            if (failure != null || view == null) {
                feedback.showWarningKey(current, "tamework.ui.talents.noUsableData");
                return;
            }
            showPage(current, owner, toolId, view, back);
        }));
    }

    /** Current world thread of {@code player}. */
    private void showPage(Player player, UUID owner, String toolId, View view, Runnable back) {
        World world = player.getWorld();
        Ref<EntityStore> playerRef = player.getReference();
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (player.getPageManager() == null || world == null || playerRef == null || !playerRef.isValid()
                || uiPlayerRef == null || !uiPlayerRef.isValid()) {
            feedback.showWarningKey(player, "tamework.ui.talents.openUnavailable");
            return;
        }
        Page page = new Page(view, uiPlayerRef.getLanguage());
        TameworkCompanionTalentsPage[] opened = new TameworkCompanionTalentsPage[1];
        opened[0] = new TameworkCompanionTalentsPage(uiPlayerRef,
                () -> pageData(page.language, page.view),
                talentId -> click(player, owner, toolId, page, Action.PURCHASE, talentId, opened[0]),
                () -> click(player, owner, toolId, page, Action.RESET, null, opened[0]),
                back);
        try {
            player.getPageManager().openCustomPage(playerRef, world.getEntityStore().getStore(), opened[0]);
        } catch (RuntimeException failure) {
            feedback.showWarningKey(player, "tamework.ui.talents.openUnavailable");
        }
    }

    /**
     * A purchase or reset on the native page, on the page's world thread. Answers the saving text
     * at once; the result is announced and drawn when it arrives.
     */
    private String click(Player player, UUID owner, String toolId, Page page, Action action,
                         @Nullable String talentId, TameworkCompanionTalentsPage opened) {
        if (!hasTool(player, toolId)) {
            return text(page.language, SAVED + "unavailable");
        }
        if (page.pending) {
            return text(page.language, SAVED + "saving");
        }
        page.pending = true;
        View before = page.view;
        changeAndReload(owner, before, action, talentId).whenComplete((done, failure) ->
                dispatcher.dispatch(owner, (ref, store) -> {
                    page.pending = false;
                    Result result = done != null ? done : new Result(Status.FAILED, null);
                    if (result.view() != null) {
                        page.view = result.view();
                    }
                    Player current = currentPlayer(ref, store);
                    if (current == null) {
                        return;
                    }
                    String message = message(page.language, action, talentId, result, before);
                    if (result.status() == Status.APPLIED) {
                        feedback.showSuccess(current, message);
                    } else {
                        feedback.showWarning(current, message);
                    }
                    opened.refresh(message);
                }));
        return text(page.language, SAVED + "saving");
    }

    /** Opens the managed command UI's talent flow for the row of {@code npcId}. Owner's world thread. */
    @Nonnull
    CompletionStage<CommandUiActionResult> openManaged(@Nonnull CommandUiSessionImpl session, @Nonnull UUID rowId,
                                                       @Nullable Player player, @Nonnull String toolId,
                                                       @Nonnull UUID npcId, @Nonnull BooleanSupplier authority) {
        String language = player == null || player.getPlayerRef() == null ? null : player.getPlayerRef().getLanguage();
        if (player == null || !authority.getAsBoolean()) {
            return CompletableFuture.completedFuture(CommandUiActionResult.denied(text(language, SAVED + "unavailable")));
        }
        UUID owner = player.getUuid();
        UUID profileId = profileFor(player, owner, toolId, npcId);
        if (owner == null || profileId == null) {
            return CompletableFuture.completedFuture(CommandUiActionResult.notFound(text(language, SAVED + "unavailable")));
        }
        long generation = session.currentManagedGeneration();
        return talents.load(owner, profileId).thenCompose(view -> onWorld(owner, current -> {
            String currentLanguage = language(current);
            if (!session.isOpen() || session.currentManagedGeneration() != generation
                    || !authority.getAsBoolean() || !hasTool(current, toolId)) {
                return CommandUiActionResult.denied(text(currentLanguage, SAVED + "unavailable"));
            }
            if (view == null) {
                return CommandUiActionResult.notFound(text(currentLanguage, SAVED + "unavailable"));
            }
            return CommandUiActionResult.presented(
                    flow(session, rowId, authority, owner, toolId, new Page(view, currentLanguage)));
        }));
    }

    /** World thread; starts a new managed flow generation, as every talent flow build does. */
    private CommandUiTalentFlowView flow(CommandUiSessionImpl session, UUID rowId, BooleanSupplier authority,
                                        UUID owner, String toolId, Page page) {
        View view = page.view;
        TameworkCompanionTalentsPage.PageData data = pageData(page.language, view);
        return flows.build(session, rowId, view.profileId().toString(), CommandUiActionGateway.Route.GENERIC,
                new CommandUiManagedTalentFlowService.Snapshot(data, view.level(), availablePoints(view),
                        Map.of("saved", "true")),
                (kind, label, talentId, confirmation) -> {
                    Action action = "RESET_TALENTS".equals(kind) ? Action.RESET : Action.PURCHASE;
                    var handle = session.issueManaged(CommandUiActionGateway.Route.GENERIC,
                            new CommandUiAction(kind, null, talentId, confirmation),
                            ignored -> authority.getAsBoolean(),
                            (bound, ignored) -> managedClick(session, rowId, authority, owner, toolId, page,
                                    action, talentId),
                            CommandUiActionGateway.InputPolicy.NONE, 0, confirmation);
                    return new CommandUiActionView(kind, label, true, null, confirmation, handle);
                });
    }

    /** A managed purchase or reset; the gateway runs it on the owner's world thread. */
    private CompletionStage<CommandUiActionResult> managedClick(CommandUiSessionImpl session, UUID rowId,
                                                                BooleanSupplier authority, UUID owner, String toolId,
                                                                Page page, Action action, @Nullable String talentId) {
        if (page.pending) {
            return CompletableFuture.completedFuture(CommandUiActionResult.conflict(text(page.language, SAVED + "saving")));
        }
        long generation = session.currentManagedGeneration();
        page.pending = true;
        View before = page.view;
        return changeAndReload(owner, before, action, talentId).thenCompose(result -> onWorld(owner, current -> {
            page.pending = false;
            if (!session.isOpen() || session.currentManagedGeneration() != generation
                    || !authority.getAsBoolean() || !hasTool(current, toolId)) {
                return CommandUiActionResult.denied(text(page.language, SAVED + "unavailable"));
            }
            if (result.view() == null) {
                return CommandUiActionResult.notFound(text(page.language, SAVED + "unavailable"));
            }
            page.view = result.view();
            return CommandUiActionResult.updated(message(page.language, action, talentId, result, before),
                    flow(session, rowId, authority, owner, toolId, page));
        }));
    }

    /** The change, then the companion read again so the page shows what is stored now. Never fails. */
    private CompletableFuture<Result> changeAndReload(UUID owner, View before, Action action, @Nullable String talentId) {
        return talents.change(owner, before, action, talentId)
                .thenCompose(status -> talents.load(owner, before.profileId())
                        .thenApply(view -> new Result(status, view)));
    }

    /** The viewer's text for a finished change. World thread (reads talent and leveling configs). */
    private String message(@Nullable String language, Action action, @Nullable String talentId, Result result,
                           View before) {
        return switch (result.status()) {
            case APPLIED -> text(language, action == Action.PURCHASE
                    ? "tamework.ui.talents.mutation.unlocked" : "tamework.ui.talents.mutation.refunded");
            case DISABLED -> text(language, "tamework.ui.talents.mutation.disabled");
            case CONFLICT -> text(language, SAVED + "changed");
            case REJECTED -> rejection(language, action, talentId, result.view() != null ? result.view() : before);
            case NO_LEVEL_DATA, BODY_UNAVAILABLE, FAILED -> text(language, SAVED + "unavailable");
        };
    }

    /** Why the rules refused the change, checked again against {@code view}. World thread. */
    private static String rejection(@Nullable String language, Action action, @Nullable String talentId, View view) {
        if (action == Action.RESET) {
            return text(language, "tamework.ui.talents.mutation.noPointsSpent");
        }
        TwTalentConfig config = roleTree(view.roleId());
        CompanionTalentService.PurchaseResult purchase = CompanionTalentService.purchase(
                CompanionTalentService.reconcileAllocation(view.talents(), config), config, view.level(),
                view.levelingConfigId(), talentId);
        // Allowed now: the stored state moved between the click and the check.
        return purchase.applied() ? text(language, SAVED + "changed")
                : CommandTalentPageService.resolveMutationMessage(language, purchase.message());
    }

    /** The page, in the viewer's language, from the role's tree as the stored change checks it. World thread. */
    private TameworkCompanionTalentsPage.PageData pageData(@Nullable String language, View view) {
        TwTalentConfig config = roleTree(view.roleId());
        String name = view.displayName() != null ? view.displayName()
                : text(language, "tamework.ui.talents.defaultCompanionName");
        return renderer.buildTalentPageData(language, name, leveling(view), availablePoints(view), config,
                CompanionTalentService.reconcileAllocation(view.talents(), config));
    }

    private static int availablePoints(View view) {
        TameworkTalentsComponent spent = CompanionTalentService.reconcileAllocation(view.talents(), roleTree(view.roleId()));
        return Math.max(0, CompanionLevelingService.resolveEarnedTalentPoints(view.level(), view.levelingConfigId())
                - (spent == null ? 0 : spent.getSpentPoints()));
    }

    /** The level line's values, as the saved card computes them; null without an enabled leveling config. */
    @Nullable
    private static CompanionLevelingService.LevelingSnapshot leveling(View view) {
        TwLevelingConfig config = view.levelingConfigId() == null ? null
                : TwLevelingConfig.resolveById(view.levelingConfigId());
        if (config == null) {
            config = TwLevelingConfig.resolveForRole(view.roleId());
        }
        if (config == null || !config.isEnabled() || config.getLevels().getMaxLevel() <= 0) {
            return null;
        }
        int maxLevel = config.getLevels().getMaxLevel();
        int level = Math.max(1, Math.min(view.level(), maxLevel));
        boolean atMax = level >= maxLevel;
        double start = CommandSavedNpcPanelSnapshot.cumulativeXp(config, level);
        double next = atMax ? start : CommandSavedNpcPanelSnapshot.cumulativeXp(config, level + 1);
        return new CompanionLevelingService.LevelingSnapshot(config.getId(), level, view.currentXp(),
                view.totalXp(), start, next, maxLevel, atMax);
    }

    /** The role's enabled tree, which {@link BondedTalentUpdates#updateStored} checks against. */
    @Nullable
    private static TwTalentConfig roleTree(String roleId) {
        TwTalentConfig config = roleId == null || roleId.isBlank() ? null : TwTalentConfig.resolveForRole(roleId);
        return config != null && config.isEnabled() ? config : null;
    }

    /** The profile behind a panel row of the held command item, or null. World thread. */
    @Nullable
    private UUID profileFor(Player player, @Nullable UUID owner, String toolId, UUID rowId) {
        ItemStack stack = inventory.findToolStack(player, toolId);
        if (owner == null || stack == null || stack.isEmpty()) {
            return null;
        }
        return rows.profileForRow(owner, rowId, linkedRecords.apply(stack)).map(ProfileId::value).orElse(null);
    }

    private boolean hasTool(Player player, String toolId) {
        ItemStack stack = inventory.findToolStack(player, toolId);
        return stack != null && !stack.isEmpty();
    }

    /** Runs {@code action} with the owner's current player on that player's world thread. */
    private CompletionStage<CommandUiActionResult> onWorld(UUID owner, Function<Player, CommandUiActionResult> action) {
        CompletableFuture<CommandUiActionResult> result = new CompletableFuture<>();
        CommandUiHostPage.WorldOperation operation = (ref, store) -> {
            try {
                Player current = currentPlayer(ref, store);
                result.complete(current == null
                        ? CommandUiActionResult.notFound(text(null, SAVED + "unavailable"))
                        : action.apply(current));
            } catch (RuntimeException | LinkageError failure) {
                result.complete(CommandUiActionResult.failed(text(null, SAVED + "unavailable")));
            }
        };
        if (!dispatcher.dispatch(owner, operation)) {
            result.complete(CommandUiActionResult.notFound(text(null, SAVED + "unavailable")));
        }
        return result;
    }

    /** The player of a dispatched ref, or null when the player is in no world now. World thread. */
    @Nullable
    private static Player currentPlayer(@Nullable Ref<EntityStore> ref, @Nullable Store<EntityStore> store) {
        try {
            return ref == null || store == null || !ref.isValid() ? null : store.getComponent(ref, Player.getComponentType());
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    @Nullable
    private static String language(Player player) {
        PlayerRef ref = player.getPlayerRef();
        return ref == null ? null : ref.getLanguage();
    }

    private static String text(@Nullable String language, String key) {
        return LocalizedText.resolve(language, key);
    }

    /** An open page: its latest model and whether a change is on its way. Owner's world thread only. */
    private static final class Page {
        private volatile View view;
        private volatile boolean pending;
        @Nullable private final String language;

        private Page(View view, @Nullable String language) {
            this.view = view;
            this.language = language;
        }
    }

    private record Result(Status status, @Nullable View view) { }

    /**
     * What the page shows of a dead or lost companion, read from its stored snapshot at
     * {@code generation}. Immutable: {@link #talents} hands out copies.
     *
     * @param displayName the companion's name, or null for the default one
     */
    record View(@Nonnull UUID profileId, long generation, @Nonnull String roleId, @Nullable String displayName,
                @Nullable String levelingConfigId, int level, double currentXp, double totalXp,
                @Nonnull TameworkTalentsComponent talents) {
        View {
            talents = talents.clone();
        }

        @Override
        @Nonnull
        public TameworkTalentsComponent talents() {
            return talents.clone();
        }

        /** Null when the snapshot has no level to check a purchase against. */
        @Nullable
        static View of(CompanionRecord record, @Nullable BondedTalentUpdates.Stored stored) {
            TameworkLevelingComponent leveling = stored == null ? null : stored.leveling();
            if (leveling == null) {
                return null;
            }
            String name = record.summary().customName() != null ? record.summary().customName() : record.displayName();
            TameworkTalentsComponent bought = stored.talents() != null ? stored.talents()
                    : new TameworkTalentsComponent(record.summary().talentsConfigId(), 0, new String[0], 0L);
            return new View(record.profileId(), record.generation(), record.roleId(),
                    name == null || name.isBlank() ? null : name, leveling.getConfigId(),
                    Math.max(1, leveling.getLevel()), leveling.getCurrentXp(), leveling.getTotalXp(), bought);
        }
    }

    /**
     * The record gate, the snapshot read and the stored change, without players or worlds. May be
     * called from any thread; the read, decode and change run on {@code offWorld}.
     */
    static final class SavedTalents {
        private final Function<UUID, CompanionRecord> records;
        private final BondedTalentUpdates updates;
        private final Executor offWorld;

        SavedTalents(@Nonnull Function<UUID, CompanionRecord> records, @Nonnull BondedTalentUpdates updates) {
            this.records = Objects.requireNonNull(records, "records");
            this.updates = Objects.requireNonNull(updates, "updates");
            // The read completes on the caller when the snapshot is still queued, so it starts here.
            this.offWorld = ForkJoinPool.commonPool();
        }

        /**
         * The page model of {@code owner}'s companion, or null when there is none to edit here:
         * not the owner's, bonded or a command-family member, not dead or lost, leveling or talents
         * turned off, no snapshot, or one that cannot be read. Never fails.
         */
        @Nonnull
        CompletableFuture<View> load(@Nonnull UUID owner, @Nonnull UUID profileId) {
            CompanionRecord record = records.apply(profileId);
            if (!editable(owner, record) || !CompanionProgressionSettings.isTalentsEnabled()
                    || !CompanionProgressionSettings.isLevelingEnabled()) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.supplyAsync(() -> updates.read(record), offWorld)
                    .thenCompose(read -> read)
                    .handle((stored, failure) -> failure != null ? null : View.of(record, stored));
        }

        /**
         * Buys {@code talentId} or resets the talents shown in {@code view}. Ends
         * {@link Status#CONFLICT} with nothing written when the companion moved on since the
         * page read it (revived, recovered, released or died again), and {@link Status#FAILED}
         * when the snapshot cannot be read. Never fails.
         */
        @Nonnull
        CompletableFuture<Status> change(@Nonnull UUID owner, @Nonnull View view, @Nonnull Action action,
                                         @Nullable String talentId) {
            CompanionRecord record = records.apply(view.profileId());
            if (!editable(owner, record) || record.generation() != view.generation()) {
                return CompletableFuture.completedFuture(Status.CONFLICT);
            }
            return CompletableFuture.supplyAsync(() -> updates.updateStored(record, action, talentId), offWorld)
                    .thenCompose(outcome -> outcome)
                    .handle((outcome, failure) -> failure != null || outcome == null ? Status.FAILED : outcome.status());
        }

        /** Owned by {@code owner}, an ordinary companion the generic owned actions may change, dead or lost. */
        private static boolean editable(UUID owner, @Nullable CompanionRecord record) {
            return record != null && owner.equals(record.ownerUuid()) && !record.bonded() && record.rosterId() == null
                    && (record.location().kind() == LocationKind.DEAD || record.location().kind() == LocationKind.LOST);
        }
    }
}
