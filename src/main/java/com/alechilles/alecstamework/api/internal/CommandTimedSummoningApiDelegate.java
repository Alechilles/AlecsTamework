package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningChangedEvent;
import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.api.CommandTimedSummoningResult;
import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import com.alechilles.alecstamework.api.CommandTimedSummoningView;
import com.alechilles.alecstamework.api.CompanionProvisioningLinkRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningResult;
import com.alechilles.alecstamework.items.CommandTimedSummoningService;
import com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonPolicySnapshot;
import com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonRepository;
import com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonSessionRecord;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceReadExecutor;
import com.alechilles.alecstamework.provisioning.CompanionProvisioningCoordinator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Runtime public API adapter; config/profile/world resolution remains an injected authority. */
public final class CommandTimedSummoningApiDelegate implements CommandTimedSummoningApi {
    private final CommandTimedSummonRepository repository;
    private final CommandTimedSummoningService service;
    private final PersistenceReadExecutor reads;
    private final RequestResolver resolver;
    private final LongSupplier clock;
    private final CopyOnWriteArrayList<Consumer<CommandTimedSummoningChangedEvent>> listeners =
            new CopyOnWriteArrayList<>();
    private final java.util.Set<SessionKey> refreshes = ConcurrentHashMap.newKeySet();

    public CommandTimedSummoningApiDelegate(@Nonnull CommandTimedSummonRepository repository,
                                            @Nonnull CommandTimedSummoningService service,
                                            @Nonnull PersistenceReadExecutor reads,
                                            @Nonnull RequestResolver resolver,
                                            @Nonnull LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.service = Objects.requireNonNull(service, "service");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<CommandTimedSummoningView> get(CommandTimedSummoningRequest identity) {
        Objects.requireNonNull(identity, "identity");
        CommandTimedSummonSessionRecord cached = repository.cachedSession(
                identity.ownerUuid(), identity.commandFamilyId(), identity.profileId());
        if (cached == null) scheduleRefresh(identity);
        return Optional.ofNullable(cached).map(session -> view(session, clock.getAsLong()));
    }

    @Override
    public CompletionStage<CommandTimedSummoningResult> summon(CommandTimedSummoningRequest request) {
        Objects.requireNonNull(request, "request");
        long nowMs = clock.getAsLong();
        return reads.submit(() -> resolver.resolve(request, Operation.SUMMON)).thenCompose(resolved -> {
            if (resolved == null) return denied("timed-summon-request-unresolvable");
            return service.summon(new CommandTimedSummoningService.SummonRequest(
                        request.ownerUuid(), request.commandFamilyId(), request.profileId(),
                        resolved.profileRevision(), resolved.roleId(), resolved.configId(),
                        resolved.configRevision(), resolved.policy(), request.idempotencyKey(), nowMs))
                    .thenApply(result -> publish(request, result, "summon", nowMs));
        });
    }

    @Override
    public CompletionStage<CommandTimedSummoningResult> dismiss(CommandTimedSummoningRequest request) {
        Objects.requireNonNull(request, "request");
        long nowMs = clock.getAsLong();
        return reads.submit(() -> resolver.resolve(request, Operation.DISMISS)).thenCompose(resolved -> {
            if (resolved == null) return denied("timed-dismiss-request-unresolvable");
            return service.dismiss(new CommandTimedSummoningService.DismissRequest(
                        request.ownerUuid(), request.commandFamilyId(), request.profileId(),
                        resolved.profileRevision(), resolved.projectionNpcUuid(),
                        request.idempotencyKey(), nowMs))
                    .thenApply(result -> publish(request, result, "dismiss", nowMs));
        });
    }

    @Override
    public AutoCloseable subscribe(Consumer<CommandTimedSummoningChangedEvent> listener) {
        Consumer<CommandTimedSummoningChangedEvent> required = Objects.requireNonNull(listener, "listener");
        listeners.add(required);
        return () -> listeners.remove(required);
    }

    /**
     * Tamework-owned post-commit hook for provision-and-link. It creates the dormant timed row first
     * and then uses the same admission/placement/lease path as a later Horn summon.
     */
    @Nonnull
    public CompanionProvisioningCoordinator.InitialProjectionHook initialProjectionHook() {
        return this::projectInitially;
    }

    private CompletionStage<CommandTimedSummoningResult> projectInitially(
            CompanionProvisioningLinkRequest link,
            CompanionProvisioningResult provisioning) {
        if (provisioning.ownerUuid() == null || provisioning.profileId() == null
                || provisioning.roleId() == null || provisioning.idempotencyKey() == null
                || provisioning.profileRevision() < 0L) {
            return denied("initial-timed-projection-identity-unavailable");
        }
        CommandTimedSummoningRequest identity = new CommandTimedSummoningRequest(
                provisioning.ownerUuid(), link.commandFamilyId(), provisioning.profileId(),
                provisioning.idempotencyKey());
        long nowMs = clock.getAsLong();
        return reads.submit(() -> resolver.resolve(identity, Operation.SUMMON)).thenCompose(resolved -> {
            if (resolved == null) return denied("initial-timed-projection-unresolvable");
            return service.registerAndSummonInitial(new CommandTimedSummoningService.InitialSummonRequest(
                        provisioning.ownerUuid(), link.commandFamilyId(), provisioning.profileId(),
                        provisioning.profileRevision(), provisioning.roleId(), resolved.configId(),
                        resolved.configRevision(), resolved.policy(), provisioning.idempotencyKey(), nowMs))
                    .thenApply(result -> publish(identity, result, "initial-projection", nowMs));
        });
    }

    private CommandTimedSummoningResult publish(CommandTimedSummoningRequest request,
                                                 CommandTimedSummoningService.ActionResult action,
                                                 String reason,
                                                 long nowMs) {
        CommandTimedSummoningView current = action.session() == null ? null : view(action.session(), nowMs);
        CommandTimedSummoningResult result = new CommandTimedSummoningResult(
                mapStatus(action.status()), action.reason(), current);
        if (result.successful() && current != null) {
            CommandTimedSummoningChangedEvent event = new CommandTimedSummoningChangedEvent(
                    null, current, reason, nowMs);
            for (Consumer<CommandTimedSummoningChangedEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException ignored) {
                    // A downstream event listener cannot roll back or mask a committed lifecycle mutation.
                }
            }
        }
        return result;
    }

