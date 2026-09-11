package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.items.locate.CapturedItemTracker;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.CaptureKey;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Sighting;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Kind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
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
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.time.Instant;
import javax.annotation.Nullable;

/**
 * Resolves and reports the current or last recorded position for a linked companion.
 */
final class CommandLinkedNpcLocateService {
    private final CommandLinkMutationService linkMutationService;
    private final CommandNpcRelocationService relocationService;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandToolInventoryService toolInventoryService;
    private final PersistenceDomainFacades persistence;
    private final CommandPersistenceView persistenceView;
    private final CapturedItemTracker itemTracker;
    private final Map<UUID, UUID> pending = new ConcurrentHashMap<>();

    CommandLinkedNpcLocateService(CommandLinkMutationService linkMutationService,
                                  CommandNpcRelocationService relocationService,
                                  CommandFeedbackService feedbackService,
                                  CommandNpcNameResolver npcNameResolver,
                                  CommandToolInventoryService toolInventoryService,
                                  PersistenceDomainFacades persistence,
                                  CommandPersistenceView persistenceView,
                                  CapturedItemTracker itemTracker) {
        this.linkMutationService = linkMutationService;
        this.relocationService = relocationService;
        this.feedbackService = feedbackService;
        this.npcNameResolver = npcNameResolver;
        this.toolInventoryService = toolInventoryService;
        this.persistence = persistence;
        this.persistenceView = persistenceView;
        this.itemTracker = itemTracker;
    }

    void locate(Player player, String toolId, UUID npcUuid) {
        locate(player, toolId, npcUuid, null, ignored -> true);
    }

    /**
     * Locates an already-authorized owned record without requiring an item-metadata link.
     */
    void locate(Player player, String toolId, UUID npcUuid,
                @Nullable LinkedNpcRecord ownedRecord) {
        locate(player, toolId, npcUuid, ownedRecord, ignored -> true);
    }

