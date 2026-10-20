package com.alechilles.alecstamework.companion.index;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The rules of a companion record's extension entries, shared by profile data and bonded
 * extension data: the key layout, the reserved namespaces and the compare-and-set.
 *
 * <p>A stored entry's revision is at least 1; 0 means "no value". Each API maps its own public
 * revision onto that.</p>
 */
public final class ExtensionEntries {
    /** Tamework's own extension namespace. Public callers may not read or write it. */
    public static final String TAMEWORK_NAMESPACE = "tamework";
    private static final String TAMEWORK_PLUGIN_NAMESPACE = "Alechilles:Tamework";

    public enum Status { WRITTEN, NOT_FOUND, REVISION_MISMATCH, FLUSH_FAILED }

    /**
     * @param record the record after the write for WRITTEN and FLUSH_FAILED, the unchanged
     *               record for REVISION_MISMATCH, null for NOT_FOUND
     * @param entry  the entry now stored; set only for WRITTEN
     */
    public record Outcome(@Nonnull Status status, @Nullable CompanionRecord record, @Nullable ExtensionEntry entry) {
    }

    private ExtensionEntries() {
    }

    /** The key of an extension entry: {@code namespace + "/" + key}, both trimmed. Keys may contain "/". */
    @Nonnull
    public static String key(@Nonnull String namespace, @Nonnull String key) {
        return namespace.trim() + "/" + key.trim();
    }

    /**
     * Whether a public caller may use {@code namespace}. Tamework's own namespaces are reserved,
     * and a namespace with "/" could not be told apart from a shorter namespace with a longer key.
     */
    public static boolean publicNamespace(@Nullable String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return false;
        }
        String normalized = namespace.trim();
        return !normalized.contains("/") && !normalized.equalsIgnoreCase(TAMEWORK_NAMESPACE)
                && !normalized.equalsIgnoreCase(TAMEWORK_PLUGIN_NAMESPACE);
    }

    /** The stored revision of an entry: 0 when there is none, else at least 1. */
    public static long storedRevision(@Nullable ExtensionEntry entry) {
        return entry == null ? 0L : Math.max(1L, entry.revision());
    }

    /**
     * Writes {@code json} at stored revision {@code expectedStoredRevision + 1} when the entry's
     * stored revision is the expected one. The compare and the change are one step under the
     * index lock. The future completes only after the owner file is written; when that write
     * fails the change is undone, unless the value has changed again, and the result is
     * FLUSH_FAILED.
     *
     * <p>A request whose value and revision are already the current ones (the same request
     * again) is WRITTEN too, after the same wait for the owner file, and a failed write then
     * leaves the value to the first caller. Never blocks the caller.</p>
     *
     * @param flush  writes an owner's file to disk; {@code CompanionWriter::flushNow}
     * @param usable checked under the lock; a missing record or one it refuses is NOT_FOUND
     */
    @Nonnull
    public static CompletableFuture<Outcome> compareAndSet(
            @Nonnull CompanionIndex index, @Nonnull Function<UUID, CompletableFuture<Void>> flush,
            @Nonnull UUID profileId, @Nonnull String extensionKey, long expectedStoredRevision,
            @Nonnull String json, @Nonnull Predicate<CompanionRecord> usable) {
        Objects.requireNonNull(flush, "flush");
        Objects.requireNonNull(usable, "usable");
        ExtensionEntry wanted = new ExtensionEntry(expectedStoredRevision + 1L, json);
        boolean[] replay = new boolean[1];
        ExtensionEntry[] previous = new ExtensionEntry[1];
        Outcome locked = index.atomically(() -> {
            CompanionRecord record = index.get(profileId);
            if (record == null || !usable.test(record)) {
                return new Outcome(Status.NOT_FOUND, null, null);
            }
            ExtensionEntry current = record.extensions().get(extensionKey);
            if (wanted.equals(current)) {
                replay[0] = true;
                return new Outcome(Status.WRITTEN, record, wanted);
            }
            if (storedRevision(current) != expectedStoredRevision) {
                return new Outcome(Status.REVISION_MISMATCH, record, null);
            }
            CompanionIndex.Mutation applied =
                    index.update(profileId, record.revision(), b -> b.extension(extensionKey, wanted));
            if (!applied.applied()) {
                return new Outcome(Status.REVISION_MISMATCH, record, null);
            }
            previous[0] = current;
            return new Outcome(Status.WRITTEN, applied.after(), wanted);
        });
        if (locked.status() != Status.WRITTEN) {
            return CompletableFuture.completedFuture(locked);
        }
        CompletableFuture<Void> flushed;
        try {
            flushed = flush.apply(locked.record().ownerUuid());
        } catch (RuntimeException failure) {
            flushed = CompletableFuture.failedFuture(failure);
        }
        return flushed.handle((ignored, failure) -> {
            if (failure == null) {
                return locked;
            }
            if (!replay[0]) {
                undo(index, profileId, extensionKey, wanted, previous[0]);
            }
            return new Outcome(Status.FLUSH_FAILED, locked.record(), null);
        });
    }

    /** Puts {@code previous} back unless the value has changed again since {@code written}. */
    private static void undo(CompanionIndex index, UUID profileId, String extensionKey, ExtensionEntry written,
                             @Nullable ExtensionEntry previous) {
        index.atomically(() -> {
            CompanionRecord record = index.get(profileId);
            if (record != null && written.equals(record.extensions().get(extensionKey))) {
                index.update(profileId, record.revision(), b -> b.extension(extensionKey, previous));
            }
            return null;
        });
    }
}
