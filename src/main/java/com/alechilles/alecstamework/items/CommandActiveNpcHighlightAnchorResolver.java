package com.alechilles.alecstamework.items;

import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3f;

/** Selects a bounds-based mounted-anchor offset above an NPC's head. */
final class CommandActiveNpcHighlightAnchorResolver {
    private static final float ROOT_MARGIN_Y = 0.65f;
    private static final float DEFAULT_ROOT_HEIGHT = 1.35f;

    /** Returns the fixed root offset used by a mounted helper entity. */
    @Nonnull
    Vector3f resolveMountedOffset(@Nullable ModelComponent modelComponent) {
        Model model = modelComponent != null ? modelComponent.getModel() : null;
        return new Vector3f(0.0f, rootHeight(model), 0.0f);
    }

    private float rootHeight(@Nullable Model model) {
        Box box = model != null ? model.getBoundingBox() : null;
        if (box == null || !Double.isFinite(box.getMax().y())) {
            return DEFAULT_ROOT_HEIGHT;
        }
        return (float) Math.max(ROOT_MARGIN_Y, box.getMax().y() + ROOT_MARGIN_Y);
    }
}
