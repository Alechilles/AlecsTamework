package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Releases a companion through the companion index, then removes its loaded body on the body's
 * world thread. Runs on the releasing player's world thread.
 */
final class CommandOwnerReleaseService {
    private final CommandFeedbackService feedbackService;
    @Nullable private final ReleaseFlow releaseFlow;
    @Nullable private final CompanionQueries companions;
    @Nullable private final CommandPersistenceView persistenceView;
    private final CommandToolInventoryService inventory;
    private final CommandLinkMutationService links;
    @Nullable private final CommandLinkedNpcInventoryRepairService inventoryRepair;

    /**
     * A null flow or null queries means companion saving is paused (the module is not READY);
     * release is then refused, never done without a record (spec 10, 12.3).
     */
    CommandOwnerReleaseService(
            CommandFeedbackService feedbackService,
            @Nullable ReleaseFlow releaseFlow,
            @Nullable CompanionQueries companions,
            @Nullable CommandPersistenceView persistenceView,
            CommandToolInventoryService inventory,
            CommandLinkMutationService links,
            @Nullable CommandLinkedNpcInventoryRepairService inventoryRepair
    ) {
        this.feedbackService = feedbackService;
        this.releaseFlow = releaseFlow;
        this.companions = companions;
        this.persistenceView = persistenceView;
        this.inventory = inventory;
        this.links = links;
        this.inventoryRepair = inventoryRepair;
    }

    void release(Player player,
                 String toolId,
                 TwCommandItemConfig config,
                 UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        UUID ownerUuid = player.getUuid();
        if (ownerUuid == null) {
            return;
        }
        if (releaseFlow == null || companions == null) {
            warn(player, "tamework.ui.notifications.command.release.unavailable");
            return;
        }
        CompanionRecord record = resolve(player, toolId, ownerUuid, npcUuid);
        // Bonded companions are released through their own flow, never by a generic tool.
        if (record == null || record.bonded()) {
            warn(player, "tamework.ui.notifications.command.release.unavailable");
            return;
        }
        UUID bodyUuid = record.currentNpcUuid();
        ReleaseFlow.Outcome outcome = releaseFlow.release(record.profileId(), ownerUuid);
        switch (outcome.result()) {
            case NOT_OWNER -> warn(player, "tamework.ui.notifications.command.release.ownedNearbyOnly");
            case NOT_FOUND, NOT_RELEASABLE -> warn(player, "tamework.ui.notifications.command.release.unavailable");
            case RELEASED -> {
                if (outcome.body() != null && bodyUuid != null) {
                    ReleasedBodyRemoval.removeOnBodyWorld(outcome.body(), bodyUuid);
                }
                if (inventoryRepair != null) {
                    inventoryRepair.canonicalize(player);
                }
                String displayName = record.displayName();
                feedbackService.showSuccessKey(
                        player,
                        "tamework.ui.notifications.command.release.success",
                        displayName == null || displayName.isBlank()
                                ? LocalizedText.resolve(player,
                                "tamework.ui.notifications.command.shared.defaultMobName")
                                : displayName
                );
            }
        }
    }

    /**
     * Resolves a panel row as owned actions do. Rows carry the loaded NPC uuid, a derived
     * presentation uuid or an old NPC uuid for a companion without a body; the item's link
     * records then the view's profile and alias lookup cover the rest.
     */
    @Nullable
    private CompanionRecord resolve(Player player, String toolId, UUID ownerUuid, UUID rowId) {
        ItemStack stack = inventory.findToolStack(player, toolId);
        List<LinkedNpcRecord> linked = stack == null ? List.of() : links.readLinkedNpcRecords(stack);
        Optional<UUID> profileId = new CommandOwnedPanelRecordSource(companions)
                .profileForRow(ownerUuid, rowId, linked).map(ProfileId::value);
        if (profileId.isEmpty() && persistenceView != null) {
            LinkedNpcRecord row = linked.stream().filter(r -> rowId.equals(r.npcUuid)).findFirst()
                    .orElseGet(() -> new LinkedNpcRecord(rowId, null, null, null, null, null));
            profileId = persistenceView.find(row).map(profile -> profile.profileId().value());
        }
        return profileId.map(companions::get).orElse(null);
    }

    private void warn(Player player, String key) {
        feedbackService.showWarningKey(player, key);
    }
}
