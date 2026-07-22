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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Focused service seam shared by provisioning, capture, and command lifecycle coordinators. */
public final class CommandFamilyRosterService implements CommandFamilyRosterApi {
    private final CommandFamilyRosterRepository repository;
    private final AccessValidator accessValidator;
    @Nullable private final TameworkEventBus eventBus;

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
            emitChanged(value);
            return new CommandFamilyRosterMutationResult(value.status(), value.reason(), value.roster(),
                    value.currentMembership(), value.idempotentReplay());
        });
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
