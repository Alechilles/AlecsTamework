package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NeedsSensorConfigMemoTest {
    @Test
    void warmLookupReusesCompiledConfigAndReloadRefreshesIt() {
        AtomicLong generation = new AtomicLong(1L);
        AtomicInteger resolutions = new AtomicInteger();
        NeedsConfigResolver.NeedsSensorConfig first =
                new NeedsConfigResolver.NeedsSensorConfig(true, 0.0, 100.0, 0.0, 100.0);
        NeedsConfigResolver.NeedsSensorConfig second =
                new NeedsConfigResolver.NeedsSensorConfig(true, 10.0, 80.0, 20.0, 90.0);
        AtomicReference<NeedsConfigResolver.NeedsSensorConfig> current = new AtomicReference<>(first);
        NeedsSensorConfigMemo memo = new NeedsSensorConfigMemo(new NeedsSensorConfigMemo.Source() {
            @Override
            public long generation() {
                return generation.get();
            }

            @Override
            public NeedsConfigResolver.NeedsSensorConfig resolve(String roleId, String configId) {
                resolutions.incrementAndGet();
                return current.get();
            }
        });

        assertSame(first, memo.resolve("Cow", "Needs_Cow"));
        assertSame(first, memo.resolve("Cow", "Needs_Cow"));
        current.set(second);
        generation.incrementAndGet();
        assertSame(second, memo.resolve("Cow", "Needs_Cow"));
        assertSame(second, memo.resolve("Cow", "Needs_Cow"));
        org.junit.jupiter.api.Assertions.assertEquals(2, resolutions.get());
    }
}
