package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves active equilibrium happiness modifiers (need bands, population bands) for an NPC.
 */
public final class CompanionHappinessModifierService {
    private static final double PERCENT_EPSILON = 0.000001;
    private static final double MIN_DISPOSITION_MULTIPLIER = 0.01;

    private CompanionHappinessModifierService() {
    }

    @Nonnull
    public static ModifierSnapshot resolve(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store,
                                           @Nullable TwHappinessConfig happinessConfig) {
        if (happinessConfig == null || !happinessConfig.isEnabled()) {
            return new ModifierSnapshot(50.0, 50.0, List.of());
        }
        double baseSetpoint = happinessConfig.getEquilibrium().getBaseSetpoint();
        ArrayList<ModifierEntry> modifiers = new ArrayList<>();
        double offsetTotal = 0.0;
        double dispositionMultiplier = resolveDispositionMultiplier(npcRef, store);

        TameworkNeedsComponent needs = resolveNeedsComponent(npcRef, store);
        TwNeedsConfig needsConfig = NeedsConfigResolver.resolveConfig(npcRef, store, needs);
        if (needs != null && needsConfig != null && needsConfig.isEnabled()) {
            offsetTotal += resolveNeedOffset(
                    "hunger",
                    "Hunger",
                    happinessConfig.getModifiers().getHunger(),
                    needs.getHunger(),
                    needsConfig.getValues().getHungerMin(),
                    needsConfig.getValues().getHungerMax(),
                    dispositionMultiplier,
                    modifiers
            );
            offsetTotal += resolveNeedOffset(
                    "thirst",
                    "Thirst",
                    happinessConfig.getModifiers().getThirst(),
                    needs.getThirst(),
                    needsConfig.getValues().getThirstMin(),
                    needsConfig.getValues().getThirstMax(),
                    dispositionMultiplier,
                    modifiers
            );
        }
        offsetTotal += resolvePopulationOffset(npcRef, store, happinessConfig, dispositionMultiplier, modifiers);

        double ownerNearbyOffset = happinessConfig.getModifiers().getOwnerNearbyOffset();
        if (Math.abs(ownerNearbyOffset) > PERCENT_EPSILON) {
            double adjustedOwnerNearbyOffset = applyDispositionToOffset(ownerNearbyOffset, dispositionMultiplier);
            if (Math.abs(adjustedOwnerNearbyOffset) > PERCENT_EPSILON) {
                modifiers.add(new ModifierEntry("owner_nearby", "Owner Nearby", adjustedOwnerNearbyOffset));
                offsetTotal += adjustedOwnerNearbyOffset;
            }
        }

        double target = baseSetpoint + offsetTotal;
        return new ModifierSnapshot(baseSetpoint, target, List.copyOf(modifiers));
    }

