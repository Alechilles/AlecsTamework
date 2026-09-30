package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpoint;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpointCodec;
import com.alechilles.alecstamework.npc.components.TameworkAlarmComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.AnimalProgressionClock;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The unloaded panel built from the index summary must show what the decoded checkpoint showed. */
class CommandSavedNpcPanelSummaryTest {
    private static final String ROLE = "Tamed_Summary_Sheep";
    private static final double GAME_RATE = 3.0;

    @Test
    void summaryPanelMatchesTheDecodedCheckpointForCountdownsAndLifeStage() throws Exception {
        try (var ignored = new AgingConfig(ROLE)) {
            long worldMs = -1_000_000L;
            TameworkLifeStageComponent lifeStage = new TameworkLifeStageComponent();
            lifeStage.setStage("Adult");
            lifeStage.setProgressionInitialized(true);
            lifeStage.setLastProgressionWorldMs(worldMs);
            lifeStage.setActiveProgressMs(5_000L);
            lifeStage.setProgressionClockMs(AnimalProgressionClock.get().current(null));
            lifeStage.setStoredProgressionPaused(true);
            lifeStage.setAgingInitialized(true);
            lifeStage.setAgeProgressMs(11 * 60_000.0);
            TameworkBreedingComponent breeding = new TameworkBreedingComponent("breed-test", 0.0, 0L, true, true,
                    worldMs + 18_000L, null, worldMs - 12_000L, 30_000L);
            TameworkAlarmComponent alarms = new TameworkAlarmComponent();
            alarms.setAlarm(CommandLinkedPanelCooldownSnapshotService.resolveHarvestAlarmName(),
                    worldMs - 6_000L, 20_000L, worldMs + 14_000L);
            TameworkNeedsComponent needs = new TameworkNeedsComponent("care-test", 33.0, 11.0, 0.0, 0L, 0L);
            TameworkAlarmComponent.AlarmEntry harvest =
                    alarms.getAlarm(CommandLinkedPanelCooldownSnapshotService.resolveHarvestAlarmName());

            ProfileId profileId = new ProfileId(UUID.randomUUID());
            CommandSavedNpcPanelSnapshot decoded = decode(profileId, lifeStage, breeding, alarms, needs);
            CompanionSummary summary = CompanionSummaries.build(new CompanionSummaries.Inputs(null, null, ROLE, null,
                    0f, 0f, null, 0.0, needs.getConfigId(), needs.getHunger(), needs.getThirst(),
                    breeding.isEnabled(), breeding.getCooldownUntilMs(), breeding.getCooldownStartedAtMs(),
                    breeding.getCooldownDurationMs(), harvest.getUntilMs(), null, 0, 0.0, 0.0, 0, Map.of(),
                    null, 0.0, null, 0L, harvest.getStartedAtMs(), harvest.getDurationMs(), null, null,
                    CompanionSummaries.progression(lifeStage)), 1L);
            CompanionRecord record = CompanionRecord.builder(profileId.value(), ROLE,
                    CompanionLocation.live("world", 0, 0, 0)).summary(summary).build();

            LinkedNpcEntry base = baseCard();
            LinkedNpcEntry expected = decoded.apply(base, null, GAME_RATE);
            LinkedNpcEntry actual = CommandSavedNpcPanelSnapshot.fromSummary(record).apply(base, null, GAME_RATE);

            // Guards against a vacuous comparison: the decoded panel shows running timers and a prime adult.
            assertEquals(6_000L, expected.breedingCooldownRemainingMs());
            assertTrue(expected.animalLifecycle().prime());

            assertEquals(expected.breedingCooldownKnown(), actual.breedingCooldownKnown());
            assertEquals(expected.breedingCooldownActive(), actual.breedingCooldownActive());
            assertEquals(expected.breedingCooldownRemainingMs(), actual.breedingCooldownRemainingMs());
            assertEquals(expected.breedingCooldownRatio(), actual.breedingCooldownRatio());
            assertEquals(expected.harvestCooldownKnown(), actual.harvestCooldownKnown());
            assertEquals(expected.harvestCooldownActive(), actual.harvestCooldownActive());
            assertEquals(expected.harvestCooldownRemainingMs(), actual.harvestCooldownRemainingMs());
            assertEquals(expected.harvestCooldownRatio(), actual.harvestCooldownRatio());
            assertEquals(expected.animalLifecycle().stage(), actual.animalLifecycle().stage());
            assertEquals(expected.animalLifecycle().prime(), actual.animalLifecycle().prime());
            assertEquals(expected.animalLifecycle().frozen(), actual.animalLifecycle().frozen());
            assertEquals(expected.animalLifecycle().nextDeath(), actual.animalLifecycle().nextDeath());
            assertEquals(expected.animalLifecycle().yieldMultiplier(), actual.animalLifecycle().yieldMultiplier());
            assertEquals(expected.animalLifecycle().remainingMs(), actual.animalLifecycle().remainingMs());
        }
    }

