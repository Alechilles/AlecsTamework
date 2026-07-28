package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChange;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.PublicImportRecoveryProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Atomically converts one exact, single-use import artifact into Lost evidence.
 *
 * <p>The caller cannot substitute another snapshot or infer generic absence:
 * every immutable source, owner, alias, and lifecycle fence is rechecked in
 * the transaction before the current alias is retired.</p>
 */
final class SqliteMissingImportedProfileRecovery {
    private static final SnapshotKind LOST = new SnapshotKind("lost");
    private static final int COMPLETE_LOST_VERSION = 2;
    private static final String SNAPSHOT_PREFIX =
            "missing-import-recovery-v1:";

    private SqliteMissingImportedProfileRecovery() {
    }

    @Nonnull
    static Result apply(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull CompanionProfileMutation.RecoverImportedMissing recovery
    ) {
        Current current = load(transaction, recovery);
        CompanionSnapshot source = transaction.snapshots().findById(
                recovery.recoverySnapshotId()
        ).orElse(null);
        CompanionSnapshot lost = expectedLost(recovery, source);
        if (alreadyApplied(transaction, current, source, lost, recovery)) {
            return new Result(current.identity(), null, null, null);
        }
        requireCurrent(transaction, current, source, recovery);

        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        recovery.profileId()
                );
        requireApplied(
                transaction.snapshots().replaceCurrent(lost),
                "lost_snapshot"
        );
        requireApplied(
                transaction.snapshots().retireCurrent(source.snapshotId()),
                "recovery_snapshot_retirement"
        );
        requireApplied(
                transaction.identities().retireAlias(
                        recovery.expectedCurrentAlias(),
                        recovery.requestedAtMs()
                ),
                "alias_retirement"
        );
        CompanionLifecycle next = recovery.resolvedLifecycle(
                current.lifecycle()
        );
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                current.lifecycle().revision(),
                                current.lifecycle().activeOperationId(),
                                next
                        )
                ),
                "lifecycle_transition"
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        recovery.profileId()
                );
        return new Result(
                current.identity(),
                before,
                after,
                new CompanionLifecycleProjectionChange(
                        current.lifecycle(),
                        next
                )
        );
    }

    private static Current load(
            SqlitePersistenceTransactionContext transaction,
            CompanionProfileMutation.RecoverImportedMissing recovery
    ) {
        return new Current(
                transaction.identities().findProfile(recovery.profileId())
                        .orElseThrow(() -> failure("profile_missing")),
                transaction.lifecycles().findByProfile(recovery.profileId())
                        .orElseThrow(() -> failure("lifecycle_missing")),
                transaction.identities().findCurrentAlias(
                        recovery.profileId()
                ).orElse(null),
                transaction.identities().resolveAlias(
                        recovery.expectedCurrentAlias()
                ).orElse(null)
        );
    }

    private static void requireCurrent(
            SqlitePersistenceTransactionContext transaction,
            Current current,
            CompanionSnapshot source,
            CompanionProfileMutation.RecoverImportedMissing recovery
    ) {
        CompanionLifecycle lifecycle = current.lifecycle();
        CompanionAlias alias = current.currentAlias();
        if (lifecycle.quarantined()
                || lifecycle.activeOperationId() != null
                || lifecycle.state() != LifecycleState.UNLOADED
                || !lifecycle.revision().equals(
                recovery.expectedLifecycleRevision()
        )
                || !lifecycle.lastReconciledGeneration().equals(
                ReconciliationGeneration.INITIAL.next()
        )
                || current.identity().metadataRevision()
                != recovery.expectedMetadataRevision()
                || !recovery.expectedOwnerId().equals(lifecycle.ownerId())
                || alias == null
                || alias.state() != CompanionAlias.State.CURRENT
                || !alias.alias().equals(recovery.expectedCurrentAlias())
                || !alias.profileId().equals(recovery.profileId())) {
            throw failure("lifecycle_or_alias_fence");
        }
        requireSource(current.identity(), source, recovery, true);
        CompanionSnapshot currentLost = transaction.snapshots().findCurrent(
                recovery.profileId(),
                LOST
        ).orElse(null);
        if (currentLost != null) {
            throw failure("conflicting_lost_snapshot");
        }
    }

    private static void requireSource(
            CompanionIdentity identity,
            CompanionSnapshot source,
            CompanionProfileMutation.RecoverImportedMissing recovery,
            boolean requireCurrent
    ) {
        if (source == null
                || !source.profileId().equals(recovery.profileId())
                || !source.kind().equals(PublicImportRecoveryProjection.KIND)
                || source.payloadVersion()
                != PublicImportRecoveryProjection.VERSION
                || source.current() != requireCurrent
                || !source.payloadHash().equals(
                recovery.recoveryPayloadHash()
        )
                || !source.payloadHash().matchesUtf8(source.payloadJson())
                || !source.sourceLifecycleRevision().equals(
                com.alechilles.alecstamework.companion.lifecycle
                        .LifecycleRevision.INITIAL
        )
                || !source.sourceLifecycleRevision().next().equals(
                recovery.expectedLifecycleRevision()
        )) {
            throw failure("recovery_snapshot_fence");
        }
        PayloadEvidence evidence = payloadEvidence(source);
        if (!evidence.alias().equals(recovery.expectedCurrentAlias())
                || identity.roleId() == null
                || !identity.roleId().equalsIgnoreCase(evidence.roleId())
                || !recovery.expectedOwnerId().toString().equals(
                evidence.ownerId()
        )
                || !recovery.expectedOwnerId().toString().equals(
                evidence.commandLinksOwnerId()
        )) {
            throw failure("recovery_payload_fence");
        }
    }

    private static boolean alreadyApplied(
            SqlitePersistenceTransactionContext transaction,
            Current current,
            CompanionSnapshot source,
            CompanionSnapshot lost,
            CompanionProfileMutation.RecoverImportedMissing recovery
    ) {
        CompanionLifecycle expected = recovery.resolvedLifecycle(
                new CompanionLifecycle(
                        recovery.profileId(),
                        recovery.expectedOwnerId(),
                        LifecycleState.UNLOADED,
                        com.alechilles.alecstamework.companion.lifecycle
                                .LifecycleLocation.none(),
                        recovery.expectedLifecycleRevision(),
                        null,
                        recovery.recallQueuedAtMs(),
                        current.lifecycle().lastReconciledGeneration(),
                        null,
                        current.lifecycle().ownerWorldKey()
                )
        );
        if (!current.lifecycle().equals(expected)
                || !current.lifecycle().lastReconciledGeneration().equals(
                ReconciliationGeneration.INITIAL.next()
        )
                || current.identity().metadataRevision()
                != recovery.expectedMetadataRevision()
                || current.currentAlias() != null
                || current.expectedAlias() == null
                || current.expectedAlias().state()
                != CompanionAlias.State.RETIRED
                || !current.expectedAlias().profileId().equals(
                recovery.profileId()
        )) {
            return false;
        }
        requireSource(current.identity(), source, recovery, false);
        CompanionSnapshot actualLost = transaction.snapshots().findCurrent(
                recovery.profileId(),
                LOST
        ).orElse(null);
        return lost.equals(actualLost);
    }

    private static CompanionSnapshot expectedLost(
            CompanionProfileMutation.RecoverImportedMissing recovery,
            CompanionSnapshot source
    ) {
        if (source == null) {
            throw failure("recovery_snapshot_missing");
        }
        return new CompanionSnapshot(
                snapshotId(recovery),
                recovery.profileId(),
                LOST,
                COMPLETE_LOST_VERSION,
                source.payloadJson(),
                recovery.recoveryPayloadHash(),
                recovery.expectedLifecycleRevision(),
                true,
                recovery.requestedAtMs()
        );
    }

    private static SnapshotId snapshotId(
            CompanionProfileMutation.RecoverImportedMissing recovery
    ) {
        String material = SNAPSHOT_PREFIX
                + recovery.profileId()
                + ":"
                + recovery.expectedLifecycleRevision().value()
                + ":"
                + recovery.recoverySnapshotId()
                + ":"
                + recovery.recoveryPayloadHash();
        return new SnapshotId(UUID.nameUUIDFromBytes(
                material.getBytes(StandardCharsets.UTF_8)
        ));
    }

    private static PayloadEvidence payloadEvidence(CompanionSnapshot source) {
        try {
            com.google.gson.JsonObject payload =
                    JsonParser.parseString(source.payloadJson())
                            .getAsJsonObject();
            return new PayloadEvidence(
                    com.alechilles.alecstamework.companion.identity.NpcAlias
                            .parse(payload.get("npcUuid").getAsString()),
                    payload.get("roleId").getAsString(),
                    payload.getAsJsonObject("owner")
                            .get("ownerId").getAsString(),
                    payload.getAsJsonObject("commandLinks")
                            .get("ownerId").getAsString()
            );
        } catch (RuntimeException failure) {
            throw failure("recovery_payload_shape");
        }
    }

    private static <T> T requireApplied(
            PersistenceMutationResult<T> result,
            String action
    ) {
        if (result == null || !result.applied()) {
            throw failure(action + "_" + (result == null
                    ? "null"
                    : result.status().name().toLowerCase()));
        }
        return result.value();
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(
                "imported_recall_recovery_" + code
        );
    }

    record Result(
            @Nonnull CompanionIdentity identity,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after,
            CompanionLifecycleProjectionChange lifecycleChange
    ) {
        @Nonnull
        CompanionProfileProjectionChange.Source source() {
            return CompanionProfileProjectionChange.Source.LIFECYCLE;
        }
    }

    private record Current(
            CompanionIdentity identity,
            CompanionLifecycle lifecycle,
            CompanionAlias currentAlias,
            CompanionAlias expectedAlias
    ) {
    }

    private record PayloadEvidence(
            com.alechilles.alecstamework.companion.identity.NpcAlias alias,
            String roleId,
            String ownerId,
            String commandLinksOwnerId
    ) {
    }
}
