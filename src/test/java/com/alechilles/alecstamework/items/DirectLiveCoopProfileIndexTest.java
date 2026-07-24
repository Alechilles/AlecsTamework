package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for stable coop identity across runtime alias rotation. */
class DirectLiveCoopProfileIndexTest {
    private static final UUID RUNTIME_ALIAS =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final ProfileId STABLE_PROFILE = new ProfileId(
            UUID.fromString("20000000-0000-0000-0000-000000000002")
    );

    @Test
    void unprofiledAliasUsesDeterministicFirstProfileIdentity() {
        DirectLiveCoopProfileIndex index =
                new DirectLiveCoopProfileIndex(Map.of());

        assertEquals(
                new ProfileId(RUNTIME_ALIAS),
                index.captureProfileId(RUNTIME_ALIAS)
        );
    }

    @Test
    void activeRotatedAliasResolvesItsStableProfileIdentity() {
        DirectLiveCoopProfileIndex index = new DirectLiveCoopProfileIndex(
                Map.of(
                        STABLE_PROFILE,
                        projection(
                                STABLE_PROFILE,
                                LifecycleState.ACTIVE,
                                null
                        )
                )
        );

        assertEquals(
                STABLE_PROFILE,
                index.captureProfileId(RUNTIME_ALIAS)
        );
    }

    @Test
    void nonActiveOrCoopedAliasCannotBeAdoptedAsAnotherProfile() {
        assertNull(new DirectLiveCoopProfileIndex(Map.of(
                STABLE_PROFILE,
                projection(
                        STABLE_PROFILE,
                        LifecycleState.CAPTURED,
                        null
                )
        )).captureProfileId(RUNTIME_ALIAS));
        assertNull(new DirectLiveCoopProfileIndex(Map.of(
                STABLE_PROFILE,
                projection(
                        STABLE_PROFILE,
                        LifecycleState.ACTIVE,
                        "coop_chicken"
                )
        )).captureProfileId(RUNTIME_ALIAS));
    }

    @Test
    void ambiguousCurrentAliasFailsClosed() {
        ProfileId other = new ProfileId(
                UUID.fromString("30000000-0000-0000-0000-000000000003")
        );
        Map<ProfileId, CompanionProfileProjectionState> profiles =
                new LinkedHashMap<>();
        profiles.put(
                STABLE_PROFILE,
                projection(STABLE_PROFILE, LifecycleState.ACTIVE, null)
        );
        profiles.put(
                other,
                projection(other, LifecycleState.ACTIVE, null)
        );

        assertNull(new DirectLiveCoopProfileIndex(profiles)
                .captureProfileId(RUNTIME_ALIAS));
    }

    private CompanionProfileProjectionState projection(
            ProfileId profileId,
            LifecycleState state,
            String coopId
    ) {
        return new CompanionProfileProjectionState(
                profileId,
                new NpcAlias(RUNTIME_ALIAS),
                state,
                null,
                null,
                "tamed_chicken",
                "Chicken",
                null,
                true,
                coopId,
                coopId == null ? null : 0,
                Set.of(),
                Set.of(),
                1L
        );
    }
}
