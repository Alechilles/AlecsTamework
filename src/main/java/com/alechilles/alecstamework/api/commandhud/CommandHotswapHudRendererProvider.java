package com.alechilles.alecstamework.api.commandhud;

import javax.annotation.Nonnull;

/** Factory for one hotswap HUD controller per renderer session. */
public interface CommandHotswapHudRendererProvider {
    /** Creates a controller from detached session data. */
    @Nonnull
    CommandHotswapHudController create(@Nonnull CommandHudOpenContext context);
}
