package com.alechilles.alecstamework.items;

import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.asset.type.model.config.DetailBox;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3f;

/** Selects a best-effort model node or bounds-based anchor above an NPC's head. */
final class CommandActiveNpcHighlightAnchorResolver {
    private static final String HEAD_NODE = "Head";
    private static final float HEAD_OFFSET_Y = 0.25f;
    private static final float ROOT_MARGIN_Y = 0.2f;
    private static final float DEFAULT_ROOT_HEIGHT = 0.85f;

    @Nonnull
    CommandActiveNpcHighlightAnchor resolve(@Nullable ModelComponent modelComponent) {
        Model model = modelComponent != null ? modelComponent.getModel() : null;
        String headNode = findHeadNode(model);
        if (headNode != null) {
            return new CommandActiveNpcHighlightAnchor(
                    headNode,
                    new Vector3f(0.0f, HEAD_OFFSET_Y, 0.0f)
            );
        }
        return new CommandActiveNpcHighlightAnchor(
                null,
                new Vector3f(0.0f, rootHeight(model), 0.0f)
        );
    }

    @Nullable
    private String findHeadNode(@Nullable Model model) {
        if (model == null) {
            return null;
        }
        Map<String, DetailBox[]> detailBoxes = model.getDetailBoxes();
        if (detailBoxes == null) {
            return null;
        }
        for (String name : detailBoxes.keySet()) {
            if (name != null && HEAD_NODE.equalsIgnoreCase(name)) {
                return name;
            }
        }
        return null;
    }

    private float rootHeight(@Nullable Model model) {
        Box box = model != null ? model.getBoundingBox() : null;
        if (box == null || !Double.isFinite(box.getMax().y())) {
            return DEFAULT_ROOT_HEIGHT;
        }
        return (float) Math.max(ROOT_MARGIN_Y, box.getMax().y() + ROOT_MARGIN_Y);
    }
}
