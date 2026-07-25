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
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.PublicImportRecoveryProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink.RecallFailure;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Guards the narrow initial-import eligibility boundary for recall recovery. */
class ImportedRecallRecoveryAuthorTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000001"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "10000000-0000-0000-0000-000000000001"
    );
    private final ImportedRecallRecoveryAuthor author =
            new ImportedRecallRecoveryAuthor();

    @Test
    void authorsOnlyTheExactFirstImportedUnloadedLineage() {
        CompanionProfileMutation.RecoverImportedMissing recovery =
                author.author(
                        profile(new LifecycleRevision(1), true),
                        failure()
                );

        assertNotNull(recovery);
        assertEquals(new LifecycleRevision(1),
                recovery.expectedLifecycleRevision());
        assertEquals(4, recovery.expectedMetadataRevision());
        assertEquals(ALIAS, recovery.expectedCurrentAlias());
        assertEquals(OWNER, recovery.expectedOwnerId());
    }

    @Test
    void rejectsEvidenceAfterAnyInterveningLifecycleChange() {
        assertNull(author.author(
                profile(new LifecycleRevision(2), true),
                failure()
        ));
    }

    @Test
    void rejectsOwnerlessRecoveryPayload() {
        assertNull(author.author(
                profile(new LifecycleRevision(1), false),
                failure()
        ));
    }

    private CompanionProfileReadModel profile(
            LifecycleRevision revision,
            boolean completeOwner
    ) {
        String payload = completeOwner
                ? """
                {"version":"1","npcUuid":"%s","roleId":"role","commandLinks":{"ownerId":"%s"},"owner":{"ownerId":"%s"}}
                """.formatted(ALIAS, OWNER, OWNER).trim()
                : """
                {"version":"1","npcUuid":"%s","roleId":"role","commandLinks":{"ownerId":"%s"}}
                """.formatted(ALIAS, OWNER).trim();
        CompanionSnapshot source = new CompanionSnapshot(
                SnapshotId.parse(
                        "60000000-0000-0000-0000-000000000001"
                ),
                PROFILE,
                PublicImportRecoveryProjection.KIND,
                PublicImportRecoveryProjection.VERSION,
                payload,
                Sha256Hash.ofUtf8(payload),
                LifecycleRevision.INITIAL,
                true,
                -9_000
        );
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Imported",
                "role",
                null,
                null,
                "world",
                -10_000,
                -9_000,
                -9_000,
                4
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
                LifecycleState.UNLOADED,
                LifecycleLocation.none(),
                revision,
                null,
                -8_000,
                new ReconciliationGeneration(1),
                null,
                "owner-world"
        );
        return new CompanionProfileReadModel(
                identity,
                alias,
                lifecycle,
                List.of(),
                List.of(source),
                null
        );
    }

    private RecallFailure failure() {
        return new RecallFailure(
                UUID.fromString(ALIAS.toString()),
                UUID.fromString(OWNER.toString()),
                -7_000,
                -6_000
        );
    }
}
