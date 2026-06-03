package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.ui.TameworkLinkedNpcLocationFormatter;
import com.alechilles.alecstamework.ui.TameworkLinkedNpcLocationPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;

/**
 * Resolves and reports the current or last recorded position for a linked companion.
 */
final class CommandLinkedNpcLocateService {
    private final CommandLinkMutationService linkMutationService;
    private final CommandNpcRelocationService relocationService;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandToolInventoryService toolInventoryService;

    CommandLinkedNpcLocateService(CommandLinkMutationService linkMutationService,
                                  CommandNpcRelocationService relocationService,
                                  CommandFeedbackService feedbackService,
                                  CommandNpcNameResolver npcNameResolver,
                                  CommandToolInventoryService toolInventoryService) {
        this.linkMutationService = linkMutationService;
        this.relocationService = relocationService;
        this.feedbackService = feedbackService;
        this.npcNameResolver = npcNameResolver;
        this.toolInventoryService = toolInventoryService;
    }

    void locate(Player player, String toolId, UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        ItemStack stack = toolInventoryService.findToolStack(player, toolId);
        if (stack == null || stack.isEmpty()) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.itemNotFound");
            return;
        }
        LinkedNpcRecord record = linkMutationService.findLinkedNpcRecord(
                linkMutationService.readLinkedNpcRecords(stack),
                npcUuid
        );
        if (record == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.notLinkedToTool");
            return;
        }
        LocationReport report = resolveLocation(player, npcUuid, record);
        if (report.position == null) {
            feedbackService.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.locate.noLocation",
                    report.displayName
            );
            return;
        }
        String unknownWorld = LocalizedText.resolve(player, "tamework.ui.notifications.command.locate.unknownWorld");
        String worldName = TameworkLinkedNpcLocationFormatter.formatDisplayWorldName(report.worldName, unknownWorld);
        String coordinates = TameworkLinkedNpcLocationFormatter.formatCoordinates(
                report.position.x,
                report.position.y,
                report.position.z
        );
        if (openLocationPage(player, report.displayName, worldName, coordinates)) {
            return;
        }
        feedbackService.showDefaultKey(player, "tamework.ui.notifications.command.locate.location", report.displayName, worldName, coordinates);
    }

    private LocationReport resolveLocation(Player player, UUID npcUuid, LinkedNpcRecord record) {
        String displayName = npcNameResolver.resolveCachedUnloadedDisplayName(record);
        if (displayName == null || displayName.isBlank()) {
            displayName = LocalizedText.resolve(player, "tamework.ui.linkedPanel.subtitle.defaultNpcName");
        }
        World world = player.getWorld();
        Store<EntityStore> store = world != null && world.getEntityStore() != null
                ? world.getEntityStore().getStore()
                : null;
        if (world != null && store != null) {
            Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
            if (npcRef != null && npcRef.isValid()) {
                NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
                TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
                if (npc != null && transform != null) {
                    return new LocationReport(
                            npcNameResolver.resolveNpcDisplayName(npcRef, store, npc),
                            world.getName(),
                            new Vector3d(transform.getPosition())
                    );
                }
            }
        }
        if (relocationService == null) {
            return new LocationReport(displayName, record.lastKnownWorldName, record.lastKnownPosition);
        }
        CommandNpcRelocationService.LastKnownLocation location =
                relocationService.getLastKnownLocation(npcUuid, record.lastKnownPosition, record.lastKnownWorldName);
        return new LocationReport(displayName, location.worldName(), location.position());
    }

    private boolean openLocationPage(Player player,
                                     String displayName,
                                     String worldName,
                                     String coordinates) {
        if (player == null || player.getPageManager() == null) {
            return false;
        }
        Ref<EntityStore> playerRef = player.getReference();
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (playerRef == null || !playerRef.isValid() || uiPlayerRef == null || !uiPlayerRef.isValid()) {
            return false;
        }
        Store<EntityStore> store = playerRef.getStore();
        if (store == null) {
            return false;
        }
        String title = LocalizedText.format(player, "tamework.ui.linkedLocation.title", displayName);
        TameworkLinkedNpcLocationPage page = new TameworkLinkedNpcLocationPage(uiPlayerRef, title, worldName, coordinates);
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
            return true;
        } catch (Throwable throwable) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_open_failed",
                    throwable,
                    "page=TameworkLinkedNpcLocationPage"
            );
            return false;
        }
    }

    private record LocationReport(String displayName, String worldName, Vector3d position) {
    }
}
