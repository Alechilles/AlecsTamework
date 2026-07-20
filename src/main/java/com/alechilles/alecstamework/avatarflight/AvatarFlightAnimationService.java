package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.AvatarFlightAbilityAnimationSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns movement, pose, suppression, and one-shot ability animations for avatar flight.
 */
final class AvatarFlightAnimationService {
    private static final String GROUNDED_IDLE_ANIMATION = "Idle";
    private final Set<String> warnedMissingAnimations = new HashSet<>();

    boolean hasOverrides(@Nonnull AvatarFlightComponent flight) {
        return !flight.getMovementAnimationId().isBlank()
                || !flight.getPitchPoseAnimationId().isBlank()
                || !flight.getRollPoseAnimationId().isBlank()
                || !flight.getAbilityAnimationId().isBlank();
    }

    static boolean shouldSuppressPlayerOverlayAnimations(@Nonnull TwAvatarFlightConfig config,
                                                          boolean applyingVelocity,
                                                          boolean hasFlightVisualOverrides) {
        if (!applyingVelocity && !hasFlightVisualOverrides) {
            return false;
        }
        return config.getAnimation().isSuppressNonMovementAnimations();
    }

    void tick(@Nonnull Ref<EntityStore> ref,
              @Nonnull CommandBuffer<EntityStore> commandBuffer,
              @Nonnull AvatarFlightComponent flight,
              @Nonnull TwAvatarFlightConfig config,
              @Nonnull AvatarFlightController.Output output,
              boolean applyingVelocity,
              boolean suppressingOverlays,
              boolean groundedMovementIntent,
              boolean nativeSwimming,
              long now) {
        boolean hadAnimationOverrides = hasOverrides(flight);
        expireAbilityAnimation(ref, commandBuffer, flight, now);
        suppressPlayerOverlayAnimations(ref, commandBuffer, flight, config, suppressingOverlays, now);
        if (!applyingVelocity) {
            if (nativeSwimming) {
                clear(ref, commandBuffer, flight, config);
                return;
            }
            if (isGroundedIdleHandoffActive(flight)) {
                if (groundedMovementIntent) {
                    clearMovementAnimation(ref, commandBuffer, flight);
                } else {
                    maintainGroundedIdle(ref, commandBuffer, flight, config, now);
                }
                return;
            }
            if (needsGroundedIdleHandoff(output.mode(), hadAnimationOverrides)) {
                handoffToGroundedIdle(ref, commandBuffer, flight, config, now);
            } else {
                clear(ref, commandBuffer, flight, config);
            }
            return;
        }
        applyPoseAnimations(ref, commandBuffer, flight, config, output, now);
        applyMovementAnimation(ref, commandBuffer, flight, config, output, now);
        applyAcceptedAbilityAnimation(ref, commandBuffer, flight, config, output, now);
    }

    void clear(@Nonnull Ref<EntityStore> ref,
               @Nonnull CommandBuffer<EntityStore> commandBuffer,
               @Nonnull AvatarFlightComponent flight,
               @Nonnull TwAvatarFlightConfig config) {
        clearAbilityAnimation(ref, commandBuffer, flight);
        clearMovementAnimation(ref, commandBuffer, flight);
        clearPoseAnimations(ref, commandBuffer, flight, config);
    }

    static boolean isAbilityAnimationProtected(@Nonnull AvatarFlightComponent flight, long now) {
        return !flight.getAbilityAnimationId().isBlank() && now < flight.getAbilityAnimationUntilMs();
    }

    static boolean doesAbilityOwnSlot(@Nonnull AvatarFlightComponent flight,
                                      @Nonnull AnimationSlot slot,
                                      long now) {
        return isAbilityAnimationProtected(flight, now)
                && resolveAnimationSlot(flight.getAbilityAnimationSlot(), AnimationSlot.Action) == slot;
    }

    static boolean needsGroundedIdleHandoff(@Nonnull AvatarFlightMode mode,
                                            boolean hasAnimationOverrides) {
        return mode == AvatarFlightMode.GROUNDED && hasAnimationOverrides;
    }

    static boolean isGroundedIdleHandoffActive(@Nonnull AvatarFlightComponent flight) {
        return GROUNDED_IDLE_ANIMATION.equals(flight.getMovementAnimationId());
    }

