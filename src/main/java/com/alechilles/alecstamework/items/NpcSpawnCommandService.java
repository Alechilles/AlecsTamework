package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.actions.TameClaimLimitPolicyService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Spawns owned+tamed NPC batches for commands and optionally links them to the held command item.
 */
public final class NpcSpawnCommandService {
    private static final double SPAWN_RING_RADIUS_STEP = 0.9;
    private static final int SPAWN_RING_SIZE = 6;

    private final SpawnerSpawnPositionService spawnPositionService;
    private final SpawnerAttachmentService attachmentService;
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLinkPolicyService linkPolicyService;
    private final TameClaimLimitPolicyService tameClaimLimitPolicyService;

    public NpcSpawnCommandService(@Nonnull Tamework plugin) {
        this.spawnPositionService = new SpawnerSpawnPositionService(plugin.getLogger());
        this.attachmentService = new SpawnerAttachmentService(plugin.getLogger());
        this.linkedNpcRecordStore = new CommandLinkedNpcRecordStore();
        this.npcNameResolver = new CommandNpcNameResolver();
        this.linkPolicyService = new CommandLinkPolicyService();
        this.tameClaimLimitPolicyService = new TameClaimLimitPolicyService();
    }

    @Nonnull
    public SpawnBatchResult spawnTamedOwnedBatch(@Nonnull Player player,
                                                 @Nonnull Store<EntityStore> store,
                                                 @Nonnull Ref<EntityStore> playerRef,
                                                 @Nonnull World world,
                                                 @Nonnull String roleId,
                                                 int quantity,
                                                 @Nullable Map<String, String> attachmentOverrides) {
        if (quantity <= 0) {
            return SpawnBatchResult.failure("Quantity must be greater than zero.");
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return SpawnBatchResult.failure("NPC plugin not available.");
        }
        int roleIndex = npcPlugin.getIndex(roleId);
        if (roleIndex < 0) {
            return SpawnBatchResult.failure("Unknown role '" + roleId + "'.");
        }

        UUID ownerUuid = player.getUuid();
        if (ownerUuid == null) {
            return SpawnBatchResult.failure("Player UUID not available.");
        }

        Vector3d baseSpawnPosition = spawnPositionService.resolveSpawnPosition(player, null);
        if (baseSpawnPosition == null) {
            return SpawnBatchResult.failure("Unable to resolve a spawn position.");
        }

        AutoLinkContext autoLink = resolveHeldCommandItem(player);
        int spawnedCount = 0;
        int linkedCount = 0;
        String stoppedReason = null;
        AttachmentResolution attachmentResolution = null;

        for (int index = 0; index < quantity; index++) {
            Vector3d spawnPosition = offsetSpawnPosition(baseSpawnPosition, index);
            OwnerPopulationCapService.Decision decision = OwnerPopulationCapService.evaluateAcquisition(store, ownerUuid);
            if (!decision.allowed()) {
                OwnerMessageUtil.sendPopulationCapReached(
                        player,
                        decision.currentCount(),
                        decision.limit(),
                        decision.scope()
                );
                stoppedReason = "Owner population cap reached.";
                break;
            }
            TameClaimLimitPolicyService.TameLimitDecision claimDecision =
                    tameClaimLimitPolicyService.evaluateForTame(store, spawnPosition);
            if (!claimDecision.allowed()) {
                if (claimDecision.claimCapReached()) {
                    OwnerMessageUtil.sendClaimPopulationCapReached(
                            player,
                            claimDecision.currentCount(),
                            claimDecision.effectiveCap()
                    );
                }
                stoppedReason = "Claim population cap reached.";
                break;
            }

            Rotation3f rotation = spawnPositionService.resolveSpawnRotation(store, playerRef, spawnPosition);
            Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(store, roleIndex, spawnPosition, rotation, null, null);
            if (spawned == null || spawned.first() == null || spawned.second() == null) {
                stoppedReason = "Spawn failed before completing the requested quantity.";
                break;
            }

            Ref<EntityStore> npcRef = spawned.first();
            NPCEntity npc = spawned.second();
            applyOwnerAndTamedState(player, store, world, playerRef, npcRef, npc, ownerUuid);
            if (attachmentOverrides != null && !attachmentOverrides.isEmpty()) {
                if (attachmentResolution == null) {
                    attachmentResolution = resolveAttachmentOverrides(npcRef, store, attachmentOverrides);
                }
                if (!attachmentResolution.appliedSelections.isEmpty()) {
                    attachmentService.applyAttachments(attachmentResolution.appliedSelections, npcRef, npc, store);
                }
            }
            spawnedCount++;

            if (autoLink != null && linkHeldCommandItem(autoLink, player, store, npcRef, npc)) {
                linkedCount++;
            }
        }

        if (autoLink != null && autoLink.changed) {
            updateHeldItem(player, autoLink.stack);
        }

        return new SpawnBatchResult(
                null,
                quantity,
                spawnedCount,
                linkedCount,
                autoLink != null,
                stoppedReason,
                attachmentResolution != null ? attachmentResolution.appliedSelections : null,
                attachmentResolution != null ? attachmentResolution.invalidSelections : List.of()
        );
    }

