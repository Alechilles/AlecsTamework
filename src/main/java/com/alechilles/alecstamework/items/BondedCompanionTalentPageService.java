package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.api.BondedCompanionTalentActionRequest;
import com.alechilles.alecstamework.companion.bonded.BondedTalentUpdates;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.alechilles.alecstamework.ui.TameworkCompanionTalentsPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Opens the shared talent page for a bonded companion and sends its purchases and resets to the
 * bonded API.
 *
 * <p>A bonded companion does not need a body to spend its points: the API changes an active
 * companion on its body and any other one in its stored snapshot. An active companion's card
 * carries its live level and talents. For any other companion the page first reads the stored
 * snapshot through the API ({@link BondedTalentUpdates.StoredReader}), because the listed card
 * has no purchased talent ids; a companion with no snapshot yet has no usable talent data.</p>
 *
 * <p>The stored read and a stored change finish off the world thread. Their results come back on
 * the owner's current world thread ({@link CommandUiHostPage.WorldDispatcher}, by player id), and
 * the player is resolved again there before any feedback is shown or the page is refreshed.</p>
 */
final class BondedCompanionTalentPageService {
    private final Supplier<BondedCompanionApi> api;
    private final CommandFeedbackService feedback;
    private final CommandUiHostPage.WorldDispatcher dispatcher;
    private final BiFunction<Ref<EntityStore>, Store<EntityStore>, Player> players;

    BondedCompanionTalentPageService(
            @Nullable Supplier<BondedCompanionApi> api,
            @Nonnull CommandFeedbackService feedback
    ) {
        this(api, feedback, CommandUiCurrentWorldDispatcher.production(),
                (ref, store) -> store.getComponent(ref, Player.getComponentType()));
    }

    /**
     * @param dispatcher runs work on the world thread the owner is in when it runs
     * @param players    the player component of the dispatched player ref, read on that thread
     */
    BondedCompanionTalentPageService(
            @Nullable Supplier<BondedCompanionApi> api,
            @Nonnull CommandFeedbackService feedback,
            @Nonnull CommandUiHostPage.WorldDispatcher dispatcher,
            @Nonnull BiFunction<Ref<EntityStore>, Store<EntityStore>, Player> players
    ) {
        this.api = api == null ? BondedCompanionApi::unavailable : api;
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.players = Objects.requireNonNull(players, "players");
    }

    void open(
            @Nullable Player player,
            @Nonnull BondedCompanionPanelPresentation presentation,
            @Nonnull Runnable backCallback
    ) {
        if (player == null || player.getPageManager() == null) {
            return;
        }
        World world = player.getWorld();
        Ref<EntityStore> playerRef = player.getReference();
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (world == null || playerRef == null || !playerRef.isValid()
                || uiPlayerRef == null || !uiPlayerRef.isValid()) {
            feedback.showWarningKey(player, "tamework.ui.talents.openUnavailable");
            return;
        }
        State state = State.from(player.getUuid(), presentation);
        CompletableFuture<BondedTalentUpdates.Stored> stored = readStored(state, presentation);
        if (stored == null) {
            openPage(player, state, backCallback);
            return;
        }
        UUID owner = state.ownerUuid;
        stored.whenComplete((read, failure) -> dispatcher.dispatch(owner, (ref, store) -> {
            Player current = currentPlayer(ref, store);
            if (current == null) {
                return;
            }
            if (failure != null || read == null || read.leveling() == null) {
                feedback.showWarningKey(current, "tamework.ui.talents.noUsableData");
                return;
            }
            state.apply(read);
            openPage(current, state, backCallback);
        }));
    }

