package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.ReleasedCompanionCleanup;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.Universe;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Preview and explicit confirmation for a finite, sequential ordinary-companion cleanup. */
public final class TameworkDebugClearOwnedCommand extends AbstractTameworkServerCommand {
    private static final String PREFIX = "server.tamework.commands.clearOwned.";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final PublicPersistenceQueries queries;
    private final PublicPersistenceOperations operations;
    private final AtomicBoolean running = new AtomicBoolean();

    public TameworkDebugClearOwnedCommand(@Nullable PublicPersistenceQueries queries,
                                          @Nullable PublicPersistenceOperations operations) {
        super("clear-owned", PREFIX + "description");
        requirePermission(TameworkCommandRoot.ROOT_PERMISSION);
        setAllowsExtraArguments(true);
        this.queries = queries;
        this.operations = operations;
    }

    @Override protected void executeServer(@Nonnull CommandContext context) {
        String[] args = TameworkCommandInput.argumentsAfter(context.getInputString(), "clear-owned");
        if (context.getInputString().contains("--") || args.length > 2
                || args.length == 2 && !"confirm".equalsIgnoreCase(args[1])) {
            context.sendMessage(Message.translation(PREFIX + "usage")); return;
        }
        UUID owner = resolveOwner(args.length == 0 ? null : args[0], context);
        if (owner == null) { context.sendMessage(Message.translation(PREFIX + "unknownPlayer")); return; }
        if (queries == null || operations == null) {
            context.sendMessage(Message.translation(PREFIX + "unavailable")); return;
        }
        boolean acquired = false;
        try {
            var protectedProfiles = new HashSet<>(queries.projectedCommandRosterActions().keySet());
            protectedProfiles.addAll(queries.projectedLaggingCommandRosterProfiles());
            var candidates = new ArrayList<ProfileId>();
            int protectedCount = 0;
            for (var profile : queries.projectedProfileSnapshot().values()) {
                if (profile.ownerId() == null || !owner.equals(profile.ownerId().value())) continue;
                if (OwnedAnimalCleanupService.eligible(profile.lifecycleState())
                        && !protectedProfiles.contains(profile.profileId())) candidates.add(profile.profileId());
                else protectedCount++;
            }
            if (args.length < 2) {
                context.sendMessage(Message.translation(PREFIX + "preview")
                        .param("owner", owner.toString()).param("count", candidates.size())
                        .param("skipped", protectedCount));
                return;
            }
            if (!running.compareAndSet(false, true)) {
                context.sendMessage(Message.translation(PREFIX + "busy")); return;
            }
            acquired = true;
            int skipped = protectedCount;
            context.sendMessage(Message.translation(PREFIX + "started")
                    .param("owner", owner.toString()).param("count", candidates.size()));
            new OwnedAnimalCleanupService(port()).clear(owner, candidates).whenComplete((result, failure) -> {
                running.set(false);
                if (failure != null) {
                    LOGGER.at(Level.WARNING).withCause(failure).log("Owned animal cleanup failed for %s", owner);
                    context.sendMessage(Message.translation(PREFIX + "unavailable"));
                } else {
                    context.sendMessage(Message.translation(PREFIX + "completed").param("owner", owner.toString())
                            .param("cleared", result.cleared()).param("skipped", skipped + result.skipped())
                            .param("failed", result.failed()));
                }
            });
        } catch (RuntimeException failure) {
            if (acquired) running.set(false);
            LOGGER.at(Level.WARNING).withCause(failure).log("Could not start owned animal cleanup for %s", owner);
            context.sendMessage(Message.translation(PREFIX + "unavailable"));
        }
    }

    private OwnedAnimalCleanupService.Port port() {
        return new OwnedAnimalCleanupService.Port() {
            public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>> read(ProfileId id) {
                return queries.findProfile(id);
            }
            public CompletionStage<Boolean> release(OwnerPopulationTransitionRequest request) {
                String key = "debug-clear-owned:" + request.profileId() + ":" + request.expectedLifecycleRevision();
                var submitted = operations.transitionOwnerPopulation(
                        new OperationId(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))),
                        new IdempotencyKey(key), request);
                if (!submitted.accepted()) return CompletableFuture.completedFuture(false);
                return submitted.completion().thenApply(result -> {
                    boolean published = result != null && result.status() == OperationWorkflowResult.Status.PUBLISHED;
                    if (!published) LOGGER.at(Level.WARNING).log("Owned animal cleanup did not publish for %s: %s",
                            request.profileId(), result == null ? "no result" : result.status());
                    return published;
                });
            }
            public CompletionStage<Void> cleanup(CompanionProfileReadModel profile) {
                if (profile.currentAlias() == null) return CompletableFuture.completedFuture(null);
                var universe = Universe.get();
                if (universe == null) return CompletableFuture.failedFuture(new IllegalStateException("universe_unavailable"));
                UUID alias = profile.currentAlias().alias().value();
                CompletionStage<Void> result = CompletableFuture.completedFuture(null);
                // Exact UUID lookup in each loaded world, not an entity scan. Unloaded aliases are handled on load.
                for (String worldName : universe.getWorlds().keySet()) {
                    result = result.thenCompose(ignored -> ReleasedCompanionCleanup.remove(queries, worldName, alias));
                }
                return result;
            }
        };
    }

    @Nullable private static UUID resolveOwner(String value, CommandContext context) {
        if (value == null || "self".equalsIgnoreCase(value)) return context.isPlayer() ? context.sender().getUuid() : null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { }
        var universe = Universe.get();
        if (universe == null) return null;
        // PlayerRef identity metadata only; no live Player components or tick scan.
        for (var player : universe.getPlayers()) {
            if (value.equalsIgnoreCase(player.getUsername())) return player.getUuid();
        }
        return null;
    }
}
