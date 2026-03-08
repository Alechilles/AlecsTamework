package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParticleAttachTarget;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FloatingTextEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.PlaySoundEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SpawnParticlesEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.UiMessageEffect;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.CombatTextUpdate;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.entities.SpawnModelParticles;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Handles presentation-facing effects like UI messages, sounds, particles, and combat text. */
final class InteractionPresentationEffects {
    private final InteractionUiMessageService uiMessageService = new InteractionUiMessageService();
    private final InteractionParticleSpawnResolver particleSpawnResolver;

    InteractionPresentationEffects(ActionTameworkInteract owner) {
        this.particleSpawnResolver = new InteractionParticleSpawnResolver(owner);
    }

    // Shows floating combat text above the NPC for a feeding heal.
    void showFeedingCombatText(Ref<EntityStore> npcRef,
                               Store<EntityStore> store,
                               Player player,
                               double healAmount) {
        if (npcRef == null || store == null || player == null) {
            return;
        }
        if (healAmount <= 0) {
            return;
        }
        String text = formatHealText(healAmount);
        if (text == null || text.isBlank()) {
            return;
        }
        queueCombatText(npcRef, store, player, text);
    }

    // Plays a sound effect near the NPC or directly to the player.
    boolean applyPlaySound(PlaySoundEffect effect,
                           Ref<EntityStore> npcRef,
                           Store<EntityStore> store,
                           Player player) {
        if (effect == null || npcRef == null || store == null) {
            return false;
        }
        String soundEvent = effect.getSoundEvent();
        if (soundEvent == null || soundEvent.isBlank()) {
            return false;
        }
        int soundIndex = SoundEvent.getAssetMap().getIndex(soundEvent);
        if (soundIndex <= 0) {
            return false;
        }
        Vector3d position = resolveNpcPosition(npcRef, store, effect.getOffset());
        if (position == null) {
            return false;
        }
        float volume = effect.getVolume() != null ? effect.getVolume().floatValue() : 1.0f;
        float pitch = effect.getPitch() != null ? effect.getPitch().floatValue() : 1.0f;
        if (effect.isPlayerOnly()) {
            if (player == null) {
                return false;
            }
            Ref<EntityStore> playerRef = player.getReference();
            if (playerRef == null || !playerRef.isValid()) {
                return false;
            }
            SoundUtil.playSoundEvent3dToPlayer(
                    playerRef,
                    soundIndex,
                    SoundCategory.SFX,
                    position.x,
                    position.y,
                    position.z,
                    volume,
                    pitch,
                    store
            );
        } else {
            SoundUtil.playSoundEvent3d(
                    soundIndex,
                    SoundCategory.SFX,
                    position.x,
                    position.y,
                    position.z,
                    volume,
                    pitch,
                    store
            );
        }
        return true;
    }

