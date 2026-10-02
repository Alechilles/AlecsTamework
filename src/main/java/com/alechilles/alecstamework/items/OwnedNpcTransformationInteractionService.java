package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.InteractionEffectContext;
import com.alechilles.alecstamework.api.InteractionEffectSpec;
import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Converts an owned pet into a fresh native NPC after releasing its durable ownership. */
public final class OwnedNpcTransformationInteractionService {
    private static final int MAX_PENDING_TRANSFORMATIONS = 256;
    private static final String FAILURE_MESSAGE = "server.tamework.ui.notifications.transformation.unavailable";
    private final CullTerminalOwnerReleaseService.Port ownerRelease;
    private final Set<UUID> pendingTargets = ConcurrentHashMap.newKeySet();
    private final TameworkUiMessageService messages = new TameworkUiMessageService();

    public OwnedNpcTransformationInteractionService(@Nullable ReleaseFlow releaseFlow,
                                                    @Nullable CompanionQueries companions) {
        ownerRelease = CullTerminalOwnerReleaseService.from(releaseFlow, companions);
    }

    /** Param is the target role; JsonPayload supplies Item and an optional Message translation key. */
    public boolean apply(@Nonnull InteractionEffectContext context, @Nonnull InteractionEffectSpec spec) {
        Configuration configuration = parseConfiguration(spec);
        Player player = context.player();
        Store<EntityStore> store = context.store();
        Ref<EntityStore> target = context.npcRef();
        NPCEntity npc = target.isValid() ? store.getComponent(target, NPCEntity.getComponentType()) : null;
        UUID actorId = player == null ? null : player.getUuid();
        World world = store.getExternalData() == null ? null : store.getExternalData().getWorld();
        if (configuration == null || actorId == null || npc == null || npc.getUuid() == null
                || world == null || !world.isAlive()
                || !isAuthorized(player, target, store, actorId, configuration.itemId())
                || !isSpawnable(configuration.roleId())) {
            if (player != null) showFailure(player);
            return false;
        }
        Request request = new Request(actorId, npc.getUuid(), npc.getRoleName(), configuration);
        synchronized (pendingTargets) {
            if (pendingTargets.size() >= MAX_PENDING_TRANSFORMATIONS || !pendingTargets.add(request.targetId())) {
                return false;
            }
        }
        // NPC actions run inside ECS processing; structural changes need a fresh world callback.
        LeaseBoundWorldDispatcher.execute(world, () -> start(world, request),
                () -> pendingTargets.remove(request.targetId()));
        return true;
    }

