package com.alechilles.alecstamework.command.roster;

import com.alechilles.alecstamework.api.CommandFamilyRosterApi;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipChangedEvent;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipView;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationResult;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationStatus;
import com.alechilles.alecstamework.api.CommandFamilyRosterView;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.persistence.sqlite.CommandFamilyRosterRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Focused service seam shared by provisioning, capture, and command lifecycle coordinators. */
public final class CommandFamilyRosterService implements CommandFamilyRosterApi {
    private final CommandFamilyRosterRepository repository;
    private final AccessValidator accessValidator;
    @Nullable private final TameworkEventBus eventBus;
    private final ConcurrentHashMap<RosterKey, RosterRevisionProof> revisionProofs =
            new ConcurrentHashMap<>();

    public CommandFamilyRosterService(@Nonnull CommandFamilyRosterRepository repository) {
        this(repository, AccessValidator.ALLOW_ALL, null);
    }

    public CommandFamilyRosterService(@Nonnull CommandFamilyRosterRepository repository,
                                      @Nonnull AccessValidator accessValidator,
                                      @Nullable TameworkEventBus eventBus) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.accessValidator = Objects.requireNonNull(accessValidator, "accessValidator");
        this.eventBus = eventBus;
    }

    @Override
    public Optional<CommandFamilyRosterView> get(UUID ownerUuid, String commandFamilyId) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(commandFamilyId, "commandFamilyId");
        try {
            return Optional.ofNullable(repository.find(ownerUuid, commandFamilyId));
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    /**
     * Loads an immutable roster revision off the world thread. Capture uses the returned proof
     * for its final world-thread preflight instead of opening SQLite synchronously.
     */
    @Nonnull
    public CompletionStage<RosterRevisionProof> loadRevisionProof(
            @Nonnull UUID ownerUuid, @Nonnull String commandFamilyId) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        String familyId = requireText(commandFamilyId, "commandFamilyId");
        RosterKey key = new RosterKey(ownerUuid, familyId);
        return CompletableFuture.supplyAsync(() -> {
            try {
                CommandFamilyRosterView roster = repository.find(ownerUuid, familyId);
                RosterRevisionProof proof = new RosterRevisionProof(
                        ownerUuid, familyId, roster == null ? 0L : roster.revision(),
                        roster == null ? 0 : roster.memberships().size());
                revisionProofs.merge(key, proof, CommandFamilyRosterService::newerProof);
                return proof;
            } catch (Exception failure) {
                throw new IllegalStateException("command-family-roster-read-failed", failure);
            }
        });
    }

    /** Pure cached comparison safe for the owning world thread. */
    public boolean isCurrent(@Nonnull RosterRevisionProof proof) {
        Objects.requireNonNull(proof, "proof");
        return proof.equals(revisionProofs.get(
                new RosterKey(proof.ownerUuid(), proof.commandFamilyId())));
    }

    @Override
    public Optional<CommandFamilyRosterMembershipView> getMembership(
            UUID ownerUuid, String commandFamilyId, String profileId) {
        Objects.requireNonNull(profileId, "profileId");
        return get(ownerUuid, commandFamilyId).flatMap(roster -> roster.memberships().stream()
                .filter(membership -> membership.profileId().equals(profileId.trim())).findFirst());
    }

    @Override
    public CompletionStage<CommandFamilyRosterMutationResult> upsert(
            CommandFamilyRosterMutationRequest request) {
        return mutate(CommandFamilyRosterRepository.MutationKind.UPSERT, request);
    }

    @Override
    public CompletionStage<CommandFamilyRosterMutationResult> remove(
            CommandFamilyRosterMutationRequest request) {
        return mutate(CommandFamilyRosterRepository.MutationKind.REMOVE, request);
    }

    private CompletionStage<CommandFamilyRosterMutationResult> mutate(
            CommandFamilyRosterRepository.MutationKind kind,
            CommandFamilyRosterMutationRequest request) {
        Objects.requireNonNull(request, "request");
        PersistenceWriteQueue.WriteSubmission<CommandFamilyRosterRepository.MutationOutcome> submission =
                repository.mutateAsync(kind, request, (profileRoleId, candidate) -> {
                    AccessDecision decision = accessValidator.validate(
                            candidate.commandFamilyId(), candidate.requiredCommandConfigId(),
                            candidate.accessItemId(), profileRoleId);
                    return decision.allowed() ? null : decision.reason();
                });
        if (!submission.accepted()) {
            return CompletableFuture.completedFuture(CommandFamilyRosterMutationResult.unavailable(
                    "command-family-roster-write-unavailable"));
        }
        return submission.completion().thenApply(outcome -> {
            if (!outcome.isCommitted() || outcome.value() == null) {
                return new CommandFamilyRosterMutationResult(
                        CommandFamilyRosterMutationStatus.FAILED,
                        outcome.failureReason() == null ? "command-family-roster-write-failed"
                                : outcome.failureReason(), null, null, false);
            }
            CommandFamilyRosterRepository.MutationOutcome value = outcome.value();
            cache(value.roster());
            emitChanged(value);
            return new CommandFamilyRosterMutationResult(value.status(), value.reason(), value.roster(),
                    value.currentMembership(), value.idempotentReplay());
        });
    }

    private void cache(@Nullable CommandFamilyRosterView roster) {
        if (roster == null) return;
        RosterRevisionProof proof = new RosterRevisionProof(
                roster.ownerUuid(), roster.commandFamilyId(), roster.revision(),
                roster.memberships().size());
        revisionProofs.merge(new RosterKey(roster.ownerUuid(), roster.commandFamilyId()), proof,
                CommandFamilyRosterService::newerProof);
    }

    private static RosterRevisionProof newerProof(
            RosterRevisionProof left, RosterRevisionProof right) {
        return left.revision() >= right.revision() ? left : right;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private record RosterKey(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId) {
    }

    public record RosterRevisionProof(@Nonnull UUID ownerUuid,
                                      @Nonnull String commandFamilyId,
                                      long revision,
                                      int membershipCount) {
        public RosterRevisionProof {
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
            if (revision < 0L || membershipCount < 0) {
                throw new IllegalArgumentException("roster proof values cannot be negative");
            }
        }
    }

    private void emitChanged(CommandFamilyRosterRepository.MutationOutcome outcome) {
        if (eventBus == null || outcome.status() != CommandFamilyRosterMutationStatus.APPLIED
                || outcome.roster() == null) return;
        long previousRevision = Math.max(0L, outcome.roster().revision() - 1L);
        long nowMs = System.currentTimeMillis();
        eventBus.emitCommandFamilyRosterEvent(new CommandFamilyRosterMembershipChangedEvent(
                outcome.operationId(), outcome.roster().ownerUuid(), outcome.roster().commandFamilyId(),
                outcome.previousMembership() != null ? outcome.previousMembership().profileId()
                        : Objects.requireNonNull(outcome.currentMembership()).profileId(),
                outcome.previousMembership(), outcome.currentMembership(), previousRevision,
                outcome.roster().revision(), nowMs, nowMs));
    }

    @FunctionalInterface
    public interface AccessValidator {
        AccessValidator ALLOW_ALL = (familyId, configId, itemId, roleId) ->
                AccessDecision.allowedDecision();

        AccessDecision validate(@Nonnull String commandFamilyId,
                                @Nullable String requiredCommandConfigId,
                                @Nullable String accessItemId,
                                @Nonnull String profileRoleId);
    }

    public record AccessDecision(boolean allowed, @Nullable String reason) {
        public static AccessDecision allowedDecision() { return new AccessDecision(true, null); }
        public static AccessDecision denied(String reason) {
            return new AccessDecision(false, Objects.requireNonNull(reason, "reason"));
        }
    }
}
