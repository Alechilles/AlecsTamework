package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for feed-trough optional block-container API detection caching. */
class FeedTroughContainerCompatTest {
    @AfterEach
    void resetCache() {
        FeedTroughContainerCompat.clearModernComponentTypeCacheForTests();
    }

    @Test
    void resolveModernComponentTypeCachesMissingApiResult() {
        FeedTroughContainerCompat.clearModernComponentTypeCacheForTests();

        FeedTroughContainerCompat.resolveModernComponentTypeForTests();
        FeedTroughContainerCompat.resolveModernComponentTypeForTests();

        assertTrue(FeedTroughContainerCompat.isModernComponentTypeResolvedForTests());
    }
}
