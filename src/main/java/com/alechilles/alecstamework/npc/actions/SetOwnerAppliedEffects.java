package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Applies standalone set-owner success effects against freshly resolved mutation context. */
final class SetOwnerAppliedEffects {
    private static final double DEFAULT_PARTICLE_HEIGHT = 0.8;

    private final boolean tame;
    private final boolean consumeHeldItem;
    @Nullable
    private final String state;
    @Nullable
    private final String particleSystem;
    @Nullable
    private final String soundEventParam;
    private final InteractionParamResolver paramResolver;
    private final InteractionStateEffects stateEffects = new InteractionStateEffects();

    SetOwnerAppliedEffects(@Nonnull BuilderActionTameworkSetOwner builder,
                           @Nonnull BuilderSupport support) {
        tame = builder.isTameOnApplied();
        consumeHeldItem = builder.isConsumeHeldItemOnApplied();
        state = normalize(builder.getStateOnApplied(support));
        particleSystem = normalize(builder.getParticleSystemOnApplied(support));
        soundEventParam = normalize(builder.getSoundEventParamOnApplied(support));
        paramResolver = new InteractionParamResolver(
                InteractionRoleParameterScope.snapshot(support), null, null, null
        );
    }

    @Nullable
    String captureHeldItemId(@Nullable Player player) {
        if (!consumeHeldItem || player == null) {
            return null;
        }
        ItemStack active = PlayerInventoryAccess.getActiveHotbarItem(player);
        return active == null || active.isEmpty() ? null : active.getItemId();
    }

    void apply(@Nonnull Ref<EntityStore> npcRef,
               @Nonnull Store<EntityStore> store,
               @Nonnull UUID playerId,
               @Nullable String expectedHeldItemId) {
        Player player = resolvePlayer(store, playerId);
        Role role = resolveRole(npcRef, store);
        if (tame) {
            stateEffects.applyTameBundleAfterOwnership(npcRef, store);
        }
        applyState(npcRef, store, role);
        if (consumeHeldItem && player != null && expectedHeldItemId != null) {
            InteractionItemConsumption.removeHeldItemQuantity(player, expectedHeldItemId, 1);
        }
        applyParticles(npcRef, store);
        applySound(npcRef, store, role);
    }

    private void applyState(Ref<EntityStore> npcRef, Store<EntityStore> store, @Nullable Role role) {
        StateSupport stateSupport = NpcSupportAccess.state(role, npcRef, store);
        if (state == null || role == null || stateSupport == null) {
            return;
        }
        String[] parts = state.split("\\.", 2);
        String stateName = parts[0];
        String subState = parts.length > 1 ? parts[1] : "";
        if (!stateName.isBlank()) {
            stateSupport.setState(npcRef, stateName, subState, store);
        }
    }

    private void applyParticles(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (particleSystem == null) {
            return;
        }
        TransformComponent transform = component(npcRef, store, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        try {
            Vector3d position = new Vector3d(transform.getPosition()).add(0.0, DEFAULT_PARTICLE_HEIGHT, 0.0);
            ParticleUtil.spawnParticleEffect(particleSystem, position, store);
        } catch (RuntimeException | LinkageError ignored) {
            // Presentation is best effort after the durable ownership/tame transition.
        }
    }

    private void applySound(Ref<EntityStore> npcRef,
                            Store<EntityStore> store,
                            @Nullable Role role) {
        if (soundEventParam == null) {
            return;
        }
        String soundEvent = paramResolver.getStringParam(role, null, soundEventParam);
        TransformComponent transform = component(npcRef, store, TransformComponent.getComponentType());
        if (soundEvent == null || soundEvent.isBlank() || transform == null) {
            return;
        }
        try {
            int soundIndex = SoundEvent.getAssetMap().getIndex(soundEvent);
            if (soundIndex <= 0) {
                return;
            }
            Vector3d position = transform.getPosition();
            SoundUtil.playSoundEvent3d(
                    soundIndex,
                    SoundCategory.SFX,
                    position.x,
                    position.y,
                    position.z,
                    1.0f,
                    1.0f,
                    store
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Presentation is best effort after the durable ownership/tame transition.
        }
    }

    @Nullable
    private static Role resolveRole(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        NPCEntity npc = component(npcRef, store, NPCEntity.getComponentType());
        return npc == null ? null : npc.getRole();
    }

    @Nullable
    private static Player resolvePlayer(Store<EntityStore> store, UUID playerId) {
        if (store.getExternalData() == null || store.getExternalData().getWorld() == null) {
            return null;
        }
        Ref<EntityStore> playerRef = store.getExternalData().getWorld().getEntityRef(playerId);
        return playerRef == null || !playerRef.isValid()
                ? null : component(playerRef, store, Player.getComponentType());
    }

    @Nullable
    private static <T extends com.hypixel.hytale.component.Component<EntityStore>> T component(
            Ref<EntityStore> ref,
            Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, T> type
    ) {
        return type == null ? null : store.getComponent(ref, type);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