    // Spawns a particle system at the NPC (optionally scoped to the player).
    boolean applySpawnParticles(SpawnParticlesEffect effect,
                                Ref<EntityStore> npcRef,
                                Role role,
                                Store<EntityStore> store,
                                Player player,
                                InteractionContextSnapshot ctx) {
        if (effect == null || npcRef == null || store == null) {
            return false;
        }
        InteractionParticleSpawnResolver.ResolvedParticleSpawn resolved = particleSpawnResolver.resolve(
                effect,
                npcRef,
                role,
                store,
                ctx
        );
        if (resolved == null) {
            return false;
        }
        String particleSystem = resolved.particleSystem;
        Vector3d position = resolved.position;
        if (resolved.attachTarget != ParticleAttachTarget.Position) {
            return applySpawnModelParticles(effect, npcRef, store, player, resolved);
        }
        boolean attachToNpc = resolved.attachToNpc;
        Color color = effect.getColor();
        if (effect.isPlayerOnly()) {
            if (player == null) {
                return false;
            }
            Ref<EntityStore> playerRef = player.getReference();
            if (playerRef == null || !playerRef.isValid()) {
                return false;
            }
            if (color != null) {
                if (attachToNpc) {
                    ParticleUtil.spawnParticleEffect(
                            particleSystem,
                            position.x,
                            position.y,
                            position.z,
                            0f,
                            0f,
                            0f,
                            1f,
                            color,
                            npcRef,
                            Collections.singletonList(playerRef),
                            store
                    );
                } else {
                    ParticleUtil.spawnParticleEffect(
                            particleSystem,
                            position,
                            0f,
                            0f,
                            0f,
                            1f,
                            color,
                            Collections.singletonList(playerRef),
                            store
                    );
                }
            } else {
                if (attachToNpc) {
                    ParticleUtil.spawnParticleEffect(
                            particleSystem,
                            position,
                            npcRef,
                            Collections.singletonList(playerRef),
                            store
                    );
                } else {
                    ParticleUtil.spawnParticleEffect(
                            particleSystem,
                            position,
                            Collections.singletonList(playerRef),
                            store
                    );
                }
            }
        } else {
            if (color != null) {
                List<Ref<EntityStore>> viewers = resolveViewerRefs(player);
                if (viewers.isEmpty()) {
                    ParticleUtil.spawnParticleEffect(particleSystem, position, store);
                } else {
                    if (attachToNpc) {
                        ParticleUtil.spawnParticleEffect(
                                particleSystem,
                                position.x,
                                position.y,
                                position.z,
                                0f,
                                0f,
                                0f,
                                1f,
                                color,
                                npcRef,
                                viewers,
                                store
                        );
                    } else {
                        ParticleUtil.spawnParticleEffect(
                                particleSystem,
                                position,
                                0f,
                                0f,
                                0f,
                                1f,
                                color,
                                viewers,
                                store
                        );
                    }
                }
            } else {
                if (attachToNpc) {
                    List<Ref<EntityStore>> viewers = resolveViewerRefs(player);
                    if (viewers.isEmpty()) {
                        ParticleUtil.spawnParticleEffect(particleSystem, position, store);
                    } else {
                        ParticleUtil.spawnParticleEffect(
                                particleSystem,
                                position,
                                npcRef,
                                viewers,
                                store
                        );
                    }
                } else {
                    ParticleUtil.spawnParticleEffect(particleSystem, position, store);
                }
            }
        }
        return true;
    }

    // Uses the same model-particle packet path as vanilla ActionSpawnParticles for entity/node attachment.
    private boolean applySpawnModelParticles(SpawnParticlesEffect effect,
                                             Ref<EntityStore> npcRef,
                                             Store<EntityStore> store,
                                             Player player,
                                             InteractionParticleSpawnResolver.ResolvedParticleSpawn resolved) {
        NetworkId networkId = store.getComponent(npcRef, NetworkId.getComponentType());
        if (networkId == null) {
            return false;
        }

        List<Ref<EntityStore>> viewers = resolveModelParticleViewers(effect, player, resolved.position, store);
        if (viewers.isEmpty()) {
            return false;
        }

        com.hypixel.hytale.server.core.asset.type.model.config.ModelParticle modelParticle =
                new com.hypixel.hytale.server.core.asset.type.model.config.ModelParticle();
        modelParticle.setSystemId(resolved.particleSystem);
        Vector3d offset = resolved.offset != null ? resolved.offset : new Vector3d(0.0, 0.0, 0.0);
        modelParticle.setPositionOffset(new Vector3f((float) offset.x, (float) offset.y, (float) offset.z));
        if (resolved.attachTarget == ParticleAttachTarget.Node
                && resolved.attachNode != null
                && !resolved.attachNode.isBlank()) {
            modelParticle.setTargetNodeName(resolved.attachNode);
        }
        com.hypixel.hytale.protocol.ModelParticle packetParticle = modelParticle.toPacket();
        if (effect.getColor() != null) {
            packetParticle.color = effect.getColor();
        }
        com.hypixel.hytale.protocol.ModelParticle[] modelParticles =
                new com.hypixel.hytale.protocol.ModelParticle[]{packetParticle};
        SpawnModelParticles packet = new SpawnModelParticles(networkId.getId(), modelParticles);
        boolean sent = false;
        for (Ref<EntityStore> viewerRef : viewers) {
            if (viewerRef == null || !viewerRef.isValid()) {
                continue;
            }
            PlayerRef playerRefComponent = store.getComponent(viewerRef, PlayerRef.getComponentType());
            if (playerRefComponent == null) {
                continue;
            }
            playerRefComponent.getPacketHandler().write(packet);
            sent = true;
        }
        return sent;
    }

