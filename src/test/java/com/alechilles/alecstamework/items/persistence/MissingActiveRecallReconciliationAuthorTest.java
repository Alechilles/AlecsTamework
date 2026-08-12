package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink.RecallFailure;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Guards the first phase of stale active Recall recovery. */
class MissingActiveRecallReconciliationAuthorTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000001"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "10000000-0000-0000-0000-000000000001"
    );
    private final MissingActiveRecallReconciliationAuthor author =
            new MissingActiveRecallReconciliationAuthor();

    @Test
    void authorsUnloadedReconciliationForExactProbedLiveClaim() {
        CompanionProfileMutation.ReconcileMissingActive request =
                author.author(profile(LifecycleState.ACTIVE), failure("world-a"));

        assertNotNull(request);
        assertEquals(PROFILE, request.profileId());
        assertEquals(ALIAS, request.expectedCurrentAlias());
        assertEquals(OWNER, request.expectedOwnerId());
        assertEquals("world-a", request.expectedWorldKey());
    }

    @Test
    void rejectsDifferentProbedWorldOrAlreadyUnloadedProfile() {
        assertNull(author.author(
                profile(LifecycleState.ACTIVE), failure("world-b")
        ));
        assertNull(author.author(
                profile(LifecycleState.UNLOADED), failure("world-a")
        ));
    }

    private CompanionProfileReadModel profile(LifecycleState state) {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE, "Dragon", "role", null, null,
                "world-a", -10_000, -9_000, -9_000, 0
        );
        CompanionAlias alias = new CompanionAlias(
                ALIAS, PROFILE, 0, CompanionAlias.State.CURRENT,
                null, -9_000, null
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                OWNER,
                state,
                state == LifecycleState.ACTIVE
                        ? LifecycleLocation.liveEntity(ALIAS.toString(), "world-a")
                        : LifecycleLocation.none(),
                new LifecycleRevision(3),
                null,
                -8_000,
                new ReconciliationGeneration(5),
                null,
                "world-a"
        );
        return new CompanionProfileReadModel(
                identity, alias, lifecycle, List.of(), List.of(), null
        );
    }

    private RecallFailure failure(String worldKey) {
        return new RecallFailure(
                ALIAS.value(), OWNER.value(), -7_000, -6_000, worldKey
        );
    }
}
