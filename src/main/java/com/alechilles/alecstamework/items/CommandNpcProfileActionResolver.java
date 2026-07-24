package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves a command record to one stable profile target at command and terminal-lost boundaries.
 *
 * <p>The underlying identity read may access persistence, so callers must not invoke this resolver
 * from tick or relocation-retry callbacks. A successful relocation result is immutable and can be
 * carried through all retries without another database read.
 */
final class CommandNpcProfileActionResolver {
    enum ResolutionStatus {
        RESOLVED,
        BLOCKED,
        UNRESOLVED,
        CONFLICT,
        FAILED
    }

    record ActionTarget(@Nonnull ResolutionStatus status,
                        @Nullable String profileId,
                        @Nullable UUID cachedNpcUuid,
                        @Nullable UUID targetNpcUuid,
                        @Nullable LinkedNpcRecord resolvedRecord,
                        @Nonnull List<UUID> aliases,
                        @Nonnull List<UUID> liveUuids,
                        @Nullable String reason) {
        ActionTarget {
            aliases = List.copyOf(aliases);
            liveUuids = List.copyOf(liveUuids);
        }

        boolean isActionable() {
            return status == ResolutionStatus.RESOLVED
                    && targetNpcUuid != null
                    && resolvedRecord != null;
        }

        boolean redirected() {
            return isActionable() && !Objects.equals(cachedNpcUuid, targetNpcUuid);
        }
    }

    record CanonicalRecords(@Nonnull List<LinkedNpcRecord> records,
                            boolean safeToPersist,
                            boolean identityChanged) {
        CanonicalRecords {
            records = List.copyOf(records);
        }
    }

    private final CommandNpcIdentityService identityService;

    CommandNpcProfileActionResolver(@Nonnull CommandNpcIdentityService identityService) {
        this.identityService = Objects.requireNonNull(identityService, "identityService");
    }

    /** Resolves one immutable target before a relocation is queued. */
    @Nonnull
    ActionTarget resolveRelocation(@Nullable LinkedNpcRecord record) {
        return resolve(record, ActionKind.RELOCATION);
    }

    /** Canonicalizes profile/current UUID metadata for one active command stack. */
    @Nonnull
    CanonicalRecords canonicalizeRecords(@Nullable List<LinkedNpcRecord> records) {
        List<LinkedNpcRecord> original = records != null ? List.copyOf(records) : List.of();
        CommandNpcIdentityService.CanonicalizationResult result = identityService.canonicalize(original);
        boolean safeToPersist = !result.hasConflicts() && !result.hasFailures();
        return new CanonicalRecords(
                result.records(),
                safeToPersist,
                safeToPersist && identityChanged(original, result.records())
        );
    }

    /** Revalidates one terminal retry before it is allowed to become a lost transition. */
    @Nonnull
    ActionTarget resolveLostTransition(@Nullable UUID droppedNpcUuid) {
        LinkedNpcRecord record = droppedNpcUuid != null
                ? new LinkedNpcRecord(
                        droppedNpcUuid, null, null, null, null,
                        null, null, null, null, true, false, null)
                : null;
        return resolve(record, ActionKind.LOST_TRANSITION);
    }

    @Nonnull
    private ActionTarget resolve(@Nullable LinkedNpcRecord record, @Nonnull ActionKind actionKind) {
        CommandNpcIdentityService.IdentityResolution identity = identityService.resolve(record);
        if (identity.status() != CommandNpcIdentityService.ResolutionStatus.RESOLVED) {
            return unresolved(identity, record);
        }
        String blockedReason = blockedReason(identity, record, actionKind);
        if (blockedReason != null) {
            return result(
                    ResolutionStatus.BLOCKED,
                    identity,
                    record,
                    selectTarget(identity, record),
                    null,
                    blockedReason
            );
        }
        UUID targetNpcUuid = selectTarget(identity, record);
        if (targetNpcUuid == null || identity.profileId() == null) {
            return result(
                    ResolutionStatus.FAILED,
                    identity,
                    record,
                    targetNpcUuid,
                    null,
                    targetNpcUuid == null ? "canonical_target_missing" : "profile_id_missing"
            );
        }
        LinkedNpcRecord resolvedRecord = copyIdentity(record, identity.profileId(), targetNpcUuid);
        return result(
                ResolutionStatus.RESOLVED,
                identity,
                record,
                targetNpcUuid,
                resolvedRecord,
                null
        );
    }

