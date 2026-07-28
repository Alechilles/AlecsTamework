package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import java.util.Optional;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves each mutation against the current immutable roster generation. */
public final class BondedCompanionPolicyResolver {
    private final BondedCompanionRosterRegistry registry;

    public BondedCompanionPolicyResolver(
            @Nonnull BondedCompanionRosterRegistry registry
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Resolves an exact roster and optionally fences its asset generation. */
    @Nonnull
    public Resolution resolve(String rosterId, long expectedRevision) {
        BondedCompanionRosterRegistry.Snapshot snapshot = registry.snapshot();
        Resolution fenced = revisionFence(snapshot, expectedRevision);
        if (fenced != null) {
            return fenced;
        }
        Optional<BondedCompanionRosterRegistry.RosterDefinition> definition =
                snapshot.resolveUnique(normalize(rosterId));
        if (definition.isPresent()) {
            return found(snapshot, definition.orElseThrow());
        }
        Status status = snapshot.containsRoster(rosterId)
                ? Status.AMBIGUOUS : Status.NOT_FOUND;
        return new Resolution(status, null, snapshot.revision());
    }

    /** Resolves one exact family and optionally fences its asset generation. */
    @Nonnull
    public Resolution resolve(
            String rosterId,
            String familyId,
            long expectedRevision
    ) {
        BondedCompanionRosterRegistry.Snapshot snapshot = registry.snapshot();
        Resolution fenced = revisionFence(snapshot, expectedRevision);
        if (fenced != null) {
            return fenced;
        }
        return resolution(
                snapshot,
                snapshot.resolve(normalize(rosterId), normalize(familyId))
        );
    }

    /** Selects an explicit family or the sole family allowing a role. */
    @Nonnull
    public Resolution resolveForRole(
            String rosterId,
            @Nullable String familyId,
            String roleId,
            long expectedRevision
    ) {
        BondedCompanionRosterRegistry.Snapshot snapshot = registry.snapshot();
        Resolution fenced = revisionFence(snapshot, expectedRevision);
        if (fenced != null) {
            return fenced;
        }
        if (familyId != null && !familyId.isBlank()) {
            Optional<BondedCompanionRosterRegistry.RosterDefinition> exact =
                    snapshot.resolve(
                            normalize(rosterId), normalize(familyId));
            if (exact.isEmpty()) {
                return new Resolution(
                        Status.NOT_FOUND, null, snapshot.revision());
            }
            BondedCompanionRosterRegistry.RosterDefinition definition =
                    exact.orElseThrow();
            return definition.allowedRoles().contains(normalize(roleId))
                    ? found(snapshot, definition)
                    : new Resolution(
                            Status.ROLE_NOT_ALLOWED, null, snapshot.revision());
        }
        BondedCompanionRosterRegistry.FamilyResolution selected =
                snapshot.resolveForRole(normalize(rosterId), normalize(roleId));
        return switch (selected.status()) {
            case FOUND -> found(snapshot, selected.definition());
            case NOT_FOUND -> new Resolution(
                    snapshot.containsRoster(rosterId)
                            ? Status.ROLE_NOT_ALLOWED : Status.NOT_FOUND,
                    null, snapshot.revision());
            case AMBIGUOUS -> new Resolution(
                    Status.AMBIGUOUS, null, snapshot.revision()
            );
        };
    }

    private static Resolution resolution(
            BondedCompanionRosterRegistry.Snapshot snapshot,
            Optional<BondedCompanionRosterRegistry.RosterDefinition> definition
    ) {
        return definition.isPresent()
                ? found(snapshot, definition.orElseThrow())
                : new Resolution(Status.NOT_FOUND, null, snapshot.revision());
    }

    private static Resolution found(
            BondedCompanionRosterRegistry.Snapshot snapshot,
            BondedCompanionRosterRegistry.RosterDefinition definition
    ) {
        return new Resolution(
                Status.FOUND,
                map(snapshot.revision(), definition),
                snapshot.revision()
        );
    }

    private static Resolution revisionFence(
            BondedCompanionRosterRegistry.Snapshot snapshot,
            long expectedRevision
    ) {
        return expectedRevision == snapshot.revision()
                ? null
                : new Resolution(
                        Status.REVISION_CONFLICT, null, snapshot.revision()
                );
    }

    private static BondedCompanionPolicy map(
            long revision,
            BondedCompanionRosterRegistry.RosterDefinition source
    ) {
        BondedCompanionRosterRegistry.RevivePrice price = source.revivePrice();
        BondedCompanionRosterRegistry.FeatureFlags flags = source.features();
        return new BondedCompanionPolicy(
                revision, source.rosterId(), source.familyId(),
                source.allowedRoles(), source.maximumOwned(),
                source.maximumActive(), source.sessionDurationSeconds(),
                source.summonCooldownSeconds(),
                price == null ? null : new BondedCompanionPolicy.RevivePrice(
                        price.costs()
                ),
                new BondedCompanionPolicy.FeatureFlags(
                        flags.capture(), flags.provision(), flags.summon(),
                        flags.dismiss(), flags.revive()
                )
        );
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }

    /** Stable lookup outcome independent from the asset implementation. */
    public enum Status {
        FOUND,
        NOT_FOUND,
        ROLE_NOT_ALLOWED,
        AMBIGUOUS,
        REVISION_CONFLICT
    }

    /** One current-generation lookup result. */
    public record Resolution(
            @Nonnull Status status,
            @Nullable BondedCompanionPolicy policy,
            long activeRevision
    ) {
        public Resolution {
            status = Objects.requireNonNull(status, "status");
            if ((status == Status.FOUND) != (policy != null)) {
                throw new IllegalArgumentException("invalid policy resolution");
            }
        }
    }
}
