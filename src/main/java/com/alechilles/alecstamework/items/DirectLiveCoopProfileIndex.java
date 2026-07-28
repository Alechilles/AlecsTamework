package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves live runtime aliases to canonical profile identities for managed-coop capture.
 *
 * <p>Spawner release rotates the runtime NPC alias while retaining the original profile ID.
 * Unprofiled NPCs use their alias as the deterministic first profile ID. Ambiguous or non-active
 * projected aliases are rejected instead of being adopted as a second companion profile.</p>
 */
final class DirectLiveCoopProfileIndex {
    private final Map<UUID, CompanionProfileProjectionState> profilesByAlias;
    private final Set<UUID> ambiguousAliases;

    DirectLiveCoopProfileIndex(
            @Nonnull Map<ProfileId, CompanionProfileProjectionState> profiles
    ) {
        profilesByAlias = new HashMap<>();
        ambiguousAliases = new HashSet<>();
        for (CompanionProfileProjectionState profile : profiles.values()) {
            if (profile == null || profile.currentAlias() == null) {
                continue;
            }
            UUID alias = profile.currentAlias().value();
            CompanionProfileProjectionState existing =
                    profilesByAlias.putIfAbsent(alias, profile);
            if (existing != null
                    && !existing.profileId().equals(profile.profileId())) {
                ambiguousAliases.add(alias);
                profilesByAlias.remove(alias);
            }
        }
    }

    /**
     * Returns the stable profile ID that may enter a coop, or {@code null} when projected state
     * proves the live alias is not an eligible active companion.
     */
    @Nullable
    ProfileId captureProfileId(@Nonnull UUID alias) {
        if (ambiguousAliases.contains(alias)) {
            return null;
        }
        CompanionProfileProjectionState profile = profilesByAlias.get(alias);
        if (profile == null) {
            return new ProfileId(alias);
        }
        return profile.lifecycleState() == LifecycleState.ACTIVE
                && profile.coopId() == null
                ? profile.profileId()
                : null;
    }
}