    @Nonnull
    public static CommandTimedSummoningView view(@Nonnull CommandTimedSummonSessionRecord session,
                                                 long nowMs) {
        Long remaining = session.remainingAt(nowMs);
        return new CommandTimedSummoningView(
                session.ownerUuid(), session.commandFamilyId(), session.profileId(), session.rowRevision(),
                CommandTimedSummoningState.valueOf(session.state().name()), session.summonSessionId(),
                remaining, session.unlimitedLease(), session.resummonCooldownUntilMs(), session.updatedAtMs());
    }

    private static CommandTimedSummoningResult.Status mapStatus(
            CommandTimedSummoningService.Status status) {
        return switch (status) {
            case SUCCESS -> CommandTimedSummoningResult.Status.SUCCESS;
            case NOOP -> CommandTimedSummoningResult.Status.IDEMPOTENT;
            case DENIED -> CommandTimedSummoningResult.Status.DENIED;
            case COOLDOWN -> CommandTimedSummoningResult.Status.COOLDOWN;
            case RECOVERING -> CommandTimedSummoningResult.Status.RECOVERING;
            case UNAVAILABLE -> CommandTimedSummoningResult.Status.UNAVAILABLE;
        };
    }

    private static CompletionStage<CommandTimedSummoningResult> denied(String reason) {
        return CompletableFuture.completedFuture(new CommandTimedSummoningResult(
                CommandTimedSummoningResult.Status.DENIED, reason, null));
    }

    private void scheduleRefresh(CommandTimedSummoningRequest identity) {
        SessionKey key = new SessionKey(
                identity.ownerUuid(), identity.commandFamilyId(), identity.profileId());
        if (!refreshes.add(key)) return;
        reads.submit(() -> repository.findSession(
                        key.ownerUuid(), key.commandFamilyId(), key.profileId()))
                .whenComplete((ignored, failure) -> refreshes.remove(key));
    }

    private record SessionKey(UUID ownerUuid, String commandFamilyId, String profileId) {
    }

    public enum Operation { SUMMON, DISMISS }

    @FunctionalInterface
    public interface RequestResolver {
        @Nullable ResolvedRequest resolve(@Nonnull CommandTimedSummoningRequest request,
                                          @Nonnull Operation operation);
    }

    public record ResolvedRequest(long profileRevision,
                                  @Nonnull String roleId,
                                  @Nullable String configId,
                                  @Nullable Long configRevision,
                                  @Nonnull CommandTimedSummonPolicySnapshot policy,
                                  @Nullable UUID projectionNpcUuid) {
        public ResolvedRequest {
            if (profileRevision < 0L) throw new IllegalArgumentException("profileRevision must be non-negative.");
            roleId = Objects.requireNonNull(roleId, "roleId");
            Objects.requireNonNull(policy, "policy");
        }
    }
}