    @Nullable
    private static TameworkNeedsComponent resolveNeedsComponent(@Nullable Ref<EntityStore> npcRef,
                                                                @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return null;
        }
        return store.getComponent(npcRef, needsType);
    }

    private static double resolvePopulationOffset(@Nullable Ref<EntityStore> npcRef,
                                                  @Nullable Store<EntityStore> store,
                                                  @Nonnull TwHappinessConfig happinessConfig,
                                                  double dispositionMultiplier,
                                                  @Nonnull List<ModifierEntry> outModifiers) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return 0.0;
        }
        TwHappinessConfig.PopulationModifierSettings settings = happinessConfig.getModifiers().getPopulation();
        if (!settings.isEnabled()) {
            return 0.0;
        }
        double radius = settings.getRadius();
        if (radius <= 0.0) {
            return 0.0;
        }
        TwHappinessConfig.PopulationBandSettings[] bands = settings.getBands();
        if (bands.length == 0) {
            return 0.0;
        }
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();
        if (npcType == null || transformType == null) {
            return 0.0;
        }
        NPCEntity sourceNpc = store.getComponent(npcRef, npcType);
        TransformComponent sourceTransform = store.getComponent(npcRef, transformType);
        if (sourceNpc == null || sourceTransform == null) {
            return 0.0;
        }
        String sourceRoleId = resolveRoleId(sourceNpc);
        if (sourceRoleId == null || sourceRoleId.isBlank()) {
            return 0.0;
        }
        TwBreedingConfig breedingConfig = TwBreedingConfig.resolveForRole(sourceRoleId);
        String sourceTypeKey = resolvePopulationTypeKey(sourceRoleId, breedingConfig);
        if (sourceTypeKey == null || sourceTypeKey.isBlank()) {
            return 0.0;
        }
        int nearbyCount = countNearbyPopulation(store, npcType, transformType, sourceNpc, sourceTransform.getPosition(), radius, sourceTypeKey, breedingConfig);
        TwHappinessConfig.PopulationBandSettings band = findPopulationBand(settings, nearbyCount);
        if (band == null) {
            return 0.0;
        }
        double offset = band.getOffset();
        if (!Double.isFinite(offset) || Math.abs(offset) <= PERCENT_EPSILON) {
            return 0.0;
        }
        double adjustedOffset = applyDispositionToOffset(offset, dispositionMultiplier);
        if (Math.abs(adjustedOffset) <= PERCENT_EPSILON) {
            return 0.0;
        }
        String suffix = band.getLabel();
        if (suffix == null || suffix.isBlank()) {
            suffix = band.getId();
        }
        if (suffix == null || suffix.isBlank()) {
            suffix = "Band";
        }
        String entryId = "population_" + normalizeToken(suffix);
        String entryLabel = "Population: " + suffix;
        outModifiers.add(new ModifierEntry(entryId, entryLabel, adjustedOffset));
        return adjustedOffset;
    }

    private static double resolveNeedOffset(@Nonnull String idPrefix,
                                            @Nonnull String labelPrefix,
                                            @Nonnull TwHappinessConfig.NeedModifierSettings modifierSettings,
                                            double currentValue,
                                            double minValue,
                                            double maxValue,
                                            double dispositionMultiplier,
                                            @Nonnull List<ModifierEntry> outModifiers) {
        if (!modifierSettings.isEnabled()) {
            return 0.0;
        }
        if (!Double.isFinite(currentValue) || !Double.isFinite(minValue) || !Double.isFinite(maxValue)) {
            return 0.0;
        }
        double span = maxValue - minValue;
        if (span <= 0.0) {
            return 0.0;
        }
        double percent = clamp(((currentValue - minValue) / span) * 100.0, 0.0, 100.0);
        TwHappinessConfig.NeedBandSettings band = findBand(modifierSettings, percent);
        if (band == null) {
            return 0.0;
        }
        double offset = band.getOffset();
        if (!Double.isFinite(offset) || Math.abs(offset) <= PERCENT_EPSILON) {
            return 0.0;
        }
        double adjustedOffset = applyDispositionToOffset(offset, dispositionMultiplier);
        if (Math.abs(adjustedOffset) <= PERCENT_EPSILON) {
            return 0.0;
        }
        String suffix = band.getLabel();
        if (suffix == null || suffix.isBlank()) {
            suffix = band.getId();
        }
        if (suffix == null || suffix.isBlank()) {
            suffix = "Band";
        }
        String entryId = idPrefix + "_" + normalizeToken(suffix);
        String entryLabel = labelPrefix + ": " + suffix;
        outModifiers.add(new ModifierEntry(entryId, entryLabel, adjustedOffset));
        return adjustedOffset;
    }

    private static double resolveDispositionMultiplier(@Nullable Ref<EntityStore> npcRef,
                                                       @Nullable Store<EntityStore> store) {
        double multiplier = TraitModifierService.resolveMultiplier(
                npcRef,
                store,
                "HappinessGainMultiplier",
                1.0
        );
        return sanitizeDispositionMultiplier(multiplier);
    }

    static double applyDispositionToOffset(double offset, double dispositionMultiplier) {
        if (!Double.isFinite(offset) || Math.abs(offset) <= PERCENT_EPSILON) {
            return 0.0;
        }
        double sanitizedMultiplier = sanitizeDispositionMultiplier(dispositionMultiplier);
        if (offset > 0.0) {
            return offset * sanitizedMultiplier;
        }
        return offset / sanitizedMultiplier;
    }

    private static double sanitizeDispositionMultiplier(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            return 1.0;
        }
        return Math.max(MIN_DISPOSITION_MULTIPLIER, multiplier);
    }

    @Nullable
    private static TwHappinessConfig.NeedBandSettings findBand(@Nonnull TwHappinessConfig.NeedModifierSettings settings,
                                                               double percent) {
        TwHappinessConfig.NeedBandSettings[] bands = settings.getBands();
        for (TwHappinessConfig.NeedBandSettings band : bands) {
            if (band == null) {
                continue;
            }
            if (matchesBand(percent, band)) {
                return band;
            }
        }
        return null;
    }

    @Nullable
    private static TwHappinessConfig.PopulationBandSettings findPopulationBand(@Nonnull TwHappinessConfig.PopulationModifierSettings settings,
                                                                               int nearbyCount) {
        TwHappinessConfig.PopulationBandSettings[] bands = settings.getBands();
        for (TwHappinessConfig.PopulationBandSettings band : bands) {
            if (band == null) {
                continue;
            }
            if (matchesPopulationBand(nearbyCount, band)) {
                return band;
            }
        }
        return null;
    }

    private static boolean matchesPopulationBand(int nearbyCount, @Nonnull TwHappinessConfig.PopulationBandSettings band) {
        int min = band.getMinCount();
        int max = band.getMaxCount();
        if (nearbyCount < min) {
            return false;
        }
        if (max < 0) {
            return true;
        }
        return nearbyCount <= max;
    }

    private static int countNearbyPopulation(@Nonnull Store<EntityStore> store,
                                             @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                                             @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
                                             @Nonnull NPCEntity sourceNpc,
                                             @Nonnull Vector3d sourcePosition,
                                             double radius,
                                             @Nonnull String sourceTypeKey,
                                             @Nullable TwBreedingConfig breedingConfig) {
        final int[] nearbyCount = new int[] {0};
        final double radiusSq = Math.max(0.0, radius) * Math.max(0.0, radius);
        store.forEachChunk(
                Query.and(npcType, transformType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        NPCEntity candidateNpc = chunk.getComponent(i, npcType);
                        TransformComponent candidateTransform = chunk.getComponent(i, transformType);
                        if (candidateNpc == null
                                || candidateTransform == null
                                || Objects.equals(candidateNpc.getUuid(), sourceNpc.getUuid())) {
                            continue;
                        }
                        Vector3d candidatePosition = candidateTransform.getPosition();
                        double dx = candidatePosition.x - sourcePosition.x;
                        double dy = candidatePosition.y - sourcePosition.y;
                        double dz = candidatePosition.z - sourcePosition.z;
                        double distanceSq = dx * dx + dy * dy + dz * dz;
                        if (!Double.isFinite(distanceSq) || distanceSq > radiusSq) {
                            continue;
                        }
                        String candidateRoleId = resolveRoleId(candidateNpc);
                        String candidateTypeKey = resolvePopulationTypeKey(candidateRoleId, breedingConfig);
                        if (candidateTypeKey != null && candidateTypeKey.equals(sourceTypeKey)) {
                            nearbyCount[0]++;
                        }
                    }
                }
        );
        return nearbyCount[0];
    }

    @Nullable
    private static String resolvePopulationTypeKey(@Nullable String roleId, @Nullable TwBreedingConfig breedingConfig) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        String canonicalRole = roleId;
        if (breedingConfig != null) {
            TwBreedingConfig.RoleFamily family = breedingConfig.resolveLifecycleFamilyForRole(roleId);
            if (family != null && family.getAdultRoleId() != null && !family.getAdultRoleId().isBlank()) {
                canonicalRole = family.getAdultRoleId();
            }
        }
        return canonicalRole.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String resolveRoleId(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0) {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin != null) {
                String resolved = plugin.getName(roleIndex);
                if (resolved != null && !resolved.isBlank()) {
                    return resolved;
                }
            }
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        return null;
    }

    private static boolean matchesBand(double percent, @Nonnull TwHappinessConfig.NeedBandSettings band) {
        double min = band.getMinPercent();
        double max = band.getMaxPercent();
        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }
        boolean upperInclusive = Math.abs(max - 100.0) <= PERCENT_EPSILON;
        if (percent < min) {
            return false;
        }
        if (upperInclusive) {
            return percent <= max;
        }
        return percent < max;
    }

    @Nonnull
    private static String normalizeToken(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase();
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(c);
            } else if (c == ' ' || c == '-' || c == '_') {
                builder.append('_');
            }
        }
        if (builder.length() == 0) {
            return "unknown";
        }
        return builder.toString();
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /**
     * One active equilibrium modifier contribution.
     */
    public record ModifierEntry(String id, String label, double value) {
    }

    /**
     * Effective base/target snapshot used by happiness updates and UI presentation.
     */
    public record ModifierSnapshot(double baseSetpoint, double target, List<ModifierEntry> modifiers) {
    }
}
