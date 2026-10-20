package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Registers a newly tamed or adopted body under the index lock. Owner and tamed components can
 * both change in one tick, before the first stamp is applied, so the check that no record already
 * names this NPC UUID, the population caps and the insert happen in one locked step (Review Focus 1,
 * spec 8.10).
 */
public final class CompanionRegistration {
    private CompanionRegistration() {
    }

    /**
     * {@code refusal} is set only when the admission refused the record; a duplicate has neither
     * flag. {@code messageKey} is the translation key of a refusal that came with one
     * ({@link #registerAdmitted}), else null.
     */
    public record Outcome(boolean registered, @Nullable CompanionAdmission.Refusal refusal,
                          @Nullable String messageKey) {
        static final Outcome REGISTERED = new Outcome(true, null, null);
        static final Outcome NOT_REGISTERED = new Outcome(false, null, null);

        public Outcome(boolean registered, @Nullable CompanionAdmission.Refusal refusal) {
            this(registered, refusal, null);
        }
    }

    /**
     * Registers {@code record} unless a record already names this body's NPC UUID or
     * {@code admission} refuses it; either way nothing changes. The duplicate check comes first, so
     * a body that already has a record is never counted against its own owner.
     */
    @Nonnull
    public static <R> Outcome register(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded,
                                       @Nonnull CompanionRecord record, @Nonnull R ref,
                                       @Nonnull Function<CompanionRecord, CompanionAdmission.Refusal> admission) {
        return registerAdmitted(index, loaded, record, ref, candidate -> {
            CompanionAdmission.Refusal refusal = admission.apply(candidate);
            return refusal == null ? null
                    : new CompanionAdmissionGate.Admission(CompanionAdmissionGate.Denial.of(refusal), candidate);
        });
    }

    /**
     * As {@link #register}, with the gate's synchronous check ({@link CompanionAdmissionGate#admit}):
     * the record that is inserted is the admitted one, which carries the claims an admission
     * provider allowed, and a refusal keeps its message key. A null answer admits {@code record}
     * as it is.
     */
    @Nonnull
    public static <R> Outcome registerAdmitted(
            @Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded, @Nonnull CompanionRecord record,
            @Nonnull R ref, @Nonnull Function<CompanionRecord, CompanionAdmissionGate.Admission> admission) {
        return index.atomically(() -> {
            if (record.currentNpcUuid() != null && index.byNpcUuid(record.currentNpcUuid()) != null) {
                return Outcome.NOT_REGISTERED;
            }
            CompanionAdmissionGate.Admission admitted = admission.apply(record);
            if (admitted != null && admitted.denial() != null) {
                return new Outcome(false, admitted.denial().refusal(), admitted.denial().messageKey());
            }
            if (!index.insert(admitted == null ? record : admitted.record()).applied()) {
                return Outcome.NOT_REGISTERED;
            }
            loaded.put(record.profileId(), ref);
            return Outcome.REGISTERED;
        });
    }
}
