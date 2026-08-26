package com.alechilles.alecstamework.api.commandhud;

import javax.annotation.Nonnull;

/** Factory for one target HUD controller per renderer session. */
public interface CommandTargetHudRendererProvider {
    /** Creates a controller from detached session data. */
    @Nonnull
    CommandTargetHudController create(@Nonnull CommandHudOpenContext context);
}
