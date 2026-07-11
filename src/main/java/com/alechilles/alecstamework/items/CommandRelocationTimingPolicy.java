package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;

/** Resolves command-relocation retry and terminal-wait settings with stable defaults. */
final class CommandRelocationTimingPolicy {
    private static final long DEFAULT_RETRY_INTERVAL_MS = 2000L;
    private static final long DEFAULT_MAX_WAIT_MS = 10000L;
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 60;

    long retryIntervalMs() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        long configured = config == null ? 0L : config.getCommandRelocationRetryIntervalMs();
        return configured > 0L ? configured : DEFAULT_RETRY_INTERVAL_MS;
    }

    long maxWaitMs() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        long configured = config == null ? 0L : config.getCommandRelocationMaxWaitMs();
        return configured > 0L ? configured : DEFAULT_MAX_WAIT_MS;
    }

    int maxRetryAttempts() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        int configured = config == null ? 0 : config.getCommandRelocationMaxRetryAttempts();
        return configured > 0 ? configured : DEFAULT_MAX_RETRY_ATTEMPTS;
    }
}
