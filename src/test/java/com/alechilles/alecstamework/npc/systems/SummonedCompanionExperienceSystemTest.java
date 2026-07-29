package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummonedCompanionExperienceSystemTest {
    @Test
    void onlyLiveBondedProjectionsAreEligibleForSummonedXp() {
        TameworkProjectionIdentityComponent bonded =
                TameworkProjectionIdentityComponent.bondedCompanion("profile-a", "lease-a");
        TameworkProjectionIdentityComponent ordinary = new TameworkProjectionIdentityComponent(
                "profile-a", "operation-a", TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                null, null, 0L);

        assertTrue(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(true, bonded, false));
        assertFalse(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(true, ordinary, false));
        assertFalse(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(true, bonded, true));
        assertFalse(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(false, bonded, false));
    }

    @Test
    void tickRoutesLiveBondedProjectionToSummonedAwardAndPausesOthers() throws Exception {
        TameworkProjectionIdentityComponent bonded =
                TameworkProjectionIdentityComponent.bondedCompanion("profile-a", "lease-a");
        TameworkProjectionIdentityComponent ordinary = new TameworkProjectionIdentityComponent(
                "profile-a", "operation-a", TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                null, null, 0L);
        TwLevelingConfig.SummonedXpSourceSettings settings = settings(2.0d, 0.5d, 50.0d);
        AtomicReference<CompanionXpSource> source = new AtomicReference<>();
        AtomicReference<Double> amount = new AtomicReference<>(0.0d);

        TameworkLevelingComponent bondedLeveling = leveling(0.25d, 1_000L);
        tick(bondedLeveling, bonded, false, settings, source, amount);

        assertEquals(CompanionXpSource.SUMMONED, source.get());
        assertEquals(1.0d, amount.get(), 0.00001d);

        TameworkLevelingComponent ordinaryLeveling = leveling(0.49d, 1_000L);
        amount.set(0.0d);
        tick(ordinaryLeveling, ordinary, false, settings, source, amount);

        assertEquals(0.0d, amount.get(), 0.00001d);
        assertEquals(0.0d, ordinaryLeveling.getSummonedActiveSeconds(), 0.00001d);

        TameworkLevelingComponent deadLeveling = leveling(0.49d, 1_000L);
        tick(deadLeveling, bonded, true, settings, source, amount);
        assertEquals(0.0d, deadLeveling.getSummonedActiveSeconds(), 0.00001d);
    }

    private static void tick(TameworkLevelingComponent leveling,
                             TameworkProjectionIdentityComponent identity,
                             boolean dead,
                             TwLevelingConfig.SummonedXpSourceSettings settings,
                             AtomicReference<CompanionXpSource> source,
                             AtomicReference<Double> amount) throws Exception {
        ComponentType<EntityStore, NPCEntity> npcType = new ComponentType<>();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> identityType = new ComponentType<>();
        ComponentType<EntityStore, TameworkLevelingComponent> levelingType = new ComponentType<>();
        ComponentType<EntityStore, DeathComponent> deathType = new ComponentType<>();
        EntityStore entityStore = new EntityStore(null);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            Ref<EntityStore> reference = store.createReference();
            store.put(reference, identityType, identity);
            store.put(reference, levelingType, leveling);
            if (dead) {
                store.put(reference, deathType, allocate(DeathComponent.class));
            }
            SummonedCompanionExperienceSystem system = new SummonedCompanionExperienceSystem(
                    npcType, identityType, levelingType, deathType,
                    (ref, ignoredStore) -> new SummonedCompanionExperienceSystem.ResolvedSettings("role", settings),
                    (ref, ignoredStore, commandBuffer, roleId, awardSource, awardAmount) -> {
                        source.set(awardSource);
                        amount.set(awardAmount);
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
