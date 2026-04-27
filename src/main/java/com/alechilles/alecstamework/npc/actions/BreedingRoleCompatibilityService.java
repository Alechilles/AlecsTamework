package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Resolves whether two adult roles are compatible breeding partners under a pairing policy.
 */
final class BreedingRoleCompatibilityService {
    boolean canPair(@Nullable String sourceRoleId,
                    @Nullable String candidateRoleId,
                    @Nullable TwBreedingConfig breedingConfig,
                    @Nullable TwBreedingConfig.PairingSettings pairing) {
        TwBreedingConfig.RoleCompatibility compatibility = pairing != null
                ? pairing.getRoleCompatibility()
                : TwBreedingConfig.RoleCompatibility.SAME_ROLE;
        return switch (compatibility) {
            case ANY -> true;
            case SAME_ROLE -> rolesMatch(sourceRoleId, candidateRoleId);
            case SAME_LIFECYCLE_FAMILY -> rolesMatch(sourceRoleId, candidateRoleId)
                    || sameLifecycleFamily(sourceRoleId, candidateRoleId, breedingConfig);
            case DIFFERENT_FAMILY_ROLE -> differentFamilyRole(sourceRoleId, candidateRoleId, breedingConfig);
        };
    }

    private boolean sameLifecycleFamily(@Nullable String sourceRoleId,
                                        @Nullable String candidateRoleId,
                                        @Nullable TwBreedingConfig breedingConfig) {
        if (breedingConfig == null || sourceRoleId == null || candidateRoleId == null) {
            return false;
        }
        TwBreedingConfig.RoleFamily sourceFamily = breedingConfig.resolveLifecycleFamilyForRole(sourceRoleId);
        TwBreedingConfig.RoleFamily candidateFamily = breedingConfig.resolveLifecycleFamilyForRole(candidateRoleId);
        if (sourceFamily == null || candidateFamily == null) {
            return false;
        }
        if (!sourceFamily.matchesAdultRole(sourceRoleId) || !candidateFamily.matchesAdultRole(candidateRoleId)) {
            return false;
        }
        return rolesMatch(sourceFamily.getAdultRoleId(), candidateFamily.getAdultRoleId());
    }

    private boolean differentFamilyRole(@Nullable String sourceRoleId,
                                        @Nullable String candidateRoleId,
                                        @Nullable TwBreedingConfig breedingConfig) {
        if (!sameLifecycleFamily(sourceRoleId, candidateRoleId, breedingConfig)) {
            return false;
        }
        return !rolesMatch(sourceRoleId, candidateRoleId);
    }

    private static boolean rolesMatch(@Nullable String left, @Nullable String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        return normalizeRoleId(left).equals(normalizeRoleId(right));
    }

    private static String normalizeRoleId(String roleId) {
        String normalized = roleId.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.lastIndexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            return normalized.substring(separator + 1);
        }
        return normalized;
    }
}
