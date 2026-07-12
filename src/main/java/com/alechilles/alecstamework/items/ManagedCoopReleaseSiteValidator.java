package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseSite;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseSitePolicy;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Revalidates a queued release against current physical and durable authority evidence. */
final class ManagedCoopReleaseSiteValidator {
    record Validation(boolean allowed,
                      int currentRotationIndex,
                      @Nullable String detail) {
        @Nonnull
        static Validation allowed(int rotation) {
            return new Validation(true, rotation, null);
        }

        @Nonnull
        static Validation blocked(@Nonnull String detail) {
            return new Validation(false, 0, detail);
        }
    }

    @Nullable
    private final ManagedCoopResidentIndex residents;
    private final BooleanSupplier compositeTrust;

    /** Exact managed blocks can be checked without persistence; removed sites fail closed. */
    ManagedCoopReleaseSiteValidator() {
        this(null, () -> false);
    }

    ManagedCoopReleaseSiteValidator(
            @Nonnull ManagedCoopResidentIndex residents,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes) {
        this(residents, compositeIndexes::isTrusted);
    }

    ManagedCoopReleaseSiteValidator(
            @Nullable ManagedCoopResidentIndex residents,
            @Nonnull BooleanSupplier compositeTrust) {
        this.residents = residents;
        this.compositeTrust = Objects.requireNonNull(compositeTrust, "compositeTrust");
    }

    @Nonnull
    Validation validate(@Nonnull ReleaseSite site,
                        @Nullable ManagedCoopRemovalEvidence.Result physical) {
        Objects.requireNonNull(site, "site");
        if (physical == null) {
            return Validation.blocked("managed_coop_release_site_evidence_missing");
        }
        if (physical.exactManagedCoop()) {
            if (site.policy() == ReleaseSitePolicy.EXACT_MANAGED_COOP) {
                return Validation.allowed(physical.currentRotationIndex());
            }
            return disabledAuthorityIsCurrent(site)
                    ? Validation.allowed(physical.currentRotationIndex())
                    : Validation.blocked("managed_coop_release_disabled_authority_not_current");
        }
        if (!physical.confirmedRemoved()) {
            return Validation.blocked("managed_coop_release_site_not_proven:"
                    + physical.status().name().toLowerCase());
        }
        if (site.policy() != ReleaseSitePolicy.EXACT_MANAGED_OR_DISABLED_REMOVAL) {
            return Validation.blocked("managed_coop_release_removed_site_not_authorized");
        }
        return disabledAuthorityIsCurrent(site)
                ? Validation.allowed(site.blockRotationIndex())
                : Validation.blocked("managed_coop_release_disabled_authority_not_current");
    }

    private boolean disabledAuthorityIsCurrent(ReleaseSite site) {
        if (residents == null || !trusted()) {
            return false;
        }
        ManagedCoopResidentIndex.Snapshot snapshot = residents.snapshot();
        AuthorityRecord authority = snapshot.authority(
                site.authorityKey(), site.expectedCoopId());
        return authority != null && authority.active()
                && authority.state() == AuthorityState.DISABLED
                && authority.authorityId().equals(site.authorityKey().authorityId())
                && trusted()
                && residents.snapshot().revision() == snapshot.revision();
    }

    private boolean trusted() {
        try {
            return compositeTrust.getAsBoolean() && residents != null && residents.isTrusted();
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
