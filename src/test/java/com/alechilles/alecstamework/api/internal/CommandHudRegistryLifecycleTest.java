package com.alechilles.alecstamework.api.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandHudRegistryLifecycleTest {
    @Test
    void closeNotifiesRendererListenersOutsideTheRegistryMonitor() {
        CommandHudRendererRegistry registry = new CommandHudRendererRegistry();
        registry.registerTarget("example:renderer", ignored -> null);
        AtomicBoolean notified = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        registry.subscribeTargetUnregister((id, generation) -> {
            notified.set(true);
            CountDownLatch queryFinished = new CountDownLatch(1);
            Thread query = new Thread(() -> {
                try {
                    registry.targetIds();
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    queryFinished.countDown();
                }
            }, "command-hud-registry-query");
            query.start();
            try {
                if (!queryFinished.await(1, TimeUnit.SECONDS)) {
                    failure.compareAndSet(null, new AssertionError(
                            "Registry query did not finish during close callback."));
                }
                query.join(1_000L);
                if (query.isAlive()) {
                    failure.compareAndSet(null, new AssertionError(
                            "Registry query thread did not finish."));
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, error);
            }
        });

        registry.close();

        assertTrue(notified.get());
        assertNull(failure.get());
        assertTrue(registry.targetIds().isEmpty());
    }
}
