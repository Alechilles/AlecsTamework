package com.alechilles.alecstamework.runtime;

import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.assets.*;
import com.alechilles.alecstamework.metrics.HStatsServerUuidFile;
import com.alechilles.alecstamework.runtime.activation.TameworkAssetActivationEvidenceAdapter;
import com.alechilles.alecstamework.runtime.activation.TameworkEffectiveAssetFact;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/** Adapts effective Tamework assets into immutable startup evidence. */
public final class TameworkRuntimeActivationEvidenceSource {
    private TameworkRuntimeActivationEvidenceSource() {
    }

    /** Collects current effective assets without starting runtime work. */
    public static List<TameworkEffectiveAssetFact> collect() {
        List<TameworkEffectiveAssetFact> facts = new ArrayList<>();
        addRoleFact(facts, TameworkRuntimeModule.CORE_OWNERSHIP,
                "Tamework/Companion", TwCompanionConfig.getAssetMap(),
                TwCompanionConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.INTERACTIONS,
                "Tamework/Interactions", TwInteractionConfig.getAssetMap(),
                TwInteractionConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.CAPTURE,
                "Tamework/CapturePolicies", TwCapturePolicyConfig.getAssetMap(),
                TwCapturePolicyConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.GENERIC_PERSISTENCE,
                "Tamework/PopulationGroups", TwPopulationGroupConfig.getAssetMap(),
                TwPopulationGroupConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.BONDED_PERSISTENCE,
                "Tamework/BondedCompanions/Rosters", TwBondedCompanionRosterConfig.getAssetMap(),
                ignored -> true, config -> Arrays.asList(config.getAllowedRoles()));
        addItemConfigFact(facts, TameworkRuntimeModule.NAMING_ITEMS,
                "Tamework/Items/Naming", TwNameItemConfig.getAssetMap(),
                ignored -> true, config -> Collections.singletonList(config.getItemId()));
        addItemConfigFact(facts, TameworkRuntimeModule.SPAWNER_ITEMS,
                "Tamework/Items/Spawners", TwSpawnerConfig.getAssetMap(),
                ignored -> true, config -> Arrays.asList(
                        config.getEmptyItemId(), config.getFilledItemId()));
        addItemConfigFact(facts, TameworkRuntimeModule.COMMAND_ITEMS,
                "Tamework/Items/Commands", TwCommandItemConfig.getAssetMap(),
                TwCommandItemConfig::isEnabled, config -> Arrays.asList(config.getItemIds()));
        addRoleFact(facts, TameworkRuntimeModule.MOUNTS,
                "Tamework/Mounts/Glide", TwMountedGlideConfig.getAssetMap(),
                TwMountedGlideConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addEnabledFact(facts, TameworkRuntimeModule.MOUNTS,
                "Tamework/Mounts/Descent", TwMountedDescentConfig.getAssetMap(),
                TwMountedDescentConfig::isEnabled,
                config -> !config.getProfiles().isEmpty());
        addItemFact(facts, TameworkRuntimeModule.AVATAR_FLIGHT,
                "Item/Tamework_Flightmasters_Talisman", "Tamework_Flightmasters_Talisman");
        addEnabledFact(facts, TameworkRuntimeModule.AVATAR_FLIGHT,
                "Tamework/AvatarFlight", TwAvatarFlightConfig.getAssetMap(),
                TwAvatarFlightConfig::isEnabled, ignored -> true);
        addRoleFact(facts, TameworkRuntimeModule.COMPANION_MOVEMENT,
                "Tamework/CompanionMovement", TwCompanionMovementConfig.getAssetMap(),
                TwCompanionMovementConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.ATTACHMENTS,
                "Tamework/Attachments/Migration", TwAttachmentMigrationConfig.getAssetMap(),
                TwAttachmentMigrationConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.ATTACHMENTS,
                "Tamework/Attachments/Display", TwAttachmentDisplayConfig.getAssetMap(),
                TwAttachmentDisplayConfig::isEnabled,
                config -> Arrays.stream(config.getEntries())
                        .flatMap(entry -> Arrays.stream(entry.getAppliesTo().getRoleIds()))
                        .toList());
        addRoleFact(facts, TameworkRuntimeModule.ATTACHMENTS,
                "Tamework/Attachments/Dynamic", TwDynamicAttachmentsConfig.getAssetMap(),
                TwDynamicAttachmentsConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.NEEDS,
                "Tamework/Needs", TwNeedsConfig.getAssetMap(),
                TwNeedsConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.HAPPINESS,
                "Tamework/Happiness", TwHappinessConfig.getAssetMap(),
                TwHappinessConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.FOOD,
                "Tamework/Food", TwFoodConfig.getAssetMap(),
                TwFoodConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.BREEDING,
                "Tamework/Breeding", TwBreedingConfig.getAssetMap(),
                TwBreedingConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.LEVELING,
                "Tamework/Leveling", TwLevelingConfig.getAssetMap(),
                TwLevelingConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.TRAITS,
                "Tamework/Traits", TwTraitConfig.getAssetMap(),
                TwTraitConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addRoleFact(facts, TameworkRuntimeModule.TALENTS,
                "Tamework/Talents", TwTalentConfig.getAssetMap(),
                TwTalentConfig::isEnabled, config -> Arrays.asList(config.getRoleIds()));
        addEnabledFact(facts, TameworkRuntimeModule.COOPS,
                "Tamework/Items/Coops", TwCoopConfig.getAssetMap(),
                TwCoopConfig::isEnabled,
                config -> config.getBlockTypeIds().length > 0
                        || config.getLifecycleRules().getAcceptedRoleIds().length > 0);
        addItemFact(facts, TameworkRuntimeModule.SCARECROWS,
                "Item/Tamework_Scarecrow", "Tamework_Scarecrow");
        addItemFact(facts, TameworkRuntimeModule.DAMAGE_PROJECTILES,
                "Item/Weapon_Arrow_Tranquilizer", "Weapon_Arrow_Tranquilizer");
        addEnabledFact(facts, TameworkRuntimeModule.DEBUG_SELF_TEST,
                "Tamework/Debug", TwDebugConfig.getAssetMap(),
                TwDebugConfig::isEnabled,
                TameworkRuntimeActivationEvidenceSource::usesDebugRuntimeSystems);
        if (HStatsServerUuidFile.readEnabledServerUuid(Path.of("hstats-server-uuid.txt")) != null) {
            facts.add(TameworkEffectiveAssetFact.of(
                    TameworkRuntimeModule.HSTATS, true, "hstats-server-uuid.txt",
                    Set.of("enabled"), Set.of()
            ));
        }
        return List.copyOf(facts);
    }

    private static <T extends JsonAsset<String>> void addRoleFact(
            List<TameworkEffectiveAssetFact> facts,
            TameworkRuntimeModule module,
            String source,
            DefaultAssetMap<String, T> assetMap,
            java.util.function.Predicate<T> enabled,
            java.util.function.Function<T, ? extends Collection<String>> roles
    ) {
        facts.add(TameworkAssetActivationEvidenceAdapter.roleConfigs(
                module, source, assetValues(assetMap), enabled, roles,
                TameworkRuntimeActivationEvidenceSource::roleExists
        ));
    }

    private static <T extends JsonAsset<String>> void addItemConfigFact(
            List<TameworkEffectiveAssetFact> facts,
            TameworkRuntimeModule module,
            String source,
            DefaultAssetMap<String, T> assetMap,
            java.util.function.Predicate<T> enabled,
            java.util.function.Function<T, ? extends Collection<String>> itemIds
    ) {
        facts.add(TameworkAssetActivationEvidenceAdapter.itemConfigs(
                module, source, assetValues(assetMap), enabled, itemIds,
                TameworkRuntimeActivationEvidenceSource::itemAssetExists
        ));
    }

    private static void addItemFact(
            List<TameworkEffectiveAssetFact> facts,
            TameworkRuntimeModule module,
            String source,
            String itemId
    ) {
        boolean exists = itemAssetExists(itemId);
        facts.add(TameworkEffectiveAssetFact.of(
                module,
                exists,
                source,
                Set.of(),
                exists ? Set.of(itemId) : Set.of()
        ));
    }

    private static <T extends JsonAsset<String>> void addEnabledFact(
            List<TameworkEffectiveAssetFact> facts,
            TameworkRuntimeModule module,
            String source,
            DefaultAssetMap<String, T> assetMap,
            java.util.function.Predicate<T> enabled,
            java.util.function.Predicate<T> hasContent
    ) {
        facts.add(TameworkAssetActivationEvidenceAdapter.enabledConfigs(
                module, source, assetValues(assetMap), enabled, hasContent
        ));
    }

    private static <T extends JsonAsset<String>> Collection<T> assetValues(
            @Nullable DefaultAssetMap<String, T> assetMap
    ) {
        return assetMap == null || assetMap.getAssetMap() == null
                ? List.of() : assetMap.getAssetMap().values();
    }

    private static boolean itemAssetExists(String itemId) {
        DefaultAssetMap<String, Item> itemMap = Item.getAssetMap();
        if (itemMap == null || itemMap.getAssetMap() == null) {
            return false;
        }
        String normalized = ItemFeatureRegistry.normalizeStateItemId(itemId);
        return itemMap.getAsset(itemId) != null
                || (normalized != null && itemMap.getAsset(normalized) != null);
    }

    private static boolean roleExists(String roleId) {
        NPCPlugin plugin = NPCPlugin.get();
        return plugin != null && plugin.getIndex(roleId) >= 0;
    }

    private static boolean usesDebugRuntimeSystems(TwDebugConfig config) {
        TwDebugConfig.DebugCommandsSection commands = config.getDebugCommands();
        return commands.isRide()
                || commands.isFlyingCompanion()
                || commands.isAvatarFlight();
    }
}
