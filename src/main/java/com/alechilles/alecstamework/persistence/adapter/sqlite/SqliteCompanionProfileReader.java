package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.sql.Connection;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Async public profile reader composed from focused replacement stores on one read connection.
 */
public final class SqliteCompanionProfileReader {
    private static final PersistenceReadKind BY_PROFILE =
            new PersistenceReadKind("companion_profile_by_id");
    private static final PersistenceReadKind BY_ALIAS =
            new PersistenceReadKind("companion_profile_by_alias");

    private final SqliteReadExecutor reads;

    public SqliteCompanionProfileReader(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException("Profile read executor is required");
        }
        this.reads = reads;
    }

    /** Reads one stable profile and all currently public identity evidence. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>> findByProfile(
            @Nonnull ProfileId profileId
    ) {
        if (profileId == null) {
            throw new IllegalArgumentException("Profile ID is required");
        }
        return reads.execute(new SqliteReadCommand<>(
                BY_PROFILE,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> compose(connection, profileId, BY_PROFILE)
        ));
    }

    /** Resolves current or historical runtime UUID evidence before composing the stable profile. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>> findByAlias(
            @Nonnull NpcAlias alias
    ) {
        if (alias == null) {
            throw new IllegalArgumentException("NPC alias is required");
        }
        return reads.execute(new SqliteReadCommand<>(
                BY_ALIAS,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    CompanionAlias resolved =
                            new SqliteCompanionIdentityStore(connection)
                                    .resolveAlias(alias)
                                    .orElse(null);
                    return resolved == null
                            ? PersistenceReadResult.absent()
                            : compose(connection, resolved.profileId(), BY_ALIAS);
                }
        ));
    }

    private PersistenceReadResult<CompanionProfileReadModel> compose(
            Connection connection,
            ProfileId profileId,
            PersistenceReadKind readKind
    ) {
        SqliteCompanionIdentityStore identities =
                new SqliteCompanionIdentityStore(connection);
        CompanionIdentity identity = identities.findProfile(profileId).orElse(null);
        if (identity == null) {
            return PersistenceReadResult.absent();
        }
        CompanionLifecycle lifecycle =
                new SqliteCompanionLifecycleStore(connection)
                        .findByProfile(profileId)
                        .orElse(null);
        if (lifecycle == null) {
            return PersistenceReadResult.failed(new StorageFailure(
                    StorageFailureKind.CORRUPT,
                    "profile_lifecycle_missing",
                    readKind.value(),
                    false,
                    null
            ));
        }
        SqliteCompanionCoopStore coops = new SqliteCompanionCoopStore(connection);
        CoopSlot coopSlot = coops.findResidencyByProfile(profileId)
                .flatMap(residency -> coops.findSlot(residency.slotKey()))
                .orElse(null);
        CompanionProfileReadModel model = new CompanionProfileReadModel(
                identity,
                identities.findCurrentAlias(profileId).orElse(null),
                lifecycle,
                new SqliteCompanionToolLinkStore(connection).findByProfile(profileId),
                new SqliteCompanionSnapshotStore(connection)
                        .findCurrentByProfile(profileId),
                coopSlot
        );
        return PersistenceReadResult.found(
                model,
                Math.max(identity.metadataRevision(), lifecycle.revision().value())
        );
    }
}
