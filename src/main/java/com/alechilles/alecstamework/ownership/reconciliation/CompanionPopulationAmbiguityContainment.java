package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureContext;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentReporter;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import com.alechilles.alecstamework.persistence.incidents.PersistenceTransactionOutcome;
import com.alechilles.alecstamework.persistence.sqlite.CompanionIdentityRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Converts bounded startup ambiguities into durable operation/profile quarantine fences.
 *
 * <p>The existing population journal remains nonterminal and conservatively counted. Reconciliation
 * may publish healthy unrelated owner scopes only after every corresponding v7 quarantine is
 * durable.</p>
 */
public final class CompanionPopulationAmbiguityContainment {
    @Nullable
    private final PersistenceIncidentReporter incidents;
    @Nullable
    private final PersistenceScopeFactory scopes;
    @Nullable
    private final CompanionIdentityRepository identities;
    @Nullable
    private final PersistenceQuarantineRegistry quarantines;

    private CompanionPopulationAmbiguityContainment() {
        this.incidents = null;
        this.scopes = null;
        this.identities = null;
        this.quarantines = null;
    }

    public CompanionPopulationAmbiguityContainment(
            @Nonnull PersistenceIncidentReporter incidents,
            @Nonnull PersistenceScopeFactory scopes
    ) {
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.identities = null;
        this.quarantines = null;
    }

    public CompanionPopulationAmbiguityContainment(
            @Nonnull PersistenceIncidentReporter incidents,
            @Nonnull PersistenceScopeFactory scopes,
            @Nonnull CompanionIdentityRepository identities,
            @Nonnull PersistenceQuarantineRegistry quarantines
    ) {
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.quarantines = Objects.requireNonNull(quarantines, "quarantines");
    }

    @Nonnull
    static CompanionPopulationAmbiguityContainment disabled() {
        return new CompanionPopulationAmbiguityContainment();
    }

    boolean enabled() {
        return incidents != null && scopes != null;
    }

    boolean evidenceEnabled() {
        return enabled() && identities != null && quarantines != null;
    }

