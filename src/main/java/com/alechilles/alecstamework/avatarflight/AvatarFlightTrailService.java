package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightTrailSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ModelTrail;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.InteractionEffects;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies interaction-authored trail definitions directly to the synchronized avatar model.
 *
 * <p>Interaction execution does not reliably clear trails rendered on a transformed player
 * model. Replacing {@link ModelComponent} gives every trail condition an explicit model-state
 * start and stop transition while keeping the trail assets configurable.</p>
 */
public final class AvatarFlightTrailService {
    private static final int FAST_GLIDE_ACTIVE = 1;
    private static final long MINIMUM_BURST_DURATION_MS = 50L;

    private final Map<String, CachedDefinition> definitionCache = new HashMap<>();

    public void tick(@Nonnull AvatarFlightComponent flight,
                     @Nonnull AvatarFlightController.Output output,
                     @Nonnull TwAvatarFlightConfig config,
                     long now,
                     @Nonnull Ref<EntityStore> ref,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        AvatarFlightTrailSettings settings = config.getTrails();
        TrailDefinitions definitions = resolveDefinitions(settings);
        if (!settings.isEnabled()) {
            flight.setFastGlideTrailChainId(0);
            flight.setBurstTrailUntilMs(0L);
            render(flight, definitions, Definition.EMPTY, ref, commandBuffer);
            return;
        }

        Definition burst = triggeredBurst(output, definitions);
        if (burst.hasTrails()) {
            flight.setBurstTrailUntilMs(now + burst.durationMs());
        }

        boolean fastGlideRunning = flight.getFastGlideTrailChainId() != 0;
        double horizontalSpeed = AvatarFlightSpeedMetrics.horizontalSpeed(
                output.velocityX(), output.velocityY(), output.velocityZ());
        boolean fastGlideDesired = output.applyVelocity()
                && definitions.fastGlide().hasTrails()
                && AvatarFlightTrailPolicy.shouldRunFastGlideTrail(
                        fastGlideRunning,
                        horizontalSpeed,
                        AvatarFlightSpeedMetrics.glideHorizontalCap(config),
                        settings
                );
        flight.setFastGlideTrailChainId(fastGlideDesired ? FAST_GLIDE_ACTIVE : 0);

        Definition desired = desiredDefinition(flight, burst, definitions, now, fastGlideDesired);
        render(flight, definitions, desired, ref, commandBuffer);
    }

