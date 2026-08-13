package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
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

    /** Resolves one current or historical alias without changing its state. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<CompanionAlias>> resolveAlias(
            @Nonnull NpcAlias alias
    ) {
        if (alias == null) {
            throw new IllegalArgumentException("NPC alias is required");
        }
        return reads.execute(new SqliteReadCommand<>(
                new PersistenceReadKind("companion_alias_resolve"),
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> new SqliteCompanionIdentityStore(connection)
                        .resolveAlias(alias)
                        .<PersistenceReadResult<CompanionAlias>>map(
                                value -> PersistenceReadResult.found(
                                        value, value.generation()
                                )
                        ).orElseGet(PersistenceReadResult::absent)
        ));
    }

    /** Reads every public projection state and alias on one consistent connection. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<
            ProjectionSeed>>
    findAllProjectionStates() {
        return reads.execute(new SqliteReadCommand<>(
                new PersistenceReadKind(
                        "companion_profile_projection_all"
                ),
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    ArrayList<CompanionProfileProjectionState> states =
                            new ArrayList<>();
                    for (CompanionLifecycle lifecycle
                            : new SqliteCompanionLifecycleStore(connection)
                            .findAll()) {
                        CompanionProfileReadModel model = requireModel(
                                connection, lifecycle.profileId()
                        );
                        states.add(CompanionProfileProjectionState.compose(
                                model.identity(),
                                model.currentAlias(),
                                model.lifecycle(),
                                model.toolLinks(),
                                model.currentSnapshots(),
                                model.currentCoopSlot()
                        ));
                    }
                    List<CompanionAlias> aliases =
                            new SqliteCompanionIdentityStore(connection)
                                    .findAllAliases();
                    return PersistenceReadResult.found(new ProjectionSeed(
                            states,
                            aliases
                    ), states.size());
                }
        ));
    }

    /** Immutable startup seed for the non-blocking profile lookup. */
    public record ProjectionSeed(
            @Nonnull List<CompanionProfileProjectionState> states,
            @Nonnull List<CompanionAlias> aliases
    ) {
        public ProjectionSeed {
            if (states == null || aliases == null) {
                throw new IllegalArgumentException(
                        "Profile projection seed is required"
                );
            }
            states = List.copyOf(states);
            aliases = List.copyOf(aliases);
        }
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
        CompanionProfileReadModel model = model(
                connection, profileId, identity, lifecycle
        );
        return PersistenceReadResult.found(
                model,
                Math.max(
                        model.identity().metadataRevision(),
                        model.lifecycle().revision().value()
                )
        );
    }

    private CompanionProfileReadModel requireModel(
            Connection connection,
            ProfileId profileId
    ) {
        CompanionIdentity identity =
                new SqliteCompanionIdentityStore(connection)
                        .findProfile(profileId)
                        .orElseThrow(() -> new IllegalStateException(
                                "profile_projection_identity_missing"
                        ));
        CompanionLifecycle lifecycle =
                new SqliteCompanionLifecycleStore(connection)
                        .findByProfile(profileId)
                        .orElseThrow(() -> new IllegalStateException(
                                "profile_projection_lifecycle_missing"
                        ));
        return model(connection, profileId, identity, lifecycle);
    }

    private CompanionProfileReadModel model(
            Connection connection,
            ProfileId profileId,
            CompanionIdentity identity,
            CompanionLifecycle lifecycle
    ) {
        SqliteCompanionIdentityStore identities =
                new SqliteCompanionIdentityStore(connection);
        SqliteCompanionCoopStore coops = new SqliteCompanionCoopStore(connection);
        CoopSlot coopSlot = coops.findResidencyByProfile(profileId)
                .flatMap(residency -> coops.findSlot(residency.slotKey()))
                .orElse(null);
        return new CompanionProfileReadModel(
                identity,
                identities.findCurrentAlias(profileId).orElse(null),
                lifecycle,
                new SqliteCompanionToolLinkStore(connection).findByProfile(profileId),
                new SqliteCompanionSnapshotStore(connection)
                        .findCurrentByProfile(profileId),
                coopSlot
        );
    }
}
