package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Supplies role-configured progression defaults for durable bonded profiles
 * captured before their live projection initializes its components.
 */
final class BondedCompanionProgressionPresentation {
    private BondedCompanionProgressionPresentation() {
    }

    @Nonnull
    static Map<String, String> enrich(
            @Nonnull Map<String, String> existing,
            @Nullable String roleId,
            @Nonnull RoleConfigResolver resolver
    ) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(existing);
        RoleConfigs configs = resolver.resolve(roleId);
        if (configs == null) {
            return Map.copyOf(result);
        }
        if (configs.levelingConfigId() != null) {
            result.putIfAbsent("level", "1");
            result.putIfAbsent("levelingConfigId", configs.levelingConfigId());
        }
        if (configs.talentConfigId() != null) {
            result.putIfAbsent("talentConfigId", configs.talentConfigId());
            result.putIfAbsent("talentSpentPoints", "0");
        }
        return Map.copyOf(result);
    }

    @Nullable
    static RoleConfigs resolveLive(@Nullable String roleId) {
        TwLevelingConfig leveling = TwLevelingConfig.resolveForRole(roleId);
        TwTalentConfig talents = TwTalentConfig.resolveForRole(roleId);
        String levelingId = enabledId(leveling == null ? null : leveling.getId(),
                leveling != null && leveling.isEnabled());
        String talentId = enabledId(talents == null ? null : talents.getId(),
                talents != null && talents.isEnabled());
        return levelingId == null && talentId == null
                ? null : new RoleConfigs(levelingId, talentId);
    }

    @Nullable
    private static String enabledId(@Nullable String id, boolean enabled) {
        return enabled && id != null && !id.isBlank() ? id.trim() : null;
    }

    @FunctionalInterface
    interface RoleConfigResolver {
        @Nullable RoleConfigs resolve(@Nullable String roleId);
    }

    record RoleConfigs(@Nullable String levelingConfigId,
                       @Nullable String talentConfigId) {
        RoleConfigs {
            levelingConfigId = normalize(levelingConfigId);
            talentConfigId = normalize(talentConfigId);
        }

        @Nullable
        private static String normalize(@Nullable String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
