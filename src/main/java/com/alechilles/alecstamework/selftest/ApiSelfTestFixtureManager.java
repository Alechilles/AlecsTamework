package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.npc.compat.NpcDisplayNameAccess;
import com.alechilles.alecstamework.config.TameworkIds;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.items.ApiSelfTestCommandToolFactory;
import com.alechilles.alecstamework.items.ApiSelfTestCommandToolFactory.LinkedNpcSpec;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Creates, discovers, and resets live fixture data for the in-game API self-test commands.
 */
public final class ApiSelfTestFixtureManager {
    public static final String FIXTURE_KEY_OWNED = "owned_linked_example";
    public static final String FIXTURE_KEY_STRANGER = "stranger_linked_example";

    private static final String STRANGER_OWNER_NAME = "API Self-Test Stranger";
    private static final String DISPLAY_NAME_OWNED = "API Self-Test Owned Example";
    private static final String DISPLAY_NAME_STRANGER = "API Self-Test Stranger Example";

    private final ApiSelfTestCommandToolFactory toolFactory;

    public ApiSelfTestFixtureManager() {
        this(new ApiSelfTestCommandToolFactory());
    }

    ApiSelfTestFixtureManager(@Nonnull ApiSelfTestCommandToolFactory toolFactory) {
        this.toolFactory = toolFactory;
    }

    @Nonnull
    public CompletableFuture<FixtureOperationResult> prepareAsync(
            @Nonnull Player player,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull World world
    ) {
        UUID ownerPlayerUuid = player.getUuid();
        String worldName = normalizeWorldName(world);
        if (ownerPlayerUuid == null) {
            return completedFailure("Unable to determine your player UUID.", null);
        }
        ApiSelfTestFixtureSet existing = resolveFixtureSet(player, store, world).orElse(null);
        if (existing != null) {
            return completedFailure(
                    "An API self-test fixture set already exists. Run /tw api test reset first.",
                    existing
            );
        }

        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            return completedFailure("Unable to read your position for fixture placement.", null);
        }
        Vector3d playerPosition = new Vector3d(playerTransform.getPosition());
        Rotation3f playerRotation = new Rotation3f(playerTransform.getRotation());
        String playerOwnerName = defaultIfBlank(OwnerNameUtil.resolve(player), "API Self-Test Owner");
        UUID strangerOwnerUuid = createStrangerOwnerUuid(ownerPlayerUuid);
        String fixtureSetId = UUID.randomUUID().toString();
        String toolId = UUID.randomUUID().toString();

