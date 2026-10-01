package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
import com.alechilles.alecstamework.companion.index.LocationKind;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * A companion's coop production watermark kept on its record, so production carries across
 * stays as in 4.x: the slot entry holds the working copy, the record extension
 * {@value #KEY} ({@code {"producedUntilMs": N}}, on the resident's active-time clock) survives the
 * morning release, and the next intake reads it back into the new entry. Release keeps
 * extensions; only a release to the wild clears them. Unowned residents have no record and start
 * each stay without one.
 */
public final class CoopProduction {
    /** Extension key under Tamework's namespace. */
    public static final String KEY = "Alechilles:Tamework/coop-production";
    private static final String FIELD = "producedUntilMs";

    private CoopProduction() {
    }

    /** The record's saved watermark, 0 when it has none or it is unreadable. */
    public static long producedUntil(@Nullable CompanionRecord record) {
        ExtensionEntry entry = record == null ? null : record.extensions().get(KEY);
        if (entry == null) {
            return 0L;
        }
        try {
            BsonValue value = BsonDocument.parse(entry.json()).get(FIELD);
            return value != null && value.isNumber() ? value.asNumber().longValue() : 0L;
        } catch (RuntimeException unreadable) {
            return 0L;
        }
    }

    /**
     * Saves {@code producedUntilMs} on the record while it is still the coop resident at
     * {@code generation}. Once the record moved on (a release committed LIVE, say) nothing is
     * written, so this never races a restore's revision check. Returns whether it was written.
     */
    public static boolean save(@Nonnull CompanionIndex index, @Nonnull UUID profileId, long generation,
                               long producedUntilMs) {
        return index.atomically(() -> {
            CompanionRecord current = index.get(profileId);
            if (current == null || current.location().kind() != LocationKind.COOP
                    || current.generation() != generation || producedUntil(current) == producedUntilMs) {
                return false;
            }
            ExtensionEntry previous = current.extensions().get(KEY);
            ExtensionEntry next = new ExtensionEntry(previous == null ? 1L : previous.revision() + 1L,
                    "{\"" + FIELD + "\":" + producedUntilMs + "}");
            return index.update(profileId, current.revision(), b -> b.extension(KEY, next)).applied();
        });
    }
}
