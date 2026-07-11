package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Spawns owned+tamed NPC batches for commands and optionally links them to the held command item.
 */
public final class NpcSpawnCommandService {
    private final SpawnerSpawnPositionService spawnPositionService;
    private final SpawnerAttachmentService attachmentService;
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLinkPolicyService linkPolicyService;
    private final NpcSpawnAttachmentResolutionService attachmentResolutionService;
    private final NpcOwnedBatchSpawnService batchSpawnService;

    public NpcSpawnCommandService(@Nonnull Tamework plugin) {
        this.spawnPositionService = new SpawnerSpawnPositionService(plugin.getLogger());
        this.attachmentService = new SpawnerAttachmentService(plugin.getLogger());
        this.linkedNpcRecordStore = new CommandLinkedNpcRecordStore();
        this.npcNameResolver = new CommandNpcNameResolver();
        this.linkPolicyService = new CommandLinkPolicyService();
        this.attachmentResolutionService = new NpcSpawnAttachmentResolutionService();
        this.batchSpawnService = new NpcOwnedBatchSpawnService(this, spawnPositionService);
    }

    public void spawnTamedOwnedBatch(@Nonnull Player player,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull Ref<EntityStore> playerRef,
                                     @Nonnull World world,
                                     @Nonnull String roleId,
                                     int quantity,
                                     @Nullable Map<String, String> attachmentOverrides,
                                     @Nonnull Consumer<SpawnBatchResult> completion) {
        Objects.requireNonNull(completion, "completion");
        batchSpawnService.schedule(
                player, store, playerRef, world, roleId, quantity,
                attachmentOverrides, completion
        );
    }

    @Nullable
    AttachmentResolution resolveAttachmentOverrides(@Nonnull Ref<EntityStore> npcRef,
                                                    @Nonnull Store<EntityStore> store,
                                                    @Nullable Map<String, String> requestedSelections) {
        NpcSpawnAttachmentResolutionService.Resolution resolution =
                attachmentResolutionService.resolve(npcRef, store, requestedSelections);
        return resolution == null ? null : new AttachmentResolution(
                resolution.applied(), resolution.invalid()
        );
    }

