package com.alechilles.alecstamework.npc.progression;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for attachment accessor lookup during repeated scale retries. */
class CompanionModelAttachmentAccessorTest {
    @Test
    void cachesResolvedAndMissingMethodsPerModelClass() {
        ConcurrentHashMap<Class<?>, AtomicInteger> lookupCounts = new ConcurrentHashMap<>();
        CompanionModelAttachmentAccessor accessor = new CompanionModelAttachmentAccessor(type -> {
            lookupCounts.computeIfAbsent(type, ignored -> new AtomicInteger()).incrementAndGet();
            return type.getMethod("getAdditionalAttachments");
        });
        ModelWithAttachments available = new ModelWithAttachments();
        ModelWithoutAttachments missing = new ModelWithoutAttachments();

        assertEquals(Map.of("collar", "red"), accessor.read(available));
        assertEquals(Map.of("collar", "red"), accessor.read(available));
        assertTrue(accessor.read(missing).isEmpty());
        assertTrue(accessor.read(missing).isEmpty());
        assertEquals(1, lookupCounts.get(ModelWithAttachments.class).get());
        assertEquals(1, lookupCounts.get(ModelWithoutAttachments.class).get());
    }

    public static final class ModelWithAttachments {
        public Map<String, String> getAdditionalAttachments() {
            return Map.of("collar", "red");
        }
    }

    public static final class ModelWithoutAttachments {
    }
}
