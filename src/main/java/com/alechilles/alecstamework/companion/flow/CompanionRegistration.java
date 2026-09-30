package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import javax.annotation.Nonnull;

/**
 * Registers a newly tamed or adopted body under the index lock. Owner and tamed components can
 * both change in one tick, before the first stamp is applied, so the check that no record already
 * names this NPC UUID and the insert happen in one locked step (Review Focus 1).
 */
public final class CompanionRegistration {
    private CompanionRegistration() {
    }

    /** Returns false, changing nothing, when a record already names this body's NPC UUID. */
    public static <R> boolean register(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded,
                                       @Nonnull CompanionRecord record, @Nonnull R ref) {
        return index.atomically(() -> {
            if (record.currentNpcUuid() != null && index.byNpcUuid(record.currentNpcUuid()) != null) {
                return false;
            }
            if (!index.insert(record).applied()) {
                return false;
            }
            loaded.put(record.profileId(), ref);
            return true;
        });
    }
}