    void locate(Player player, String toolId, UUID npcUuid,
                @Nullable LinkedNpcRecord ownedRecord, Predicate<Player> authority) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        ItemStack stack = toolInventoryService.findToolStack(player, toolId);
        if (stack == null || stack.isEmpty()) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.itemNotFound");
            return;
        }
        LinkedNpcRecord record = ownedRecord != null
                ? ownedRecord
                : linkMutationService.findLinkedNpcRecord(
                        linkMutationService.readLinkedNpcRecords(stack),
                        npcUuid
                );
        if (record == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.notLinkedToTool");
            return;
        }
        if (record.npcUuid == null) {
            feedbackService.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.locate.noLocation",
                    npcNameResolver.resolveCachedUnloadedDisplayName(record)
            );
            return;
        }
        if (persistence != null) {
            locateCanonical(player, toolId, record, ownedRecord != null, authority);
            return;
        }
        showLiveLocation(player, record);
    }

    private void showLiveLocation(Player player, LinkedNpcRecord record) {
        LocationReport report = resolveLocation(player, record.npcUuid, record);
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
        if (openLocationPage(player, report.displayName, worldName, coordinates, "")) {
            return;
        }
        feedbackService.showDefaultKey(player, "tamework.ui.notifications.command.locate.location", report.displayName, worldName, coordinates);
    }

    /** One bounded on-demand read; no database or world work is polled while the panel is open. */
    private void locateCanonical(Player player, String toolId, LinkedNpcRecord record,
                                 boolean owned, Predicate<Player> authority) {
        UUID viewer = player.getUuid();
        UUID request = UUID.randomUUID();
        if (viewer == null || pending.size() >= 256 || pending.putIfAbsent(viewer, request) != null) return;
        try {
            var id = persistenceView == null ? null : persistenceView.profileId(record);
            var read = id == null ? persistence.queries().findProfile(new NpcAlias(record.npcUuid))
                    : persistence.queries().findProfile(id);
            read.thenCompose(value -> {
                if (value instanceof PersistenceReadResult.Absent<?>) {
                    return CompletableFuture.completedFuture(new ContainedResult(null, Optional.empty()));
                }
                if (!(value instanceof PersistenceReadResult.Found<CompanionProfileReadModel> found)) {
                    return CompletableFuture.<ContainedResult>failedFuture(
                            new IllegalStateException("Companion location read unavailable"));
                }
                CompanionProfileReadModel profile = found.value();
                CaptureKey capture = captureKey(profile, record.npcUuid);
                return capture == null ? CompletableFuture.completedFuture(new ContainedResult(profile, Optional.empty()))
                        : itemTracker.verify(capture).thenApply(sighting -> new ContainedResult(profile, sighting));
            }).toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).whenComplete((result, failure) -> {
                boolean dispatched = CommandUiCurrentWorldDispatcher.production().dispatch(viewer, new CommandUiHostPage.WorldOperation() {
                    @Override public void run(Ref<EntityStore> ref, Store<EntityStore> store) {
                        if (!pending.remove(viewer, request)) return;
                        Player current = ref == null || !ref.isValid() ? null : store.getComponent(ref, Player.getComponentType());
                        if (current == null || !authority.test(current)) return;
                        ItemStack tool = toolInventoryService.findToolStack(current, toolId);
                        if (tool == null || tool.isEmpty()) return;
                        if (!owned && linkMutationService.findLinkedNpcRecord(
                                linkMutationService.readLinkedNpcRecords(tool), record.npcUuid) == null) return;
                        if (failure != null || result == null || result.profile() != null && !currentProfile(result.profile())) {
                            feedbackService.showWarningKey(current, "tamework.ui.notifications.command.locate.unavailable");
                            return;
                        }
                        if (result.profile() == null) {
                            if (!owned) showLiveLocation(current, record);
                            return;
                        }
                        if (owned && !CommandOwnedActionService.allowsLocate(viewer, result.profile(),
                                persistence.queries().projectedCommandRosterActions().keySet(),
                                persistence.queries().projectedLaggingCommandRosterProfiles())) return;
                        showCanonical(current, record, result);
                    }
                    @Override public void unavailable() { pending.remove(viewer, request); }
                });
                if (!dispatched) pending.remove(viewer, request);
            });
        } catch (RuntimeException failure) {
            pending.remove(viewer, request);
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.locate.unavailable");
        }
    }

    private boolean currentProfile(CompanionProfileReadModel profile) {
        var latest = persistence.queries().projectedProfile(profile.identity().profileId());
        if (latest.isEmpty()) return true;
        var read = CompanionProfileProjectionState.compose(profile.identity(), profile.currentAlias(),
                profile.lifecycle(), profile.toolLinks(), profile.currentSnapshots(), profile.currentCoopSlot());
        return latest.get().lifecycleState() == read.lifecycleState()
                && java.util.Objects.equals(latest.get().ownerId(), read.ownerId())
                && latest.get().lastUpdatedAtMs() <= read.lastUpdatedAtMs();
    }

    @Nullable static CaptureKey captureKey(CompanionProfileReadModel profile, UUID fallbackAlias) {
        if (profile.lifecycle().state() != LifecycleState.CAPTURED) return null;
        return new CaptureKey(profile.identity().profileId().toString(), profile.lifecycle().location().key(),
                profile.currentAlias() == null ? fallbackAlias : profile.currentAlias().alias().value());
    }

    private void showCanonical(Player player, LinkedNpcRecord record, ContainedResult result) {
        var profile = result.profile();
        String name = npcNameResolver.resolveCachedUnloadedDisplayName(record);
        if (name == null || name.isBlank()) name = profile.identity().displayName();
        if (name == null || name.isBlank()) name = LocalizedText.resolve(player, "tamework.ui.linkedPanel.subtitle.defaultNpcName");
        String world = "";
        String coordinates = "";
        String status;
        if (profile.lifecycle().state() == LifecycleState.COOP) {
            CoopSlotKey coop = profile.currentCoopSlot().key();
            world = coop.worldKey();
            coordinates = TameworkLinkedNpcLocationFormatter.formatCoordinates(coop.x(), coop.y(), coop.z());
            status = LocalizedText.resolve(player, "tamework.ui.notifications.command.locate.coop");
        } else if (profile.lifecycle().state() == LifecycleState.CAPTURED) {
            Sighting sighting = result.sighting().orElse(null);
            if (sighting == null) {
                status = LocalizedText.resolve(player, "tamework.ui.notifications.command.locate.captureUnknown");
            } else {
                var holder = sighting.holder();
                String key = switch (holder.kind()) {
                    case PLAYER -> "player";
                    case CONTAINER -> "container";
                    case DROPPED -> "dropped";
                };
                String place = LocalizedText.format(player, "tamework.ui.notifications.command.locate." + key,
                        holder.name() == null || holder.name().isBlank() ? holder.id() : holder.name());
                status = sighting.loaded() ? place : LocalizedText.format(player,
                        "tamework.ui.notifications.command.locate.lastSeen", place, Instant.ofEpochMilli(sighting.observedAtMs()).toString());
                // Inventory location identifies the holder; old player coordinates would be misleading.
                if (holder.kind() != Kind.PLAYER) {
                    world = holder.worldName();
                    coordinates = TameworkLinkedNpcLocationFormatter.formatCoordinates(holder.x(), holder.y(), holder.z());
                }
            }
        } else if (profile.lifecycle().state() == LifecycleState.ACTIVE || profile.lifecycle().state() == LifecycleState.UNLOADED) {
            LinkedNpcRecord current = new LinkedNpcRecord(
                    profile.currentAlias() == null ? record.npcUuid : profile.currentAlias().alias().value(),
                    profile.identity().profileId().toString(), record.lastKnownPosition,
                    record.lastKnownWorldName == null ? profile.identity().lastKnownWorldKey() : record.lastKnownWorldName,
                    record.homePosition, name, record.cachedNameKey, record.cachedRoleId,
                    record.cachedCommandState, record.active, record.breedingEnabled, record.groupId);
            showLiveLocation(player, current);
            return;
        } else {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.locate.unavailable");
            return;
        }
        String displayWorld = world.isBlank() ? "—" : TameworkLinkedNpcLocationFormatter.formatDisplayWorldName(world, world);
        if (!openLocationPage(player, name, displayWorld, coordinates, status)) {
            feedbackService.showDefault(player, name + ": " + status + (coordinates.isBlank() ? "" : " — " + displayWorld + " (" + coordinates + ")"));
        }
    }

    void close() { pending.clear(); }

    private record ContainedResult(CompanionProfileReadModel profile, Optional<Sighting> sighting) { }

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
                                     String coordinates, String status) {
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
        TameworkLinkedNpcLocationPage page = new TameworkLinkedNpcLocationPage(uiPlayerRef, title, worldName, coordinates, status);
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
            return true;
        } catch (Throwable throwable) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_open_failed",
                    throwable,
                    TameworkTelemetryContext.uiPage(
                            "TameworkLinkedNpcLocationPage",
                            "command_item",
                            "open",
                            "Failed to open linked NPC location page."
                    ).build()
            );
            return false;
        }
    }

    private record LocationReport(String displayName, String worldName, Vector3d position) {
    }
}