    private void handoffToGroundedIdle(@Nonnull Ref<EntityStore> ref,
                                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                       @Nonnull AvatarFlightComponent flight,
                                       @Nonnull TwAvatarFlightConfig config,
                                       long now) {
        clearAbilityAnimation(ref, commandBuffer, flight);
        clearPoseAnimations(ref, commandBuffer, flight, config);
        ModelComponent component = commandBuffer.getComponent(ref, ModelComponent.getComponentType());
        Model model = component == null ? null : component.getModel();
        if (model == null
                || model.getAnimationSetMap() == null
                || !model.getAnimationSetMap().containsKey(GROUNDED_IDLE_ANIMATION)) {
            clearMovementAnimation(ref, commandBuffer, flight);
            return;
        }
        AnimationUtils.playAnimation(
                ref, AnimationSlot.Movement, GROUNDED_IDLE_ANIMATION, true, commandBuffer);
        // Keep ownership until movement input arrives so the explicit idle can be stopped once.
        flight.setMovementAnimationId(GROUNDED_IDLE_ANIMATION);
        flight.setNextMovementAnimationAtMs(nextMovementAnimationAt(
                now, config.getAnimation().getResendIntervalMs()));
    }

    private void maintainGroundedIdle(@Nonnull Ref<EntityStore> ref,
                                      @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                      @Nonnull AvatarFlightComponent flight,
                                      @Nonnull TwAvatarFlightConfig config,
                                      long now) {
        long resendIntervalMs = config.getAnimation().getResendIntervalMs();
        if (!isMovementAnimationResendDue(
                now, flight.getNextMovementAnimationAtMs(), resendIntervalMs)) {
            return;
        }
        AnimationUtils.playAnimation(
                ref, AnimationSlot.Movement, GROUNDED_IDLE_ANIMATION, true, commandBuffer);
        flight.setNextMovementAnimationAtMs(nextMovementAnimationAt(now, resendIntervalMs));
    }

    private void applyMovementAnimation(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                        @Nonnull AvatarFlightComponent flight,
                                        @Nonnull TwAvatarFlightConfig config,
                                        @Nonnull AvatarFlightController.Output output,
                                        long now) {
        if (doesAbilityOwnSlot(flight, AnimationSlot.Movement, now)) {
            return;
        }
        String animationId = config.getAnimation().animationFor(output.horizontalIdle(), output.fastFlight());
        long resendIntervalMs = config.getAnimation().getResendIntervalMs();
        boolean sameAnimation = animationId.equals(flight.getMovementAnimationId());
        if (sameAnimation && !isMovementAnimationResendDue(
                now, flight.getNextMovementAnimationAtMs(), resendIntervalMs)) {
            return;
        }
        AnimationUtils.playAnimation(ref, AnimationSlot.Movement, animationId, true, commandBuffer);
        flight.setMovementAnimationId(animationId);
        flight.setNextMovementAnimationAtMs(nextMovementAnimationAt(now, resendIntervalMs));
    }

    static boolean isMovementAnimationResendDue(long now, long nextAt, long resendIntervalMs) {
        return resendIntervalMs > 0L && nextAt > 0L && now >= nextAt;
    }

    static long nextMovementAnimationAt(long now, long resendIntervalMs) {
        return resendIntervalMs > 0L ? now + resendIntervalMs : 0L;
    }

    private void clearMovementAnimation(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                        @Nonnull AvatarFlightComponent flight) {
        if (flight.getMovementAnimationId().isBlank()) {
            return;
        }
        AnimationUtils.stopAnimation(ref, AnimationSlot.Movement, true, commandBuffer);
        flight.setMovementAnimationId("");
        flight.setNextMovementAnimationAtMs(0L);
    }