    /** Opens exact durable fences and reports whether every fence commit succeeded. */
    @Nonnull
    public CompletableFuture<Boolean> containAsync(
            @Nonnull List<CompanionPopulationOperationRecoveryService.AmbiguousOperation> ambiguous
    ) {
        Objects.requireNonNull(ambiguous, "ambiguous");
        if (!enabled()) {
            return CompletableFuture.completedFuture(false);
        }
        if (ambiguous.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        PersistenceIncidentReporter reporter = Objects.requireNonNull(incidents);
        PersistenceScopeFactory scopeFactory = Objects.requireNonNull(scopes);
        List<CompletableFuture<Boolean>> durable = new ArrayList<>();
        try {
            for (var operation : ambiguous) {
                List<PersistenceScope> exactScopes = List.of(
                        scopeFactory.operation(operation.operationId()),
                        scopeFactory.profile(operation.profileId())
                );
                PersistenceFailureContext context = new PersistenceFailureContext(
                        normalize(operation.reason()),
                        PersistenceDomain.RECONCILIATION,
                        PersistenceOperationPhase.RECOVERY,
                        PersistenceTransactionOutcome.NOT_STARTED,
                        exactScopes,
                        true,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        operation.operationId(),
                        null
                );
                durable.add(reporter.report(context).durableCompletion());
            }
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.allOf(durable.toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> failure == null
                        && durable.stream().allMatch(future -> Boolean.TRUE.equals(future.join())));
    }

    /** Fences every uniquely identified conflict profile before healthy evidence is repaired. */
    @Nonnull
    public CompletableFuture<EvidenceContainmentResult> containEvidenceAsync(
            @Nonnull List<CompanionPopulationEvidenceSet.Conflict> conflicts
    ) {
        Objects.requireNonNull(conflicts, "conflicts");
        if (!evidenceEnabled()) {
            return CompletableFuture.completedFuture(EvidenceContainmentResult.incomplete());
        }
        if (conflicts.isEmpty()) {
            return CompletableFuture.completedFuture(EvidenceContainmentResult.complete(Set.of(), 0));
        }
        return CompletableFuture.supplyAsync(this::loadIdentities).thenCompose(snapshot -> {
            List<CompanionIdentityRepository.AliasRecord> aliases = snapshot.aliases();
            Map<String, List<CompanionPopulationEvidenceSet.Conflict>> byProfile =
                    conflictsByProfile(conflicts, aliases);
            if (byProfile == null
                    || !snapshot.populationProfileIds().containsAll(byProfile.keySet())) {
                return CompletableFuture.completedFuture(EvidenceContainmentResult.incomplete());
            }
            Set<UUID> containedAliases = aliases.stream()
                    .filter(alias -> byProfile.containsKey(alias.profileId()))
                    .map(CompanionIdentityRepository.AliasRecord::npcUuid)
                    .collect(Collectors.toUnmodifiableSet());
            return fenceEvidenceProfiles(byProfile).thenApply(durable -> durable
                    ? EvidenceContainmentResult.complete(
                            containedAliases, byProfile.size())
                    : EvidenceContainmentResult.incomplete());
        }).exceptionally(ignored -> EvidenceContainmentResult.incomplete());
    }

    @Nonnull
    private CompanionIdentityRepository.PopulationIdentitySnapshot loadIdentities() {
        try {
            return Objects.requireNonNull(identities).loadPopulationIdentitySnapshot();
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    @Nullable
    private static Map<String, List<CompanionPopulationEvidenceSet.Conflict>> conflictsByProfile(
            @Nonnull List<CompanionPopulationEvidenceSet.Conflict> conflicts,
            @Nonnull List<CompanionIdentityRepository.AliasRecord> aliases
    ) {
        Map<UUID, Set<String>> profilesByUuid = new HashMap<>();
        for (var alias : aliases) {
            profilesByUuid.computeIfAbsent(alias.npcUuid(), ignored -> new LinkedHashSet<>())
                    .add(alias.profileId());
        }
        Map<String, List<CompanionPopulationEvidenceSet.Conflict>> grouped = new LinkedHashMap<>();
        for (var conflict : conflicts) {
            Set<String> profiles = profilesByUuid.getOrDefault(conflict.npcUuid(), Set.of());
            if (profiles.size() != 1) {
                return null;
            }
            grouped.computeIfAbsent(profiles.iterator().next(), ignored -> new ArrayList<>())
                    .add(conflict);
        }
        return grouped;
    }

    @Nonnull
    private CompletableFuture<Boolean> fenceEvidenceProfiles(
            @Nonnull Map<String, List<CompanionPopulationEvidenceSet.Conflict>> byProfile
    ) {
        PersistenceIncidentReporter reporter = Objects.requireNonNull(incidents);
        PersistenceScopeFactory scopeFactory = Objects.requireNonNull(scopes);
        PersistenceQuarantineRegistry registry = Objects.requireNonNull(quarantines);
        List<CompletableFuture<Boolean>> durable = new ArrayList<>();
        try {
            for (var entry : byProfile.entrySet()) {
                var conflict = entry.getValue().getFirst();
                String reason = normalizeEvidence(conflict.reason());
                var existing = registry.find(PersistenceScopeType.PROFILE, entry.getKey());
                if (existing.isPresent() && !existing.get().reasonCode().equals(reason)) {
                    continue;
                }
                PersistenceFailureContext context = new PersistenceFailureContext(
                        reason,
                        PersistenceDomain.RECONCILIATION,
                        PersistenceOperationPhase.RECOVERY,
                        PersistenceTransactionOutcome.NOT_STARTED,
                        List.of(scopeFactory.profile(entry.getKey())),
                        true, true, false, false, false, true, false, false, null, null
                );
                durable.add(reporter.report(context).durableCompletion());
            }
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.allOf(durable.toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> failure == null
                        && durable.stream().allMatch(future -> Boolean.TRUE.equals(future.join())));
    }

    @Nonnull
    private static String normalize(@Nonnull String reason) {
        String normalized = reason.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? "reconciliation_operation_ambiguous" : normalized;
    }

    @Nonnull
    private static String normalizeEvidence(@Nonnull String reason) {
        String normalized = normalize(reason);
        return switch (normalized) {
            case "conflicting_dormant_lifecycle_evidence",
                    "conflicting_owner_evidence",
                    "conflicting_physical_death_evidence",
                    "duplicate_physical_identity" ->
                    "reconciliation_evidence_conflict_" + normalized;
            default -> throw new IllegalArgumentException(
                    "Unclassified reconciliation evidence conflict: " + normalized
            );
        };
    }

    public record EvidenceContainmentResult(boolean complete,
                                            @Nonnull Set<UUID> containedNpcUuids,
                                            int containedProfileCount) {
        public EvidenceContainmentResult {
            containedNpcUuids = Set.copyOf(containedNpcUuids);
        }

        @Nonnull
        private static EvidenceContainmentResult complete(Set<UUID> uuids, int profileCount) {
            return new EvidenceContainmentResult(true, uuids, profileCount);
        }

        @Nonnull
        private static EvidenceContainmentResult incomplete() {
            return new EvidenceContainmentResult(false, Set.of(), 0);
        }
    }
}
