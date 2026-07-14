package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.LostRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Indexes finalized lost-recovery sources that must yield to their current projections.
 *
 * <p>The durable repository seeds the index after restart. Runtime finalization records mappings
 * immediately so a stale source cannot reappear in the gap before another process start.</p>
 */
final class CommandRecoveredSourceSuppressionIndex {
    private final ConcurrentHashMap<UUID, UUID> replacementsBySource = new ConcurrentHashMap<>();

    CommandRecoveredSourceSuppressionIndex(@Nullable LostRepository repository) {
        this(repository != null ? repository.loadRecoveredSourceReplacements() : Map.of());
    }

    CommandRecoveredSourceSuppressionIndex(Map<UUID, UUID> persistedMappings) {
        if (persistedMappings != null) {
            replacementsBySource.putAll(persistedMappings);
        }
    }

    void record(UUID sourceNpcUuid, UUID replacementNpcUuid) {
        if (sourceNpcUuid == null || replacementNpcUuid == null
                || sourceNpcUuid.equals(replacementNpcUuid)) {
            return;
        }
        replacementsBySource.put(sourceNpcUuid, replacementNpcUuid);
    }

    @Nullable
    UUID replacementFor(@Nullable UUID sourceNpcUuid) {
        return sourceNpcUuid != null ? replacementsBySource.get(sourceNpcUuid) : null;
    }
}