    /** Removes every avatar-flight trail before the flight model is restored. */
    public static void stopFastGlideTrail(@Nullable AvatarFlightComponent flight,
                                          @Nonnull Ref<EntityStore> ref,
                                          @Nonnull Store<EntityStore> store) {
        if (flight == null) return;
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(flight.getConfigId());
        AvatarFlightTrailService service = new AvatarFlightTrailService();
        TrailDefinitions definitions = service.resolveDefinitions(config.getTrails());
        ModelComponent modelComponent = store.getComponent(ref, ModelComponent.getComponentType());
        if (modelComponent != null && modelComponent.getModel() != null) {
            Model updated = AvatarFlightModelTrailComposer.withTrails(
                    modelComponent.getModel(), definitions.managedGroups(), null);
            store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(updated));
        }
        flight.setFastGlideTrailChainId(0);
        flight.setBurstTrailUntilMs(0L);
        flight.setActiveTrailRootInteraction("");
    }

    @Nonnull
    private static Definition triggeredBurst(@Nonnull AvatarFlightController.Output output,
                                               @Nonnull TrailDefinitions definitions) {
        Definition triggered = Definition.EMPTY;
        if (output.launchApplied()) triggered = definitions.launch();
        if (output.jumpApplied()) triggered = definitions.flap();
        if (output.boostApplied()) triggered = definitions.boost();
        return triggered;
    }

    @Nonnull
    private Definition desiredDefinition(@Nonnull AvatarFlightComponent flight,
                                         @Nonnull Definition triggeredBurst,
                                         @Nonnull TrailDefinitions definitions,
                                         long now,
                                         boolean fastGlideDesired) {
        if (triggeredBurst.hasTrails()) return triggeredBurst;

        long burstUntil = flight.getBurstTrailUntilMs();
        if (burstUntil != 0L && now < burstUntil) {
            Definition activeBurst = definitions.byRootId(flight.getActiveTrailRootInteraction());
            if (activeBurst.hasTrails() && !activeBurst.equals(definitions.fastGlide())) {
                return activeBurst;
            }
        }

        flight.setBurstTrailUntilMs(0L);
        return fastGlideDesired ? definitions.fastGlide() : Definition.EMPTY;
    }

    private static void render(@Nonnull AvatarFlightComponent flight,
                               @Nonnull TrailDefinitions definitions,
                               @Nonnull Definition desired,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (desired.rootId().equals(flight.getActiveTrailRootInteraction())) return;

        ModelComponent modelComponent = commandBuffer.getComponent(
                ref, ModelComponent.getComponentType());
        if (modelComponent == null || modelComponent.getModel() == null) {
            flight.setActiveTrailRootInteraction("");
            return;
        }

        Model updated = AvatarFlightModelTrailComposer.withTrails(
                modelComponent.getModel(), definitions.managedGroups(), desired.trails());
        commandBuffer.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(updated));
        flight.setActiveTrailRootInteraction(desired.rootId());
    }

    @Nonnull
    private TrailDefinitions resolveDefinitions(@Nonnull AvatarFlightTrailSettings settings) {
        return new TrailDefinitions(
                resolve(settings.getLaunchRootInteraction()),
                resolve(settings.getFlapRootInteraction()),
                resolve(settings.getBoostRootInteraction()),
                resolve(settings.getFastGlideRootInteraction())
        );
    }

    @Nonnull
    private Definition resolve(@Nonnull String rootId) {
        if (rootId.isBlank()) return Definition.EMPTY;
        RootInteraction root = RootInteraction.getAssetMap().getAsset(rootId);
        if (root == null) return Definition.EMPTY;

        CachedDefinition cached = definitionCache.get(rootId);
        if (cached != null && cached.root() == root) return cached.definition();

        List<ModelTrail> trails = new ArrayList<>();
        float durationSeconds = 0.0F;
        String[] interactionIds = root.getInteractionIds();
        if (interactionIds != null) {
            for (String interactionId : interactionIds) {
                Interaction interaction = Interaction.getAssetMap().getAsset(interactionId);
                if (interaction == null) continue;
                durationSeconds = Math.max(durationSeconds, interaction.getRunTime());
                InteractionEffects effects = interaction.getEffects();
                if (effects == null || effects.getTrails() == null) continue;
                for (ModelTrail trail : effects.getTrails()) {
                    if (trail != null) trails.add(new ModelTrail(trail));
                }
            }
        }

        long durationMs = Math.max(
                MINIMUM_BURST_DURATION_MS, Math.round(durationSeconds * 1000.0F));
        Definition definition = new Definition(
                rootId, trails.toArray(ModelTrail[]::new), durationMs);
        definitionCache.put(rootId, new CachedDefinition(root, definition));
        return definition;
    }

    private record CachedDefinition(@Nonnull RootInteraction root,
                                    @Nonnull Definition definition) {
    }

    private record Definition(@Nonnull String rootId,
                              @Nonnull ModelTrail[] trails,
                              long durationMs) {
        private static final Definition EMPTY = new Definition("", new ModelTrail[0], 0L);

        boolean hasTrails() {
            return trails.length > 0;
        }
    }

    private record TrailDefinitions(@Nonnull Definition launch,
                                    @Nonnull Definition flap,
                                    @Nonnull Definition boost,
                                    @Nonnull Definition fastGlide) {
        @Nonnull
        ModelTrail[][] managedGroups() {
            return new ModelTrail[][]{
                    launch.trails(), flap.trails(), boost.trails(), fastGlide.trails()
            };
        }

        @Nonnull
        Definition byRootId(@Nonnull String rootId) {
            if (launch.rootId().equals(rootId)) return launch;
            if (flap.rootId().equals(rootId)) return flap;
            if (boost.rootId().equals(rootId)) return boost;
            if (fastGlide.rootId().equals(rootId)) return fastGlide;
            return Definition.EMPTY;
        }
    }
}
