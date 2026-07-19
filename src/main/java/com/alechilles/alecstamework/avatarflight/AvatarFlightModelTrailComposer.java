package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.protocol.EntityPart;
import com.hypixel.hytale.protocol.ModelTrail;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rebuilds an immutable entity model with a controlled set of avatar-flight trails.
 */
final class AvatarFlightModelTrailComposer {
    private AvatarFlightModelTrailComposer() {
    }

    @Nonnull
    static Model withTrails(@Nonnull Model model,
                            @Nonnull ModelTrail[][] managedGroups,
                            @Nullable ModelTrail[] desired) {
        ModelTrail[] trails = compose(model.getTrails(), managedGroups, desired);
        return new Model(
                model.getModelAssetId(),
                model.getScale(),
                model.getRandomAttachmentIds(),
                model.getAttachments(),
                model.getBoundingBox(),
                model.getModel(),
                model.getTexture(),
                model.getGradientSet(),
                model.getGradientId(),
                model.getEyeHeight(),
                model.getCrouchOffset(),
                model.getSittingOffset(),
                model.getSleepingOffset(),
                model.getAnimationSetMap(),
                model.getCamera(),
                model.getLight(),
                model.getParticles(),
                trails,
                model.getPhysicsValues(),
                model.getDetailBoxes(),
                model.getPhobia(),
                model.getPhobiaModelAssetId()
        );
    }

    @Nonnull
    static ModelTrail[] compose(@Nullable ModelTrail[] current,
                                @Nonnull ModelTrail[][] managedGroups,
                                @Nullable ModelTrail[] desired) {
        Set<TrailKey> managed = managedKeys(managedGroups);
        List<ModelTrail> result = new ArrayList<>();
        if (current != null) {
            for (ModelTrail trail : current) {
                if (trail != null && !managed.contains(TrailKey.from(trail))) {
                    result.add(new ModelTrail(trail));
                }
            }
        }
        if (desired != null) {
            for (ModelTrail trail : desired) {
                if (trail != null && trail.trailId != null && !trail.trailId.isBlank()) {
                    result.add(asModelTrail(trail));
                }
            }
        }
        return result.toArray(ModelTrail[]::new);
    }

    @Nonnull
    private static Set<TrailKey> managedKeys(@Nonnull ModelTrail[][] groups) {
        Set<TrailKey> managed = new HashSet<>();
        for (ModelTrail[] group : groups) {
            if (group == null) continue;
            for (ModelTrail trail : group) {
                if (trail != null) managed.add(TrailKey.from(trail));
            }
        }
        return managed;
    }

    @Nonnull
    private static ModelTrail asModelTrail(@Nonnull ModelTrail source) {
        ModelTrail copy = new ModelTrail(source);
        copy.targetEntityPart = EntityPart.Self;
        return copy;
    }

    private record TrailKey(@Nullable String trailId, @Nullable String nodeName) {
        @Nonnull
        static TrailKey from(@Nonnull ModelTrail trail) {
            return new TrailKey(
                    Objects.requireNonNullElse(trail.trailId, ""),
                    Objects.requireNonNullElse(trail.targetNodeName, "")
            );
        }
    }
}
