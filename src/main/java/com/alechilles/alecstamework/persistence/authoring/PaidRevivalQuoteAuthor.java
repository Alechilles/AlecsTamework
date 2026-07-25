package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.PaidCommandRevivalCostQuoteView;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuoteRequest;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.items.persistence.TameworkDormantSnapshotFactsReader;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nullable;

/** Reads one current, inventory-backed paid revival quote. */
final class PaidRevivalQuoteAuthor {
    private final ReplacementFeatureEvidenceQueries queries;
    private final ReplacementFeaturePolicySource policies;
    private final ReplacementFeatureLiveEvidenceSource live;
    private final TameworkDormantSnapshotFactsReader facts;

    PaidRevivalQuoteAuthor(
            ReplacementFeatureEvidenceQueries queries,
            ReplacementFeaturePolicySource policies,
            ReplacementFeatureLiveEvidenceSource live,
            TameworkDormantSnapshotFactsReader facts
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.live = Objects.requireNonNull(live, "live");
        this.facts = Objects.requireNonNull(facts, "facts");
    }

    CompletionStage<PaidCommandRevivalQuote> quote(
            PaidCommandRevivalQuoteRequest request
    ) {
        if (request == null) {
            return CompletableFuture.completedFuture(null);
        }
        final ProfileId profileId;
        try {
            profileId = ProfileId.parse(request.profileId());
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(null);
        }
        return queries.findProfile(profileId).thenCompose(profileRead -> {
            CompanionProfileReadModel profile = found(profileRead);
            if (!validProfile(request, profile)) {
                return CompletableFuture.completedFuture(null);
            }
            return queries.findMembership(profileId)
                    .thenCompose(membershipRead -> {
                        CommandRosterMembership membership =
                                found(membershipRead);
                        if (!validMembership(request, membership)) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return quote(request, profile);
                    });
        });
    }

    private CompletionStage<PaidCommandRevivalQuote> quote(
            PaidCommandRevivalQuoteRequest request,
            CompanionProfileReadModel profile
    ) {
        var policy = policies.resolve(profile.identity().roleId());
        CompanionSnapshot source = PaidRevivalDormantSource.exact(profile);
        TameworkDormantSnapshotFactsReader.ReadResult read =
                source == null ? null : facts.read(source);
        if (policy == null || read == null || !read.successful()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletionStage<ReplacementFeatureLiveEvidenceSource
                .PaidInventoryEvidence> inventoryStage =
                live.freezePaidInventory(
                        new ReplacementFeatureLiveEvidenceSource
                                .PaidInventoryIntent(
                                request.ownerUuid(),
                                profile,
                                policy.revivalCost(),
                                null,
                                true
                        )
                );
        if (inventoryStage == null) {
            return CompletableFuture.completedFuture(null);
        }
        return inventoryStage.thenApply(inventory -> build(
                request, policy, read.facts(), inventory
        ));
    }

    @Nullable
    private PaidCommandRevivalQuote build(
            PaidCommandRevivalQuoteRequest request,
            ReplacementFeaturePolicySource.RolePolicySnapshot policy,
            TameworkDormantSnapshotFactsReader.Facts facts,
            @Nullable ReplacementFeatureLiveEvidenceSource
                    .PaidInventoryEvidence inventory
    ) {
        if (inventory == null
                || !inventory.ownerUuid().equals(request.ownerUuid())) {
            return null;
        }
        Long availableAt = PaidRevivalDormantSource.availableAt(
                facts, policy.revivalCooldownMs()
        );
        long remaining = availableAt != null
                && inventory.observedAtMs() < availableAt
                ? remainingUntil(
                        inventory.observedAtMs(), availableAt
                )
                : 0L;
        List<PaidCommandRevivalCostQuoteView> costs =
                costs(policy.revivalCost(), inventory.costs());
        if (costs == null) {
            return null;
        }
        PaidCommandRevivalQuote.Status status =
                !policy.paidRevivalEnabled()
                        ? PaidCommandRevivalQuote.Status.DISABLED
                        : remaining != 0L
                        ? PaidCommandRevivalQuote.Status.COOLDOWN
                        : costs.stream().allMatch(
                                PaidCommandRevivalCostQuoteView::satisfied
                        )
                        ? PaidCommandRevivalQuote.Status.READY
                        : PaidCommandRevivalQuote.Status.INSUFFICIENT_COST;
        return new PaidCommandRevivalQuote(
                request.ownerUuid(),
                request.profileId(),
                request.commandFamilyId(),
                status,
                remaining,
                costs,
                policy.configRevision(),
                status == PaidCommandRevivalQuote.Status.INSUFFICIENT_COST
                        ? policy.insufficientCostMessage()
                        : null,
                null
        );
    }

    private long remainingUntil(long nowMs, long availableAtMs) {
        try {
            return Math.subtractExact(availableAtMs, nowMs);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    @Nullable
    private List<PaidCommandRevivalCostQuoteView> costs(
            List<RevivalCostItem> exact,
            List<ReplacementFeatureLiveEvidenceSource
                    .PaidCostAvailability> available
    ) {
        ArrayList<PaidCommandRevivalCostQuoteView> result =
                new ArrayList<>();
        for (RevivalCostItem cost : exact) {
            var matches = available.stream()
                    .filter(value -> value.itemId().equals(cost.itemId()))
                    .toList();
            if (matches.size() != 1) {
                return null;
            }
            var value = matches.get(0);
            result.add(new PaidCommandRevivalCostQuoteView(
                    cost.itemId(),
                    cost.quantity(),
                    value.ownedQuantity(),
                    value.localizedName(),
                    value.iconAssetId()
            ));
        }
        return List.copyOf(result);
    }

    private boolean validProfile(
            PaidCommandRevivalQuoteRequest request,
            @Nullable CompanionProfileReadModel profile
    ) {
        return profile != null
                && profile.identity().roleId() != null
                && PaidRevivalDormantSource.supports(
                profile.lifecycle().state()
        )
                && profile.lifecycle().ownerId() != null
                && profile.lifecycle().ownerId().value().equals(
                request.ownerUuid()
        )
                && !profile.lifecycle().quarantined()
                && profile.lifecycle().activeOperationId() == null;
    }

    private boolean validMembership(
            PaidCommandRevivalQuoteRequest request,
            @Nullable CommandRosterMembership membership
    ) {
        return membership != null
                && membership.familyKey().equals(new CommandFamilyKey(
                new OwnerId(request.ownerUuid()),
                request.commandFamilyId()
        ));
    }

    @Nullable
    private <T> T found(PersistenceReadResult<T> read) {
        return read instanceof PersistenceReadResult.Found<T> found
                ? found.value()
                : null;
    }
}
