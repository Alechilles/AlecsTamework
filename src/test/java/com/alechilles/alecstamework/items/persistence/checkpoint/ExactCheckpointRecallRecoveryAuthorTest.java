package com.alechilles.alecstamework.items.persistence.checkpoint;

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
import java.util.List;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards identity and exact source-evidence fences for full-state Recall. */
class ExactCheckpointRecallRecoveryAuthorTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000001"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final CompanionEntityCheckpointCodec CODEC =
            new CompanionEntityCheckpointCodec();
    private final ExactCheckpointRecallRecoveryAuthor author =
            new ExactCheckpointRecallRecoveryAuthor();

    @Test
    void authorizesOnlyTheCurrentCheckpointAndIdentifiesItsSourceSection() {
        ImportedRecallRecoverySink.RecallSourceSection source =
                new ImportedRecallRecoverySink.RecallSourceSection(
                        "world-a", -2, 2, 2
                );
        ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan = author.author(
                profile(new LifecycleRevision(9)),
                checkpoint(new LifecycleRevision(9)),
                failure(Set.of(source))
        );

        assertNotNull(plan);
        assertEquals(source, plan.sourceSection());
        assertTrue(plan.sourceAlreadyProbed());
        assertEquals("world-b", plan.destination().worldName());

        ExactCheckpointRecallRecoveryAuthor.RecoveryPlan unprobed =
                author.author(
                        profile(new LifecycleRevision(9)),
                        checkpoint(new LifecycleRevision(9)),
                        failure(Set.of())
                );
        assertNotNull(unprobed);
        assertFalse(unprobed.sourceAlreadyProbed());
    }

    @Test
    void rejectsAStaleCheckpointWithoutChangingTheProfile() {
        assertNull(author.author(
                profile(new LifecycleRevision(10)),
                checkpoint(new LifecycleRevision(9)),
                failure(Set.of())
        ));
    }

    private CompanionProfileReadModel profile(LifecycleRevision revision) {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE, "Cat", "Cat_Pet", null, null,
                "world-a", -10_000, -9_000, -9_000, 0
        );
        CompanionAlias alias = new CompanionAlias(
                ALIAS, PROFILE, 7, CompanionAlias.State.CURRENT,
                null, -9_000, null
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        ALIAS.toString(), "world-a"
                ),
                revision,
                null,
                -8_000,
                new ReconciliationGeneration(4),
                null,
                "world-a"
        );
        return new CompanionProfileReadModel(
                identity, alias, lifecycle, List.of(), List.of(), null
        );
    }

    private CompanionEntityCheckpoint checkpoint(
            LifecycleRevision revision
    ) {
        return CompanionEntityCheckpoint.create(
                PROFILE,
                ALIAS,
                7,
                OWNER,
                revision,
                new ReconciliationGeneration(4),
                "world-a",
                -33,
                65,
                64,
                CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                -7_000,
                BsonDocument.parse("{\"Model\":{\"Scale\":0.8}}"),
                CODEC
        );
    }

    private ImportedRecallRecoverySink.RecallFailure failure(
            Set<ImportedRecallRecoverySink.RecallSourceSection> probes
    ) {
        return new ImportedRecallRecoverySink.RecallFailure(
                ALIAS.value(),
                OWNER.value(),
                -6_000,
                -5_000,
                "world-a",
                new ImportedRecallRecoverySink.RecallDestination(
                        "world-b", 10, 20, 30
                ),
                probes
        );
    }
}
