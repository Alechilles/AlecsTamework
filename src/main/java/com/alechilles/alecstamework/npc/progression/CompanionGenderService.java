package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Resolves and persists optional binary companion gender state from breeding config.
 */
public final class CompanionGenderService {
    public static final String MALE = "Male";
    public static final String FEMALE = "Female";

    private CompanionGenderService() {
    }

    @Nullable
    public static String ensureGender(@Nullable Ref<EntityStore> npcRef,
                                      @Nullable Store<EntityStore> store,
                                      @Nullable String roleIdHint) {
        return ensureGender(npcRef, store, roleIdHint, null, null);
    }

    @Nullable
    public static String ensureGender(@Nullable Ref<EntityStore> npcRef,
                                      @Nullable Store<EntityStore> store,
                                      @Nullable String roleIdHint,
                                      @Nullable TwBreedingConfig configHint,
                                      @Nullable TwBreedingConfig.Gender preferredGender) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        String roleId = firstNonBlank(roleIdHint, CompanionRoleIdResolver.resolveRoleId(npcRef, store));
        TwBreedingConfig config = configHint != null
                ? configHint
                : roleId != null ? TwBreedingConfig.resolveForRole(roleId) : null;
        TwBreedingConfig.GenderSettings settings = config != null ? config.resolveGender(roleId) : null;
        if (settings == null || !TameworkRuntimeSettings.breedingGenderEnabled(settings.isEnabled())) {
            return null;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return null;
        }
        TameworkLifeStageComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            return null;
        }
        String existing = normalizeGender(component.getGender());
        if (existing != null) {
            return existing;
        }
        String resolved = preferredGender != null
                ? preferredGender.toConfigValue()
                : resolveGender(npcRef, store, roleId, config);
        if (resolved == null) {
            return null;
        }
        component.setGender(resolved);
        store.putComponent(npcRef, type, component);
        return resolved;
    }

    @Nullable
    public static String resolveGender(@Nullable Ref<EntityStore> npcRef,
                                       @Nullable Store<EntityStore> store,
                                       @Nullable String roleIdHint,
                                       @Nullable TwBreedingConfig config) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        String roleId = firstNonBlank(roleIdHint, CompanionRoleIdResolver.resolveRoleId(npcRef, store));
        TwBreedingConfig effectiveConfig = config != null
                ? config
                : roleId != null ? TwBreedingConfig.resolveForRole(roleId) : null;
        TwBreedingConfig.GenderSettings settings = effectiveConfig != null
                ? effectiveConfig.resolveGender(roleId)
                : null;
        if (settings == null || !TameworkRuntimeSettings.breedingGenderEnabled(settings.isEnabled())) {
            return null;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        TameworkLifeStageComponent component = type != null ? store.getComponent(npcRef, type) : null;
        String existing = component != null ? normalizeGender(component.getGender()) : null;
        if (existing != null) {
            return existing;
        }
        TwBreedingConfig.Gender roleGender = resolveRoleGender(effectiveConfig, roleId, component);
        if (roleGender != null) {
            return roleGender.toConfigValue();
        }
        return settings.selectGender(stableGenderRoll(npcRef, store)).toConfigValue();
    }

    @Nullable
    public static String normalizeGender(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        TwBreedingConfig.Gender gender = TwBreedingConfig.Gender.fromConfigValue(value.trim());
        return gender == null ? null : gender.toConfigValue();
    }

    public static boolean different(@Nullable String left, @Nullable String right) {
        String normalizedLeft = normalizeGender(left);
        String normalizedRight = normalizeGender(right);
        return normalizedLeft != null && normalizedRight != null && !normalizedLeft.equals(normalizedRight);
    }

    @Nullable
    private static TwBreedingConfig.Gender resolveRoleGender(@Nullable TwBreedingConfig config,
                                                            @Nullable String roleId,
                                                            @Nullable TameworkLifeStageComponent component) {
        if (config == null) {
            return null;
        }
        TwBreedingConfig.RoleFamily family = null;
        if (roleId != null && !roleId.isBlank()) {
            family = config.resolveLifecycleFamilyForRole(roleId);
            if (family != null) {
                TwBreedingConfig.Gender gender = family.resolveGenderForAdultRole(roleId);
                if (gender != null) {
                    return gender;
                }
            }
        }
        String adultRoleId = component != null ? component.getAdultRoleId() : null;
        if (adultRoleId != null && !adultRoleId.isBlank()) {
            if (family == null) {
                family = config.resolveLifecycleFamilyForRole(adultRoleId);
            }
            if (family != null) {
                return family.resolveGenderForAdultRole(adultRoleId);
            }
        }
        return null;
    }

    private static double stableGenderRoll(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        long seed = 0L;
        if (npcRef != null && npcRef.isValid() && store != null) {
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            UUID uuid = npc != null ? npc.getUuid() : null;
            if (uuid != null) {
                seed = uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 17);
            }
        }
        if (seed == 0L) {
            seed = 0x5DEECE66DL;
        }
        long mixed = mix64(seed);
        long positive = mixed >>> 11;
        return positive * 0x1.0p-53;
    }

    private static long mix64(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    @Nullable
    public static String toDisplayLabel(@Nullable String gender) {
        String normalized = normalizeGender(gender);
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if ("male".equals(lower)) {
            return MALE;
        }
        if ("female".equals(lower)) {
            return FEMALE;
        }
        return null;
    }
}