    private void suppressPlayerOverlayAnimations(@Nonnull Ref<EntityStore> ref,
                                                 @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                 @Nonnull AvatarFlightComponent flight,
                                                 @Nonnull TwAvatarFlightConfig config,
                                                 boolean suppressingOverlays,
                                                 long now) {
        if (!suppressingOverlays || now < flight.getNextSuppressedAnimationAtMs()) {
            return;
        }
        TwAvatarFlightConfig.AnimationSettings animation = config.getAnimation();
        AnimationSlot pitchSlot = animation.isPoseAnimationsEnabled()
                ? resolveAnimationSlot(animation.getPitchPoseSlot(), AnimationSlot.Status) : null;
        AnimationSlot rollSlot = animation.isPoseAnimationsEnabled()
                ? resolveAnimationSlot(animation.getRollPoseSlot(), AnimationSlot.Emote) : null;
        if (animation.isSuppressActionAnimation()
                && !isPoseSlot(AnimationSlot.Action, pitchSlot, rollSlot)
                && !doesAbilityOwnSlot(flight, AnimationSlot.Action, now)) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Action, true, commandBuffer);
        }
        if (animation.isSuppressStatusAnimation() && !isPoseSlot(AnimationSlot.Status, pitchSlot, rollSlot)) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, commandBuffer);
        }
        if (animation.isSuppressEmoteAnimation() && !isPoseSlot(AnimationSlot.Emote, pitchSlot, rollSlot)) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Emote, true, commandBuffer);
        }
        if (animation.isSuppressFaceAnimation() && !isPoseSlot(AnimationSlot.Face, pitchSlot, rollSlot)) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Face, true, commandBuffer);
        }
        flight.setNextSuppressedAnimationAtMs(now + animation.getSuppressionIntervalMs());
    }

    private void applyPoseAnimations(@Nonnull Ref<EntityStore> ref,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull AvatarFlightComponent flight,
                                     @Nonnull TwAvatarFlightConfig config,
                                     @Nonnull AvatarFlightController.Output output,
                                     long now) {
        TwAvatarFlightConfig.AnimationSettings animation = config.getAnimation();
        AnimationSlot pitchSlot = resolveAnimationSlot(animation.getPitchPoseSlot(), AnimationSlot.Status);
        AnimationSlot rollSlot = resolveAnimationSlot(animation.getRollPoseSlot(), AnimationSlot.Emote);
        if (!animation.isPoseAnimationsEnabled()) {
            clearPoseAnimation(ref, commandBuffer, flight, true, pitchSlot);
            clearPoseAnimation(ref, commandBuffer, flight, false, rollSlot);
            return;
        }
        if (pitchSlot == rollSlot) {
            drivePoseAnimation(ref, commandBuffer, flight, true, pitchSlot,
                    AvatarFlightPoseAnimationCatalog.sharedPoseAnimationFor(
                            animation,
                            Math.toDegrees(output.visualPitchRadians()),
                            Math.toDegrees(output.visualRollRadians())
                    ), now, animation.getPoseResendIntervalMs());
            flight.setRollPoseAnimationId("");
            flight.setNextRollPoseAnimationAtMs(0L);
            return;
        }
        drivePoseAnimation(ref, commandBuffer, flight, true, pitchSlot,
                AvatarFlightPoseAnimationCatalog.pitchPoseAnimationFor(
                        animation, Math.toDegrees(output.visualPitchRadians())),
                now, animation.getPoseResendIntervalMs());
        drivePoseAnimation(ref, commandBuffer, flight, false, rollSlot,
                AvatarFlightPoseAnimationCatalog.rollPoseAnimationFor(
                        animation, Math.toDegrees(output.visualRollRadians())),
                now, animation.getPoseResendIntervalMs());
    }

    private void drivePoseAnimation(@Nonnull Ref<EntityStore> ref,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull AvatarFlightComponent flight,
                                    boolean pitch,
                                    @Nonnull AnimationSlot slot,
                                    @Nonnull String animationId,
                                    long now,
                                    long resendIntervalMs) {
        if (doesAbilityOwnSlot(flight, slot, now)) {
            setPoseAnimationNextAt(flight, pitch, 0L);
            return;
        }
        String current = pitch ? flight.getPitchPoseAnimationId() : flight.getRollPoseAnimationId();
        long nextAt = pitch ? flight.getNextPitchPoseAnimationAtMs() : flight.getNextRollPoseAnimationAtMs();
        if (animationId.isBlank()) {
            clearPoseAnimation(ref, commandBuffer, flight, pitch, slot);
            return;
        }
        if (animationId.equals(current) && now < nextAt) {
            return;
        }
        AnimationUtils.playAnimation(ref, slot, animationId, true, commandBuffer);
        setPoseAnimationState(flight, pitch, animationId, now + resendIntervalMs);
    }

    private void clearPoseAnimations(@Nonnull Ref<EntityStore> ref,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull AvatarFlightComponent flight,
                                     @Nonnull TwAvatarFlightConfig config) {
        TwAvatarFlightConfig.AnimationSettings animation = config.getAnimation();
        clearPoseAnimation(ref, commandBuffer, flight, true,
                resolveAnimationSlot(animation.getPitchPoseSlot(), AnimationSlot.Status));
        clearPoseAnimation(ref, commandBuffer, flight, false,
                resolveAnimationSlot(animation.getRollPoseSlot(), AnimationSlot.Emote));
    }

    private void clearPoseAnimation(@Nonnull Ref<EntityStore> ref,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull AvatarFlightComponent flight,
                                    boolean pitch,
                                    @Nonnull AnimationSlot slot) {
        String current = pitch ? flight.getPitchPoseAnimationId() : flight.getRollPoseAnimationId();
        if (current.isBlank()) {
            return;
        }
        AnimationUtils.stopAnimation(ref, slot, true, commandBuffer);
        setPoseAnimationState(flight, pitch, "", 0L);
    }

    private void applyAcceptedAbilityAnimation(@Nonnull Ref<EntityStore> ref,
                                               @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                               @Nonnull AvatarFlightComponent flight,
                                               @Nonnull TwAvatarFlightConfig config,
                                               @Nonnull AvatarFlightController.Output output,
                                               long now) {
        AvatarFlightAbilityAnimationSettings settings = config.getAbilityAnimation();
        AvatarFlightAbilityAnimationSelector.Cue cue = AvatarFlightAbilityAnimationSelector.select(output, settings);
        if (cue == null || !settings.isEnabled() || cue.animationId().isBlank()) {
            return;
        }
        ModelComponent component = commandBuffer.getComponent(ref, ModelComponent.getComponentType());
        Model model = component == null ? null : component.getModel();
        if (model == null
                || model.getAnimationSetMap() == null
                || !model.getAnimationSetMap().containsKey(cue.animationId())) {
            warnMissingAnimation(model, cue);
            return;
        }
        AnimationSlot slot = resolveAnimationSlot(settings.getSlot(), AnimationSlot.Action);
        AnimationUtils.playAnimation(ref, slot, cue.animationId(), true, commandBuffer);
        flight.setAbilityAnimationId(cue.animationId());
        flight.setAbilityAnimationSlot(slot.name());
        flight.setAbilityAnimationKind(cue.ability().name());
        flight.setAbilityAnimationUntilMs(now + cue.durationMs());
        logAbilityEvent(config, cue, true, "played");
    }

    private void expireAbilityAnimation(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                        @Nonnull AvatarFlightComponent flight,
                                        long now) {
        if (flight.getAbilityAnimationId().isBlank() || now < flight.getAbilityAnimationUntilMs()) {
            return;
        }
        clearAbilityAnimation(ref, commandBuffer, flight);
    }

    private void clearAbilityAnimation(@Nonnull Ref<EntityStore> ref,
                                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                       @Nonnull AvatarFlightComponent flight) {
        if (flight.getAbilityAnimationId().isBlank()) {
            flight.clearAbilityAnimationState();
            return;
        }
        AnimationSlot slot = resolveAnimationSlot(flight.getAbilityAnimationSlot(), AnimationSlot.Action);
        AnimationUtils.stopAnimation(ref, slot, true, commandBuffer);
        if (slot == AnimationSlot.Movement) {
            flight.setMovementAnimationId("");
            flight.setNextMovementAnimationAtMs(0L);
        }
        flight.clearAbilityAnimationState();
    }

    private void warnMissingAnimation(@Nullable Model model,
                                      @Nonnull AvatarFlightAbilityAnimationSelector.Cue cue) {
        String modelId = model == null ? "<missing-model>" : model.getModelAssetId();
        String warningKey = modelId + '|' + cue.animationId();
        if (!warnedMissingAnimations.add(warningKey)) {
            return;
        }
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.WARNING).log(
                    "TameworkAvatarFlight ability animation skipped: model=" + modelId
                            + " ability=" + cue.ability()
                            + " animation=" + cue.animationId()
                            + " reason=missing_model_animation_set"
            );
        }
    }

    private static void logAbilityEvent(@Nonnull TwAvatarFlightConfig config,
                                        @Nonnull AvatarFlightAbilityAnimationSelector.Cue cue,
                                        boolean played,
                                        @Nonnull String reason) {
        if (!config.getDebug().isLogControllerTicks()) {
            return;
        }
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.INFO).log(
                    "TameworkAvatarFlight abilityAnimation: ability=" + cue.ability()
                            + " animation=" + cue.animationId()
                            + " played=" + played
                            + " reason=" + reason
            );
        }
    }

    private static void setPoseAnimationState(@Nonnull AvatarFlightComponent flight,
                                              boolean pitch,
                                              @Nonnull String animationId,
                                              long nextAtMs) {
        if (pitch) {
            flight.setPitchPoseAnimationId(animationId);
        } else {
            flight.setRollPoseAnimationId(animationId);
        }
        setPoseAnimationNextAt(flight, pitch, nextAtMs);
    }

    private static void setPoseAnimationNextAt(@Nonnull AvatarFlightComponent flight,
                                               boolean pitch,
                                               long nextAtMs) {
        if (pitch) {
            flight.setNextPitchPoseAnimationAtMs(nextAtMs);
        } else {
            flight.setNextRollPoseAnimationAtMs(nextAtMs);
        }
    }

    private static boolean isPoseSlot(@Nonnull AnimationSlot slot,
                                      @Nullable AnimationSlot pitchSlot,
                                      @Nullable AnimationSlot rollSlot) {
        return slot == pitchSlot || slot == rollSlot;
    }

    @Nonnull
    private static AnimationSlot resolveAnimationSlot(@Nullable String name, @Nonnull AnimationSlot fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (AnimationSlot slot : AnimationSlot.VALUES) {
            if (slot.name().toUpperCase(Locale.ROOT).equals(normalized)) {
                return slot;
            }
        }
        return fallback;
    }
}