    @Nonnull
    private ActionTarget unresolved(@Nonnull CommandNpcIdentityService.IdentityResolution identity,
                                    @Nullable LinkedNpcRecord record) {
        ResolutionStatus status = switch (identity.status()) {
            case UNRESOLVED -> ResolutionStatus.UNRESOLVED;
            case CONFLICT -> ResolutionStatus.CONFLICT;
            case FAILED -> ResolutionStatus.FAILED;
            case RESOLVED -> ResolutionStatus.FAILED;
        };
        return result(status, identity, record, null, null, identity.failureReason());
    }

    @Nullable
    private String blockedReason(@Nonnull CommandNpcIdentityService.IdentityResolution identity,
                                 @Nullable LinkedNpcRecord record,
                                 @Nonnull ActionKind actionKind) {
        CommandNpcIdentityService.DurableStateFlags durable = identity.durableState();
        if (actionKind == ActionKind.LOST_TRANSITION && !identity.liveUuids().isEmpty()) {
            return "profile_alias_is_live";
        }
        if (durable.captured()) {
            return "profile_is_captured";
        }
        if (durable.dead()) {
            return "profile_is_dead";
        }
        if (durable.inCoop()) {
            return "profile_is_cooped";
        }
        if (durable.lost()) {
            return "profile_is_lost";
        }
        return null;
    }

    @Nullable
    private UUID selectTarget(@Nonnull CommandNpcIdentityService.IdentityResolution identity,
                              @Nullable LinkedNpcRecord record) {
        if (identity.liveUuids().size() == 1) {
            return identity.liveUuids().get(0);
        }
        if (identity.currentNpcUuid() != null) {
            return identity.currentNpcUuid();
        }
        return record != null ? record.npcUuid : null;
    }

    @Nonnull
    private ActionTarget result(@Nonnull ResolutionStatus status,
                                @Nonnull CommandNpcIdentityService.IdentityResolution identity,
                                @Nullable LinkedNpcRecord record,
                                @Nullable UUID targetNpcUuid,
                                @Nullable LinkedNpcRecord resolvedRecord,
                                @Nullable String reason) {
        return new ActionTarget(
                status,
                identity.profileId(),
                record != null ? record.npcUuid : identity.cachedHistoricalUuid(),
                targetNpcUuid,
                resolvedRecord,
                identity.aliases(),
                identity.liveUuids(),
                reason
        );
    }

    @Nonnull
    private LinkedNpcRecord copyIdentity(@Nonnull LinkedNpcRecord source,
                                         @Nonnull String profileId,
                                         @Nonnull UUID targetNpcUuid) {
        return new LinkedNpcRecord(
                targetNpcUuid,
                profileId,
                source.lastKnownPosition,
                source.lastKnownWorldName,
                source.homePosition,
                source.cachedDisplayName,
                source.cachedNameKey,
                source.cachedRoleId,
                source.cachedCommandState,
                source.active,
                source.breedingEnabled,
                source.groupId
        );
    }

    private boolean identityChanged(@Nonnull List<LinkedNpcRecord> original,
                                    @Nonnull List<LinkedNpcRecord> canonical) {
        if (original.size() != canonical.size()) {
            return true;
        }
        for (int index = 0; index < original.size(); index++) {
            LinkedNpcRecord before = original.get(index);
            LinkedNpcRecord after = canonical.get(index);
            if (before == null || after == null
                    || !Objects.equals(before.npcUuid, after.npcUuid)
                    || !Objects.equals(before.profileId, after.profileId)) {
                return true;
            }
        }
        return false;
    }

    private enum ActionKind {
        RELOCATION,
        LOST_TRANSITION
    }
}
