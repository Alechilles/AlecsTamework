package com.alechilles.alecstamework.api;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Durable owner/command-family roster authority. Item metadata is only a projection of this API. */
public interface CommandFamilyRosterApi {
    @Nonnull
    Optional<CommandFamilyRosterView> get(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId);

    @Nonnull
    Optional<CommandFamilyRosterMembershipView> getMembership(
            @Nonnull UUID ownerUuid, @Nonnull String commandFamilyId, @Nonnull String profileId);

    @Nonnull
    CompletionStage<CommandFamilyRosterMutationResult> upsert(
            @Nonnull CommandFamilyRosterMutationRequest request);

    @Nonnull
    CompletionStage<CommandFamilyRosterMutationResult> remove(
            @Nonnull CommandFamilyRosterMutationRequest request);

    static CommandFamilyRosterApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    final class UnavailableHolder {
        private static final CommandFamilyRosterApi INSTANCE = new CommandFamilyRosterApi() {
            @Override
            public Optional<CommandFamilyRosterView> get(UUID ownerUuid, String commandFamilyId) {
                Objects.requireNonNull(ownerUuid, "ownerUuid");
                Objects.requireNonNull(commandFamilyId, "commandFamilyId");
                return Optional.empty();
            }

            @Override
            public Optional<CommandFamilyRosterMembershipView> getMembership(
                    UUID ownerUuid, String commandFamilyId, String profileId) {
                Objects.requireNonNull(ownerUuid, "ownerUuid");
                Objects.requireNonNull(commandFamilyId, "commandFamilyId");
                Objects.requireNonNull(profileId, "profileId");
                return Optional.empty();
            }

            @Override
            public CompletionStage<CommandFamilyRosterMutationResult> upsert(
                    CommandFamilyRosterMutationRequest request) {
                Objects.requireNonNull(request, "request");
                return unavailableResult();
            }

            @Override
            public CompletionStage<CommandFamilyRosterMutationResult> remove(
                    CommandFamilyRosterMutationRequest request) {
                Objects.requireNonNull(request, "request");
                return unavailableResult();
            }

            private CompletionStage<CommandFamilyRosterMutationResult> unavailableResult() {
                return CompletableFuture.completedFuture(CommandFamilyRosterMutationResult.unavailable(
                        "command-family-roster-authority-unavailable"));
            }
        };

        private UnavailableHolder() {
        }
    }
}
