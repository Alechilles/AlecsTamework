package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.HappinessConfigResolver;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds linked-companion panel entries for command-item UI.
 *
 * <p>This service isolates panel-oriented data assembly (loaded/dead/captured status, display names,
 * health snapshots, and home flags) from command orchestration flows.
 */
final class CommandLinkedPanelEntryService {
    private static final int MAX_TRAIT_INDICATORS = 3;

    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcCaptureService captureService;
    private final CommandNpcNameResolver npcNameResolver;

    CommandLinkedPanelEntryService(CommandLinkedNpcRecordStore linkedNpcRecordStore,
                                   CommandLinkedNpcDeathService deathService,
                                   CommandLinkedNpcCaptureService captureService,
                                   CommandNpcNameResolver npcNameResolver) {
        this.linkedNpcRecordStore = linkedNpcRecordStore;
        this.deathService = deathService;
        this.captureService = captureService;
        this.npcNameResolver = npcNameResolver;
    }

    List<LinkedNpcEntry> buildEntries(Player player,
                                      Store<EntityStore> store,
                                      ItemStack stack,
                                      String toolId) {
        if (player == null || store == null || stack == null || stack.isEmpty()) {
            return List.of();
        }
        List<LinkedNpcRecord> records = linkedNpcRecordStore.read(stack);
        if (records.isEmpty()) {
            return List.of();
        }
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        boolean deadRespawnEnabled = globalConfig != null && globalConfig.isCommandDeadRespawnEnabled();
        World world = player.getWorld();
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        ComponentType<EntityStore, TameworkTraitsComponent> traitType = TameworkTraitsComponent.getComponentType();
        ArrayList<LinkedNpcEntry> entries = new ArrayList<>(records.size());
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            boolean loaded = false;
            boolean dead = false;
            boolean captured = false;
            long deadRespawnRemainingMs = 0L;
            boolean hasHome = record.homePosition != null;
            String displayName = npcNameResolver.resolveCachedUnloadedDisplayName(record);
            if (displayName == null || displayName.isBlank()) {
                displayName = "Unloaded companion (" + abbreviateUuid(record.npcUuid) + ")";
            }
            int health = 0;
            int maxHealth = 0;
            int happiness = 0;
            int maxHappiness = 0;
            int hunger = 0;
            int maxHunger = 0;
            int thirst = 0;
            int maxThirst = 0;
            LinkedNpcTraitIndicator[] traitIndicators = LinkedNpcTraitIndicator.EMPTY;
            if (world != null) {
                Ref<EntityStore> npcRef = world.getEntityRef(record.npcUuid);
                if (npcRef != null && npcRef.isValid()) {
                    NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
                    if (npc != null) {
                        loaded = true;
                        displayName = npcNameResolver.resolveNpcDisplayName(npcRef, store, npc);
                        TameworkCommandLinksComponent links =
                                store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
                        if (links != null && links.hasHome()) {
                            hasHome = true;
                        }
                        HealthSnapshot snapshot = readNpcHealthSnapshot(npcRef, store);
                        if (snapshot != null) {
                            health = snapshot.current;
                            maxHealth = snapshot.max;
                        }
                        HappinessSnapshot happinessSnapshot = readNpcHappinessSnapshot(
                                npcRef,
                                store,
                                happinessType,
                                breedingType
                        );
                        if (happinessSnapshot != null) {
                            happiness = happinessSnapshot.current;
                            maxHappiness = happinessSnapshot.max;
                        }
                        NeedsSnapshot needsSnapshot = readNpcNeedsSnapshot(npcRef, store, needsType);
                        if (needsSnapshot != null) {
                            hunger = needsSnapshot.hungerCurrent;
                            maxHunger = needsSnapshot.hungerMax;
                            thirst = needsSnapshot.thirstCurrent;
                            maxThirst = needsSnapshot.thirstMax;
                        }
                        traitIndicators = readTraitIndicators(npcRef, store, traitType);
                    }
                }
            }
            if (!loaded && deathService != null) {
                CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot = deathService.getDeadSnapshotForTool(
                        record.npcUuid,
                        toolId,
                        player.getUuid()
                );
                if (deadSnapshot != null) {
                    dead = true;
                    String deadName = deadSnapshot.displayName();
                    if (deadName != null && !deadName.isBlank()) {
                        displayName = deadName;
                    }
                    if (deadRespawnEnabled) {
                        deadRespawnRemainingMs = Math.max(0L, deadSnapshot.respawnAvailableAtMs() - System.currentTimeMillis());
                    } else {
                        deadRespawnRemainingMs = -1L;
                    }
                }
            }
            if (!loaded && !dead && captureService != null) {
                CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot capturedSnapshot =
                        captureService.getCapturedSnapshotForTool(record.npcUuid, toolId, player.getUuid());
                if (capturedSnapshot != null) {
                    captured = true;
                    String capturedName = capturedSnapshot.displayName();
                    if (capturedName != null && !capturedName.isBlank()) {
                        displayName = capturedName;
                    }
                }
            }
            entries.add(new LinkedNpcEntry(
                    record.npcUuid,
                    displayName,
                    health,
                    maxHealth,
                    happiness,
                    maxHappiness,
                    hunger,
                    maxHunger,
                    thirst,
                    maxThirst,
                    loaded,
                    hasHome,
                    dead,
                    captured,
                    deadRespawnRemainingMs,
                    traitIndicators
            ));
        }
        return entries;
    }

    private LinkedNpcTraitIndicator[] readTraitIndicators(Ref<EntityStore> npcRef,
                                                          Store<EntityStore> store,
                                                          ComponentType<EntityStore, TameworkTraitsComponent> traitType) {
        if (npcRef == null || !npcRef.isValid() || store == null || traitType == null) {
            return LinkedNpcTraitIndicator.EMPTY;
        }
        TameworkTraitsComponent traits = store.getComponent(npcRef, traitType);
        if (traits == null) {
            return LinkedNpcTraitIndicator.EMPTY;
        }
        TwTraitConfig config = resolveTraitConfig(npcRef, store, traits);
        if (config == null) {
            return LinkedNpcTraitIndicator.EMPTY;
        }
        Map<String, Double> rolledValues = buildRolledValueMap(traits);
        if (rolledValues.isEmpty()) {
            return LinkedNpcTraitIndicator.EMPTY;
        }
        ArrayList<LinkedNpcTraitIndicator> indicators = new ArrayList<>(MAX_TRAIT_INDICATORS);
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition == null) {
                continue;
            }
            String traitId = normalize(definition.getId());
            if (traitId == null) {
                continue;
            }
            Double value = rolledValues.get(traitId);
            if (value == null || !Double.isFinite(value)) {
                continue;
            }
            double min = Math.min(definition.getBreedingMin(), definition.getBreedingMax());
            double max = Math.max(definition.getBreedingMin(), definition.getBreedingMax());
            double defaultValue = clamp(definition.getDefaultValue(), min, max);
            boolean belowDefault = value < defaultValue;
            double fillRatio = belowDefault
                    ? ratioToLowerBound(value, min, defaultValue)
                    : ratioToUpperBound(value, defaultValue, max);
            String label = resolveLabel(definition);
            indicators.add(new LinkedNpcTraitIndicator(
                    resolveIconGlyph(definition),
                    label,
                    buildTooltip(label, value, min, defaultValue, max),
                    fillRatio,
                    !belowDefault,
                    belowDefault
            ));
            if (indicators.size() >= MAX_TRAIT_INDICATORS) {
                break;
            }
        }
        return indicators.isEmpty()
                ? LinkedNpcTraitIndicator.EMPTY
                : indicators.toArray(new LinkedNpcTraitIndicator[0]);
    }

    private HealthSnapshot readNpcHealthSnapshot(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (statType == null) {
            return null;
        }
        EntityStatMap statMap = store.getComponent(npcRef, statType);
        if (statMap == null) {
            return null;
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
        if (healthIndex < 0) {
            return null;
        }
        EntityStatValue value = statMap.get(healthIndex);
        if (value == null) {
            return null;
        }
        int current = Math.max(0, Math.round(value.get()));
        int max = Math.max(1, Math.round(value.getMax()));
        if (current > max) {
            current = max;
        }
        return new HealthSnapshot(current, max);
    }

    private HappinessSnapshot readNpcHappinessSnapshot(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            ComponentType<EntityStore, TameworkHappinessComponent> happinessType,
            ComponentType<EntityStore, TameworkBreedingComponent> breedingType) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        TameworkHappinessComponent happinessComponent = happinessType != null
                ? store.getComponent(npcRef, happinessType)
                : null;
        double value = happinessComponent != null ? happinessComponent.getValue() : Double.NaN;
        if (!Double.isFinite(value) && breedingType != null) {
            TameworkBreedingComponent breedingComponent = store.getComponent(npcRef, breedingType);
            if (breedingComponent != null && Double.isFinite(breedingComponent.getHappiness())) {
                value = breedingComponent.getHappiness();
            }
        }
        if (!Double.isFinite(value)) {
            return null;
        }
        double max = 100.0;
        TwHappinessConfig config = HappinessConfigResolver.resolveConfig(npcRef, store, happinessComponent);
        if (config != null && config.isEnabled()) {
            TwHappinessConfig.ValueSettings values = config.getValues();
            double min = values.getMin();
            max = values.getMax();
            if (max < min) {
                double swap = min;
                min = max;
                max = swap;
            }
            value = clamp(value, min, max);
        } else {
            value = Math.max(0.0, value);
        }
        int roundedMax = Math.max(1, Math.round((float) max));
        int roundedValue = Math.max(0, Math.min(roundedMax, Math.round((float) value)));
        return new HappinessSnapshot(roundedValue, roundedMax);
    }

    private NeedsSnapshot readNpcNeedsSnapshot(Ref<EntityStore> npcRef,
                                               Store<EntityStore> store,
                                               ComponentType<EntityStore, TameworkNeedsComponent> needsType) {
        if (npcRef == null || !npcRef.isValid() || store == null || needsType == null) {
            return null;
        }
        TameworkNeedsComponent needs = store.getComponent(npcRef, needsType);
        if (needs == null) {
            return null;
        }
        TwNeedsConfig config = NeedsConfigResolver.resolveConfig(npcRef, store, needs);
        if (config == null || !config.isEnabled()) {
            return null;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hungerMin = values.getHungerMin();
        double hungerMax = values.getHungerMax();
        double thirstMin = values.getThirstMin();
        double thirstMax = values.getThirstMax();

        int roundedHungerMax = Math.max(1, Math.round((float) hungerMax));
        int roundedThirstMax = Math.max(1, Math.round((float) thirstMax));
        int roundedHunger = Math.max(
                0,
                Math.min(roundedHungerMax, Math.round((float) clamp(needs.getHunger(), hungerMin, hungerMax)))
        );
        int roundedThirst = Math.max(
                0,
                Math.min(roundedThirstMax, Math.round((float) clamp(needs.getThirst(), thirstMin, thirstMax)))
        );
        return new NeedsSnapshot(roundedHunger, roundedHungerMax, roundedThirst, roundedThirstMax);
    }

    private String abbreviateUuid(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        String raw = uuid.toString();
        return raw.length() >= 8 ? raw.substring(0, 8) : raw;
    }

    private TwTraitConfig resolveTraitConfig(Ref<EntityStore> npcRef,
                                             Store<EntityStore> store,
                                             TameworkTraitsComponent traits) {
        String configId = traits.getConfigId();
        if (configId != null && !configId.isBlank()) {
            TwTraitConfig config = TwTraitConfig.resolveById(configId);
            if (config != null) {
                return config;
            }
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return TwTraitConfig.resolveForRole(roleId);
    }

    private Map<String, Double> buildRolledValueMap(TameworkTraitsComponent traits) {
        HashMap<String, Double> values = new HashMap<>();
        for (TameworkTraitsComponent.TraitValue traitValue : traits.getTraitValues()) {
            if (traitValue == null) {
                continue;
            }
            String traitId = normalize(traitValue.getId());
            if (traitId == null || values.containsKey(traitId)) {
                continue;
            }
            double value = traitValue.getValue();
            if (!Double.isFinite(value)) {
                continue;
            }
            values.put(traitId, value);
        }
        return values;
    }

    private String resolveIconGlyph(TwTraitConfig.TraitDefinition definition) {
        String source = resolveLabel(definition);
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "?";
    }

    private String resolveLabel(TwTraitConfig.TraitDefinition definition) {
        String displayName = definition.getDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        String id = definition.getId();
        if (id != null && !id.isBlank()) {
            return id;
        }
        return "Trait";
    }

    private double ratioToUpperBound(double value, double defaultValue, double max) {
        double distance = max - defaultValue;
        if (distance <= 0.0) {
            return 0.0;
        }
        return clamp((value - defaultValue) / distance, 0.0, 1.0);
    }

    private double ratioToLowerBound(double value, double min, double defaultValue) {
        double distance = defaultValue - min;
        if (distance <= 0.0) {
            return 0.0;
        }
        return clamp((defaultValue - value) / distance, 0.0, 1.0);
    }

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String buildTooltip(String label,
                                double value,
                                double min,
                                double defaultValue,
                                double max) {
        double safeMin = Double.isFinite(min) ? min : 0.0;
        double safeMax = Double.isFinite(max) ? max : 0.0;
        if (safeMax < safeMin) {
            double swap = safeMin;
            safeMin = safeMax;
            safeMax = swap;
        }
        double safeDefault = clamp(defaultValue, safeMin, safeMax);
        double safeValue = clamp(value, safeMin, safeMax);
        boolean belowDefault = safeValue < safeDefault;
        double normalized = belowDefault
                ? ratioToLowerBound(safeValue, safeMin, safeDefault)
                : ratioToUpperBound(safeValue, safeDefault, safeMax);
        String boundLabel = belowDefault ? "min" : "max";
        double boundValue = belowDefault ? safeMin : safeMax;
        return label
                + ": "
                + format(safeValue)
                + " / "
                + format(boundValue)
                + " "
                + boundLabel
                + " ("
                + formatPercent(normalized, belowDefault)
                + ")";
    }

    private String format(double value) {
        if (!Double.isFinite(value)) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String formatPercent(double ratio, boolean negativeDirection) {
        if (!Double.isFinite(ratio)) {
            return "0%";
        }
        int percent = (int) Math.round(clamp(ratio, 0.0, 1.0) * 100.0);
        if (negativeDirection && percent > 0) {
            return "-" + percent + "%";
        }
        return percent + "%";
    }

    private static final class HealthSnapshot {
        private final int current;
        private final int max;

        private HealthSnapshot(int current, int max) {
            this.current = current;
            this.max = max;
        }
    }

    private static final class HappinessSnapshot {
        private final int current;
        private final int max;

        private HappinessSnapshot(int current, int max) {
            this.current = current;
            this.max = max;
        }
    }

    private static final class NeedsSnapshot {
        private final int hungerCurrent;
        private final int hungerMax;
        private final int thirstCurrent;
        private final int thirstMax;

        private NeedsSnapshot(int hungerCurrent, int hungerMax, int thirstCurrent, int thirstMax) {
            this.hungerCurrent = hungerCurrent;
            this.hungerMax = hungerMax;
            this.thirstCurrent = thirstCurrent;
            this.thirstMax = thirstMax;
        }
    }
}
