package com.alechilles.alecstamework.persistence.incidents;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/** O(1) process-local view of durable active quarantine fences. */
public final class PersistenceQuarantineRegistry {
    private final ConcurrentHashMap<PersistenceScope.ScopeKey, PersistenceQuarantineRecord> active =
            new ConcurrentHashMap<>();

    /** Opens the denial immediately; callers persist it before reopening any broader gate. */
    @Nonnull
    public PersistenceQuarantineRecord openImmediate(@Nonnull PersistenceQuarantineRecord record) {
        if (!record.isActive()) throw new IllegalArgumentException("Only active quarantine records can be opened");
        return active.compute(record.scope().lookupKey(), (ignored, current) -> preferCurrent(current, record));
    }

    public void reload(@Nonnull Collection<PersistenceQuarantineRecord> records) {
        ConcurrentHashMap<PersistenceScope.ScopeKey, PersistenceQuarantineRecord> replacement =
                new ConcurrentHashMap<>();
        for (PersistenceQuarantineRecord record : records) {
            if (record.isActive()) replacement.merge(record.scope().lookupKey(), record,
                    PersistenceQuarantineRegistry::preferCurrent);
        }
        active.clear();
        active.putAll(replacement);
    }

    @Nonnull
    public Optional<PersistenceQuarantineRecord> find(@Nonnull PersistenceScope scope) {
        return Optional.ofNullable(active.get(scope.lookupKey()));
    }

    @Nonnull
    public Optional<PersistenceQuarantineRecord> find(@Nonnull PersistenceScopeType type,
                                                       @Nonnull String key) {
        return Optional.ofNullable(active.get(new PersistenceScope.ScopeKey(type, key)));
    }

    public boolean clearVerified(@Nonnull String quarantineId,
                                 long expectedGeneration,
                                 @Nonnull String expectedEvidenceHash) {
        for (var entry : active.entrySet()) {
            PersistenceQuarantineRecord record = entry.getValue();
            if (record.quarantineId().equals(quarantineId)
                    && record.generation() == expectedGeneration
                    && record.evidenceHash().equals(expectedEvidenceHash)) {
                return active.remove(entry.getKey(), record);
            }
        }
        return false;
    }

    @Nonnull
    public List<PersistenceQuarantineRecord> snapshot() {
        return List.copyOf(active.values());
    }

    public int size() {
        return active.size();
    }

    private static PersistenceQuarantineRecord preferCurrent(PersistenceQuarantineRecord current,
                                                             PersistenceQuarantineRecord candidate) {
        if (current == null) return candidate;
        if (candidate.generation() > current.generation()) return candidate;
        return candidate.updatedAtMs() > current.updatedAtMs() ? candidate : current;
    }
}
