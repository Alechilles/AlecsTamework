package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class TameworkReflectionAccessCacheTest {
    @AfterEach
    void clearCache() {
        TameworkReflectionAccessCache.clearForTests();
    }

    @Test
    void invokeNoArgCachesResolvedMethod() {
        MethodState state = new MethodState();

        assertEquals("coop/chicken_oak", TameworkReflectionAccessCache.invokeNoArg(state, "getCoopAssetId"));
        assertEquals("coop/chicken_oak", TameworkReflectionAccessCache.invokeNoArg(state, "getCoopAssetId"));

        assertEquals(1, TameworkReflectionAccessCache.methodCacheSizeForTests());
    }

    @Test
    void invokeNoArgCachesMissingMethod() {
        MissingState state = new MissingState();

        assertNull(TameworkReflectionAccessCache.invokeNoArg(state, "getCoopAsset"));
        assertNull(TameworkReflectionAccessCache.invokeNoArg(state, "getCoopAsset"));

        assertEquals(1, TameworkReflectionAccessCache.methodCacheSizeForTests());
    }

    @Test
    void invokeNoArgReturnsNullWhenMethodThrows() {
        ThrowingState state = new ThrowingState();

        assertNull(TameworkReflectionAccessCache.invokeNoArg(state, "getCoopAssetId"));

        assertEquals(1, TameworkReflectionAccessCache.methodCacheSizeForTests());
    }

    @Test
    void readFieldCachesSuperclassField() {
        ChildState state = new ChildState();

        assertEquals("coop/parent", TameworkReflectionAccessCache.readField(state, "coopAssetId"));
        assertEquals("coop/parent", TameworkReflectionAccessCache.readField(state, "coopAssetId"));

        assertEquals(1, TameworkReflectionAccessCache.fieldCacheSizeForTests());
    }

    @Test
    void readFieldCachesMissingField() {
        MissingState state = new MissingState();

        assertNull(TameworkReflectionAccessCache.readField(state, "coopAssetId"));
        assertNull(TameworkReflectionAccessCache.readField(state, "coopAssetId"));

        assertEquals(1, TameworkReflectionAccessCache.fieldCacheSizeForTests());
    }

    private static final class MethodState {
        public String getCoopAssetId() {
            return "coop/chicken_oak";
        }
    }

    private static final class MissingState {
    }

    private static class ParentState {
        @SuppressWarnings("unused")
        private final String coopAssetId = "coop/parent";
    }

    private static final class ChildState extends ParentState {
    }

    private static final class ThrowingState {
        public String getCoopAssetId() {
            throw new IllegalStateException("boom");
        }
    }
}