    private static CommandSavedNpcPanelSnapshot decode(ProfileId profileId, TameworkLifeStageComponent lifeStage,
                                                       TameworkBreedingComponent breeding,
                                                       TameworkAlarmComponent alarms, TameworkNeedsComponent needs) {
        BsonDocument components = new BsonDocument()
                .append("TameworkBreeding", TameworkBreedingComponent.CODEC.encode(breeding, new ExtraInfo()))
                .append("TameworkAlarm", TameworkAlarmComponent.CODEC.encode(alarms, new ExtraInfo()))
                .append("TameworkNeeds", TameworkNeedsComponent.CODEC.encode(needs, new ExtraInfo()))
                .append("TameworkLifeStage", TameworkLifeStageComponent.CODEC.encode(lifeStage, new ExtraInfo()));
        CompanionEntityCheckpointCodec codec = new CompanionEntityCheckpointCodec();
        CompanionEntityCheckpoint checkpoint = CompanionEntityCheckpoint.create(
                profileId, new NpcAlias(UUID.randomUUID()), 0L, new OwnerId(UUID.randomUUID()),
                LifecycleRevision.INITIAL, ReconciliationGeneration.INITIAL, "world", 1.0, 2.0,
                3.0, CompanionEntityCheckpoint.CaptureBoundary.UNLOAD, -15L,
                new BsonDocument().append("Components", components), codec);
        CompanionProfileReadModel profile = new CompanionProfileReadModel(
                new CompanionIdentity(profileId, "Sheep", ROLE, null, null, "world", -20L, -20L, -20L, 0L),
                new CompanionAlias(new NpcAlias(UUID.randomUUID()), profileId, 0L,
                        CompanionAlias.State.CURRENT, null, -20L, null),
                new CompanionLifecycle(profileId, new OwnerId(UUID.randomUUID()), LifecycleState.UNLOADED,
                        LifecycleLocation.none(), LifecycleRevision.INITIAL, null, -20L,
                        ReconciliationGeneration.INITIAL, null, null),
                List.of(), List.of(), null);
        CommandSavedNpcPanelSnapshot saved = CommandSavedNpcPanelSnapshot.decode(profile, codec.encode(checkpoint));
        assertNotNull(saved);
        return saved;
    }

    private static LinkedNpcEntry baseCard() {
        return new LinkedNpcEntry(UUID.randomUUID(), "Sheep", 1, 1, 0, 0,
                null, 0, 0, 0, 0, false, false, false, false, false, false,
                0L, new com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator[0]);
    }

    /** Installs one breeding config with aging enabled for the role, without starting a server. */
    private static final class AgingConfig implements AutoCloseable {
        private final Field storeField;
        private final Object previous;

        AgingConfig(String role) throws Exception {
            TwBreedingConfig config = TwBreedingConfig.CODEC.decode(BsonDocument.parse("""
                    {"RoleIds":["%s"],"Aging":{"Enabled":true,"AdultToPrimeMinutes":10,
                     "PrimeMinutes":60,"SeniorMinutes":60,"NonPrimeYieldMultiplier":0.5}}
                    """.formatted(role)), new ExtraInfo());
            Field id = TwBreedingConfig.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(config, "test-aging");
            storeField = TwBreedingConfig.class.getDeclaredField("ASSET_STORE");
            storeField.setAccessible(true);
            previous = storeField.get(null);
            storeField.set(null, new MemoryStore(new DefaultAssetMap<>(Map.of("test-aging", config))));
            TwBreedingConfig.clearRoleCache();
        }

        @Override
        public void close() throws Exception {
            storeField.set(null, previous);
            TwBreedingConfig.clearRoleCache();
        }
    }

    private static final class MemoryStore extends AssetStore<String,
            TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> {
        MemoryStore(DefaultAssetMap<String, TwBreedingConfig> map) { super(new Builder(map)); }
        @Override protected com.hypixel.hytale.event.IEventBus getEventBus() { return null; }
        @Override public void addFileMonitor(String pack, Path path) { }
        @Override public void removeFileMonitor(Path path) { }
        @Override protected void handleRemoveOrUpdate(Set<String> removed,
                Map<String, TwBreedingConfig> changed, AssetUpdateQuery query) { }

        private static final class Builder extends AssetStore.Builder<String,
                TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>, Builder> {
            private final DefaultAssetMap<String, TwBreedingConfig> map;
            Builder(DefaultAssetMap<String, TwBreedingConfig> map) {
                super(String.class, TwBreedingConfig.class, map);
                this.map = map;
                setPath("Tamework/Breeding");
                setCodec(TwBreedingConfig.CODEC);
                setKeyFunction(TwBreedingConfig::getId);
            }
            @Override public AssetStore<String, TwBreedingConfig,
                    DefaultAssetMap<String, TwBreedingConfig>> build() { return new MemoryStore(map); }
        }
    }
}
