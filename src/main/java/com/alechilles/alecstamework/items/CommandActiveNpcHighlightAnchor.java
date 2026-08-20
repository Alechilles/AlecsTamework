package com.alechilles.alecstamework.items;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3f;

/** Defines the model node and local offset used for one active NPC highlight. */
record CommandActiveNpcHighlightAnchor(
        @Nullable String targetNodeName,
        @Nonnull Vector3f positionOffset
) {
}
