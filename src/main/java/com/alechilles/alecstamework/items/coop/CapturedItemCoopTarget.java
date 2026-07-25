package com.alechilles.alecstamework.items.coop;

import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Engine-neutral managed-coop evidence frozen from one exact loaded block.
 *
 * <p>The target carries only configured admission policy and physical slot identity. Vanilla
 * coop occupancy is not a second authority; canonical replacement projections decide which
 * configured slot may be reserved.</p>
 */
public record CapturedItemCoopTarget(
        @Nonnull String worldKey,
        @Nonnull String coopId,
        int x,
        int y,
        int z,
        int maxResidents,
        @Nonnull Set<String> acceptedRoleIds,
        boolean requireTamed,
        boolean requireOwner,
        boolean ownerRestricted
) {
    public CapturedItemCoopTarget {
        worldKey = requireText(worldKey, "Managed coop world");
        coopId = requireText(coopId, "Managed coop ID");
        if (maxResidents <= 0) {
            throw new IllegalArgumentException(
                    "Managed coop resident capacity must be positive"
            );
        }
        acceptedRoleIds = normalizeRoles(acceptedRoleIds);
    }

    /** Returns configured physical slots in stable resident-index order. */
    @Nonnull
    public List<CoopSlotKey> slots() {
        ArrayList<CoopSlotKey> slots = new ArrayList<>(maxResidents);
        for (int index = 0; index < maxResidents; index++) {
            slots.add(new CoopSlotKey(
                    worldKey, coopId, x, y, z, index
            ));
        }
        return List.copyOf(slots);
    }

    /** Returns whether this managed config admits the canonical role. */
    public boolean acceptsRole(String roleId) {
        if (acceptedRoleIds.isEmpty()) {
            return roleId != null && !roleId.isBlank();
        }
        return roleId != null
                && acceptedRoleIds.contains(
                roleId.trim().toLowerCase(Locale.ROOT)
        );
    }

    private static Set<String> normalizeRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                normalized.add(role.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