    private void start(World world, Request request) {
        UUID spawnedId = null;
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            Ref<EntityStore> target = world.getEntityRef(request.targetId());
            WorldPlayerResolver.ResolvedPlayer resolved = WorldPlayerResolver.resolve(world, request.actorId());
            Player player = resolved == null ? null : resolved.player();
            if (!matchesOriginal(target, store, request)
                    || !isAuthorized(player, target, store, request.actorId(), request.configuration().itemId())
                    || !isSpawnable(request.configuration().roleId())) {
                if (player != null) showFailure(player);
                pendingTargets.remove(request.targetId());
                return;
            }
            TransformComponent transform = store.getComponent(target, TransformComponent.getComponentType());
            if (transform == null) {
                showFailure(player);
                pendingTargets.remove(request.targetId());
                return;
            }
            var spawned = NPCPlugin.get().spawnNPC(store, request.configuration().roleId(), null,
                    new Vector3d(transform.getPosition()), new Rotation3f(transform.getRotation()));
            if (spawned == null || spawned.first() == null || !spawned.first().isValid()) {
                showFailure(player);
                pendingTargets.remove(request.targetId());
                return;
            }
            UUIDComponent identity = store.getComponent(spawned.first(), UUIDComponent.getComponentType());
            if (identity == null || identity.getUuid() == null) {
                store.removeEntity(spawned.first(), RemoveReason.REMOVE);
                showFailure(player);
                pendingTargets.remove(request.targetId());
                return;
            }
            spawnedId = identity.getUuid();
            UUID bossId = spawnedId;
            CompletionStage<CullTerminalOwnerReleaseService.Outcome> release =
                    ownerRelease.release(request.actorId(), request.targetId());
            continueAfterRelease(release,
                    task -> LeaseBoundWorldDispatcher.execute(world, task,
                            () -> pendingTargets.remove(request.targetId())),
                    () -> finish(world, request, bossId, true),
                    () -> finish(world, request, bossId, false));
        } catch (RuntimeException | LinkageError failure) {
            if (spawnedId != null) removeSpawned(world, spawnedId, request.configuration().roleId());
            pendingTargets.remove(request.targetId());
            notifyFailure(world, request.actorId());
            logFailure(request, failure);
        }
    }

    /** Keeps completion threads outside the live world boundary, including failed/null submissions. */
    static void continueAfterRelease(@Nullable CompletionStage<CullTerminalOwnerReleaseService.Outcome> release,
                                     Consumer<Runnable> worldDispatcher,
                                     Runnable committed,
                                     Runnable rolledBack) {
        if (release == null) {
            worldDispatcher.accept(rolledBack);
            return;
        }
        release.whenComplete((outcome, failure) -> worldDispatcher.accept(failure == null
                && (outcome == CullTerminalOwnerReleaseService.Outcome.RELEASED
                || outcome == CullTerminalOwnerReleaseService.Outcome.NOT_TRACKED)
                ? committed : rolledBack));
    }

    private void finish(World world, Request request, UUID bossId, boolean released) {
        try {
            if (!released) {
                removeSpawned(world, bossId, request.configuration().roleId());
                notifyFailure(world, request.actorId());
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            Ref<EntityStore> target = world.getEntityRef(request.targetId());
            // Publication cleanup may already have removed the released alias. That is success.
            if (matchesOriginal(target, store, request)) store.removeEntity(target, RemoveReason.REMOVE);
            WorldPlayerResolver.ResolvedPlayer resolved = WorldPlayerResolver.resolve(world, request.actorId());
            if (resolved != null) {
                // Authorization was captured before release; never charge a different live item.
                PlayerInventoryAccess.removeActiveHotbarItem(resolved.player(), request.configuration().itemId(), 1);
                messages.showKey(resolved.player(), request.configuration().messageKey());
            }
        } catch (RuntimeException | LinkageError failure) {
            logFailure(request, failure);
        } finally {
            pendingTargets.remove(request.targetId());
        }
    }

    private static boolean matchesOriginal(@Nullable Ref<EntityStore> target, Store<EntityStore> store,
                                           Request request) {
        if (target == null || !target.isValid()) return false;
        NPCEntity npc = store.getComponent(target, NPCEntity.getComponentType());
        return npc != null && request.targetId().equals(npc.getUuid())
                && Objects.equals(request.originalRole(), npc.getRoleName())
                && ownsLiveTamedNpc(target, store, request.actorId());
    }

    private static boolean isAuthorized(@Nullable Player player, Ref<EntityStore> target,
                                         Store<EntityStore> store, UUID actorId, String itemId) {
        if (player == null || !ownsLiveTamedNpc(target, store, actorId)) return false;
        Ref<EntityStore> playerRef = worldPlayerReference(player, store);
        ComponentType<EntityStore, MovementStatesComponent> movementType = MovementStatesComponent.getComponentType();
        MovementStatesComponent movement = playerRef == null || movementType == null ? null
                : store.getComponent(playerRef, movementType);
        MovementStates states = movement == null ? null : movement.getMovementStates();
        ItemStack held = PlayerInventoryAccess.getActiveHotbarItem(player);
        return states != null && (states.crouching || states.forcedCrouching)
                && held != null && !held.isEmpty() && itemId.equals(held.getItemId()) && held.getQuantity() >= 1;
    }

    @Nullable
    private static Ref<EntityStore> worldPlayerReference(Player player, Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        Ref<EntityStore> ref = world.getEntityRef(player.getUuid());
        return ref != null && ref.isValid() ? ref : null;
    }

    private static boolean ownsLiveTamedNpc(Ref<EntityStore> target, Store<EntityStore> store, UUID actorId) {
        if (!CommandGenericTargetAuthority.allowsGenericTargetMutation(target, store)) return false;
        // Command-family companions need their roster authority, outside this ordinary-pet conversion.
        if (CommandGenericTargetAuthority.isRosterMember(target, store)) return false;
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType == null ? null : store.getComponent(target, ownerType);
        ComponentType<EntityStore, DeathComponent> deathType = DeathComponent.getComponentType();
        return owner != null && actorId.equals(owner.getOwnerId())
                && deathType != null && store.getComponent(target, deathType) == null
                && TamedStateResolver.isTamed(target, store);
    }

    private static boolean isSpawnable(String roleId) {
        NPCPlugin plugin = NPCPlugin.get();
        if (plugin == null || plugin.getIndex(roleId) < 0) return false;
        try {
            plugin.validateSpawnableRole(roleId);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static void removeSpawned(World world, UUID bossId, String roleId) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            Ref<EntityStore> ref = world.getEntityRef(bossId);
            NPCEntity npc = ref == null || !ref.isValid() ? null : store.getComponent(ref, NPCEntity.getComponentType());
            if (npc != null && bossId.equals(npc.getUuid()) && roleId.equals(npc.getRoleName())) {
                store.removeEntity(ref, RemoveReason.REMOVE);
            }
        } catch (RuntimeException | LinkageError failure) {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin != null) plugin.getLogger().at(Level.WARNING).withCause(failure).log(
                    "Could not roll back transformed NPC: target=%s role=%s", bossId, roleId);
        }
    }

    private void notifyFailure(World world, UUID actorId) {
        WorldPlayerResolver.ResolvedPlayer resolved = WorldPlayerResolver.resolve(world, actorId);
        if (resolved != null) showFailure(resolved.player());
    }

    private void showFailure(Player player) {
        messages.showKey(player, NotificationStyle.Warning, FAILURE_MESSAGE);
    }

    private static void logFailure(Request request, Throwable failure) {
        NPCPlugin plugin = NPCPlugin.get();
        if (plugin != null) plugin.getLogger().at(Level.WARNING).withCause(failure).log(
                "Owned NPC transformation failed: target=%s role=%s", request.targetId(), request.configuration().roleId());
    }

    @Nullable
    private static Configuration parseConfiguration(InteractionEffectSpec spec) {
        if (spec.param() == null || spec.jsonPayload() == null) return null;
        try {
            JsonObject payload = JsonParser.parseString(spec.jsonPayload()).getAsJsonObject();
            String itemId = stringValue(payload, "Item");
            return itemId == null ? null : new Configuration(spec.param(), itemId, stringValue(payload, "Message"));
        } catch (RuntimeException failure) {
            return null;
        }
    }

    @Nullable
    private static String stringValue(JsonObject payload, String field) {
        JsonElement value = payload.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String text = value.getAsString().trim();
        return text.isEmpty() ? null : text;
    }

    private record Configuration(String roleId, String itemId, @Nullable String messageKey) { }
    private record Request(UUID actorId, UUID targetId, @Nullable String originalRole, Configuration configuration) { }
}
