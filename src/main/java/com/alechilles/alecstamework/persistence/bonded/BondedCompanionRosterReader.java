package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads one owner roster behind a bounded profile-generation fence.
 *
 * <p>Lease replacement is committed atomically with a profile revision
 * change. Reading the profile generation before and after the leases therefore
 * prevents callers from attaching a prior projection lease to a later ACTIVE
 * profile generation.</p>
 */
final class BondedCompanionRosterReader {
    private static final int MAX_ATTEMPTS = 3;

    private final BondedCompanionStore store;

    BondedCompanionRosterReader(BondedCompanionStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Returns one coherent roster read or fails closed after bounded churn. */
    Read read(UUID ownerUuid, String rosterId) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        String roster = requireText(rosterId, "rosterId");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            List<BondedCompanionRecord.Profile> before = List.copyOf(
                    store.listProfiles(ownerUuid, roster));
            List<BondedCompanionRecord.Lease> leases = List.copyOf(
                    store.findActiveLeases(ownerUuid, roster));
            List<BondedCompanionRecord.Profile> after = List.copyOf(
                    store.listProfiles(ownerUuid, roster));
            if (!generations(before).equals(generations(after))) {
                continue;
            }
            validate(ownerUuid, roster, after, leases);
            return new Read(after, leases);
        }
        throw new IllegalStateException("bonded-roster-read-unstable");
    }

    private Map<String, Long> generations(
            List<BondedCompanionRecord.Profile> profiles
    ) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (BondedCompanionRecord.Profile profile : profiles) {
            Long prior = result.putIfAbsent(
                    profile.profileId(), profile.revision());
            if (prior != null) {
                throw new IllegalStateException(
                        "duplicate-bonded-profile-in-roster-read");
            }
        }
        return result;
    }

    private void validate(
            UUID ownerUuid,
            String rosterId,
            List<BondedCompanionRecord.Profile> profiles,
            List<BondedCompanionRecord.Lease> leases
    ) {
        LinkedHashMap<String, BondedCompanionRecord.Profile> byProfile =
                new LinkedHashMap<>();
        for (BondedCompanionRecord.Profile profile : profiles) {
            if (!ownerUuid.equals(profile.ownerUuid())
                    || !rosterId.equals(profile.rosterId())
                    || byProfile.putIfAbsent(profile.profileId(), profile)
                    != null) {
                throw new IllegalStateException(
                        "invalid-bonded-profile-in-roster-read");
            }
        }
        LinkedHashMap<String, BondedCompanionRecord.Lease> byLease =
                new LinkedHashMap<>();
        for (BondedCompanionRecord.Lease lease : leases) {
            BondedCompanionRecord.Profile profile =
                    byProfile.get(lease.profileId());
            if (profile == null
                    || profile.state() != BondedCompanionState.ACTIVE
                    || byLease.putIfAbsent(lease.profileId(), lease) != null) {
                throw new IllegalStateException(
                        "invalid-bonded-lease-in-roster-read");
            }
        }
        for (BondedCompanionRecord.Profile profile : profiles) {
            if ((profile.state() == BondedCompanionState.ACTIVE)
                    != byLease.containsKey(profile.profileId())) {
                throw new IllegalStateException(
                        "incomplete-bonded-roster-read");
            }
        }
    }

    private String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    /** Immutable profile and active-lease view from one stable generation. */
    record Read(
            List<BondedCompanionRecord.Profile> profiles,
            List<BondedCompanionRecord.Lease> leases
    ) {
        Read {
            profiles = List.copyOf(profiles);
            leases = List.copyOf(leases);
        }
    }
}
