package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Production bridge from timed sessions to the mutation-bound population/group authority. */
public final class CommandTimedSummonPopulationPort
        implements CommandTimedSummoningService.PopulationPort {
    private final OwnerPopulationRuntime runtime;
    private final PopulationAdmissionApi admissions;
    private final RoleResolver roles;
    private final ConcurrentHashMap<String, PopulationAdmissionToken> activeReservations =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, StorageTransition> storageTransitions =
            new ConcurrentHashMap<>();

    public CommandTimedSummonPopulationPort(@Nonnull OwnerPopulationRuntime runtime,
                                            @Nonnull PopulationAdmissionApi admissions,
                                            @Nonnull RoleResolver roles) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    @Override
    public CompletionStage<CommandTimedSummoningService.PopulationReservation> reserveActive(
            CommandTimedSummoningService.PopulationContext context) {
        OwnerPopulationEntry owner = owner(context);
        if (owner == null || owner.lifecycleState() != CompanionLifecycleState.ROSTER_STORED) {
            return completedReservation(false, null, "roster-stored-population-source-required");
        }
        PopulationAdmissionLocation destination = destination(context);
        if (destination == null) return completedReservation(false, null, "summon-destination-required");
        UUID currentNpc = currentNpc(context);
        PopulationAdmissionRequest request = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(context.profileId(), null, context.idempotencyKey()),
                currentNpc, owner.revision(), owner.ownerId(), owner.ownerId(), null, destination,
                PopulationAdmissionOperation.RESTORE, 1, PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE);
        PopulationAdmissionRequestV2 v2 = new PopulationAdmissionRequestV2(
                request, requireRole(context), ownershipWorld(owner, context));
        return admissions.tryAdmitV2(v2).thenApply(decision -> {
            if (!decision.accepted() || decision.token() == null) {
                return new CommandTimedSummoningService.PopulationReservation(
                        false, null, decision.reason());
            }
            String operationId = decision.token().operationId().toString();
            activeReservations.put(operationId, decision.token());
            return new CommandTimedSummoningService.PopulationReservation(
                    true, operationId, decision.reason());
        });
    }

    @Override
    public CommandTimedSummoningService.PopulationDecision claimActive(
            CommandTimedSummoningService.PopulationReservation reservation) {
        PopulationAdmissionToken token = token(reservation.populationOperationId());
        return token == null
                ? denied("population-active-reservation-missing")
                : map(admissions.claimForApply(token));
    }

    @Override
    public CompletionStage<CommandTimedSummoningService.PopulationDecision> commitActive(
            CommandTimedSummoningService.PopulationReservation reservation,
            CommandTimedSummoningService.PopulationContext context) {
        PopulationAdmissionToken token = token(reservation.populationOperationId());
        if (token == null) return completed(denied("population-active-reservation-missing"));
        return admissions.commit(token).thenApply(decision -> {
            if (decision.status() == PopulationAdmissionDecision.Status.COMMITTED) {
                activeReservations.remove(token.operationId().toString(), token);
            }
            return map(decision);
        });
    }

    @Override
    public CompletionStage<CommandTimedSummoningService.PopulationDecision> cancel(
            CommandTimedSummoningService.PopulationReservation reservation) {
        PopulationAdmissionToken token = token(reservation.populationOperationId());
        if (token == null) return completed(accepted("population-reservation-already-closed"));
        return admissions.cancel(token).thenApply(decision -> {
            if (decision.status() == PopulationAdmissionDecision.Status.CANCELED) {
                activeReservations.remove(token.operationId().toString(), token);
            }
            return map(decision);
        });
    }

    @Override
    public CompletionStage<CommandTimedSummoningService.PopulationDecision> beginStoring(
            CommandTimedSummoningService.PopulationContext context) {
        OwnerPopulationEntry owner = owner(context);
        if (owner == null || (owner.lifecycleState() != CompanionLifecycleState.ACTIVE
                && owner.lifecycleState() != CompanionLifecycleState.UNLOADED)) {
            return completed(denied("active-population-source-required"));
        }
        CompanionLifecycleState prior = owner.lifecycleState();
        Key key = Key.of(context);
        return transition(context, owner, PopulationCompanionLifecycle.STORING,
                context.idempotencyKey() + ":storing").thenCompose(storing -> {
            if (!storing.accepted()) return completed(storing);
            OwnerPopulationEntry storingOwner = owner(context);
            if (storingOwner == null || storingOwner.lifecycleState() != CompanionLifecycleState.STORING) {
                return completed(denied("storing-population-commit-not-visible"));
            }
            return prepareTransition(context, storingOwner, PopulationCompanionLifecycle.ROSTER_STORED,
                    context.idempotencyKey() + ":stored").thenApply(prepared -> {
                if (prepared.decision().accepted() && prepared.decision().token() != null) {
                    storageTransitions.put(key, new StorageTransition(
                            prior, prepared.decision().token(), requireRole(context)));
                    return accepted("population-storing-held");
                }
                return map(prepared.decision());
            });
        });
    }

    @Override
    public CompletionStage<CommandTimedSummoningService.PopulationDecision> commitRosterStored(
            CommandTimedSummoningService.PopulationContext context) {
        Key key = Key.of(context);
        StorageTransition transition = storageTransitions.get(key);
        if (transition == null) return completed(denied("roster-storage-population-reservation-missing"));
        PopulationAdmissionDecision claimed = admissions.claimForApply(transition.storedToken());
        if (claimed.status() != PopulationAdmissionDecision.Status.APPLYING) return completed(map(claimed));
        return admissions.commit(transition.storedToken()).thenApply(decision -> {
            if (decision.status() == PopulationAdmissionDecision.Status.COMMITTED) {
                storageTransitions.remove(key, transition);
            }
            return map(decision);
        });
    }

    @Override
    public CompletionStage<CommandTimedSummoningService.PopulationDecision> rollbackStoring(
            CommandTimedSummoningService.PopulationContext context) {
        Key key = Key.of(context);
        StorageTransition pending = storageTransitions.remove(key);
        CompletionStage<PopulationAdmissionDecision> cancellation = pending == null
                ? CompletableFuture.completedFuture(null) : admissions.cancel(pending.storedToken());
        return cancellation.thenCompose(ignored -> {
            OwnerPopulationEntry owner = owner(context);
            if (owner == null || owner.lifecycleState() != CompanionLifecycleState.STORING) {
                return completed(denied("storing-population-source-missing"));
            }
            PopulationCompanionLifecycle target = pending != null
                    && pending.priorState() == CompanionLifecycleState.UNLOADED
                    ? PopulationCompanionLifecycle.UNLOADED : PopulationCompanionLifecycle.ACTIVE;
            return transition(context, owner, target, context.idempotencyKey() + ":rollback");
        });
    }

    @Override
    public CompletionStage<CommandTimedSummoningService.PopulationDecision> recoverCancel(
            String operationId, @Nullable String populationOperationId) {
        PopulationAdmissionToken token = token(populationOperationId);
        if (token == null) return completed(accepted("population-recovery-reservation-absent"));
        return admissions.cancel(token).thenApply(CommandTimedSummonPopulationPort::map);
    }

    @Override
    public CompletionStage<CommandTimedSummoningService.PopulationDecision> recoverActive(
            CommandTimedSummoningService.PopulationContext context,
            @Nullable String populationOperationId) {
        OwnerPopulationEntry owner = owner(context);
        if (owner != null && (owner.lifecycleState() == CompanionLifecycleState.ACTIVE
                || owner.lifecycleState() == CompanionLifecycleState.UNLOADED)) {
            return completed(accepted("population-already-active"));
        }
        if (owner != null && owner.lifecycleState() == CompanionLifecycleState.STORING) {
            return rollbackStoring(context);
        }
        return completed(denied("population-active-recovery-unresolved"));
    }

    @Override
    public CompletionStage<CommandTimedSummoningService.PopulationDecision> recoverRosterStored(
            CommandTimedSummoningService.PopulationContext context,
            @Nullable String populationOperationId) {
        OwnerPopulationEntry owner = owner(context);
        if (owner != null && owner.lifecycleState() == CompanionLifecycleState.ROSTER_STORED) {
            return completed(accepted("population-already-roster-stored"));
        }
        return commitRosterStored(context);
    }

    private CompletionStage<CommandTimedSummoningService.PopulationDecision> transition(
            CommandTimedSummoningService.PopulationContext context,
            OwnerPopulationEntry owner,
            PopulationCompanionLifecycle target,
            String operationKey) {
        return prepareTransition(context, owner, target, operationKey).thenCompose(prepared -> {
            PopulationAdmissionDecision decision = prepared.decision();
            if (!decision.accepted() || decision.token() == null) return completed(map(decision));
            PopulationAdmissionDecision claimed = admissions.claimForApply(decision.token());
            if (claimed.status() != PopulationAdmissionDecision.Status.APPLYING) return completed(map(claimed));
            return admissions.commit(decision.token()).thenApply(CommandTimedSummonPopulationPort::map);
        });
    }

    private CompletionStage<PreparedTransition> prepareTransition(
            CommandTimedSummoningService.PopulationContext context,
            OwnerPopulationEntry owner,
            PopulationCompanionLifecycle target,
            String operationKey) {
        ClaimOccupancyEntry claim = runtime.claimOccupancyIndex().entry(context.profileId()).orElse(null);
        PopulationAdmissionLocation source = claim == null || claim.physicalChunk() == null ? null
                : new PopulationAdmissionLocation(claim.physicalChunk().worldName(),
                claim.physicalChunk().chunkX(), claim.physicalChunk().chunkZ());
        PopulationAdmissionLocation destination = target.occupiesPhysicalClaim() ? source : null;
        if (target.occupiesPhysicalClaim() && destination == null) {
            return CompletableFuture.completedFuture(new PreparedTransition(
                    PopulationAdmissionDecision.unavailable("physical-population-location-unavailable")));
        }
        PopulationAdmissionRequest request = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(context.profileId(), null, operationKey),
                currentNpc(context), owner.revision(), owner.ownerId(), owner.ownerId(), source, destination,
                PopulationAdmissionOperation.LIFECYCLE_CHANGE, 1,
                PopulationAdmissionForcePolicy.ENFORCE, target);
        PopulationAdmissionRequestV2 v2 = new PopulationAdmissionRequestV2(
                request, requireRole(context), ownershipWorld(owner, context));
        return admissions.tryAdmitV2(v2).thenApply(PreparedTransition::new);
    }

    @Nullable private OwnerPopulationEntry owner(CommandTimedSummoningService.PopulationContext context) {
        return runtime.index().entry(context.profileId()).orElse(null);
    }

    @Nullable private UUID currentNpc(CommandTimedSummoningService.PopulationContext context) {
        return context.projectionNpcUuid() != null ? context.projectionNpcUuid()
                : runtime.identityResolver().currentNpcUuid(context.profileId()).orElse(null);
    }

    @Nullable private PopulationAdmissionToken token(@Nullable String operationId) {
        return operationId == null ? null : activeReservations.get(operationId);
    }

    private String requireRole(CommandTimedSummoningService.PopulationContext context) {
        String role = context.roleId() != null ? context.roleId() : roles.resolve(context.profileId());
        if (role == null || role.isBlank()) throw new IllegalStateException("timed-summon-role-unavailable");
        return role.trim();
    }

    private static String ownershipWorld(OwnerPopulationEntry owner,
                                         CommandTimedSummoningService.PopulationContext context) {
        String world = context.destinationWorld() != null
                ? context.destinationWorld() : owner.ownershipWorldName();
        if (world == null || world.isBlank()) throw new IllegalStateException("ownership-world-unavailable");
        return world;
    }

    @Nullable
    private static PopulationAdmissionLocation destination(
            CommandTimedSummoningService.PopulationContext context) {
        return context.destinationWorld() == null || context.destinationChunkX() == null
                || context.destinationChunkZ() == null ? null
                : new PopulationAdmissionLocation(context.destinationWorld(),
                context.destinationChunkX(), context.destinationChunkZ());
    }

    private static CommandTimedSummoningService.PopulationDecision map(PopulationAdmissionDecision decision) {
        return new CommandTimedSummoningService.PopulationDecision(decision.accepted(), decision.reason());
    }

    private static CommandTimedSummoningService.PopulationDecision accepted(String reason) {
        return CommandTimedSummoningService.PopulationDecision.accepted(reason);
    }

    private static CommandTimedSummoningService.PopulationDecision denied(String reason) {
        return CommandTimedSummoningService.PopulationDecision.denied(reason);
    }

    private static CompletionStage<CommandTimedSummoningService.PopulationDecision> completed(
            CommandTimedSummoningService.PopulationDecision decision) {
        return CompletableFuture.completedFuture(decision);
    }

    private static CompletionStage<CommandTimedSummoningService.PopulationReservation> completedReservation(
            boolean accepted, @Nullable String operation, String reason) {
        return CompletableFuture.completedFuture(
                new CommandTimedSummoningService.PopulationReservation(accepted, operation, reason));
    }

    @FunctionalInterface
    public interface RoleResolver { @Nullable String resolve(@Nonnull String profileId); }

    private record PreparedTransition(@Nonnull PopulationAdmissionDecision decision) { }
    private record StorageTransition(@Nonnull CompanionLifecycleState priorState,
                                     @Nonnull PopulationAdmissionToken storedToken,
                                     @Nonnull String roleId) { }
    private record Key(@Nonnull UUID ownerUuid, @Nonnull String familyId, @Nonnull String profileId) {
        static Key of(CommandTimedSummoningService.PopulationContext context) {
            return new Key(context.ownerUuid(), context.commandFamilyId(), context.profileId());
        }
    }
}