    /** World thread of {@code player}. */
    private void openPage(Player player, State state, Runnable backCallback) {
        World world = player.getWorld();
        Ref<EntityStore> playerRef = player.getReference();
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (player.getPageManager() == null || world == null || playerRef == null || !playerRef.isValid()
                || uiPlayerRef == null || !uiPlayerRef.isValid()) {
            feedback.showWarningKey(player, "tamework.ui.talents.openUnavailable");
            return;
        }
        TameworkCompanionTalentsPage[] opened = new TameworkCompanionTalentsPage[1];
        Consumer<String> refresh = message -> opened[0].refresh(message);
        opened[0] = new TameworkCompanionTalentsPage(
                uiPlayerRef,
                () -> pageData(resolveLanguage(player), state),
                talentId -> update(player, state,
                        BondedCompanionTalentActionRequest.Action.PURCHASE,
                        talentId, refresh),
                () -> update(player, state,
                        BondedCompanionTalentActionRequest.Action.RESET, null, refresh),
                backCallback
        );
        try {
            player.getPageManager().openCustomPage(playerRef,
                    world.getEntityStore().getStore(), opened[0]);
        } catch (RuntimeException failure) {
            feedback.showWarningKey(player, "tamework.ui.talents.openUnavailable");
        }
    }

    /**
     * The stored talent state of a companion that is not active, or null when the card already
     * holds what the page needs: an active companion (its card carries the live body's values),
     * or an API that cannot read stored talents.
     */
    @Nullable
    private CompletableFuture<BondedTalentUpdates.Stored> readStored(
            State state, BondedCompanionPanelPresentation presentation) {
        if (presentation.status().state() == BondedCompanionStateView.ACTIVE) {
            return null;
        }
        try {
            return api.get() instanceof BondedTalentUpdates.StoredReader reader
                    ? reader.storedTalents(state.ownerUuid, state.rosterId, state.profileId) : null;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** The player of a dispatched ref, or null when the player is in no world now. World thread. */
    @Nullable
    private Player currentPlayer(@Nullable Ref<EntityStore> ref, @Nullable Store<EntityStore> store) {
        try {
            return ref == null || store == null || !ref.isValid() ? null : players.apply(ref, store);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    @Nullable
    ManagedTarget managedTarget(
            @Nullable Player player,
            @Nullable BondedCompanionPanelPresentation presentation
    ) {
        if (player == null || presentation == null) {
            return null;
        }
        State state = State.from(player.getUuid(), presentation);
        CompletableFuture<BondedTalentUpdates.Stored> stored = readStored(state, presentation);
        if (stored != null) {
            // The managed flow reads its snapshot again on every refresh, so the stored talents
            // only need to reach the state object; that touches no entity.
            stored.thenAccept(read -> {
                if (read != null && read.leveling() != null) {
                    state.apply(read);
                }
            });
        }
        return new ManagedTarget(state);
    }

    @Nullable
    ManagedSnapshot managedSnapshot(
            @Nullable Player player,
            @Nonnull ManagedTarget target
    ) {
        if (player == null) return null;
        State state = target.state;
        return new ManagedSnapshot(
                pageData(resolveLanguage(player), state),
                state.profileId, state.level, availablePoints(state),
                state.revision);
    }

    @Nonnull
    ManagedMutation purchaseManaged(
            @Nullable Player player,
            @Nonnull ManagedTarget target,
            @Nullable String talentId
    ) {
        return updateManaged(player, target,
                BondedCompanionTalentActionRequest.Action.PURCHASE, talentId, null);
    }

    @Nonnull
    ManagedMutation resetManaged(
            @Nullable Player player,
            @Nonnull ManagedTarget target
    ) {
        return updateManaged(player, target,
                BondedCompanionTalentActionRequest.Action.RESET, null, null);
    }

    @Nonnull
    private String update(
            @Nonnull Player player,
            @Nonnull State state,
            @Nonnull BondedCompanionTalentActionRequest.Action action,
            @Nullable String talentId,
            @Nonnull Consumer<String> refreshPage
    ) {
        ManagedMutation outcome = updateManaged(
                player, new ManagedTarget(state), action, talentId, refreshPage);
        if (outcome.pending()) {
            // The result is announced when it arrives; the page shows the saving text until then.
            return outcome.message();
        }
        if (outcome.applied()) {
            feedback.showSuccess(player, outcome.message());
        } else {
            feedback.showWarning(player, outcome.message());
        }
        return outcome.message();
    }

    /**
     * @param refreshPage shows a late result's text on the open talent page; null when the caller
     *                    has no page of its own (the managed flow refreshes itself)
     */
    @Nonnull
    private ManagedMutation updateManaged(
            @Nullable Player player,
            @Nonnull ManagedTarget target,
            @Nonnull BondedCompanionTalentActionRequest.Action action,
            @Nullable String talentId,
            @Nullable Consumer<String> refreshPage
    ) {
        State state = target.state;
        String language = player == null ? null : resolveLanguage(player);
        BondedCompanionApi current = api.get();
        if (current == null || !current.availability().available()) {
            return new ManagedMutation(false, false,
                    BondedCompanionResultCode.UNAVAILABLE,
                    LocalizedText.resolve(language,
                            "tamework.ui.talents.mutation.bondedUnavailable"));
        }
        String idempotency = "talents:" + state.profileId + ":"
                + state.revision + ":" + action + ":"
                + (talentId == null ? "" : talentId) + ":"
                + (state.talents.getConfigId() == null ? "" : state.talents.getConfigId());
        BondedCompanionTalentActionRequest request =
                new BondedCompanionTalentActionRequest(
                        "tamework.command-item", idempotency,
                        state.ownerUuid, state.rosterId, state.profileId,
                        state.revision, action, talentId,
                        state.talents.getConfigId());
        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> future =
                current.updateTalents(request);
        BondedCompanionResult<BondedCompanionProfileView> result = future == null
                ? null : future.getNow(null);
        if (result == null) {
            if (future != null) {
                future.whenComplete((late, failure) -> announce(state, action, refreshPage,
                        failure == null && late != null && late.successful() ? late.value() : null));
            }
            return new ManagedMutation(false, true,
                    BondedCompanionResultCode.UNAVAILABLE,
                    LocalizedText.resolve(language,
                            "tamework.ui.talents.mutation.saving"));
        }
        if (!result.successful() || result.value() == null) {
            return new ManagedMutation(false, false, result.code(),
                    LocalizedText.resolve(language,
                            action == BondedCompanionTalentActionRequest.Action.PURCHASE
                                    ? "tamework.ui.talents.mutation.bondedUnlockFailed"
                                    : "tamework.ui.talents.mutation.bondedRefundFailed"));
        }
        state.apply(result.value());
        String message = LocalizedText.resolve(language,
                action == BondedCompanionTalentActionRequest.Action.PURCHASE
                        ? "tamework.ui.talents.mutation.unlocked"
                        : "tamework.ui.talents.mutation.refunded");
        return new ManagedMutation(true, false,
                BondedCompanionResultCode.SUCCESS, message);
    }

    /**
     * A change that finished after its click. Runs on the thread that finished it, so it touches
     * only the page's own state object, then tells the owner on the owner's current world thread:
     * the unlocked or refunded text for a change that worked ({@code changed} is its view), the
     * failure text otherwise. An owner who has left gets nothing.
     */
    private void announce(State state, BondedCompanionTalentActionRequest.Action action,
                          @Nullable Consumer<String> refreshPage, @Nullable BondedCompanionProfileView changed) {
        if (changed != null) {
            state.apply(changed);
        }
        boolean purchase = action == BondedCompanionTalentActionRequest.Action.PURCHASE;
        String key = changed != null
                ? purchase ? "tamework.ui.talents.mutation.unlocked" : "tamework.ui.talents.mutation.refunded"
                : purchase ? "tamework.ui.talents.mutation.bondedUnlockFailed"
                        : "tamework.ui.talents.mutation.bondedRefundFailed";
        dispatcher.dispatch(state.ownerUuid, (ref, store) -> {
            Player current = currentPlayer(ref, store);
            if (current == null) {
                return;
            }
            String message = LocalizedText.resolve(resolveLanguage(current), key);
            if (changed != null) {
                feedback.showSuccess(current, message);
            } else {
                feedback.showWarning(current, message);
            }
            if (refreshPage != null) {
                refreshPage.accept(message);
            }
        });
    }

    @Nonnull
    private TameworkCompanionTalentsPage.PageData pageData(
            @Nullable String language,
            @Nonnull State state
    ) {
        TwTalentConfig config = resolveConfig(state.talents, state.roleId);
        int points = availablePoints(state);
        String displayName = state.displayName != null ? state.displayName
                : LocalizedText.resolve(language, "tamework.ui.talents.defaultCompanionName");
        String levelSummary = LocalizedText.format(language,
                "tamework.ui.talents.levelSummary.max", state.level);
        String pointsSummary = LocalizedText.format(language,
                "tamework.ui.talents.points.available", points);
        if (config == null || !config.isEnabled() || config.getTalents().length == 0) {
            return new TameworkCompanionTalentsPage.PageData(displayName,
                    levelSummary, pointsSummary,
                    LocalizedText.resolve(language, "tamework.ui.talents.status.noTree"),
                    false, List.of());
        }
        ArrayList<TameworkCompanionTalentsPage.TreeNodeEntry> entries = new ArrayList<>();
        for (TwTalentConfig.TalentDefinition talent : config.getTalents()) {
            if (talent == null || talent.getId() == null) {
                continue;
            }
            boolean purchased = state.talents.hasPurchasedTalent(talent.getId());
            boolean levelMet = state.level >= talent.getMinLevel();
            String missing = missingPrerequisite(language, state.talents, config, talent);
            boolean canPurchase = !purchased && levelMet && missing == null
                    && points >= talent.getPointCost();
            String cardState = purchased ? TameworkCompanionTalentsPage.STATE_PURCHASED
                    : !levelMet || missing != null ? TameworkCompanionTalentsPage.STATE_LOCKED
                    : points < talent.getPointCost()
                    ? TameworkCompanionTalentsPage.STATE_UNAFFORDABLE
                    : TameworkCompanionTalentsPage.STATE_AVAILABLE;
            String status = purchased
                    ? LocalizedText.resolve(language, "tamework.ui.talents.status.unlocked")
                    : !levelMet ? LocalizedText.format(language,
                            "tamework.ui.talents.status.requiresLevel", talent.getMinLevel())
                    : missing != null ? LocalizedText.format(language,
                            "tamework.ui.talents.status.requiresTalent", missing)
                    : points < talent.getPointCost() ? LocalizedText.format(language,
                            "tamework.ui.talents.status.costsPoints", talent.getPointCost())
                    : LocalizedText.format(language,
                            "tamework.ui.talents.status.costPoints", talent.getPointCost());
            entries.add(entry(language, config, talent, cardState, status, canPurchase));
        }
        entries.sort((left, right) -> {
            int branch = normalizeBranch(left.branchName()).compareTo(
                    normalizeBranch(right.branchName()));
            return branch != 0 ? branch : Integer.compare(left.tier(), right.tier());
        });
        return new TameworkCompanionTalentsPage.PageData(displayName,
                levelSummary, pointsSummary, entries.isEmpty()
                ? LocalizedText.resolve(language,
                        "tamework.ui.talents.status.noTalentsConfigured")
                : LocalizedText.resolve(language,
                        "tamework.ui.talents.status.chooseTalent"),
                state.talents.getSpentPoints() > 0
                        || state.talents.getPurchasedTalentIds().length > 0,
                entries);
    }

    private TameworkCompanionTalentsPage.TreeNodeEntry entry(
            String language, TwTalentConfig config,
            TwTalentConfig.TalentDefinition talent, String state,
            String status, boolean canPurchase
    ) {
        String displayName = LocalizedText.resolveConfigValue(language,
                talent.getDisplayName(), talent.getId());
        return new TameworkCompanionTalentsPage.TreeNodeEntry(
                talent.getId(), LocalizedText.resolveConfigValue(language,
                talent.getBranch(), LocalizedText.resolve(language,
                        "tamework.ui.talents.branch.general")), talent.getTier(), state,
                displayName, LocalizedText.resolveConfigValue(language,
                talent.getDescription(), ""), LocalizedText.format(language,
                "tamework.ui.talents.status.stateDetail", state, status),
                talent.getPointCost(), talent.getMinLevel(),
                Arrays.stream(talent.getRequiresTalentIds())
                        .filter(value -> value != null && !value.isBlank()).toList(),
                prerequisiteNames(language, config, talent),
                effectSummary(language, talent), canPurchase);
    }

    private int availablePoints(State state) {
        return Math.max(0, CompanionLevelingService.resolveEarnedTalentPoints(
                state.level, state.levelingConfigId) - state.talents.getSpentPoints());
    }

    private TwTalentConfig resolveConfig(TameworkTalentsComponent talents, String roleId) {
        if (talents.getConfigId() != null && !talents.getConfigId().isBlank()) {
            TwTalentConfig configured = TwTalentConfig.resolveById(talents.getConfigId());
            if (configured != null && configured.isEnabled()) return configured;
        }
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        TwTalentConfig roleConfig = TwTalentConfig.resolveForRole(roleId);
        return roleConfig != null && roleConfig.isEnabled() ? roleConfig : null;
    }

    private String missingPrerequisite(String language, TameworkTalentsComponent talents,
                                       TwTalentConfig config,
                                       TwTalentConfig.TalentDefinition talent) {
        for (String required : talent.getRequiresTalentIds()) {
            if (required != null && !required.isBlank() && !talents.hasPurchasedTalent(required)) {
                TwTalentConfig.TalentDefinition node = config.findTalent(required);
                return node == null ? required : LocalizedText.resolveConfigValue(
                        language, node.getDisplayName(), required);
            }
        }
        return null;
    }

    private List<String> prerequisiteNames(String language, TwTalentConfig config,
                                           TwTalentConfig.TalentDefinition talent) {
        ArrayList<String> names = new ArrayList<>();
        for (String required : talent.getRequiresTalentIds()) {
            if (required == null || required.isBlank()) continue;
            TwTalentConfig.TalentDefinition node = config.findTalent(required);
            names.add(node == null ? required : LocalizedText.resolveConfigValue(
                    language, node.getDisplayName(), required));
        }
        return names;
    }

    private String effectSummary(String language, TwTalentConfig.TalentDefinition talent) {
        TwTalentConfig.PassiveEffect[] effects = talent.getEffects();
        if (effects == null || effects.length == 0) return "";
        ArrayList<String> summaries = new ArrayList<>();
        for (TwTalentConfig.PassiveEffect effect : effects) {
            if (effect == null || effect.getEffectKey() == null || effect.getEffectKey().isBlank()) {
                continue;
            }
            if (effect.getEffectKey().equalsIgnoreCase(talent.getId())
                    && Math.abs(effect.getMultiplier() - 1.0) < 0.0001) {
                // This is a talent marker used by NPC behavior, not a second player-facing effect.
                continue;
            }
            summaries.add(formatEffectSummary(language, effect));
        }
        return String.join("\n", summaries);
    }

    private String formatEffectSummary(String language, TwTalentConfig.PassiveEffect effect) {
        String label = formatEffectKey(language, effect.getEffectKey());
        String change = formatMultiplierChange(effect.getMultiplier());
        return change.isBlank() ? label : LocalizedText.format(language,
                "tamework.ui.talents.effects.line", label, change);
    }

    private String formatEffectKey(String language, String effectKey) {
        String spaced = effectKey.replace("Multiplier", "")
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .trim();
        return LocalizedText.resolveConfigValue(language,
                "tamework.ui.talents.effect." + effectKey,
                spaced.isBlank() ? effectKey : spaced);
    }

    private String formatMultiplierChange(double multiplier) {
        double percent = (multiplier - 1.0) * 100.0;
        if (Math.abs(percent) < 0.05) {
            return "";
        }
        double rounded = Math.rint(Math.abs(percent));
        String magnitude = Math.abs(Math.abs(percent) - rounded) < 0.05
                ? Long.toString(Math.round(rounded))
                : String.format(Locale.ROOT, "%.1f", Math.abs(percent));
        return (percent > 0.0 ? "+" : "-") + magnitude + "%";
    }

    private String normalizeBranch(String branch) {
        return branch == null || branch.isBlank() ? "general"
                : branch.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveLanguage(Player player) {
        PlayerRef ref = player.getPlayerRef();
        return ref == null ? null : ref.getLanguage();
    }

    private static int integer(Map<String, String> attributes, String key, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(attributes.get(key)));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long longInteger(Map<String, String> attributes,
                                    String key, long fallback) {
        try {
            return Math.max(0L, Long.parseLong(attributes.get(key)));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String text(Map<String, String> attributes, String key) {
        String value = attributes.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    static final class ManagedTarget {
        private final State state;

        private ManagedTarget(State state) {
            this.state = state;
        }
    }

    record ManagedSnapshot(
            @Nonnull TameworkCompanionTalentsPage.PageData pageData,
            @Nonnull String profileId,
            int level,
            int availablePoints,
            long revision
    ) {
    }

    record ManagedMutation(
            boolean applied,
            boolean pending,
            @Nonnull BondedCompanionResultCode code,
            @Nonnull String message
    ) {
    }

    private static final class State {
        private final UUID ownerUuid;
        private final String rosterId;
        private final String profileId;
        private final String roleId;
        /** Null for a companion with neither a name nor a species; the page then shows the default name. */
        @Nullable private final String displayName;
        // Written by apply, which a late API result calls on another thread.
        private volatile String levelingConfigId;
        private volatile int level;
        private volatile long revision;
        private volatile TameworkTalentsComponent talents;

        private State(UUID ownerUuid, String rosterId, String profileId,
                      String roleId, String displayName, String levelingConfigId,
                      int level, TameworkTalentsComponent talents,
                      long revision) {
            this.ownerUuid = ownerUuid; this.rosterId = rosterId; this.profileId = profileId;
            this.roleId = roleId; this.displayName = displayName;
            this.levelingConfigId = levelingConfigId; this.level = level;
            this.talents = talents; this.revision = revision;
        }

        private static State from(UUID owner, BondedCompanionPanelPresentation row) {
            Map<String, String> data = row.attributes();
            String displayName = row.displayName() == null ? row.species() : row.displayName();
            String configId = text(data, "talentConfigId");
            TameworkTalentsComponent talents = new TameworkTalentsComponent(configId,
                    Math.max(0, integer(data, "talentSpentPoints", 0)),
                    text(data, "talents") == null ? new String[0]
                            : text(data, "talents").split("\\s*,\\s*"),
                    longInteger(data, "talentAllocationRevision", 0L));
            return new State(owner, row.rosterId(), row.profileId(), row.roleId(),
                    displayName,
                    text(data, "levelingConfigId"), Math.max(1,
                    integer(data, "level", 1)),
                    talents, row.revision());
        }

        private void apply(BondedCompanionProfileView view) {
            revision = view.revision();
            Map<String, String> data = view.snapshotPresentationData();
            talents = new TameworkTalentsComponent(text(data, "talentConfigId"),
                    Math.max(0, integer(data, "talentSpentPoints", 0)),
                    text(data, "talents") == null ? new String[0]
                            : text(data, "talents").split("\\s*,\\s*"),
                    longInteger(data, "talentAllocationRevision", 0L));
        }

        /** Takes the level and talents of the stored snapshot; a companion with no talents yet has none bought. */
        private void apply(BondedTalentUpdates.Stored stored) {
            level = Math.max(1, stored.leveling().getLevel());
            levelingConfigId = stored.leveling().getConfigId();
            talents = stored.talents() != null ? stored.talents().clone()
                    : new TameworkTalentsComponent(talents.getConfigId(), 0, new String[0], 0L);
        }
    }
}
