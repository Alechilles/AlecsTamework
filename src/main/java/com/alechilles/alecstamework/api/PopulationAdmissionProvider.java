package com.alechilles.alecstamework.api;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Evaluates one immutable population-admission request against an external policy snapshot.
 *
 * <p>Evaluation can run on any platform executor. It has no game-loop or thread-affinity
 * guarantee, and it must not depend on thread-local game state. A synchronous throw, null stage
 * or decision, exceptional completion, or completion after the coordinator's bounded timeout is
 * translated to {@link PopulationAdmissionProviderStatus#UNAVAILABLE}. Tamework then fails the
 * managed admission closed.
 *
 * <p>Tamework may invoke one registered provider for more than one request at the same time.
 * Request delivery order and completion order have no guarantee. Implementations must be
 * thread-safe and must base each decision only on the immutable request and an external policy
 * snapshot.
 *
 * <p><b>Cached decisions at synchronous sites.</b> A tame, a capture item changing holder and a
 * litter cannot wait for a provider. For these, Tamework asks once per owner and managed family
 * and may reuse the decision for up to 30 seconds. The request for such a decision always
 * describes a new companion for the owner: its operation is {@code NEW_OWNERSHIP}, it has no old
 * owner, and its destination names the world but not a real chunk (chunk 0,0). Do not base the
 * decision on the operation, the old owner or the chunk of such a request. Restores and captures
 * are asked about the exact change and are not cached.
 *
 * <p>Within the reuse window Tamework holds an allowed owner only to the claims and domain limits
 * of the decision, which it counts itself in the step that stores the companion. A provider that
 * counts companions on its own and returns no domain claims can therefore be overshot within
 * that window. Return the claims and limits so Tamework can enforce them.
 */
@FunctionalInterface
public interface PopulationAdmissionProvider {
    /** Returns an allow, deny, or unavailable decision without mutating Tamework state. */
    @Nonnull
    CompletionStage<PopulationAdmissionProviderDecision> evaluate(
            @Nonnull PopulationAdmissionProviderRequest request
    );
}
