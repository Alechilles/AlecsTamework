package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.PortableCompanionStore;
import com.alechilles.alecstamework.api.PortableCompanionsApi;
import com.alechilles.alecstamework.companion.network.PortableSync;
import java.util.Objects;
import javax.annotation.Nonnull;

/** {@link PortableCompanionsApi} over the runtime's {@link PortableSync}. */
public final class PortableCompanionsFacade implements PortableCompanionsApi {
    private final PortableSync sync;

    public PortableCompanionsFacade(@Nonnull PortableSync sync) {
        this.sync = Objects.requireNonNull(sync, "sync");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean active() {
        return sync.active();
    }

    @Override
    @Nonnull
    public AutoCloseable register(@Nonnull PortableCompanionStore store) {
        return sync.register(store);
    }
}
