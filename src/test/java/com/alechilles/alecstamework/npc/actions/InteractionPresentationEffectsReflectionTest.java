package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Regression coverage for cached combat-text reflection lookup paths. */
class InteractionPresentationEffectsReflectionTest {
    @AfterEach
    void resetCaches() {
        InteractionPresentationEffects.clearReflectionCachesForTests();
    }

    @Test
    void resolveQueueUpdateMethodCachesCompatibleMethod() {
        InteractionPresentationEffects.clearReflectionCachesForTests();

        Method first = InteractionPresentationEffects.resolveQueueUpdateMethodForTests(
                CompatibleViewer.class,
                String.class
        );
        Method second = InteractionPresentationEffects.resolveQueueUpdateMethodForTests(
                CompatibleViewer.class,
                String.class
        );

        assertSame(first, second);
        assertEquals(1, InteractionPresentationEffects.cachedQueueUpdateMethodCountForTests());
        assertEquals(0, InteractionPresentationEffects.missingQueueUpdateMethodCountForTests());
    }

    @Test
    void resolveQueueUpdateMethodCachesMissingMethod() {
        InteractionPresentationEffects.clearReflectionCachesForTests();

        Method first = InteractionPresentationEffects.resolveQueueUpdateMethodForTests(
                IncompatibleViewer.class,
                String.class
        );
        Method second = InteractionPresentationEffects.resolveQueueUpdateMethodForTests(
                IncompatibleViewer.class,
                String.class
        );

        assertNull(first);
        assertNull(second);
        assertEquals(0, InteractionPresentationEffects.cachedQueueUpdateMethodCountForTests());
        assertEquals(1, InteractionPresentationEffects.missingQueueUpdateMethodCountForTests());
    }

    /** Viewer fixture with a compatible queueUpdate overload. */
    public static final class CompatibleViewer {
        public void queueUpdate(Ref<?> ref, CharSequence update) {
        }
    }

    /** Viewer fixture without a queueUpdate overload. */
    public static final class IncompatibleViewer {
        public void ignored(Ref<?> ref, CharSequence update) {
        }
    }
}
