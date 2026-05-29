package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwTalentConfig;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
                () -> buildTalentPageData(player, toolId, npcUuid),
                talentId -> applyTalentPurchase(player, toolId, npcUuid, talentId),
                () -> applyTalentReset(player, toolId, npcUuid),
                backCallback
        );
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
        } catch (Throwable throwable) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_open_failed",
                    throwable,
                    "page=TameworkCompanionTalentsPage npc=" + npcUuid
            );
            feedbackService.showWarning(player, "Talent page is unavailable right now.");
        }
    }

    @Nonnull
    private TameworkCompanionTalentsPage.PageData buildTalentPageData(@Nonnull Player player,
                                                                      @Nonnull String toolId,
                                                                      @Nonnull UUID npcUuid) {
        LoadedCompanionTalentContext context = resolveLoadedCompanionTalentContext(player, toolId, npcUuid);
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
            levelSummary = "Level data unavailable";
        } else if (leveling.atMaxLevel()) {
            levelSummary = "Level " + leveling.level() + " (MAX)";
        } else {
            levelSummary = "Level "
                    + leveling.level()
                    + " - XP "
                    + Math.max(0, Math.round(leveling.currentXp()))
                    + "/"
                    + Math.max(1, Math.round(leveling.nextLevelDeltaXp()));
        }
        String pointsSummary = "Talent Points: " + availablePoints + " available";
        TwTalentConfig talentConfig = CompanionTalentService.resolveTalentConfig(context.npcRef(), context.store());
        if (talentConfig == null || !talentConfig.isEnabled() || talentConfig.getTalents().length == 0) {
            return new TameworkCompanionTalentsPage.PageData(
                    context.displayName(),
                    levelSummary,
                    pointsSummary,
                    "No talent tree is configured for this companion.",
                    false,
                    List.of()
            );
        }
        ComponentType<EntityStore, TameworkTalentsComponent> talentsType = TameworkTalentsComponent.getComponentType();
        TameworkTalentsComponent talents = talentsType != null ? context.store().getComponent(context.npcRef(), talentsType) : null;
        boolean canReset = talents != null && (talents.getSpentPoints() > 0 || talents.getPurchasedTalentIds().length > 0);
        ArrayList<TameworkCompanionTalentsPage.TalentEntry> entries = new ArrayList<>();
        for (TwTalentConfig.TalentDefinition talent : talentConfig.getTalents()) {
            if (talent == null || talent.getId() == null) {
                continue;
            }
            boolean purchased = talents != null && talents.hasPurchasedTalent(talent.getId());
            boolean levelMet = leveling != null && leveling.level() >= talent.getMinLevel();
            String missingPrerequisite = resolveMissingPrerequisiteName(talents, talentConfig, talent);
            boolean prerequisitesMet = missingPrerequisite == null;
            boolean canPurchase = !purchased && levelMet && prerequisitesMet && availablePoints >= talent.getPointCost();
            String status;
            if (purchased) {
                status = "Unlocked";
            } else if (!levelMet) {
                status = "Requires Level " + talent.getMinLevel();
            } else if (!prerequisitesMet) {
                status = "Requires " + missingPrerequisite;
            } else if (availablePoints < talent.getPointCost()) {
                status = "Costs " + talent.getPointCost() + " points";
            } else {
                status = "Cost " + talent.getPointCost() + " points";
            }
            entries.add(new TameworkCompanionTalentsPage.TalentEntry(
                    talent.getId(),
                    "Tier " + talent.getTier() + " - " + talent.getDisplayName(),
                    talent.getDescription() != null ? talent.getDescription() : "Passive talent",
                    status,
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
            return left.displayName().compareToIgnoreCase(right.displayName());
        });
        return new TameworkCompanionTalentsPage.PageData(
                context.displayName(),
                levelSummary,
                pointsSummary,
                entries.isEmpty() ? "No talents are configured for this companion." : "Choose a talent to inspect or unlock.",
                canReset,
                entries
        );
    }

    @Nonnull
    private String applyTalentPurchase(@Nonnull Player player,
                                       @Nonnull String toolId,
                                       @Nonnull UUID npcUuid,
                                       @Nullable String talentId) {
        LoadedCompanionTalentContext context = resolveLoadedCompanionTalentContext(player, toolId, npcUuid);
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
                                    @Nonnull String toolId,
                                    @Nonnull UUID npcUuid) {
        LoadedCompanionTalentContext context = resolveLoadedCompanionTalentContext(player, toolId, npcUuid);
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
    private String resolveMissingPrerequisiteName(@Nullable TameworkTalentsComponent talents,
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
            return prerequisite != null ? prerequisite.getDisplayName() : requiredTalentId;
        }
        return null;
    }

    @Nullable
    private LoadedCompanionTalentContext resolveLoadedCompanionTalentContext(@Nonnull Player player,
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
        return new LoadedCompanionTalentContext(npcRef, store, displayName, roleId);
    }

    private record LoadedCompanionTalentContext(@Nonnull Ref<EntityStore> npcRef,
                                                @Nonnull Store<EntityStore> store,
                                                @Nonnull String displayName,
                                                @Nullable String roleId) {
    }
}
