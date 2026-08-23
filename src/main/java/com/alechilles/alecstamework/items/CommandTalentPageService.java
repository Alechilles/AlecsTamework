package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.ui.TameworkCompanionTalentsPage;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Opens and mutates the companion talents page for command-linked companions.
 */
final class CommandTalentPageService {
    private final CommandLinkMutationService linkMutationService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;

    CommandTalentPageService(@Nonnull CommandLinkMutationService linkMutationService,
                             @Nonnull CommandToolInventoryService toolInventoryService,
                             @Nonnull CommandFeedbackService feedbackService,
                             @Nonnull CommandNpcNameResolver npcNameResolver) {
        this.linkMutationService = linkMutationService;
        this.toolInventoryService = toolInventoryService;
        this.feedbackService = feedbackService;
        this.npcNameResolver = npcNameResolver;
    }

    void openTalentPage(@Nullable Player player,
                        @Nullable String toolId,
                        @Nullable UUID npcUuid,
                        @Nonnull Runnable backCallback) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        openTalentPage(player,
                () -> resolveLinkedCompanionTalentContext(player, toolId, npcUuid),
                backCallback);
    }

    /** Opens the shared talent page for a caller-authorized live companion. */
    void openTalentPage(@Nullable Player player,
                        @Nonnull TalentTargetResolver targetResolver,
                        @Nonnull Runnable backCallback) {
        if (player == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null || player.getPageManager() == null) {
            feedbackService.showWarning(player, "Talent page is unavailable right now.");
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (store == null || playerRef == null || !playerRef.isValid() || uiPlayerRef == null || !uiPlayerRef.isValid()) {
            feedbackService.showWarning(player, "Talent page is unavailable right now.");
            return;
        }
        TameworkCompanionTalentsPage page = new TameworkCompanionTalentsPage(
                uiPlayerRef,
                () -> buildTalentPageData(player, targetResolver),
                talentId -> applyTalentPurchase(player, targetResolver, talentId),
                () -> applyTalentReset(player, targetResolver),
                backCallback
        );
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
        } catch (Throwable throwable) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_open_failed",
                    throwable,
                    TameworkTelemetryContext.uiPage(
                            "TameworkCompanionTalentsPage",
                            "command_item",
                            "open",
                            "Failed to open companion talents page."
                    ).build()
            );
            feedbackService.showWarning(player, "Talent page is unavailable right now.");
        }
    }

    @Nonnull
    private TameworkCompanionTalentsPage.PageData buildTalentPageData(
            @Nonnull Player player,
            @Nonnull TalentTargetResolver targetResolver
    ) {
        return buildTalentPageData(
                resolveLanguage(player), targetResolver.resolve());
    }

    @Nonnull
    private TameworkCompanionTalentsPage.PageData buildTalentPageData(
            @Nullable String language,
            @Nullable TalentTarget context
    ) {
        if (context == null) {
            return TameworkCompanionTalentsPage.PageData.empty();
        }
        CompanionLevelingService.LevelingSnapshot leveling = CompanionLevelingService.resolveSnapshot(
                context.npcRef(),
                context.store(),
                context.roleId()
        );
        int availablePoints = CompanionTalentService.resolveAvailablePoints(context.npcRef(), context.store());
        String levelSummary;
        if (leveling == null) {
            levelSummary = LocalizedText.resolve(language, "tamework.ui.talents.levelSummary.unavailable");
        } else if (leveling.atMaxLevel()) {
            levelSummary = LocalizedText.format(language, "tamework.ui.talents.levelSummary.max", leveling.level());
        } else {
            levelSummary = LocalizedText.format(
                    language,
                    "tamework.ui.talents.levelSummary.xp",
                    leveling.level(),
                    Math.max(0, Math.round(leveling.currentXp())),
                    Math.max(1, Math.round(leveling.nextLevelDeltaXp()))
            );
        }
        String pointsSummary = LocalizedText.format(language, "tamework.ui.talents.points.available", availablePoints);
        TwTalentConfig talentConfig = CompanionTalentService.resolveTalentConfig(context.npcRef(), context.store());
        if (talentConfig == null || !talentConfig.isEnabled() || talentConfig.getTalents().length == 0) {
            return new TameworkCompanionTalentsPage.PageData(
                    context.displayName(),
                    levelSummary,
                    pointsSummary,
                    LocalizedText.resolve(language, "tamework.ui.talents.status.noTree"),
                    false,
                    List.of()
            );
        }
        ComponentType<EntityStore, TameworkTalentsComponent> talentsType = TameworkTalentsComponent.getComponentType();
        TameworkTalentsComponent talents = talentsType != null ? context.store().getComponent(context.npcRef(), talentsType) : null;
        boolean canReset = talents != null && (talents.getSpentPoints() > 0 || talents.getPurchasedTalentIds().length > 0);
        ArrayList<TameworkCompanionTalentsPage.TreeNodeEntry> entries = new ArrayList<>();
        for (TwTalentConfig.TalentDefinition talent : talentConfig.getTalents()) {
            if (talent == null || talent.getId() == null) {
                continue;
            }
            boolean purchased = talents != null && talents.hasPurchasedTalent(talent.getId());
            boolean levelMet = leveling != null && leveling.level() >= talent.getMinLevel();
            String missingPrerequisite = resolveMissingPrerequisiteName(language, talents, talentConfig, talent);
            boolean prerequisitesMet = missingPrerequisite == null;
            boolean canPurchase = !purchased && levelMet && prerequisitesMet && availablePoints >= talent.getPointCost();
            String state;
            String status;
            if (purchased) {
                state = TameworkCompanionTalentsPage.STATE_PURCHASED;
                status = LocalizedText.resolve(language, "tamework.ui.talents.status.unlocked");
            } else if (!levelMet) {
                state = TameworkCompanionTalentsPage.STATE_LOCKED;
                status = LocalizedText.format(language, "tamework.ui.talents.status.requiresLevel", talent.getMinLevel());
            } else if (!prerequisitesMet) {
                state = TameworkCompanionTalentsPage.STATE_LOCKED;
                status = LocalizedText.format(language, "tamework.ui.talents.status.requiresTalent", missingPrerequisite);
            } else if (availablePoints < talent.getPointCost()) {
                state = TameworkCompanionTalentsPage.STATE_UNAFFORDABLE;
                status = LocalizedText.format(language, "tamework.ui.talents.status.costsPoints", talent.getPointCost());
            } else {
                state = TameworkCompanionTalentsPage.STATE_AVAILABLE;
                status = LocalizedText.format(language, "tamework.ui.talents.status.costPoints", talent.getPointCost());
            }
            String talentName = LocalizedText.resolveConfigValue(language, talent.getDisplayName(), talent.getId());
            String talentDescription = LocalizedText.resolveConfigValue(
                    language,
                    talent.getDescription(),
                    LocalizedText.resolve(language, "tamework.ui.talents.description.default")
            );
            String branchName = LocalizedText.resolveConfigValue(
                    language,
                    talent.getBranch(),
                    LocalizedText.resolve(language, "tamework.ui.talents.branch.general")
            );
            entries.add(new TameworkCompanionTalentsPage.TreeNodeEntry(
                    talent.getId(),
                    branchName,
                    talent.getTier(),
                    state,
                    talentName,
                    talentDescription,
                    LocalizedText.format(
                            language,
                            "tamework.ui.talents.status.stateDetail",
                            formatStateLabel(language, state),
                            status
                    ),
                    talent.getPointCost(),
                    talent.getMinLevel(),
                    Arrays.stream(talent.getRequiresTalentIds())
                            .filter(requiredId -> requiredId != null && !requiredId.isBlank())
                            .toList(),
                    resolvePrerequisiteNames(language, talentConfig, talent),
                    summarizeEffects(language, talent.getEffects()),
                    canPurchase
            ));
        }
        entries.sort((left, right) -> {
            if (left == null && right == null) {
                return 0;
            }
            if (left == null) {
                return 1;
            }
            if (right == null) {
                return -1;
            }
            int branch = normalizeBranch(left.branchName()).compareTo(normalizeBranch(right.branchName()));
            if (branch != 0) {
                return branch;
            }
            int tier = Integer.compare(left.tier(), right.tier());
            if (tier != 0) {
                return tier;
            }
            return left.displayName().compareToIgnoreCase(right.displayName());
        });
        return new TameworkCompanionTalentsPage.PageData(
                context.displayName(),
                levelSummary,
                pointsSummary,
                entries.isEmpty()
                        ? LocalizedText.resolve(language, "tamework.ui.talents.status.noTalentsConfigured")
                        : LocalizedText.resolve(language, "tamework.ui.talents.status.chooseTalent"),
                canReset,
                entries
        );
    }

    @Nullable
    ManagedSnapshot managedSnapshot(
            @Nullable Player player,
            @Nullable String toolId,
            @Nullable UUID npcUuid
    ) {
        if (player == null || toolId == null || npcUuid == null) return null;
        TalentTarget target = resolveLinkedCompanionTalentContext(
                player, toolId, npcUuid);
        if (target == null) return null;
        CompanionLevelingService.LevelingSnapshot leveling =
                CompanionLevelingService.resolveSnapshot(
                        target.npcRef(), target.store(), target.roleId());
        int level = leveling == null ? 0 : Math.max(0, leveling.level());
        int points = CompanionTalentService.resolveAvailablePoints(
                target.npcRef(), target.store());
        return new ManagedSnapshot(
                buildTalentPageData(resolveLanguage(player), target),
                level, Math.max(0, points));
    }

    @Nonnull
    ManagedMutation purchaseManaged(
            @Nullable Player player,
            @Nullable String toolId,
            @Nullable UUID npcUuid,
            @Nullable String talentId
    ) {
        TalentTarget target = player == null || toolId == null || npcUuid == null
                ? null : resolveLinkedCompanionTalentContext(
                        player, toolId, npcUuid);
        if (target == null) {
            return ManagedMutation.notFound(
                    "Companion is no longer loaded.");
        }
        CompanionTalentService.PurchaseResult result =
                CompanionTalentService.purchaseTalent(
                        target.npcRef(), target.store(), talentId);
        return new ManagedMutation(result.applied(), false, result.message());
    }

    @Nonnull
    ManagedMutation resetManaged(
            @Nullable Player player,
            @Nullable String toolId,
            @Nullable UUID npcUuid
    ) {
        TalentTarget target = player == null || toolId == null || npcUuid == null
                ? null : resolveLinkedCompanionTalentContext(
                        player, toolId, npcUuid);
        if (target == null) {
            return ManagedMutation.notFound(
                    "Companion is no longer loaded.");
        }
        CompanionTalentService.ResetResult result =
                CompanionTalentService.resetTalents(
                        target.npcRef(), target.store());
        return new ManagedMutation(result.applied(), false, result.message());
    }

    @Nonnull
    private String summarizeEffects(@Nullable TwTalentConfig.PassiveEffect[] effects) {
        return summarizeEffects(null, effects);
    }

    @Nonnull
    private String summarizeEffects(@Nullable String language, @Nullable TwTalentConfig.PassiveEffect[] effects) {
        if (effects == null || effects.length == 0) {
            return LocalizedText.resolve(language, "tamework.ui.talents.effects.none");
        }
        ArrayList<String> summaries = new ArrayList<>();
        for (TwTalentConfig.PassiveEffect effect : effects) {
            if (effect == null || effect.getEffectKey() == null) {
                continue;
            }
            summaries.add(LocalizedText.format(
                    language,
                    "tamework.ui.talents.effects.line",
                    formatEffectKey(language, effect.getEffectKey()),
                    formatMultiplierChange(effect.getMultiplier())
            ));
        }
        return summaries.isEmpty()
                ? LocalizedText.resolve(language, "tamework.ui.talents.effects.none")
                : String.join("\n", summaries);
    }

    @Nonnull
    private String formatEffectKey(@Nonnull String effectKey) {
        return formatEffectKey(null, effectKey);
    }

    @Nonnull
    private String formatEffectKey(@Nullable String language, @Nonnull String effectKey) {
        String spaced = effectKey
                .replace("Multiplier", "")
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .trim();
        return LocalizedText.resolveConfigValue(
                language,
                "tamework.ui.talents.effect." + effectKey,
                spaced.isBlank() ? effectKey : spaced
        );
    }

    @Nonnull
    private String formatStateLabel(@Nullable String language, @Nonnull String state) {
        return LocalizedText.resolveConfigValue(
                language,
                "tamework.ui.talents.state." + state,
                state
        );
    }

    @Nonnull
    private String formatMultiplierChange(double multiplier) {
        double percent = (multiplier - 1.0) * 100.0;
        if (Math.abs(percent) < 0.05) {
            return "+0%";
        }
        return (percent > 0.0 ? "+" : "-") + formatPercentMagnitude(Math.abs(percent)) + "%";
    }

    @Nonnull
    private String formatPercentMagnitude(double percent) {
        double roundedWhole = Math.rint(percent);
        if (Math.abs(percent - roundedWhole) < 0.05) {
            return String.format(Locale.ROOT, "%.0f", roundedWhole);
        }
        return String.format(Locale.ROOT, "%.1f", percent);
    }

    @Nonnull
    private List<String> resolvePrerequisiteNames(@Nullable String language,
                                                  @Nonnull TwTalentConfig talentConfig,
                                                  @Nonnull TwTalentConfig.TalentDefinition talent) {
        ArrayList<String> names = new ArrayList<>();
        for (String requiredTalentId : talent.getRequiresTalentIds()) {
            if (requiredTalentId == null || requiredTalentId.isBlank()) {
                continue;
            }
            TwTalentConfig.TalentDefinition prerequisite = talentConfig.findTalent(requiredTalentId);
            names.add(prerequisite != null
                    ? LocalizedText.resolveConfigValue(language, prerequisite.getDisplayName(), requiredTalentId.trim())
                    : requiredTalentId.trim());
        }
        return names;
    }

    @Nonnull
    private String normalizeBranch(@Nullable String value) {
        return value == null || value.isBlank() ? "general" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private String applyTalentPurchase(@Nonnull Player player,
                                       @Nonnull TalentTargetResolver targetResolver,
                                       @Nullable String talentId) {
        TalentTarget context = targetResolver.resolve();
        if (context == null) {
            String message = "Companion is no longer loaded.";
            feedbackService.showWarning(player, message);
            return message;
        }
        CompanionTalentService.PurchaseResult result = CompanionTalentService.purchaseTalent(
                context.npcRef(),
                context.store(),
                talentId
        );
        if (result.applied()) {
            feedbackService.showSuccess(player, result.message());
        } else {
            feedbackService.showWarning(player, result.message());
        }
        return result.message();
    }

    @Nonnull
    private String applyTalentReset(@Nonnull Player player,
                                    @Nonnull TalentTargetResolver targetResolver) {
        TalentTarget context = targetResolver.resolve();
        if (context == null) {
            String message = "Companion is no longer loaded.";
            feedbackService.showWarning(player, message);
            return message;
        }
        CompanionTalentService.ResetResult result = CompanionTalentService.resetTalents(
                context.npcRef(),
                context.store()
        );
        if (result.applied()) {
            feedbackService.showSuccess(player, result.message());
        } else {
            feedbackService.showWarning(player, result.message());
        }
        return result.message();
    }

    @Nullable
    private String resolveMissingPrerequisiteName(@Nullable String language,
                                                  @Nullable TameworkTalentsComponent talents,
                                                  @Nonnull TwTalentConfig talentConfig,
                                                  @Nonnull TwTalentConfig.TalentDefinition talent) {
        for (String requiredTalentId : talent.getRequiresTalentIds()) {
            if (requiredTalentId == null || requiredTalentId.isBlank()) {
                continue;
            }
            if (talents != null && talents.hasPurchasedTalent(requiredTalentId)) {
                continue;
            }
            TwTalentConfig.TalentDefinition prerequisite = talentConfig.findTalent(requiredTalentId);
            return prerequisite != null
                    ? LocalizedText.resolveConfigValue(language, prerequisite.getDisplayName(), requiredTalentId)
                    : requiredTalentId;
        }
        return null;
    }

    @Nullable
    private static String resolveLanguage(@Nonnull Player player) {
        PlayerRef playerRef = player.getPlayerRef();
        return playerRef != null ? playerRef.getLanguage() : null;
    }

    @Nullable
    private TalentTarget resolveLinkedCompanionTalentContext(@Nonnull Player player,
                                                             @Nonnull String toolId,
                                                             @Nonnull UUID npcUuid) {
        if (toolId == null || toolId.isBlank()) {
            return null;
        }
        World world = player.getWorld();
        if (world == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return null;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            return null;
        }
        ItemStack toolStack = toolInventoryService.findToolStack(player, toolId);
        if (toolStack == null || toolStack.isEmpty()) {
            return null;
        }
        LinkedNpcRecord record = linkMutationService.findLinkedNpcRecord(
                linkMutationService.readLinkedNpcRecords(toolStack),
                npcUuid
        );
        if (record == null) {
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return null;
        }
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (links == null || !links.containsToolId(toolId)) {
            return null;
        }
        UUID ownerId = links.getOwnerId();
        if (ownerId != null && !ownerId.equals(player.getUuid())) {
            return null;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            roleId = npcNameResolver.resolveNpcRoleId(npc);
        }
        String displayName = npcNameResolver.resolveNpcDisplayName(npcRef, store, npc);
        if (displayName == null || displayName.isBlank()) {
            displayName = "Companion";
        }
        return new TalentTarget(npcRef, store, displayName, roleId);
    }

    @FunctionalInterface
    interface TalentTargetResolver extends Supplier<TalentTarget> {
        @Override
        @Nullable TalentTarget get();

        @Nullable
        default TalentTarget resolve() {
            return get();
        }
    }

    record TalentTarget(@Nonnull Ref<EntityStore> npcRef,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull String displayName,
                        @Nullable String roleId) {
        TalentTarget {
            java.util.Objects.requireNonNull(npcRef, "npcRef");
            java.util.Objects.requireNonNull(store, "store");
            if (displayName == null || displayName.isBlank()) {
                displayName = "Companion";
            }
        }
    }

    record ManagedSnapshot(
            @Nonnull TameworkCompanionTalentsPage.PageData pageData,
            int level,
            int availablePoints
    ) {
    }

    record ManagedMutation(
            boolean applied,
            boolean notFound,
            @Nonnull String message
    ) {
        private static ManagedMutation notFound(String message) {
            return new ManagedMutation(false, true, message);
        }
    }
}
