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
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Guards the exact-evidence boundary for terminal Recall recovery. */
class ImportedCompanionRecallRecoveryTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000001"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "10000000-0000-0000-0000-000000000001"
    );

    @Test
    void timeoutAloneCannotChangeOrdinaryActiveOrUnloadedProfiles() {
        AtomicInteger submissions = new AtomicInteger();

        assertEquals(
                ImportedRecallRecoverySink.RecoveryOutcome.NONE,
                recover(profile(LifecycleState.ACTIVE), submissions)
        );
        assertEquals(
                ImportedRecallRecoverySink.RecoveryOutcome.NONE,
                recover(profile(LifecycleState.UNLOADED), submissions)
        );
        assertEquals(0, submissions.get());
    }

    private ImportedRecallRecoverySink.RecoveryOutcome recover(
            CompanionProfileReadModel profile,
            AtomicInteger submissions
    ) {
        return ImportedCompanionRecallRecovery.recoverFoundProfile(
                profile,
                failure(),
                mutation -> {
                    submissions.incrementAndGet();
                    return CompletableFuture.completedFuture(true);
                }
        ).toCompletableFuture().join();
    }

    private CompanionProfileReadModel profile(LifecycleState state) {
        String metadata = "{\"tamed\":true}";
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Companion",
                "role-a",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world-a",
                -10_000,
                -9_000,
                -9_000,
                0
        );
        CompanionAlias alias = new CompanionAlias(
                ALIAS,
                PROFILE,
                0,
                CompanionAlias.State.CURRENT,
                null,
                -9_000,
                null
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                OWNER,
                state,
                state == LifecycleState.ACTIVE
                        ? LifecycleLocation.liveEntity(
                                ALIAS.toString(), "world-a"
                        )
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

    private ImportedRecallRecoverySink.RecallFailure failure() {
        return new ImportedRecallRecoverySink.RecallFailure(
                ALIAS.value(), OWNER.value(), -7_000, -6_000, "world-a"
        );
    }
}
