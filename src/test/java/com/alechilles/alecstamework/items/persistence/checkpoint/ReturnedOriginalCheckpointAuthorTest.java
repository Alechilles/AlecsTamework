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
import java.util.List;
import java.util.Set;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Guards preference for an exact returned body over an absent replacement. */
class ReturnedOriginalCheckpointAuthorTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias CURRENT = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias RETURNED = NpcAlias.parse(
            "40000000-0000-0000-0000-000000000001"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "10000000-0000-0000-0000-000000000001"
    );
    private final ReturnedOriginalCheckpointAuthor author =
            new ReturnedOriginalCheckpointAuthor(
                    new CompanionEntityCheckpointCodec()
            );

    @Test
    void targetsCurrentAliasWithTheReturnedRetiredBody() {
        CompanionEntityCheckpoint checkpoint = author.author(
                profile(), retiredAlias(), capture(), true
        );

        assertNotNull(checkpoint);
        assertEquals(CURRENT, checkpoint.alias());
        assertEquals(RETURNED, checkpoint.sourceAlias());
        assertEquals(
                CompanionEntityCheckpoint.CaptureBoundary
                        .RETURNED_RETIRED_ORIGINAL,
                checkpoint.boundary()
        );
        assertNotNull(new ExactCheckpointRecallRecoveryAuthor().author(
                profile(),
                checkpoint,
                new ImportedRecallRecoverySink.RecallFailure(
                        CURRENT.value(),
                        OWNER.value(),
                        -6_000,
                        -5_000,
                        "world-a",
                        new ImportedRecallRecoverySink.RecallDestination(
                                "world-a", 1, 2, 3
                        ),
                        Set.of()
                )
        ));
    }

    @Test
    void keepsBothBodiesWhenCurrentReplacementEvidenceConflicts() {
        assertNull(author.author(
                profile(), retiredAlias(), capture(), false
        ));
    }

    private CompanionProfileReadModel profile() {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE, "Cat", "Cat_Pet", null, null,
                "world-a", -10_000, -9_000, -9_000, 0
        );
        CompanionAlias alias = new CompanionAlias(
                CURRENT, PROFILE, 7, CompanionAlias.State.CURRENT,
                null, -9_000, null
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        CURRENT.toString(), "world-a"
                ),
                new LifecycleRevision(9),
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

    private CompanionAlias retiredAlias() {
        return new CompanionAlias(
                RETURNED,
                PROFILE,
                3,
                CompanionAlias.State.RETIRED,
                null,
                -12_000,
                -11_000L
        );
    }

    private CompanionEntityCheckpointCapture capture() {
        return new CompanionEntityCheckpointCapture(
                RETURNED,
                OWNER,
                "world-a",
                1,
                2,
                3,
                CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                -7_000,
                BsonDocument.parse("{\"Model\":{\"Scale\":0.8}}")
        );
    }
}