    @Nonnull
    private AttachmentResolution resolveAttachmentOverrides(@Nonnull Ref<EntityStore> npcRef,
                                                            @Nonnull Store<EntityStore> store,
                                                            @Nonnull Map<String, String> requestedSelections) {
        ModelAsset modelAsset = CompanionModelAttachmentService.resolveModelAsset(npcRef, store);
        Map<String, Set<String>> options = CompanionModelAttachmentService.resolveAttachmentOptionIds(modelAsset);
        if (options.isEmpty()) {
            return new AttachmentResolution(Map.of(), List.of("role has no attachment sets"));
        }

        LinkedHashMap<String, String> applied = new LinkedHashMap<>();
        ArrayList<String> invalid = new ArrayList<>();
        for (Map.Entry<String, String> entry : requestedSelections.entrySet()) {
            String requestedSet = entry.getKey();
            String requestedValue = entry.getValue();
            String canonicalSet = findCanonicalMatch(options.keySet(), requestedSet);
            if (canonicalSet == null) {
                invalid.add(requestedSet + ":" + requestedValue);
                continue;
            }
            String canonicalValue = findCanonicalMatch(options.get(canonicalSet), requestedValue);
            if (canonicalValue == null) {
                invalid.add(requestedSet + ":" + requestedValue);
                continue;
            }
            applied.put(canonicalSet, canonicalValue);
        }
        return new AttachmentResolution(applied, invalid);
    }

    @Nullable
    private String findCanonicalMatch(@Nullable Iterable<String> candidates, @Nullable String requested) {
        if (candidates == null || requested == null || requested.isBlank()) {
            return null;
        }
        String requestedKey = normalizeAttachmentToken(requested);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (normalizeAttachmentToken(candidate).equals(requestedKey)) {
                return candidate;
            }
        }
        return null;
    }

    @Nonnull
    private String normalizeAttachmentToken(@Nonnull String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLetterOrDigit(current)) {
                builder.append(Character.toLowerCase(current));
            }
        }
        return builder.toString();
    }

    private void applyOwnerAndTamedState(Player player,
                                         Store<EntityStore> store,
                                         World world,
                                         Ref<EntityStore> playerRef,
                                         Ref<EntityStore> npcRef,
                                         NPCEntity npc,
                                         UUID ownerUuid) {
        if (TameworkOwnerComponent.getComponentType() != null) {
            store.putComponent(
                    npcRef,
                    TameworkOwnerComponent.getComponentType(),
                    new TameworkOwnerComponent(ownerUuid, OwnerNameUtil.resolve(player))
            );
        }
        if (TameworkTamedComponent.getComponentType() != null) {
            store.putComponent(npcRef, TameworkTamedComponent.getComponentType(), new TameworkTamedComponent(true));
        }
        CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store);
        Ref<EntityStore> masterRef = playerRef;
        Ref<EntityStore> ownerRef = world.getEntityRef(ownerUuid);
        if (ownerRef != null && ownerRef.isValid()) {
            masterRef = ownerRef;
        }
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

        return new AutoLinkContext(heldStack, toolId, config, changed);
    }

    private boolean linkHeldCommandItem(AutoLinkContext context,
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

    private Vector3d offsetSpawnPosition(Vector3d basePosition, int spawnIndex) {
        if (spawnIndex <= 0) {
            return new Vector3d(basePosition);
        }
        int ring = (spawnIndex - 1) / SPAWN_RING_SIZE + 1;
        int ringSlot = (spawnIndex - 1) % SPAWN_RING_SIZE;
        double radius = ring * SPAWN_RING_RADIUS_STEP;
        double angle = (Math.PI * 2.0 * ringSlot) / SPAWN_RING_SIZE;
        return new Vector3d(
                basePosition.x + (Math.cos(angle) * radius),
                basePosition.y,
                basePosition.z + (Math.sin(angle) * radius)
        );
    }

    private boolean updateHeldItem(Player player, ItemStack updated) {
        ItemContainer hotbar = PlayerInventoryAccess.getHotbar(player);
        if (hotbar == null) {
            return false;
        }
        byte activeSlot = PlayerInventoryAccess.getActiveHotbarSlot(player);
        if (activeSlot < 0) {
            return false;
        }
        hotbar.setItemStackForSlot((short) activeSlot, updated);
        return true;
    }

    private static final class AutoLinkContext {
        private ItemStack stack;
        private final String toolId;
        private final TwCommandItemConfig config;
        private boolean changed;

        private AutoLinkContext(ItemStack stack, String toolId, TwCommandItemConfig config, boolean changed) {
            this.stack = stack;
            this.toolId = toolId;
            this.config = config;
            this.changed = changed;
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

        private SpawnBatchResult(@Nullable String failureMessage,
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
        private static SpawnBatchResult failure(@Nonnull String failureMessage) {
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

    private static final class AttachmentResolution {
        private final Map<String, String> appliedSelections;
        private final List<String> invalidSelections;

        private AttachmentResolution(Map<String, String> appliedSelections, List<String> invalidSelections) {
            this.appliedSelections = appliedSelections != null ? Map.copyOf(appliedSelections) : Map.of();
            this.invalidSelections = invalidSelections != null ? List.copyOf(invalidSelections) : List.of();
        }
    }
}
