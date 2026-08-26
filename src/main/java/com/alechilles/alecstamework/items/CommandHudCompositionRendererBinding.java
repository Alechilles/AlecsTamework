package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudRendererId;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.internal.CommandHudRendererRegistry;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Installs one exact renderer-generation lifecycle subscription. */
final class CommandHudCompositionRendererBinding {
    private CommandHudCompositionRendererBinding() {
    }

    static void install(
            @Nullable CommandHudRendererRegistry registry,
            @Nonnull CommandHudSurface surface,
            @Nullable String rendererId,
            long generation,
            @Nonnull BooleanSupplier active,
            @Nonnull Runnable ended,
            @Nonnull Consumer<AutoCloseable> subscriptionSink
    ) {
        if (registry == null || rendererId == null) return;
        Optional<CommandHudRendererId> parsed = CommandHudRendererId.tryParse(rendererId);
        if (parsed.isEmpty()) return;
        CommandHudRendererId id = parsed.orElseThrow();
        CommandHudRendererRegistry.ExactSubscription subscription = surface
                == CommandHudSurface.TARGET
                ? registry.subscribeExactTargetUnregister(id, generation,
                        (ignoredId, ignoredGeneration) -> ended.run())
                : registry.subscribeExactHotswapUnregister(id, generation,
                        (ignoredId, ignoredGeneration) -> ended.run());
        subscriptionSink.accept(subscription.handle());
        if (!subscription.active() || !active.getAsBoolean()) ended.run();
    }
}
