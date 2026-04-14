package com.alechilles.alecstamework.npc.params;

import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class StdScopeLookupCacheTest {

    @Test
    void cachesScalarFallbackForStringArrayLookups() {
        StdScopeLookupCache cache = new StdScopeLookupCache();
        CountingStringFallbackScope scope = new CountingStringFallbackScope();

        assertArrayEquals(new String[] {"test:item"}, cache.getStringArrayOrString(scope, "FeedItems"));
        assertArrayEquals(new String[] {"test:item"}, cache.getStringArrayOrString(scope, "FeedItems"));
        assertEquals(1, scope.stringArrayAttempts);
        assertEquals(2, scope.stringAttempts);
    }

    @Test
    void cachesMissingScalarStringLookups() {
        StdScopeLookupCache cache = new StdScopeLookupCache();
        CountingMissingScope scope = new CountingMissingScope();

        assertNull(cache.getString(scope, "MissingParam"));
        assertNull(cache.getString(scope, "MissingParam"));
        assertEquals(1, scope.stringAttempts);
    }

    @Test
    void cachesMissingBooleanLookups() {
        StdScopeLookupCache cache = new StdScopeLookupCache();
        CountingMissingScope scope = new CountingMissingScope();

        assertNull(cache.getBoolean(scope, "MissingBoolean"));
        assertNull(cache.getBoolean(scope, "MissingBoolean"));
        assertEquals(1, scope.booleanAttempts);
    }

    @Test
    void returnsCachedBooleanValuesWithoutRepeatedExceptions() {
        StdScopeLookupCache cache = new StdScopeLookupCache();
        CountingBooleanScope scope = new CountingBooleanScope();

        assertEquals(Boolean.TRUE, cache.getBoolean(scope, "Flag"));
        assertEquals(Boolean.TRUE, cache.getBoolean(scope, "Flag"));
        assertFalse(scope.booleanAttempts < 2);
    }

    private static final class CountingStringFallbackScope extends StdScope {
        private int stringArrayAttempts;
        private int stringAttempts;

        private CountingStringFallbackScope() {
            super(null);
        }

        @Override
        public Supplier<String[]> getStringArraySupplier(String name) {
            stringArrayAttempts++;
            throw new IllegalStateException("Not a string array");
        }

        @Override
        public Supplier<String> getStringSupplier(String name) {
            stringAttempts++;
            return () -> "test:item";
        }
    }

    private static class CountingMissingScope extends StdScope {
        private int stringAttempts;
        private int booleanAttempts;

        private CountingMissingScope() {
            super(null);
        }

        @Override
        public Supplier<String> getStringSupplier(String name) {
            stringAttempts++;
            throw new IllegalStateException("Missing symbol");
        }

        @Override
        public BooleanSupplier getBooleanSupplier(String name) {
            booleanAttempts++;
            throw new IllegalStateException("Missing symbol");
        }
    }

    private static final class CountingBooleanScope extends StdScope {
        private int booleanAttempts;

        private CountingBooleanScope() {
            super(null);
        }

        @Override
        public BooleanSupplier getBooleanSupplier(String name) {
            booleanAttempts++;
            return () -> true;
        }
    }
}