    // Shows floating combat text with a custom message.
    boolean applyFloatingText(FloatingTextEffect effect,
                              Ref<EntityStore> npcRef,
                              Store<EntityStore> store,
                              Player player) {
        if (effect == null) {
            return false;
        }
        return applyMessageText(effect.getMessage(), npcRef, store, player);
    }

    // Shows a floating combat text message directly.
    boolean showFloatingTextMessage(String message,
                                    Ref<EntityStore> npcRef,
                                    Store<EntityStore> store,
                                    Player player) {
        return applyMessageText(message, npcRef, store, player);
    }

    // Shows the custom UI message to the interacting player.
    boolean applyUiMessage(UiMessageEffect effect, Player player) {
        if (effect == null) {
            return false;
        }
        return applyUiMessage(effect.getMessage(), player);
    }

    // Shows a custom UI message string to the interacting player.
    boolean applyUiMessage(String message, Player player) {
        return uiMessageService.show(player, message);
    }

    // Sends a combat text update with the supplied message.
    private boolean applyMessageText(String message,
                                     Ref<EntityStore> npcRef,
                                     Store<EntityStore> store,
                                     Player player) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (npcRef == null || store == null || player == null) {
            return false;
        }
        // Size/Duration/Color are placeholders for now; CombatText uses the global UI asset.
        return queueCombatText(npcRef, store, player, message);
    }

    // Resolves the NPC world position including a configured offset.
    private Vector3d resolveNpcPosition(Ref<EntityStore> npcRef,
                                        Store<EntityStore> store,
                                        Vector3d offset) {
        if (npcRef == null || store == null) {
            return null;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        if (offset != null) {
            position.x += offset.x;
            position.y += offset.y;
            position.z += offset.z;
        }
        return position;
    }

    // Resolves viewer refs when spawning player-scoped particles.
    private List<Ref<EntityStore>> resolveViewerRefs(Player player) {
        if (player == null || player.getWorld() == null) {
            return List.of();
        }
        List<Ref<EntityStore>> refs = new ArrayList<>();
        for (PlayerRef playerRef : player.getWorld().getPlayerRefs()) {
            if (playerRef == null) {
                continue;
            }
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                refs.add(ref);
            }
        }
        return refs;
    }

    // Resolves packet recipients for model-bound particle spawns.
    private List<Ref<EntityStore>> resolveModelParticleViewers(SpawnParticlesEffect effect,
                                                               Player player,
                                                               Vector3d position,
                                                               Store<EntityStore> store) {
        if (effect.isPlayerOnly()) {
            if (player == null) {
                return List.of();
            }
            Ref<EntityStore> playerRef = player.getReference();
            if (playerRef == null || !playerRef.isValid()) {
                return List.of();
            }
            return Collections.singletonList(playerRef);
        }

        List<Ref<EntityStore>> refs = resolveViewerRefs(player);
        if (!refs.isEmpty()) {
            return refs;
        }

        if (position == null || store == null) {
            return List.of();
        }
        SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource =
                store.getResource(EntityModule.get().getPlayerSpatialResourceType());
        if (playerSpatialResource == null) {
            return List.of();
        }

        ObjectList<Ref<EntityStore>> results = SpatialResource.getThreadLocalReferenceList();
        playerSpatialResource.getSpatialStructure().collect(position, ParticleUtil.DEFAULT_PARTICLE_DISTANCE, results);
        if (results.isEmpty()) {
            return List.of();
        }
        List<Ref<EntityStore>> collected = new ArrayList<>(results.size());
        for (Ref<EntityStore> result : results) {
            if (result != null && result.isValid()) {
                collected.add(result);
            }
        }
        return collected;
    }

    // Queues a combat text update for the NPC to the target player.
    private boolean queueCombatText(Ref<EntityStore> npcRef,
                                    Store<EntityStore> store,
                                    Player player,
                                    String text) {
        if (npcRef == null || store == null || player == null || text == null || text.isBlank()) {
            return false;
        }
        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        EntityTrackerSystems.EntityViewer viewer = store.getComponent(
                playerRef,
                EntityTrackerSystems.EntityViewer.getComponentType()
        );
        if (viewer == null) {
            return false;
        }
        try {
            CombatTextUpdate combatTextUpdate = new CombatTextUpdate();
            combatTextUpdate.hitAngleDeg = 0.0f;
            combatTextUpdate.text = text;
            if (invokeQueueUpdate(viewer, npcRef, combatTextUpdate)) {
                return true;
            }
            return invokeLegacyCombatTextUpdate(viewer, npcRef, combatTextUpdate);
        } catch (Exception | LinkageError ex) {
            return false;
        }
    }

    // Supports old protocol shapes where CombatText had to be wrapped inside ComponentUpdate.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean invokeLegacyCombatTextUpdate(EntityTrackerSystems.EntityViewer viewer,
                                                        Ref<EntityStore> npcRef,
                                                        CombatTextUpdate combatTextUpdate) {
        try {
            Class<?> componentUpdateClass = Class.forName("com.hypixel.hytale.protocol.ComponentUpdate");
            if (Modifier.isAbstract(componentUpdateClass.getModifiers())) {
                return false;
            }
            Object legacyUpdate = componentUpdateClass.getDeclaredConstructor().newInstance();

            Class<?> componentUpdateTypeClass = Class.forName("com.hypixel.hytale.protocol.ComponentUpdateType");
            if (!componentUpdateTypeClass.isEnum()) {
                return false;
            }
            Object combatTextType = Enum.valueOf(
                    (Class<? extends Enum>) componentUpdateTypeClass.asSubclass(Enum.class),
                    "CombatText"
            );

            Field typeField = componentUpdateClass.getField("type");
            typeField.set(legacyUpdate, combatTextType);
            Field combatTextField = componentUpdateClass.getField("combatTextUpdate");
            combatTextField.set(legacyUpdate, combatTextUpdate);

            return invokeQueueUpdate(viewer, npcRef, legacyUpdate);
        } catch (Exception | LinkageError ex) {
            return false;
        }
    }

    private static boolean invokeQueueUpdate(EntityTrackerSystems.EntityViewer viewer,
                                             Ref<EntityStore> npcRef,
                                             Object update) {
        if (viewer == null || npcRef == null || update == null) {
            return false;
        }
        try {
            Method method = viewer.getClass().getMethod("queueUpdate", Ref.class, update.getClass());
            method.invoke(viewer, npcRef, update);
            return true;
        } catch (NoSuchMethodException ignored) {
            // Fallback: find any compatible queueUpdate overload.
            for (Method method : viewer.getClass().getMethods()) {
                if (!"queueUpdate".equals(method.getName()) || method.getParameterCount() != 2) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (!params[0].isInstance(npcRef)) {
                    continue;
                }
                if (!params[1].isInstance(update)) {
                    continue;
                }
                try {
                    method.invoke(viewer, npcRef, update);
                    return true;
                } catch (Exception | LinkageError ex) {
                    return false;
                }
            }
            return false;
        } catch (Exception | LinkageError ex) {
            return false;
        }
    }

    // Formats a healing value for floating combat text.
    private String formatHealText(double healAmount) {
        if (healAmount <= 0) {
            return null;
        }
        double rounded = Math.round(healAmount);
        if (Math.abs(healAmount - rounded) < 0.01) {
            return "+" + (int) rounded + " HP";
        }
        return String.format(Locale.US, "+%.1f HP", healAmount);
    }
}
