package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SummonedCompanionExperienceSystemTest {
    @Test
    void tickRoutesLiveBondedProjectionToSummonedAwardAndPausesOthers() throws Exception {
        UUID bonded = UUID.randomUUID();
        UUID ordinary = UUID.randomUUID();
        TwLevelingConfig.SummonedXpSourceSettings settings = settings(2.0d, 0.5d, 50.0d);
        AtomicReference<CompanionXpSource> source = new AtomicReference<>();
        AtomicReference<Double> amount = new AtomicReference<>(0.0d);
        AtomicInteger awards = new AtomicInteger();

        TameworkLevelingComponent bondedLeveling = leveling(0.25d, 1_000L);
        tick(bondedLeveling, bonded, bonded, false, settings, source, amount, awards);

        assertEquals(CompanionXpSource.SUMMONED, source.get());
        assertEquals(1.0d, amount.get(), 0.00001d);
        assertEquals(1, awards.get());

        TameworkLevelingComponent ordinaryLeveling = leveling(0.49d, 1_000L);
        amount.set(0.0d);
        awards.set(0);
        tick(ordinaryLeveling, ordinary, bonded, false, settings, source, amount, awards);

        assertEquals(0, awards.get(), "A companion that is not bonded earns no summoned XP.");

        TameworkLevelingComponent deadLeveling = leveling(0.49d, 1_000L);
        awards.set(0);
        tick(deadLeveling, bonded, bonded, true, settings, source, amount, awards);
        assertEquals(0.0d, deadLeveling.getSummonedActiveSeconds(), 0.00001d);
        assertEquals(0, awards.get(), "Dead projections must not invoke the XP awarder.");
    }

    private static void tick(TameworkLevelingComponent leveling,
                             UUID profileId,
                             UUID bondedProfileId,
                             boolean dead,
                             TwLevelingConfig.SummonedXpSourceSettings settings,
                             AtomicReference<CompanionXpSource> source,
                             AtomicReference<Double> amount,
                             AtomicInteger awards) throws Exception {
        ComponentType<EntityStore, NPCEntity> npcType = new ComponentType<>();
        ComponentType<EntityStore, TameworkCompanionComponent> stampType = new ComponentType<>();
        ComponentType<EntityStore, TameworkLevelingComponent> levelingType = new ComponentType<>();
        ComponentType<EntityStore, DeathComponent> deathType = new ComponentType<>();
        EntityStore entityStore = new EntityStore(null);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            Ref<EntityStore> reference = store.createReference();
            store.put(reference, stampType, new TameworkCompanionComponent(profileId, 0L));
            store.put(reference, levelingType, leveling);
            if (dead) {
                store.put(reference, deathType, allocate(DeathComponent.class));
            }
            SummonedCompanionExperienceSystem system = new SummonedCompanionExperienceSystem(
                    npcType, stampType, bondedProfileId::equals, levelingType, deathType,
                    (ref, ignoredStore) -> new SummonedCompanionExperienceSystem.ResolvedSettings("role", settings),
                    (ref, ignoredStore, commandBuffer, roleId, awardSource, awardAmount) -> {
                        source.set(awardSource);
                        amount.set(awardAmount);
                        awards.incrementAndGet();
                    },
                    () -> 1_250L);
            store.forEachChunk(Query.any(), (BiConsumer<com.hypixel.hytale.component.ArchetypeChunk<EntityStore>,
                    CommandBuffer<EntityStore>>) (chunk, ignoredBuffer) ->
                    system.tick(0.25f, 0, chunk, store, null));
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(type));
    }

    private static TameworkLevelingComponent leveling(double activeSeconds, long lastSampleAtMs) {
        return new TameworkLevelingComponent(
                "leveling", 1, 0.0d, 0.0d, 0L, activeSeconds, 0.0d, 0L, lastSampleAtMs);
    }

    private static TwLevelingConfig.SummonedXpSourceSettings settings(double xpPerActiveSecond,
                                                                        double awardIntervalSeconds,
                                                                        double maxXpPerHour) throws Exception {
        TwLevelingConfig.SummonedXpSourceSettings settings = new TwLevelingConfig.SummonedXpSourceSettings();
        setField(settings, "enabled", true);
        setField(settings, "xpPerActiveSecond", xpPerActiveSecond);
        setField(settings, "awardIntervalSeconds", awardIntervalSeconds);
        setField(settings, "maxXpPerHour", maxXpPerHour);
        return settings;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
