package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex;
import com.alechilles.alecstamework.items.locate.CapturedItemMetadata;
import com.hypixel.hytale.assetstore.TestItemAssetStore;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The unloaded panel built from the index summary shows the saved place, countdowns and life stage. */
class CommandSavedNpcPanelSummaryTest {
    private static final String ROLE = "Tamed_Summary_Sheep";
    private static final double GAME_RATE = 3.0;

    @Test
    void coopRecordCarriesItsCoopBlockEvenWithoutASummary() {
        CompanionRecord record = CompanionRecord.builder(UUID.randomUUID(), ROLE,
                CompanionLocation.coop("farm", 4, 64, -9, 2)).build();

        CommandSavedNpcPanelSnapshot saved = CommandSavedNpcPanelSnapshot.fromSummary(record);

        assertNotNull(saved);
        assertEquals(new CommandSavedNpcPanelSnapshot.CoopLocation("farm", 4, 64, -9), saved.storedLocation().coop());
    }

    /** The card finds a capture item's holder only if the record builds the key its item is tracked under. */
    @Test
    void itemRecordFindsTheTrackedLocationOfTheCaptureItemMadeAtItsGeneration() throws Exception {
        Field itemStore = Item.class.getDeclaredField("ASSET_STORE");
        itemStore.setAccessible(true);
        Object previous = itemStore.get(null);
        itemStore.set(null, new TestItemAssetStore(new DefaultAssetMap<>(Map.of("Soul_Lantern", new Item("Soul_Lantern")))));
        try {
            UUID profileId = UUID.randomUUID();
            ItemStack lantern = CaptureItemKeys.write(new ItemStack("Soul_Lantern", 1), new CaptureItemKeys.Ref(profileId, 3));
            CapturedItemLocationIndex locations = new CapturedItemLocationIndex();
            CapturedItemLocationIndex.Holder chest = new CapturedItemLocationIndex.Holder(
                    CapturedItemLocationIndex.Kind.CONTAINER, "farm", "4,64,-9", "Wooden_Chest", 4, 64, -9);
            locations.observe(chest, Map.of(CapturedItemMetadata.read(lantern), lantern.getItemId()), 1L);

            CompanionRecord held = CompanionRecord.builder(profileId, ROLE, CompanionLocation.item()).generation(3).build();
            var sighting = locations.find(CommandSavedNpcPanelSnapshot.fromSummary(held).storedLocation().capture());
            assertEquals(chest, sighting.orElseThrow().holder());
            assertEquals("Soul_Lantern", sighting.orElseThrow().itemId());

            // A recapture is tracked under its own generation; the old item's holder is not reported for it.
            CompanionRecord recaptured = CompanionRecord.builder(profileId, ROLE, CompanionLocation.item()).generation(5).build();
            assertTrue(locations.find(CommandSavedNpcPanelSnapshot.fromSummary(recaptured).storedLocation().capture()).isEmpty());
        } finally {
            itemStore.set(null, previous);
        }
    }

    @Test
    void summaryPanelShowsRunningCountdownsAndLifeStage() throws Exception {
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
            CompanionSummary summary = CompanionSummaries.build(new CompanionSummaries.Inputs(null, null, ROLE, null,
                    0f, 0f, null, 0.0, needs.getConfigId(), needs.getHunger(), needs.getThirst(),
                    true, breeding.isEnabled(), breeding.getCooldownUntilMs(), breeding.getCooldownStartedAtMs(),
                    breeding.getCooldownDurationMs(), harvest.getUntilMs(), null, 0, 0.0, 0.0, 0, Map.of(),
                    harvest.getStartedAtMs(), harvest.getDurationMs(), null, null,
                    CompanionSummaries.progression(lifeStage)), 1L);
            CompanionRecord record = CompanionRecord.builder(profileId.value(), ROLE,
                    CompanionLocation.live("world", 0, 0, 0)).summary(summary).build();

            LinkedNpcEntry actual = CommandSavedNpcPanelSnapshot.fromSummary(record).apply(baseCard(), null, GAME_RATE);

            assertTrue(actual.breedingCooldownKnown());
            assertTrue(actual.breedingCooldownActive());
            assertEquals(6_000L, actual.breedingCooldownRemainingMs());
            assertTrue(actual.harvestCooldownKnown());
            assertTrue(actual.harvestCooldownActive());
            assertTrue(actual.animalLifecycle().prime());
        }
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
