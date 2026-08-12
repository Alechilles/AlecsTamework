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
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Guards current identity and owner fences for checkpoint persistence. */
class CompanionEntityCheckpointAuthorTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000001"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "10000000-0000-0000-0000-000000000001"
    );
    private final CompanionEntityCheckpointAuthor author =
            new CompanionEntityCheckpointAuthor(
                    new CompanionEntityCheckpointCodec()
            );

    @Test
    void authorsOnlyForTheExactCurrentOwnedAlias() {
        CompanionEntityCheckpoint checkpoint = author.author(
                profile(ALIAS, OWNER), capture(ALIAS, OWNER)
        );

        assertNotNull(checkpoint);
        assertEquals(PROFILE, checkpoint.profileId());
        assertEquals(7, checkpoint.aliasGeneration());
        assertEquals(new LifecycleRevision(9),
                checkpoint.lifecycleRevision());
        assertNull(author.author(
                profile(ALIAS, OWNER),
                capture(
                        NpcAlias.parse(
                                "40000000-0000-0000-0000-000000000001"
                        ),
                        OWNER
                )
        ));
        assertNull(author.author(
                profile(ALIAS, OWNER),
                capture(
                        ALIAS,
                        OwnerId.parse(
                                "50000000-0000-0000-0000-000000000001"
                        )
                )
        ));
    }

    private CompanionProfileReadModel profile(
            NpcAlias aliasValue,
            OwnerId owner
    ) {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE, "Cat", "Cat_Pet", null, null,
                "default", -10_000, -9_000, -9_000, 0
        );
        CompanionAlias alias = new CompanionAlias(
                aliasValue, PROFILE, 7, CompanionAlias.State.CURRENT,
                null, -9_000, null
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                owner,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        aliasValue.toString(), "default"
                ),
                new LifecycleRevision(9),
                null,
                -8_000,
                new ReconciliationGeneration(4),
                null,
                "default"
        );
        return new CompanionProfileReadModel(
                identity, alias, lifecycle, List.of(), List.of(), null
        );
    }

    private CompanionEntityCheckpointCapture capture(
            NpcAlias alias,
            OwnerId owner
    ) {
        return new CompanionEntityCheckpointCapture(
                alias,
                owner,
                "default",
                1,
                2,
                3,
                CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                -7_000,
                BsonDocument.parse("{\"Model\":{\"Scale\":0.8}}")
        );
    }
}
