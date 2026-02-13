package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FloatingTextEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.PlaySoundEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SpawnParticlesEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.UiMessageEffect;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.CombatTextUpdate;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.ComponentUpdate;
import com.hypixel.hytale.protocol.ComponentUpdateType;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

// Handles presentation-facing effects like UI messages, sounds, particles, and combat text.
final class InteractionPresentationEffects {
    private final InteractionUiMessageService uiMessageService = new InteractionUiMessageService();

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
                                Store<EntityStore> store,
                                Player player) {
        if (effect == null || npcRef == null || store == null) {
            return false;
        }
        String particleSystem = effect.getParticleSystem();
        if (particleSystem == null || particleSystem.isBlank()) {
            return false;
        }
        Vector3d position = resolveNpcPosition(npcRef, store, effect.getOffset());
        if (position == null) {
            return false;
        }
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
            } else {
                ParticleUtil.spawnParticleEffect(
                        particleSystem,
                        position,
                        Collections.singletonList(playerRef),
                        store
                );
            }
        } else {
            if (color != null) {
                List<Ref<EntityStore>> viewers = resolveViewerRefs(player);
                if (viewers.isEmpty()) {
                    ParticleUtil.spawnParticleEffect(particleSystem, position, store);
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
            } else {
                ParticleUtil.spawnParticleEffect(particleSystem, position, store);
            }
        }
        return true;
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
        ComponentUpdate update = new ComponentUpdate();
        update.type = ComponentUpdateType.CombatText;
        CombatTextUpdate combatTextUpdate = new CombatTextUpdate();
        combatTextUpdate.hitAngleDeg = 0.0f;
        combatTextUpdate.text = text;
        update.combatTextUpdate = combatTextUpdate;
        viewer.queueUpdate(npcRef, update);
        return true;
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