    void applyPostAdmissionState(Store<EntityStore> store,
                                 World world,
                                 Ref<EntityStore> playerRef,
                                 Ref<EntityStore> npcRef,
                                 NPCEntity npc,
                                 @Nullable AttachmentResolution attachmentResolution) {
        if (TameworkTamedComponent.getComponentType() != null) {
            store.putComponent(npcRef, TameworkTamedComponent.getComponentType(), new TameworkTamedComponent(true));
        }
        CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store);
        if (attachmentResolution != null && !attachmentResolution.appliedSelections.isEmpty()) {
            attachmentService.applyAttachments(attachmentResolution.appliedSelections, npcRef, npc, store);
        }
        Ref<EntityStore> masterRef = playerRef;
        if (npc.getRole() != null) {
            npc.getRole().setMarkedTarget("MasterTarget", masterRef);
        }
    }

    @Nullable
    private AutoLinkContext resolveHeldCommandItem(Player player) {
        Tamework plugin = Tamework.getInstance();
        CommandItemRegistry registry = plugin != null ? plugin.getCommandItemRegistry() : null;
        if (registry == null) {
            return null;
        }

        ItemContainer hotbar = PlayerInventoryAccess.getHotbar(player);
        if (hotbar == null) {
            return null;
        }
        byte activeSlot = PlayerInventoryAccess.getActiveHotbarSlot(player);
        if (activeSlot < 0) {
            return null;
        }

        ItemStack heldStack = hotbar.getItemStack((short) activeSlot);
        if (heldStack == null || heldStack.isEmpty()) {
            return null;
        }
        ItemStack originalStack = heldStack;

        TwCommandItemConfig config = registry.get(heldStack.getItemId());
        if (config == null || !config.isEnabled() || !config.isLinkEnabled()) {
            return null;
        }

        String toolId = heldStack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
        boolean changed = false;
        if (toolId == null || toolId.isBlank()) {
            toolId = UUID.randomUUID().toString();
            heldStack = heldStack.withMetadata(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING, toolId);
            changed = true;
        }
        if (toolId.isBlank()) {
            return null;
        }

        return new AutoLinkContext(
                heldStack,
                originalStack,
                hotbar,
                (short) activeSlot,
                toolId,
                config,
                changed
        );
    }

    boolean linkHeldCommandItem(@Nullable AutoLinkContext context,
                               Player player,
                               Store<EntityStore> store,
                               Ref<EntityStore> npcRef,
                               NPCEntity npc) {
        if (context == null || npcRef == null || !npcRef.isValid() || npc == null) {
            return false;
        }
        String roleId = linkPolicyService.resolveRoleId(npc);
        if (!linkPolicyService.isRoleAllowed(roleId, context.config)) {
            return false;
        }
        if (context.config.isRequireTamed() && !TamedStateResolver.isTamed(npcRef, store)) {
            return false;
        }

        UUID ownerUuid = player.getUuid();
        boolean requireOwner = resolveLinkingRequireOwner();
        TameworkCommandLinksComponent current = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (current == null) {
            current = new TameworkCommandLinksComponent(ownerUuid, new String[0]);
        }
        if (requireOwner && current.getOwnerId() != null && !current.getOwnerId().equals(ownerUuid)) {
            return false;
        }

        TameworkCommandLinksComponent updated = current.containsToolId(context.toolId)
                ? current
                : current.withToolIdAdded(context.toolId);
        updated.setOwnerId(ownerUuid);
        store.putComponent(npcRef, TameworkCommandLinksComponent.getComponentType(), updated);

        UUID npcUuid = npc.getUuid();
        if (npcUuid == null) {
            return false;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        Vector3d lastKnown = transform != null ? new Vector3d(transform.getPosition()) : null;
        boolean activate = shouldActivateOnLink(context.stack, context.config);
        context.stack = linkedNpcRecordStore.upsert(
                context.stack,
                npcUuid,
                lastKnown,
                resolveWorldName(player),
                updated.hasHome() ? updated.getHomePosition() : null,
                npcNameResolver.resolveNpcDisplayNameFromComponents(npcRef, store),
                npcNameResolver.resolveNpcNameKey(npc),
                roleId,
                activate,
                resolveCachedCommandState(npc)
        );
        context.changed = true;
        return true;
    }

    private boolean shouldActivateOnLink(ItemStack stack, TwCommandItemConfig config) {
        int maxActive = config != null ? Math.max(0, config.getMaxActive()) : 0;
        if (maxActive <= 0) {
            return true;
        }
        List<LinkedNpcRecord> records = linkedNpcRecordStore.read(stack);
        int activeCount = 0;
        for (LinkedNpcRecord record : records) {
            if (record != null && record.active) {
                activeCount++;
            }
        }
        return activeCount < maxActive;
    }

    @Nullable
    private String resolveWorldName(Player player) {
        World world = player != null ? player.getWorld() : null;
        if (world == null || world.getName() == null || world.getName().isBlank()) {
            return null;
        }
        return world.getName();
    }

    private boolean resolveLinkingRequireOwner() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        TwGlobalConfig resolved = config != null ? config : TwGlobalConfig.defaultConfig();
        return TameworkRuntimeSettings.linkingRequiresOwner(resolved.isOwnershipLinkingRequiresOwner());
    }

    @Nullable
    private String resolveCachedCommandState(NPCEntity npc) {
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return null;
        }
        String stateName = npc.getRole().getStateSupport().getStateName();
        return (stateName != null && !stateName.isBlank()) ? stateName : null;
    }

    private boolean updateHeldItem(AutoLinkContext context) {
        if (context == null || !Objects.equals(
                context.hotbar.getItemStack(context.slot),
                context.originalStack
        )) {
            return false;
        }
        context.hotbar.setItemStackForSlot(context.slot, context.stack);
        return true;
    }

    static final class AutoLinkContext {
        private ItemStack stack;
        private final ItemStack originalStack;
        private final ItemContainer hotbar;
        private final short slot;
        private final String toolId;
        private final TwCommandItemConfig config;
        private boolean changed;

        private AutoLinkContext(ItemStack stack,
                                ItemStack originalStack,
                                ItemContainer hotbar,
                                short slot,
                                String toolId,
                                TwCommandItemConfig config,
                                boolean changed) {
            this.stack = stack;
            this.originalStack = originalStack;
            this.hotbar = hotbar;
            this.slot = slot;
            this.toolId = toolId;
            this.config = config;
            this.changed = changed;
        }
    }

    BatchTracker newBatchTracker(int requestedCount,
                                 Player player,
                                 Consumer<SpawnBatchResult> completion) {
        return new BatchTracker(requestedCount, resolveHeldCommandItem(player), completion);
    }

    final class BatchTracker {
        private final int requestedCount;
        @Nullable
        private final AutoLinkContext autoLink;
        private final Consumer<SpawnBatchResult> completion;
        private int pendingCount;
        private int spawnedCount;
        private int linkedCount;
        private boolean sealed;
        private boolean completed;
        @Nullable
        private String stoppedReason;
        @Nullable
        private AttachmentResolution attachmentResolution;

        private BatchTracker(int requestedCount,
                             @Nullable AutoLinkContext autoLink,
                             Consumer<SpawnBatchResult> completion) {
            this.requestedCount = requestedCount;
            this.autoLink = autoLink;
            this.completion = completion;
        }

        synchronized void register(@Nullable AttachmentResolution resolution) {
            pendingCount++;
            if (attachmentResolution == null && resolution != null) {
                attachmentResolution = resolution;
            }
        }

        synchronized void stop(@Nonnull String reason) {
            if (stoppedReason == null) {
                stoppedReason = reason;
            }
        }

        synchronized void denied(@Nonnull String reason) {
            stop(reason);
            pendingCount = Math.max(0, pendingCount - 1);
            finishIfReady();
        }

        synchronized void applied(boolean linked, @Nullable AttachmentResolution resolution) {
            if (attachmentResolution == null && resolution != null) {
                attachmentResolution = resolution;
            }
            spawnedCount++;
            if (linked) {
                linkedCount++;
            }
            pendingCount = Math.max(0, pendingCount - 1);
            finishIfReady();
        }

        synchronized void durabilityDegraded(@Nonnull String reason) {
            if (!completed && stoppedReason == null) {
                stoppedReason = "Ownership durability degraded: " + reason + ".";
            }
        }

        synchronized void seal() {
            sealed = true;
            finishIfReady();
        }

        private void finishIfReady() {
            if (!sealed || pendingCount > 0 || completed) {
                return;
            }
            completed = true;
            if (autoLink != null && autoLink.changed && !updateHeldItem(autoLink)) {
                stoppedReason = "Held command item changed before auto-linking could be finalized.";
                linkedCount = 0;
            }
            completion.accept(new SpawnBatchResult(
                    null,
                    requestedCount,
                    spawnedCount,
                    linkedCount,
                    autoLink != null,
                    stoppedReason,
                    attachmentResolution == null ? null : attachmentResolution.appliedSelections,
                    attachmentResolution == null ? List.of() : attachmentResolution.invalidSelections
            ));
        }

        @Nullable
        AutoLinkContext autoLink() {
            return autoLink;
        }
    }

    public static final class SpawnBatchResult {
        @Nullable
        private final String failureMessage;
        private final int requestedCount;
        private final int spawnedCount;
        private final int linkedCount;
        private final boolean hadHeldCommandItem;
        @Nullable
        private final String stoppedReason;
        @Nullable
        private final Map<String, String> appliedAttachments;
        @Nonnull
        private final List<String> invalidAttachments;

        SpawnBatchResult(@Nullable String failureMessage,
                                 int requestedCount,
                                 int spawnedCount,
                                 int linkedCount,
                                 boolean hadHeldCommandItem,
                                 @Nullable String stoppedReason,
                                 @Nullable Map<String, String> appliedAttachments,
                                 @Nullable List<String> invalidAttachments) {
            this.failureMessage = failureMessage;
            this.requestedCount = requestedCount;
            this.spawnedCount = spawnedCount;
            this.linkedCount = linkedCount;
            this.hadHeldCommandItem = hadHeldCommandItem;
            this.stoppedReason = stoppedReason;
            this.appliedAttachments = appliedAttachments;
            this.invalidAttachments = invalidAttachments != null ? List.copyOf(invalidAttachments) : List.of();
        }

        @Nonnull
        static SpawnBatchResult failure(@Nonnull String failureMessage) {
            return new SpawnBatchResult(failureMessage, 0, 0, 0, false, null, null, List.of());
        }

        @Nullable
        public String getFailureMessage() {
            return failureMessage;
        }

        public int getRequestedCount() {
            return requestedCount;
        }

        public int getSpawnedCount() {
            return spawnedCount;
        }

        public int getLinkedCount() {
            return linkedCount;
        }

        public boolean hadHeldCommandItem() {
            return hadHeldCommandItem;
        }

        @Nullable
        public String getStoppedReason() {
            return stoppedReason;
        }

        @Nullable
        public Map<String, String> getAppliedAttachments() {
            return appliedAttachments;
        }

        @Nonnull
        public List<String> getInvalidAttachments() {
            return invalidAttachments;
        }
    }

    static final class AttachmentResolution {
        private final Map<String, String> appliedSelections;
        private final List<String> invalidSelections;

        private AttachmentResolution(Map<String, String> appliedSelections, List<String> invalidSelections) {
            this.appliedSelections = appliedSelections != null ? Map.copyOf(appliedSelections) : Map.of();
            this.invalidSelections = invalidSelections != null ? List.copyOf(invalidSelections) : List.of();
        }
    }
}
