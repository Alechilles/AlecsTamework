package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionTalentActionRequest;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.alechilles.alecstamework.ui.TameworkCompanionTalentsPage;
import com.hypixel.hytale.component.ComponentType;
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
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Opens the shared talent page against a bonded profile's durable state.
 *
 * <p>Unlike legacy linked companions, bonded profiles do not require a live
 * projection to inspect or spend their earned points. A successful durable
 * mutation is mirrored to a present projection as a convenience only.</p>
 */
final class BondedCompanionTalentPageService {
    private final Supplier<BondedCompanionApi> api;
    private final CommandFeedbackService feedback;

    BondedCompanionTalentPageService(
            @Nullable Supplier<BondedCompanionApi> api,
            @Nonnull CommandFeedbackService feedback
    ) {
        this.api = api == null ? BondedCompanionApi::unavailable : api;
        this.feedback = Objects.requireNonNull(feedback, "feedback");
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
            feedback.showWarning(player, "Talent page is unavailable right now.");
            return;
        }
        State state = State.from(player.getUuid(), presentation);
        if (state == null) {
            feedback.showWarning(player, "This companion has no usable talent data.");
            return;
        }
        TameworkCompanionTalentsPage page = new TameworkCompanionTalentsPage(
                uiPlayerRef,
                () -> pageData(resolveLanguage(player), state),
                talentId -> update(player, state,
                        BondedCompanionTalentActionRequest.Action.PURCHASE,
                        talentId),
                () -> update(player, state,
                        BondedCompanionTalentActionRequest.Action.RESET, null),
                backCallback
        );
        try {
            player.getPageManager().openCustomPage(playerRef,
                    world.getEntityStore().getStore(), page);
        } catch (RuntimeException failure) {
            feedback.showWarning(player, "Talent page is unavailable right now.");
        }
    }

    @Nonnull
    private String update(
            @Nonnull Player player,
            @Nonnull State state,
            @Nonnull BondedCompanionTalentActionRequest.Action action,
            @Nullable String talentId
    ) {
        BondedCompanionApi current = api.get();
        if (current == null || !current.availability().available()) {
            return warning(player, "Bonded companion data is unavailable right now.");
        }
        String idempotency = "talents:" + state.profileId + ":"
                + state.revision + ":" + action + ":"
                + (talentId == null ? "" : talentId);
        BondedCompanionTalentActionRequest request =
                new BondedCompanionTalentActionRequest(
                        "tamework.command-item", idempotency,
                        state.ownerUuid, state.rosterId, state.profileId,
                        state.revision, action, talentId);
        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> future =
                current.updateTalents(request);
        BondedCompanionResult<BondedCompanionProfileView> result = future == null
                ? null : future.getNow(null);
        if (result == null) {
            return warning(player, "Talent changes are still being saved. Try again shortly.");
        }
        if (!result.successful() || result.value() == null) {
            return warning(player, action == BondedCompanionTalentActionRequest.Action.PURCHASE
                    ? "That talent can no longer be unlocked."
                    : "No talent points could be refunded.");
        }
        state.apply(result.value());
        applyLiveProjection(player, state);
        String message = action == BondedCompanionTalentActionRequest.Action.PURCHASE
                ? "Talent unlocked." : "Talent points refunded.";
        feedback.showSuccess(player, message);
        return message;
    }

    private String warning(@Nonnull Player player, @Nonnull String message) {
        feedback.showWarning(player, message);
        return message;
    }

    private void applyLiveProjection(@Nonnull Player player, @Nonnull State state) {
        UUID liveNpcUuid = state.liveNpcUuid;
        World world = player.getWorld();
        if (liveNpcUuid == null || world == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> npcRef = world.getEntityRef(liveNpcUuid);
        ComponentType<EntityStore, TameworkTalentsComponent> type =
                TameworkTalentsComponent.getComponentType();
        if (store == null || npcRef == null || !npcRef.isValid() || type == null) {
            return;
        }
        store.putComponent(npcRef, type, state.talents.clone());
        CompanionStatModifierService.applyTraitModifiers(npcRef, store);
    }

    @Nonnull
    private TameworkCompanionTalentsPage.PageData pageData(
            @Nullable String language,
            @Nonnull State state
    ) {
        TwTalentConfig config = resolveConfig(state.talents, state.roleId);
        int points = availablePoints(state);
        String levelSummary = LocalizedText.format(language,
                "tamework.ui.talents.levelSummary.max", state.level);
        String pointsSummary = LocalizedText.format(language,
                "tamework.ui.talents.points.available", points);
        if (config == null || !config.isEnabled() || config.getTalents().length == 0) {
            return new TameworkCompanionTalentsPage.PageData(state.displayName,
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
        return new TameworkCompanionTalentsPage.PageData(state.displayName,
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
                talent.getBranch(), "General"), talent.getTier(), state,
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
        if (roleId != null && !roleId.isBlank()) {
            TwTalentConfig roleConfig = TwTalentConfig.resolveForRole(roleId);
            if (roleConfig != null && roleConfig.isEnabled()) {
                return roleConfig;
            }
            return null;
        }
        if (talents.getConfigId() != null && !talents.getConfigId().isBlank()) {
            TwTalentConfig configured = TwTalentConfig.resolveById(talents.getConfigId());
            if (configured != null && configured.isEnabled()) return configured;
        }
        return null;
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
                String description = LocalizedText.resolveConfigValue(language,
                        talent.getDescription(), "");
                if (!description.isBlank() && !summaries.contains(description)) {
                    summaries.add(description);
                }
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

    private static UUID parseUuid(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int integer(Map<String, String> attributes, String key, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(attributes.get(key)));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String text(Map<String, String> attributes, String key) {
        String value = attributes.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class State {
        private final UUID ownerUuid;
        private final String rosterId;
        private final String profileId;
        private final String roleId;
        private final String displayName;
        private final String levelingConfigId;
        private final int level;
        private UUID liveNpcUuid;
        private long revision;
        private TameworkTalentsComponent talents;

        private State(UUID ownerUuid, String rosterId, String profileId,
                      String roleId, String displayName, String levelingConfigId,
                      int level, TameworkTalentsComponent talents,
                      long revision, UUID liveNpcUuid) {
            this.ownerUuid = ownerUuid; this.rosterId = rosterId; this.profileId = profileId;
            this.roleId = roleId; this.displayName = displayName;
            this.levelingConfigId = levelingConfigId; this.level = level;
            this.talents = talents; this.revision = revision; this.liveNpcUuid = liveNpcUuid;
        }

        private static State from(UUID owner, BondedCompanionPanelPresentation row) {
            Map<String, String> data = row.attributes();
            String displayName = row.displayName() == null ? row.species() : row.displayName();
            String configId = text(data, "talentConfigId");
            TameworkTalentsComponent talents = new TameworkTalentsComponent(configId,
                    Math.max(0, integer(data, "talentSpentPoints", 0)),
                    text(data, "talents") == null ? new String[0]
                            : text(data, "talents").split("\\s*,\\s*"));
            return new State(owner, row.rosterId(), row.profileId(), row.roleId(),
                    displayName == null ? "Companion" : displayName,
                    text(data, "levelingConfigId"), Math.max(1,
                    integer(data, "level", 1)),
                    talents, row.revision(), parseUuid(data.get(
                            BondedCompanionPresentationAttributes.LIVE_NPC_UUID)));
        }

        private void apply(BondedCompanionProfileView view) {
            revision = view.revision();
            Map<String, String> data = view.snapshotPresentationData();
            talents = new TameworkTalentsComponent(text(data, "talentConfigId"),
                    Math.max(0, integer(data, "talentSpentPoints", 0)),
                    text(data, "talents") == null ? new String[0]
                            : text(data, "talents").split("\\s*,\\s*"));
            liveNpcUuid = view.activeLease() == null ? null
                    : view.activeLease().liveNpcUuid();
        }
    }
}
