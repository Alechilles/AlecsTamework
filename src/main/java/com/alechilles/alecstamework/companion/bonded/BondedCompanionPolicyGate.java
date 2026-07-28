package com.alechilles.alecstamework.companion.bonded;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects the exact family policy used by one bonded lifecycle mutation. */
final class BondedCompanionPolicyGate {
    private final BondedCompanionPolicyResolver resolver;

    BondedCompanionPolicyGate(@Nonnull BondedCompanionPolicyResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    Check forCreation(BondedCompanionTransitionService.CreationRequest request) {
        return map(resolver.resolveForRole(
                request.rosterId(), request.familyId(), request.roleId(),
                request.expectedPolicyRevision()
        ));
    }

    Check forProfile(BondedCompanionProfile profile, long expectedRevision) {
        return map(resolver.resolve(
                profile.rosterId(), profile.familyId(), expectedRevision
        ));
    }

    private static Check map(BondedCompanionPolicyResolver.Resolution resolved) {
        return switch (resolved.status()) {
            case FOUND -> new Check(null, resolved.policy());
            case NOT_FOUND -> denied(
                    BondedCompanionTransitionService.ResultCode.POLICY_NOT_FOUND
            );
            case ROLE_NOT_ALLOWED -> denied(
                    BondedCompanionTransitionService.ResultCode.ROLE_NOT_ALLOWED
            );
            case AMBIGUOUS -> denied(
                    BondedCompanionTransitionService.ResultCode.POLICY_AMBIGUOUS
            );
            case REVISION_CONFLICT -> denied(
                    BondedCompanionTransitionService.ResultCode
                            .POLICY_REVISION_CONFLICT
            );
        };
    }

    private static Check denied(
            BondedCompanionTransitionService.ResultCode code
    ) {
        return new Check(code, null);
    }

    record Check(
            @Nullable BondedCompanionTransitionService.ResultCode code,
            @Nullable BondedCompanionPolicy policy
    ) {
        boolean allowed() {
            return policy != null;
        }
    }
}
