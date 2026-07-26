package com.alechilles.alecstamework.companion.bonded;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Pure policy-gated transition authority for the three-state bonded lifecycle. */
public final class BondedCompanionTransitionService {
    private final BondedCompanionPolicyResolver policies;

    public BondedCompanionTransitionService(
            @Nonnull BondedCompanionPolicyResolver policies
    ) {
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    /** Creates one captured profile directly in durable stored state. */
    @Nonnull
    public TransitionResult createCaptured(
            @Nonnull CreationRequest request,
            @Nonnull RosterCounts counts
    ) {
        return create(request, counts, policy -> policy.features().capture());
    }

    /** Creates one provisioned profile directly in durable stored state. */
    @Nonnull
    public TransitionResult createProvisioned(
            @Nonnull CreationRequest request,
            @Nonnull RosterCounts counts
    ) {
        return create(request, counts, policy -> policy.features().provision());
    }

    private TransitionResult create(
            CreationRequest request,
            RosterCounts counts,
            Predicate<BondedCompanionPolicy> enabled
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(counts, "counts");
        PolicyCheck checked = policy(request.rosterId(),
                request.expectedPolicyRevision());
        if (!checked.allowed()) {
            return rejected(checked.code(), null);
        }
        BondedCompanionPolicy policy = checked.policy();
        ResultCode denial = validateCreation(policy, request, counts, enabled);
        if (denial != null) {
            return rejected(denial, null);
        }
        return applied(new BondedCompanionProfile(
                request.profileId(), request.ownerUuid(), request.rosterId(),
                policy.familyId(), request.roleId(), BondedCompanionState.STORED,
                0L, request.snapshot(), null, 0L, null, 0L,
                request.operationId()
        ));
    }

    /** Starts the only allowed {@code STORED -> ACTIVE} transition. */
    @Nonnull
    public TransitionResult summon(
            @Nonnull MutationRequest request,
            @Nonnull BondedCompanionProfile profile,
            @Nonnull RosterCounts counts,
            @Nonnull String leaseToken,
            @Nonnull String worldKey
    ) {
        TransitionResult prerequisite = prerequisite(request, profile);
        if (prerequisite != null) {
            return prerequisite;
        }
        PolicyCheck checked = policy(profile.rosterId(),
                request.expectedPolicyRevision());
        ResultCode denial = validateSummon(
                checked, request, profile, counts
        );
        if (denial != null) {
            return rejected(denial, profile);
        }
        try {
            BondedCompanionPolicy policy = checked.policy();
            long expiresAt = timeAfterSeconds(
                    request.nowMs(), policy.sessionDurationSeconds()
            );
            BondedCompanionLease lease = new BondedCompanionLease(
                    leaseToken, worldKey, request.nowMs(), expiresAt
            );
            return applied(copy(
                    profile, BondedCompanionState.ACTIVE,
                    profile.snapshot(), lease, profile.summonCooldownUntilMs(),
                    null, profile.reviveCount(), request.operationId()
            ));
        } catch (ArithmeticException invalidTime) {
            return rejected(ResultCode.VALIDATION_FAILED, profile);
        }
    }

    /** Stores one active projection while retaining unobserved prior state. */
    @Nonnull
    public TransitionResult store(
            @Nonnull MutationRequest request,
            @Nonnull BondedCompanionProfile profile,
            @Nonnull BondedCompanionSnapshot captured
    ) {
        TransitionResult prerequisite = prerequisite(request, profile);
        if (prerequisite != null) {
            return prerequisite;
        }
        PolicyCheck checked = policy(profile.rosterId(),
                request.expectedPolicyRevision());
        ResultCode denial = validateExistingPolicy(
                checked, profile, policy -> policy.features().dismiss()
        );
        if (denial != null) {
            return rejected(denial, profile);
        }
        if (profile.state() != BondedCompanionState.ACTIVE) {
            return rejected(ResultCode.INVALID_STATE, profile);
        }
        try {
            long cooldownUntil = timeAfterSeconds(
                    request.nowMs(), checked.policy().summonCooldownSeconds()
            );
            return applied(copy(
                    profile, BondedCompanionState.STORED,
                    profile.snapshot().mergeForStore(captured), null,
                    cooldownUntil, null, profile.reviveCount(),
                    request.operationId()
            ));
        } catch (ArithmeticException invalidTime) {
            return rejected(ResultCode.VALIDATION_FAILED, profile);
        }
    }

    /** Marks death only from a positively confirmed active projection death. */
    @Nonnull
    public TransitionResult confirmDeath(
            @Nonnull MutationRequest request,
            @Nonnull BondedCompanionProfile profile
    ) {
        TransitionResult prerequisite = prerequisite(request, profile);
        if (prerequisite != null) {
            return prerequisite;
        }
        PolicyCheck checked = policy(profile.rosterId(),
                request.expectedPolicyRevision());
        ResultCode denial = validateExistingPolicy(checked, profile, policy -> true);
        if (denial != null) {
            return rejected(denial, profile);
        }
        if (profile.state() != BondedCompanionState.ACTIVE) {
            return rejected(ResultCode.INVALID_STATE, profile);
        }
        return applied(copy(
                profile, BondedCompanionState.DEAD, profile.snapshot(), null,
                0L, request.nowMs(), profile.reviveCount(),
                request.operationId()
        ));
    }

    /** Applies an exact paid quote and returns a dead profile to stored state. */
    @Nonnull
    public TransitionResult revive(
            @Nonnull MutationRequest request,
            @Nonnull BondedCompanionProfile profile,
            @Nonnull RevivePayment payment
    ) {
        TransitionResult prerequisite = prerequisite(request, profile);
        if (prerequisite != null) {
            return prerequisite;
        }
        PolicyCheck checked = policy(profile.rosterId(),
                request.expectedPolicyRevision());
        ResultCode denial = validateExistingPolicy(
                checked, profile, policy -> policy.features().revive()
        );
        if (denial != null) {
            return rejected(denial, profile);
        }
        if (profile.state() != BondedCompanionState.DEAD) {
            return rejected(ResultCode.INVALID_STATE, profile);
        }
        if (!matches(checked.policy().revivePrice(), payment)) {
            return rejected(ResultCode.REVIVE_PRICE_MISMATCH, profile);
        }
        try {
            return applied(copy(
                    profile, BondedCompanionState.STORED, profile.snapshot(),
                    null, 0L, null, Math.incrementExact(profile.reviveCount()),
                    request.operationId()
            ));
        } catch (ArithmeticException counterOverflow) {
            return rejected(ResultCode.VALIDATION_FAILED, profile);
        }
    }

    private TransitionResult prerequisite(
            MutationRequest request,
            BondedCompanionProfile profile
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(profile, "profile");
        if (profile.wasApplied(request.operationId())) {
            return new TransitionResult(ResultCode.IDEMPOTENT_REPLAY, profile);
        }
        if (!profile.ownerUuid().equals(request.actorOwnerUuid())) {
            return rejected(ResultCode.NOT_OWNER, profile);
        }
        if (profile.revision() != request.expectedRevision()) {
            return rejected(ResultCode.REVISION_CONFLICT, profile);
        }
        return null;
    }

    private ResultCode validateCreation(
            BondedCompanionPolicy policy,
            CreationRequest request,
            RosterCounts counts,
            Predicate<BondedCompanionPolicy> enabled
    ) {
        if (!enabled.test(policy)) {
            return ResultCode.FEATURE_DISABLED;
        }
        if (!policy.allowedRoles().contains(request.roleId())) {
            return ResultCode.ROLE_NOT_ALLOWED;
        }
        if (counts.owned() >= policy.maximumOwned()) {
            return ResultCode.OWNED_CAPACITY_REACHED;
        }
        return null;
    }

    private ResultCode validateSummon(
            PolicyCheck checked,
            MutationRequest request,
            BondedCompanionProfile profile,
            RosterCounts counts
    ) {
        ResultCode policyDenial = validateExistingPolicy(
                checked, profile, policy -> policy.features().summon()
        );
        if (policyDenial != null) {
            return policyDenial;
        }
        if (profile.state() != BondedCompanionState.STORED) {
            return ResultCode.INVALID_STATE;
        }
        if (counts.active() >= checked.policy().maximumActive()) {
            return ResultCode.ACTIVE_CAPACITY_REACHED;
        }
        long cooldown = profile.summonCooldownUntilMs();
        return cooldown != 0L && request.nowMs() < cooldown
                ? ResultCode.COOLDOWN_ACTIVE : null;
    }

    private ResultCode validateExistingPolicy(
            PolicyCheck checked,
            BondedCompanionProfile profile,
            Predicate<BondedCompanionPolicy> enabled
    ) {
        if (!checked.allowed()) {
            return checked.code();
        }
        BondedCompanionPolicy policy = checked.policy();
        if (!policy.rosterId().equals(profile.rosterId())
                || !policy.familyId().equals(profile.familyId())) {
            return ResultCode.POLICY_MISMATCH;
        }
        if (!policy.allowedRoles().contains(profile.roleId())) {
            return ResultCode.ROLE_NOT_ALLOWED;
        }
        return enabled.test(policy) ? null : ResultCode.FEATURE_DISABLED;
    }

    private PolicyCheck policy(String rosterId, long expectedRevision) {
        BondedCompanionPolicyResolver.Resolution resolved =
                policies.resolve(rosterId, expectedRevision);
        return switch (resolved.status()) {
            case FOUND -> new PolicyCheck(null, resolved.policy());
            case NOT_FOUND -> new PolicyCheck(
                    ResultCode.POLICY_NOT_FOUND, null
            );
            case REVISION_CONFLICT -> new PolicyCheck(
                    ResultCode.POLICY_REVISION_CONFLICT, null
            );
        };
    }

    private BondedCompanionProfile copy(
            BondedCompanionProfile source,
            BondedCompanionState state,
            BondedCompanionSnapshot snapshot,
            BondedCompanionLease lease,
            long cooldownUntil,
            Long diedAt,
            long reviveCount,
            String operationId
    ) {
        return new BondedCompanionProfile(
                source.profileId(), source.ownerUuid(), source.rosterId(),
                source.familyId(), source.roleId(), state,
                Math.incrementExact(source.revision()), snapshot, lease,
                cooldownUntil, diedAt, reviveCount, operationId
        );
    }

    private static boolean matches(
            BondedCompanionPolicy.RevivePrice price,
            RevivePayment payment
    ) {
        return price != null
                && price.itemId().equals(payment.itemId())
                && price.quantity() == payment.quantity();
    }

    private static long timeAfterSeconds(long nowMs, long seconds) {
        return seconds == 0L
                ? 0L
                : Math.addExact(nowMs, Math.multiplyExact(seconds, 1_000L));
    }

    private static TransitionResult applied(BondedCompanionProfile profile) {
        return new TransitionResult(ResultCode.APPLIED, profile);
    }

    private static TransitionResult rejected(
            ResultCode code,
            BondedCompanionProfile profile
    ) {
        return new TransitionResult(code, profile);
    }

    /** Explicit lifecycle and policy outcomes for callers and player feedback. */
    public enum ResultCode {
        APPLIED,
        IDEMPOTENT_REPLAY,
        POLICY_NOT_FOUND,
        POLICY_REVISION_CONFLICT,
        POLICY_MISMATCH,
        FEATURE_DISABLED,
        NOT_OWNER,
        REVISION_CONFLICT,
        INVALID_STATE,
        ROLE_NOT_ALLOWED,
        OWNED_CAPACITY_REACHED,
        ACTIVE_CAPACITY_REACHED,
        COOLDOWN_ACTIVE,
        REVIVE_PRICE_MISMATCH,
        VALIDATION_FAILED
    }

    /** New capture/provision boundary. */
    public record CreationRequest(
            @Nonnull String operationId,
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            @Nonnull String roleId,
            @Nonnull BondedCompanionSnapshot snapshot,
            long expectedPolicyRevision,
            long nowMs
    ) {
        public CreationRequest {
            operationId = text(operationId, "operationId");
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            rosterId = text(rosterId, "rosterId");
            profileId = text(profileId, "profileId");
            roleId = text(roleId, "roleId");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            if (expectedPolicyRevision < 0L) {
                throw new IllegalArgumentException("negative policy revision");
            }
        }
    }

    /** Owner, revision, policy generation, and signed-time mutation fence. */
    public record MutationRequest(
            @Nonnull String operationId,
            @Nonnull UUID actorOwnerUuid,
            long expectedRevision,
            long expectedPolicyRevision,
            long nowMs
    ) {
        public MutationRequest {
            operationId = text(operationId, "operationId");
            actorOwnerUuid = Objects.requireNonNull(
                    actorOwnerUuid, "actorOwnerUuid"
            );
            if (expectedRevision < 0L || expectedPolicyRevision < 0L) {
                throw new IllegalArgumentException("negative revision fence");
            }
        }
    }

    /** Counts observed inside the owner-roster mutation boundary. */
    public record RosterCounts(int owned, int active) {
        public RosterCounts {
            if (owned < 0 || active < 0 || active > owned) {
                throw new IllegalArgumentException("invalid roster counts");
            }
        }
    }

    /** Exact item payment consumed only after the transition is accepted. */
    public record RevivePayment(@Nonnull String itemId, int quantity) {
        public RevivePayment {
            itemId = text(itemId, "itemId");
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
        }
    }

    /** Immutable transition outcome containing the unchanged or new profile. */
    public record TransitionResult(
            @Nonnull ResultCode code,
            @Nullable BondedCompanionProfile profile
    ) {
        public TransitionResult {
            code = Objects.requireNonNull(code, "code");
        }

        public boolean applied() {
            return code == ResultCode.APPLIED;
        }
    }

    private record PolicyCheck(
            @Nullable ResultCode code,
            @Nullable BondedCompanionPolicy policy
    ) {
        private boolean allowed() {
            return policy != null;
        }
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
