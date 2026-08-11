package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipView;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterHome;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipRequest;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.facade.ReplacementCommandFamilyRosterApi;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authors one roster mutation from canonical profile/roster rows and exact
 * current command access observed on the owner's world thread.
 */
public final class CommandRosterEvidenceAuthor
        implements ReplacementCommandFamilyRosterApi.MutationAuthor {
    private static final String IDS = "command-roster-api:v1";

    private final ReplacementFeatureEvidenceQueries queries;
    private final ReplacementFeatureLiveEvidenceSource live;

    public CommandRosterEvidenceAuthor(
            @Nonnull ReplacementFeatureEvidenceQueries queries,
            @Nonnull ReplacementFeatureLiveEvidenceSource live
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.live = Objects.requireNonNull(live, "live");
    }

    @Override
    public CompletionStage<ReplacementCommandFamilyRosterApi.PreparedMutation>
    prepare(
            CommandFamilyRosterMutationRequest request,
            CommandRosterMembershipRequest.Action action
    ) {
        if (request == null || action == null) {
            return CompletableFuture.completedFuture(null);
        }
        final ProfileId profile;
        final CommandFamilyKey family;
        final IdempotencyKey operationKey;
        try {
            profile = ProfileId.parse(request.profileId());
            family = new CommandFamilyKey(
                    new OwnerId(request.ownerUuid()),
                    request.commandFamilyId()
            );
            operationKey = StablePersistenceIds.idempotencyKey(
                    IDS,
                    request.callerNamespace(),
                    request.idempotencyKey()
            );
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(null);
        }
        return queries.findOperation(
                CommandRosterMembershipDefinition.KIND, operationKey
        ).thenCompose(existing -> existing(
                existing, request, action, operationKey
        )).thenCompose(replay -> replay != null
                ? CompletableFuture.completedFuture(replay)
                : canonical(request, action, profile, family, operationKey));
    }

    private CompletionStage<
            ReplacementCommandFamilyRosterApi.PreparedMutation> existing(
            PersistenceReadResult<PublicOperationEvidence> read,
            CommandFamilyRosterMutationRequest request,
            CommandRosterMembershipRequest.Action action,
            IdempotencyKey key
    ) {
        if (read instanceof PersistenceReadResult.Failed<?>) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "command_roster_operation_read_failed"
                    )
            );
        }
        if (!(read instanceof PersistenceReadResult.Found<
                PublicOperationEvidence> found)) {
            return CompletableFuture.completedFuture(null);
        }
        CommandRosterMembershipRequest durable =
                CommandRosterMembershipDefinition.INSTANCE.decode(
                        found.value().operation().payloadJson()
                );
        if (!matches(request, action, durable)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "command_roster_idempotency_conflict"
                    )
            );
        }
        return CompletableFuture.completedFuture(
                new ReplacementCommandFamilyRosterApi.PreparedMutation(
                        found.value().operation().operationId(),
                        key,
                        durable,
                        null
                )
        );
    }

    private CompletionStage<
            ReplacementCommandFamilyRosterApi.PreparedMutation> canonical(
            CommandFamilyRosterMutationRequest request,
            CommandRosterMembershipRequest.Action action,
            ProfileId profileId,
            CommandFamilyKey family,
            IdempotencyKey operationKey
    ) {
        return queries.findProfile(profileId).thenCompose(profileRead -> {
            CompanionProfileReadModel profile = found(profileRead);
            if (!validProfile(request, profile)) {
                return CompletableFuture.completedFuture(null);
            }
            return queries.findRoster(family).thenCompose(rosterRead -> {
                if (rosterRead instanceof PersistenceReadResult.Failed<?>) {
                    return failedRead("command_roster_family_read_failed");
                }
                CommandRoster roster = found(rosterRead);
                long revision = roster == null ? 0 : roster.rosterRevision();
                if (revision != request.expectedRevision()) {
                    return CompletableFuture.completedFuture(null);
                }
                return queries.findMembership(profileId)
                        .thenCompose(membershipRead -> membership(
                                request,
                                action,
                                operationKey,
                                profile,
                                roster,
                                membershipRead
                        ));
            });
        });
    }

    private CompletionStage<
            ReplacementCommandFamilyRosterApi.PreparedMutation> membership(
            CommandFamilyRosterMutationRequest request,
            CommandRosterMembershipRequest.Action action,
            IdempotencyKey operationKey,
            CompanionProfileReadModel profile,
            @Nullable CommandRoster roster,
            PersistenceReadResult<CommandRosterMembership> membershipRead
    ) {
        if (membershipRead instanceof PersistenceReadResult.Failed<?>) {
            return failedRead("command_roster_membership_read_failed");
        }
        CommandRosterMembership existing = found(membershipRead);
        if (action == CommandRosterMembershipRequest.Action.REMOVE
                && existing == null
                || existing != null && !existing.familyKey().equals(
                new CommandFamilyKey(
                        new OwnerId(request.ownerUuid()),
                        request.commandFamilyId()
                )
        )) {
            return CompletableFuture.completedFuture(null);
        }
        CompletionStage<ReplacementFeatureLiveEvidenceSource.RosterAccess>
                accessStage = live.freezeRosterAccess(
                new ReplacementFeatureLiveEvidenceSource.RosterAccessIntent(
                        request,
                        action,
                        profile.identity().roleId()
                )
        );
        if (accessStage == null) {
            return CompletableFuture.completedFuture(null);
        }
        return accessStage.thenApply(access -> author(
                request,
                action,
                operationKey,
                profile,
                roster,
                existing,
                access
        ));
    }

    @Nullable
    private ReplacementCommandFamilyRosterApi.PreparedMutation author(
            CommandFamilyRosterMutationRequest request,
            CommandRosterMembershipRequest.Action action,
            IdempotencyKey operationKey,
            CompanionProfileReadModel profile,
            @Nullable CommandRoster roster,
            @Nullable CommandRosterMembership existing,
            @Nullable ReplacementFeatureLiveEvidenceSource.RosterAccess access
    ) {
        if (!validAccess(request, existing, roster, access)) {
            return null;
        }
        var slotId = existing == null
                ? access.slotId()
                : existing.slotId();
        CommandRosterHome home = request.homePosition() == null
                ? null
                : home(
                        profile.lifecycle().ownerWorldKey(),
                        request.homePosition()
                );
        CommandRosterMembershipRequest durable =
                new CommandRosterMembershipRequest(
                        action,
                        profile.identity().profileId(),
                        new CommandFamilyKey(
                                new OwnerId(request.ownerUuid()),
                                request.commandFamilyId()
                        ),
                        slotId,
                        request.expectedRevision(),
                        existing == null
                                ? null
                                : existing.membershipRevision(),
                        profile.identity().metadataRevision(),
                        profile.identity().roleId(),
                        profile.lifecycle().revision(),
                        profile.lifecycle().ownerWorldKey(),
                        request.groupId(),
                        request.activeForBulkCommands(),
                        home,
                        access.observedAtMs()
                );
        String[] identity = {
                request.callerNamespace(), request.idempotencyKey()
        };
        return new ReplacementCommandFamilyRosterApi.PreparedMutation(
                StablePersistenceIds.operationId(IDS, identity),
                operationKey,
                durable,
                existing == null ? null : previous(profile, existing)
        );
    }

    private boolean validProfile(
            CommandFamilyRosterMutationRequest request,
            @Nullable CompanionProfileReadModel profile
    ) {
        return profile != null
                && profile.identity().roleId() != null
                && profile.identity().metadataRevision()
                == request.expectedProfileRevision()
                && profile.lifecycle().ownerId() != null
                && profile.lifecycle().ownerId().value().equals(
                request.ownerUuid()
        )
                && !profile.lifecycle().quarantined()
                && profile.lifecycle().activeOperationId() == null
                && state(profile.lifecycle().state()) == request.state();
    }

    private boolean validAccess(
            CommandFamilyRosterMutationRequest request,
            @Nullable CommandRosterMembership existing,
            @Nullable CommandRoster roster,
            @Nullable ReplacementFeatureLiveEvidenceSource.RosterAccess access
    ) {
        if (access == null
                || !request.ownerUuid().equals(access.ownerUuid())
                || !request.commandFamilyId().equals(
                access.commandFamilyId()
        )
                || request.requiredCommandConfigId() != null
                && !request.requiredCommandConfigId().equals(
                access.commandConfigId()
        )
                || request.accessItemId() != null
                && !request.accessItemId().equals(access.accessItemId())
        ) {
            return false;
        }
        var selectedSlot = existing == null
                ? access.slotId()
                : existing.slotId();
        return roster == null || roster.memberships().stream().noneMatch(
                member -> member.slotId().equals(selectedSlot)
                        && (existing == null
                        || !member.profileId().equals(existing.profileId()))
        );
    }

    private boolean matches(
            CommandFamilyRosterMutationRequest request,
            CommandRosterMembershipRequest.Action action,
            CommandRosterMembershipRequest durable
    ) {
        return durable.action() == action
                && durable.profileId().toString().equals(request.profileId())
                && durable.familyKey().ownerId().value().equals(
                request.ownerUuid()
        )
                && durable.familyKey().familyId().equals(
                request.commandFamilyId()
        )
                && durable.expectedRosterRevision()
                == request.expectedRevision()
                && durable.expectedMetadataRevision()
                == request.expectedProfileRevision()
                && Objects.equals(durable.groupId(), request.groupId())
                && durable.activeForBulkCommands()
                == request.activeForBulkCommands()
                && homeMatches(
                durable.home(), request.homePosition()
        );
    }

    private CommandFamilyRosterMembershipView previous(
            CompanionProfileReadModel profile,
            CommandRosterMembership membership
    ) {
        CommandRosterHome home = membership.home();
        return new CommandFamilyRosterMembershipView(
                membership.familyKey().ownerId().value(),
                membership.familyKey().familyId(),
                membership.profileId().toString(),
                profile.identity().roleId(),
                profile.identity().metadataRevision(),
                state(profile.lifecycle().state()),
                membership.groupId(),
                membership.activeForBulkCommands(),
                home == null
                        ? null
                        : new Vector3View(home.x(), home.y(), home.z()),
                membership.updatedAtMs()
        );
    }

    private CommandRosterHome home(String worldKey, Vector3View home) {
        return new CommandRosterHome(
                worldKey, home.x(), home.y(), home.z()
        );
    }

    private boolean homeMatches(
            @Nullable CommandRosterHome durable,
            @Nullable Vector3View requested
    ) {
        return requested == null
                ? durable == null
                : durable != null
                && Double.compare(durable.x(), requested.x()) == 0
                && Double.compare(durable.y(), requested.y()) == 0
                && Double.compare(durable.z(), requested.z()) == 0;
    }

    private CommandFamilyRosterMemberState state(LifecycleState state) {
        return switch (state) {
            case ACTIVE -> CommandFamilyRosterMemberState.ACTIVE;
            case ROSTER_STORED, PROVISIONED_DORMANT ->
                    CommandFamilyRosterMemberState.ROSTER_STORED;
            case DEAD_REVIVABLE ->
                    CommandFamilyRosterMemberState.DEAD_REVIVABLE;
            case LOST -> CommandFamilyRosterMemberState.LOST;
            case UNRESOLVED -> CommandFamilyRosterMemberState.UNAVAILABLE;
            default -> CommandFamilyRosterMemberState.UNLOADED;
        };
    }

    @Nullable
    private <T> T found(PersistenceReadResult<T> read) {
        return read instanceof PersistenceReadResult.Found<T> found
                ? found.value()
                : null;
    }

    private <T> CompletionStage<T> failedRead(String code) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(code)
        );
    }
}
