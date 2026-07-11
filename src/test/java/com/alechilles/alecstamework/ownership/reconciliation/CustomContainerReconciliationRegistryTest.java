package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the custom-container SPI boundary against invoking third-party code under its lock. */
class CustomContainerReconciliationRegistryTest {
    @Test
    void sourceFactoryRunsOutsideCatalogMonitorAndConcurrentChangeInvalidatesSnapshot() throws Exception {
        CustomContainerReconciliationRegistry registry = new CustomContainerReconciliationRegistry();
        CountDownLatch enteredFactory = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        registry.register(provider("blocking", () -> {
            enteredFactory.countDown();
            if (!releaseFactory.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test factory timed out");
            }
            return source("blocking");
        }));
        CompletableFuture<CustomContainerReconciliationRegistry.Snapshot> snapshot =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return registry.snapshot();
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    }
                });
        assertTrue(enteredFactory.await(2L, TimeUnit.SECONDS));

        CompletableFuture.runAsync(() -> registry.register(provider(
                "concurrent", () -> source("concurrent")
        ))).get(2L, TimeUnit.SECONDS);
        releaseFactory.countDown();

        assertThrows(CompletionException.class, snapshot::join);
    }

    private static CustomContainerPopulationEvidenceProvider provider(
            String id,
            ThrowingSourceFactory factory
    ) {
        return new CustomContainerPopulationEvidenceProvider() {
            @Override
            public String providerId() {
                return id;
            }

            @Override
            public CompanionPopulationEvidenceSource createEvidenceSource() throws Exception {
                return factory.create();
            }
        };
    }

    private static CompanionPopulationEvidenceSource source(String id) {
        return new CompanionPopulationEvidenceSource() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor(
                        id,
                        CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS,
                        null,
                        "generation",
                        0L
                );
            }

            @Override
            public CompletableFuture<Batch> scan(long offset, int maxUnits) {
                return CompletableFuture.completedFuture(new Batch(List.of(), offset, 0L, true));
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingSourceFactory {
        CompanionPopulationEvidenceSource create() throws Exception;
    }
}