        ArrayList<SpawnedFixture> spawnedFixtures = new ArrayList<>();
        try {
            SpawnedFixture owned = spawnFixture(
                    store,
                    new Vector3d(playerPosition).add(new Vector3d(2.0, 0.0, 2.0)),
                    playerRotation,
                    fixtureSetId,
                    FIXTURE_KEY_OWNED,
                    toolId,
                    ownerPlayerUuid,
                    ownerPlayerUuid,
                    playerOwnerName,
                    DISPLAY_NAME_OWNED,
                    new Vector3d(playerPosition.x + 4.0, playerPosition.y, playerPosition.z + 4.0)
            );
            spawnedFixtures.add(owned);

            SpawnedFixture stranger = spawnFixture(
                    store,
                    new Vector3d(playerPosition).add(new Vector3d(-2.0, 0.0, 2.0)),
                    playerRotation,
                    fixtureSetId,
                    FIXTURE_KEY_STRANGER,
                    toolId,
                    ownerPlayerUuid,
                    strangerOwnerUuid,
                    STRANGER_OWNER_NAME,
                    DISPLAY_NAME_STRANGER,
                    new Vector3d(playerPosition.x - 4.0, playerPosition.y, playerPosition.z + 4.0)
            );
            spawnedFixtures.add(stranger);

            return onWorld(world, () -> finishPrepare(
                            player, owned.fixture(), stranger.fixture(), fixtureSetId,
                            ownerPlayerUuid, worldName, toolId
                    ))
                    .handle((result, failure) -> {
                        if (failure == null) {
                            return result;
                        }
                        cleanupSpawnedFixtures(
                                world,
                                spawnedFixtures.stream()
                                        .map(fixture -> fixture.fixture().npcUuid())
                                        .toList()
                        );
                        return FixtureOperationResult.failure(
                                "Failed to prepare self-test fixtures: " + rootMessage(failure),
                                null
                        );
                    });
        } catch (Exception ex) {
            cleanupSpawnedFixtures(world, spawnedFixtures.stream()
                    .map(fixture -> fixture.fixture().npcUuid()).toList());
            return completedFailure("Failed to prepare self-test fixtures: " + rootMessage(ex), null);
        }
    }

    @Nonnull
    public CompletableFuture<FixtureOperationResult> resetAsync(
            @Nonnull Player player,
            @Nonnull Store<EntityStore> store,
            @Nonnull World world
    ) {
        UUID ownerPlayerUuid = player.getUuid();
        if (ownerPlayerUuid == null) {
            return completedFailure("Unable to determine your player UUID.", null);
        }
        String worldName = normalizeWorldName(world);
        List<ToolStackMatch> tools = findSelfTestTools(player, worldName);
        List<LiveFixtureMarkerMatch> liveFixtures = collectLiveFixtures(store, ownerPlayerUuid, null);
        if (tools.isEmpty() && liveFixtures.isEmpty()) {
            return completedFailure("No API self-test fixture set found for this player/world.", null);
        }
        LinkedHashSet<UUID> referencedNpcUuids = toolNpcUuids(tools);
        LinkedHashSet<UUID> liveNpcUuids = new LinkedHashSet<>();
        for (LiveFixtureMarkerMatch liveFixture : liveFixtures) {
            liveNpcUuids.add(liveFixture.npcUuid());
        }
        referencedNpcUuids.removeAll(liveNpcUuids);
        if (!referencedNpcUuids.isEmpty()) {
            return completedFailure(
                    "Cannot safely reset while " + referencedNpcUuids.size()
                            + " fixture companion(s) are not loaded; their owner slots were preserved.",
                    null
            );
        }
        return onWorld(world, () -> {
                    for (LiveFixtureMarkerMatch liveFixture : liveFixtures) {
                        despawn(store, liveFixture.reference());
                    }
                    for (ToolStackMatch tool : tools) {
                        removeHotbarSlot(player, tool.slot());
                    }
                    return FixtureOperationResult.success(
                            "Reset " + liveNpcUuids.size() + " API self-test fixture(s).",
                            null
                    );
                })
                .exceptionally(failure -> FixtureOperationResult.failure(
                        "Failed to reset self-test fixtures safely: " + rootMessage(failure),
                        null
                ));
    }

    @Nonnull
    private FixtureOperationResult finishPrepare(
            @Nonnull Player player,
            @Nonnull ApiSelfTestFixtureRecord owned,
            @Nonnull ApiSelfTestFixtureRecord stranger,
            @Nonnull String fixtureSetId,
            @Nonnull UUID ownerPlayerUuid,
            @Nonnull String worldName,
            @Nonnull String toolId
    ) {
        ItemStack toolStack = toolFactory.createExampleCommandTool(
                fixtureSetId,
                ownerPlayerUuid,
                worldName,
                toolId,
                List.of(linkedSpec(owned), linkedSpec(stranger))
        );
        int toolSlot = placeToolInHotbar(player, toolStack);
        if (toolSlot < 0) {
            throw new IllegalStateException("fixture-tool-placement-failed");
        }
        LinkedHashMap<String, ApiSelfTestFixtureRecord> fixtures = new LinkedHashMap<>();
        fixtures.put(owned.fixtureKey(), owned);
        fixtures.put(stranger.fixtureKey(), stranger);
        return FixtureOperationResult.success(
                "Prepared API self-test fixtures in hotbar slot " + toolSlot + ".",
                new ApiSelfTestFixtureSet(
                        fixtureSetId, ownerPlayerUuid, worldName, toolId, fixtures
                )
        );
    }

    @Nonnull
    private static LinkedNpcSpec linkedSpec(@Nonnull ApiSelfTestFixtureRecord fixture) {
        return new LinkedNpcSpec(
                fixture.npcUuid(),
                fixture.lastKnownPosition(),
                fixture.homePosition(),
                fixture.displayName(),
                null,
                fixture.roleId()
        );
    }

    @Nonnull
    private static LinkedHashSet<UUID> toolNpcUuids(@Nonnull List<ToolStackMatch> tools) {
        LinkedHashSet<UUID> npcUuids = new LinkedHashSet<>();
        for (ToolStackMatch tool : tools) {
            for (LinkedNpcSpec spec : tool.linkedNpcSpecs()) {
                npcUuids.add(spec.npcUuid());
            }
        }
        return npcUuids;
    }

    @Nonnull
    private static <T> CompletableFuture<T> onWorld(
            @Nonnull World world,
            @Nonnull Supplier<T> action
    ) {
        CompletableFuture<T> completion = new CompletableFuture<>();
        LeaseBoundWorldDispatcher.execute(world, () -> {
            try {
                completion.complete(action.get());
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        }, () -> completion.completeExceptionally(
                new IllegalStateException("fixture-world-dispatch-rejected")
        ));
        return completion;
    }

    @Nonnull
    private static CompletableFuture<FixtureOperationResult> completedFailure(
            @Nonnull String summary,
            @Nullable ApiSelfTestFixtureSet fixtureSet
    ) {
        return CompletableFuture.completedFuture(
                FixtureOperationResult.failure(summary, fixtureSet)
        );
    }

    @Nonnull
    private static String rootMessage(@Nonnull Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current.getCause() != null)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    @Nonnull
    public Optional<ApiSelfTestFixtureSet> resolveFixtureSet(@Nonnull Player player,
                                                             @Nonnull Store<EntityStore> store,
                                                             @Nonnull World world) {
        UUID ownerPlayerUuid = player.getUuid();
        if (ownerPlayerUuid == null) {
            return Optional.empty();
        }
        String worldName = normalizeWorldName(world);
        List<ToolStackMatch> tools = findSelfTestTools(player, worldName);
        String fixtureSetId = tools.isEmpty() ? null : tools.get(0).fixtureSetId();
        List<LiveFixtureMarkerMatch> liveFixtures = collectLiveFixtures(store, ownerPlayerUuid, fixtureSetId);
        return resolveFromMatches(ownerPlayerUuid, worldName, tools, liveFixtures);
    }

    @Nonnull
    private Optional<ApiSelfTestFixtureSet> resolveFromMatches(@Nonnull UUID ownerPlayerUuid,
                                                               @Nonnull String worldName,
                                                               @Nonnull List<ToolStackMatch> tools,
                                                               @Nonnull List<LiveFixtureMarkerMatch> liveFixtures) {
        String fixtureSetId = null;
        String toolId = null;
        if (!tools.isEmpty()) {
            fixtureSetId = tools.get(0).fixtureSetId();
            toolId = tools.get(0).toolId();
        }
        if (fixtureSetId == null && !liveFixtures.isEmpty()) {
            fixtureSetId = liveFixtures.get(0).fixtureSetId();
        }
        if (toolId == null && !liveFixtures.isEmpty()) {
            toolId = liveFixtures.get(0).toolId();
        }
        if (fixtureSetId == null || fixtureSetId.isBlank() || toolId == null || toolId.isBlank()) {
            return Optional.empty();
        }

        Map<UUID, LinkedNpcSpec> specsByUuid = new LinkedHashMap<>();
        for (ToolStackMatch tool : tools) {
            for (LinkedNpcSpec spec : tool.linkedNpcSpecs()) {
                specsByUuid.putIfAbsent(spec.npcUuid(), spec);
            }
        }

        LinkedHashMap<String, ApiSelfTestFixtureRecord> fixtures = new LinkedHashMap<>();
        for (LiveFixtureMarkerMatch liveFixture : liveFixtures) {
            LinkedNpcSpec spec = specsByUuid.remove(liveFixture.npcUuid());
            fixtures.put(liveFixture.fixtureKey(), buildFixtureRecord(
                    liveFixture.fixtureKey(),
                    liveFixture.npcUuid(),
                    liveFixture.ownerUuid(),
                    liveFixture.ownerName(),
                    spec != null ? defaultIfBlank(spec.roleId(), TameworkIds.NPC_ROLE_TAMEWORK_EXAMPLE)
                            : TameworkIds.NPC_ROLE_TAMEWORK_EXAMPLE,
                    spec != null ? defaultIfBlank(spec.displayName(), displayNameForKey(liveFixture.fixtureKey()))
                            : displayNameForKey(liveFixture.fixtureKey()),
                    spec != null && spec.homePosition() != null ? spec.homePosition() : liveFixture.homePosition(),
                    spec != null && spec.lastKnownPosition() != null ? spec.lastKnownPosition() : liveFixture.lastKnownPosition()
            ));
        }

        ArrayList<LinkedNpcSpec> remainingSpecs = new ArrayList<>(specsByUuid.values());
        fillFixtureFromRemainingSpecs(fixtures, remainingSpecs, ownerPlayerUuid);
        if (fixtures.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ApiSelfTestFixtureSet(fixtureSetId, ownerPlayerUuid, worldName, toolId, fixtures));
    }

    private void fillFixtureFromRemainingSpecs(@Nonnull Map<String, ApiSelfTestFixtureRecord> fixtures,
                                               @Nonnull List<LinkedNpcSpec> remainingSpecs,
                                               @Nonnull UUID ownerPlayerUuid) {
        String[] orderedKeys = { FIXTURE_KEY_OWNED, FIXTURE_KEY_STRANGER };
        int specIndex = 0;
        for (String fixtureKey : orderedKeys) {
            if (fixtures.containsKey(fixtureKey)) {
                continue;
            }
            if (specIndex >= remainingSpecs.size()) {
                return;
            }
            LinkedNpcSpec spec = remainingSpecs.get(specIndex++);
            UUID ownerUuid = FIXTURE_KEY_OWNED.equals(fixtureKey) ? ownerPlayerUuid : createStrangerOwnerUuid(ownerPlayerUuid);
            String ownerName = FIXTURE_KEY_OWNED.equals(fixtureKey) ? "owner" : STRANGER_OWNER_NAME;
            fixtures.put(fixtureKey, buildFixtureRecord(
                    fixtureKey,
                    spec.npcUuid(),
                    ownerUuid,
                    ownerName,
                    defaultIfBlank(spec.roleId(), TameworkIds.NPC_ROLE_TAMEWORK_EXAMPLE),
                    defaultIfBlank(spec.displayName(), displayNameForKey(fixtureKey)),
                    spec.homePosition(),
                    spec.lastKnownPosition()
            ));
        }
    }

    @Nonnull
    private SpawnedFixture spawnFixture(@Nonnull Store<EntityStore> store,
                                        @Nonnull Vector3d spawnPosition,
                                        @Nonnull Rotation3f rotation,
                                        @Nonnull String fixtureSetId,
                                        @Nonnull String fixtureKey,
                                        @Nonnull String toolId,
                                        @Nonnull UUID fixtureSetOwnerUuid,
                                        @Nonnull UUID ownerUuid,
                                        @Nonnull String ownerName,
                                        @Nonnull String displayName,
                                        @Nonnull Vector3d homePosition) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            throw new IllegalStateException("NPC plugin unavailable");
        }
        int roleIndex = npcPlugin.getIndex(TameworkIds.NPC_ROLE_TAMEWORK_EXAMPLE);
        if (roleIndex < 0) {
            throw new IllegalStateException("Example NPC role missing: " + TameworkIds.NPC_ROLE_TAMEWORK_EXAMPLE);
        }
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(store, roleIndex, spawnPosition, rotation, null, null);
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            throw new IllegalStateException("spawnEntity returned null");
        }

        Ref<EntityStore> npcRef = spawned.first();
        NPCEntity npc = spawned.second();
        UUID npcUuid = npc.getUuid();
        if (npcUuid == null) {
            throw new IllegalStateException("Spawned fixture NPC did not have a UUID");
        }

        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType != null) {
            store.putComponent(npcRef, linksType, new TameworkCommandLinksComponent(ownerUuid, new String[] { toolId }, homePosition));
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            throw new IllegalStateException("Tamework owner component is unavailable");
        }
        store.putComponent(
                npcRef,
                ownerType,
                new TameworkOwnerComponent(ownerUuid, ownerName)
        );
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            store.putComponent(npcRef, tamedType, new TameworkTamedComponent(true));
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType != null) {
            store.putComponent(
                    npcRef,
                    nameType,
                    new TameworkNpcNameComponent(
                            displayName,
                            ownerUuid,
                            System.currentTimeMillis(),
                            TameworkNpcNameComponent.NameSource.System
                    )
            );
        }
        CompanionProgressionBootstrapService.ensureProgressionComponents(
                npcRef,
                store,
                TameworkIds.NPC_ROLE_TAMEWORK_EXAMPLE
        );
        ComponentType<EntityStore, ApiSelfTestFixtureMarkerComponent> markerType = ApiSelfTestFixtureMarkerComponent.getComponentType();
        if (markerType != null) {
            store.putComponent(
                    npcRef,
                    markerType,
                    new ApiSelfTestFixtureMarkerComponent(
                            fixtureSetId,
                            fixtureKey,
                            fixtureSetOwnerUuid,
                            toolId,
                            System.currentTimeMillis()
                    )
            );
        }
        NpcDisplayNameAccess.set(npcRef, displayName, store);

        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        Vector3d actualPosition = transform != null ? new Vector3d(transform.getPosition()) : new Vector3d(spawnPosition);
        return new SpawnedFixture(
                npcRef,
                buildFixtureRecord(
                        fixtureKey,
                        npcUuid,
                        ownerUuid,
                        ownerName,
                        TameworkIds.NPC_ROLE_TAMEWORK_EXAMPLE,
                        displayName,
                        homePosition,
                        actualPosition
                )
        );
    }

    private void cleanupSpawnedFixtures(@Nonnull World world, @Nonnull Iterable<UUID> spawnedNpcUuids) {
        for (UUID npcUuid : spawnedNpcUuids) {
            if (npcUuid == null) {
                continue;
            }
            Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
            if (npcRef == null || !npcRef.isValid()) {
                continue;
            }
            Store<EntityStore> store = world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
            if (store == null) {
                continue;
            }
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            if (npc != null) {
                npc.setToDespawn();
            }
        }
    }

    private static void despawn(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> reference
    ) {
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        if (npc != null) {
            npc.setToDespawn();
        }
    }

    private int placeToolInHotbar(@Nonnull Player player, @Nonnull ItemStack stack) {
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return -1;
        }
        ItemContainer hotbar = inventory.getHotbar();
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack existing = hotbar.getItemStack(slot);
            if (existing == null || existing.isEmpty()) {
                hotbar.setItemStackForSlot(slot, stack);
                return slot;
            }
        }
        byte activeSlot = PlayerInventoryAccess.getActiveHotbarSlot(player);
        short fallbackSlot = activeSlot >= 0 ? (short) activeSlot : 0;
        if (fallbackSlot >= capacity) {
            return -1;
        }
        hotbar.setItemStackForSlot(fallbackSlot, stack);
        return fallbackSlot;
    }

    private void removeHotbarSlot(@Nonnull Player player, short slot) {
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return;
        }
        ItemContainer hotbar = inventory.getHotbar();
        ItemStack stack = hotbar.getItemStack(slot);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        hotbar.removeItemStackFromSlot(slot, Math.max(1, stack.getQuantity()));
    }

    @Nonnull
    private List<ToolStackMatch> findSelfTestTools(@Nonnull Player player, @Nonnull String worldName) {
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return List.of();
        }
        ItemContainer hotbar = inventory.getHotbar();
        ArrayList<ToolStackMatch> matches = new ArrayList<>();
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (!toolFactory.isSelfTestTool(stack, player.getUuid(), worldName)) {
                continue;
            }
            String fixtureSetId = toolFactory.getFixtureSetId(stack);
            String toolId = toolFactory.getToolId(stack);
            if (fixtureSetId == null || fixtureSetId.isBlank() || toolId == null || toolId.isBlank()) {
                continue;
            }
            matches.add(new ToolStackMatch(slot, fixtureSetId, toolId, toolFactory.readLinkedNpcSpecs(stack)));
        }
        return matches;
    }

    @Nonnull
    private List<LiveFixtureMarkerMatch> collectLiveFixtures(@Nonnull Store<EntityStore> store,
                                                             @Nonnull UUID ownerPlayerUuid,
                                                             @Nullable String fixtureSetIdFilter) {
        ComponentType<EntityStore, ApiSelfTestFixtureMarkerComponent> markerType =
                ApiSelfTestFixtureMarkerComponent.getComponentType();
        if (markerType == null) {
            return List.of();
        }
        ArrayList<LiveFixtureMarkerMatch> matches = new ArrayList<>();
        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                ApiSelfTestFixtureMarkerComponent marker = chunk.getComponent(i, markerType);
                if (npc == null || npc.getUuid() == null || marker == null || marker.getOwnerPlayerUuid() == null) {
                    continue;
                }
                if (!ownerPlayerUuid.equals(marker.getOwnerPlayerUuid())) {
                    continue;
                }
                if (fixtureSetIdFilter != null
                        && marker.getFixtureSetId() != null
                        && !fixtureSetIdFilter.equalsIgnoreCase(marker.getFixtureSetId())) {
                    continue;
                }
                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                TameworkCommandLinksComponent links = TameworkCommandLinksComponent.getComponentType() != null
                        ? chunk.getComponent(i, TameworkCommandLinksComponent.getComponentType())
                        : null;
                TameworkOwnerComponent owner = TameworkOwnerComponent.getComponentType() != null
                        ? chunk.getComponent(i, TameworkOwnerComponent.getComponentType())
                        : null;
                matches.add(new LiveFixtureMarkerMatch(
                        chunk.getReferenceTo(i),
                        defaultIfBlank(marker.getFixtureSetId(), fixtureSetIdFilter != null ? fixtureSetIdFilter : ""),
                        defaultIfBlank(marker.getFixtureKey(), "fixture-" + i),
                        marker.getToolId(),
                        npc.getUuid(),
                        owner != null ? owner.getOwnerId() : null,
                        owner != null ? owner.getOwnerName() : null,
                        links != null ? links.getHomePosition() : null,
                        transform != null ? new Vector3d(transform.getPosition()) : null
                ));
            }
        });
        return matches;
    }

    @Nonnull
    private ApiSelfTestFixtureRecord buildFixtureRecord(@Nonnull String fixtureKey,
                                                        @Nonnull UUID npcUuid,
                                                        @Nullable UUID ownerUuid,
                                                        @Nullable String ownerName,
                                                        @Nonnull String roleId,
                                                        @Nonnull String displayName,
                                                        @Nullable Vector3d homePosition,
                                                        @Nullable Vector3d lastKnownPosition) {
        return new ApiSelfTestFixtureRecord(
                fixtureKey,
                npcUuid,
                ownerUuid,
                ownerName,
                roleId,
                displayName,
                homePosition != null ? new Vector3d(homePosition) : null,
                lastKnownPosition != null ? new Vector3d(lastKnownPosition) : null
        );
    }

    @Nonnull
    private static UUID createStrangerOwnerUuid(@Nonnull UUID playerUuid) {
        return UUID.nameUUIDFromBytes(("tamework-api-self-test-stranger:" + playerUuid)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Nonnull
    private static String displayNameForKey(@Nonnull String fixtureKey) {
        if (FIXTURE_KEY_OWNED.equals(fixtureKey)) {
            return DISPLAY_NAME_OWNED;
        }
        if (FIXTURE_KEY_STRANGER.equals(fixtureKey)) {
            return DISPLAY_NAME_STRANGER;
        }
        return fixtureKey;
    }

    @Nonnull
    private static String normalizeWorldName(@Nullable World world) {
        if (world == null || world.getName() == null || world.getName().isBlank()) {
            return "unknown";
        }
        return world.getName().trim();
    }

    @Nonnull
    private static String defaultIfBlank(@Nullable String value, @Nonnull String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    public record FixtureOperationResult(boolean success,
                                         @Nonnull String summary,
                                         @Nullable ApiSelfTestFixtureSet fixtureSet) {
        @Nonnull
        static FixtureOperationResult success(@Nonnull String summary, @Nullable ApiSelfTestFixtureSet fixtureSet) {
            return new FixtureOperationResult(true, summary, fixtureSet);
        }

        @Nonnull
        static FixtureOperationResult failure(@Nonnull String summary, @Nullable ApiSelfTestFixtureSet fixtureSet) {
            return new FixtureOperationResult(false, summary, fixtureSet);
        }
    }

    private record SpawnedFixture(@Nonnull Ref<EntityStore> reference,
                                  @Nonnull ApiSelfTestFixtureRecord fixture) {
    }

    private record ToolStackMatch(short slot,
                                  @Nonnull String fixtureSetId,
                                  @Nonnull String toolId,
                                  @Nonnull List<LinkedNpcSpec> linkedNpcSpecs) {
    }

    private record LiveFixtureMarkerMatch(@Nonnull Ref<EntityStore> reference,
                                          @Nonnull String fixtureSetId,
                                          @Nonnull String fixtureKey,
                                          @Nullable String toolId,
                                          @Nonnull UUID npcUuid,
                                          @Nullable UUID ownerUuid,
                                          @Nullable String ownerName,
                                          @Nullable Vector3d homePosition,
                                          @Nullable Vector3d lastKnownPosition) {
    }
}
