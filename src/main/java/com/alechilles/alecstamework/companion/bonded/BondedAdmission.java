package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Family caps of a bonded roster ({@code MaximumOwned}, {@code MaximumActive}; 0 = no limit),
 * checked under the index lock in the step that changes the record (plan 6 R15). Owned counts
 * every bonded record of the owner in the family that is not a RELEASED tombstone; active counts
 * the LIVE ones. As in {@code CompanionAdmission}, a change is refused only where it adds the
 * companion to a bucket it was not counted in and that bucket would then pass its limit, so an
 * owner already over a lowered limit can still store companions or see them die.
 *
 * <p>A record whose role resolves to no single family passes: it counts toward no family, and
 * the caller refuses such a provision, capture, summon or revive before it gets here.</p>
 */
public final class BondedAdmission {
    public enum Refusal { OWNED_CAPACITY, ACTIVE_CAPACITY }

    private BondedAdmission() {
    }

    /**
     * @param ownerRecords every record filed under {@code after}'s owner
     * @param before       the record before the change, or null when it is new
     */
    @Nullable
    public static Refusal check(@Nonnull Collection<CompanionRecord> ownerRecords, @Nullable CompanionRecord before,
                                @Nonnull CompanionRecord after, @Nonnull BondedRecords.Families families) {
        UUID owner = after.ownerUuid();
        BondedCompanionPolicy family = BondedRecords.policy(after, families);
        if (owner == null || family == null || !after.countsAsOwned()) {
            return null;
        }
        CompanionRecord prior = before != null && owner.equals(before.ownerUuid())
                && BondedRecords.inFamily(before, family, families) ? before : null;
        if (family.maximumOwned() > 0 && prior == null
                && 1 + count(ownerRecords, after, family, families, false) > family.maximumOwned()) {
            return Refusal.OWNED_CAPACITY;
        }
        if (family.maximumActive() > 0 && after.isDeployed() && !(prior != null && prior.isDeployed())
                && 1 + count(ownerRecords, after, family, families, true) > family.maximumActive()) {
            return Refusal.ACTIVE_CAPACITY;
        }
        return null;
    }

    /**
     * {@code base} followed by the family caps, for a flow's under-lock admission check. A family
     * refusal is reported as the built-in refusal of the same kind: owned as
     * {@link CompanionAdmission.Refusal#OWNED}, active as
     * {@link CompanionAdmission.Refusal#GROUP_DEPLOYED}.
     *
     * @param ownerRecords every record filed under an owner; {@code CompanionIndex::fileRecords}
     */
    @Nonnull
    public static CompanionAdmissionGate.Check withFamilyCaps(
            @Nonnull CompanionAdmissionGate.Check base,
            @Nonnull Function<UUID, ? extends Collection<CompanionRecord>> ownerRecords,
            @Nonnull BondedRecords.Families families) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(ownerRecords, "ownerRecords");
        Objects.requireNonNull(families, "families");
        return (before, after, provided) -> {
            CompanionAdmissionGate.Denial denied = base.deny(before, after, provided);
            if (denied != null || !after.bonded() || after.ownerUuid() == null) {
                return denied;
            }
            Refusal refusal = check(ownerRecords.apply(after.ownerUuid()), before, after, families);
            return refusal == null ? null : CompanionAdmissionGate.Denial.of(refusal == Refusal.OWNED_CAPACITY
                    ? CompanionAdmission.Refusal.OWNED : CompanionAdmission.Refusal.GROUP_DEPLOYED);
        };
    }

    private static int count(Collection<CompanionRecord> records, CompanionRecord self, BondedCompanionPolicy family,
                             BondedRecords.Families families, boolean deployedOnly) {
        int n = 0;
        for (CompanionRecord record : records) {
            if (!record.profileId().equals(self.profileId()) && (!deployedOnly || record.isDeployed())
                    && BondedRecords.inFamily(record, family, families)) {
                n++;
            }
        }
        return n;
    }
}
