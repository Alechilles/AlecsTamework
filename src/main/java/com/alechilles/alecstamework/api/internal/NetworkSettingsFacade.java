package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NetworkSettingsApi;
import com.alechilles.alecstamework.api.NetworkSettingsStore;
import com.alechilles.alecstamework.settings.NetworkSettingsSync;
import java.util.Objects;
import javax.annotation.Nonnull;

/** {@link NetworkSettingsApi} over the runtime's {@link NetworkSettingsSync}. */
public final class NetworkSettingsFacade implements NetworkSettingsApi {
    private final NetworkSettingsSync sync;

    public NetworkSettingsFacade(@Nonnull NetworkSettingsSync sync) {
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
    public AutoCloseable register(@Nonnull NetworkSettingsStore store) {
        return sync.register(store);
    }

    @Override
    public void notifyChanged(@Nonnull String key) {
        sync.notifyChanged(key);
    }
}
