package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;

/** Resolves ordinary unloaded panel names from the newest last-live companion snapshot. */
final class CommandLinkedPanelUnloadedNameService {
    private final CommandNpcNameResolver nameResolver;
    private final NameSnapshotLookup snapshotLookup;

    CommandLinkedPanelUnloadedNameService(
            CommandNpcNameResolver nameResolver,
            @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService
    ) {
        this(nameResolver, npcUuid -> toNameSnapshot(stateSnapshotService, npcUuid));
    }

    CommandLinkedPanelUnloadedNameService(
            CommandNpcNameResolver nameResolver,
            NameSnapshotLookup snapshotLookup
    ) {
        this.nameResolver = Objects.requireNonNull(nameResolver, "nameResolver");
        this.snapshotLookup = Objects.requireNonNull(snapshotLookup, "snapshotLookup");
    }

    @Nullable
    String resolve(LinkedNpcRecord record) {
        if (record == null || record.npcUuid == null) {
            return null;
        }
        NameSnapshot snapshot = snapshotLookup.find(record.npcUuid);
        if (snapshot != null) {
            if (snapshot.customName() != null && !snapshot.customName().isBlank()) {
                return snapshot.customName();
            }
            String snapshotName = nameResolver.resolveSnapshotDisplayName(
                    snapshot.displayName(),
                    record.cachedNameKey,
                    firstNonBlank(snapshot.roleId(), record.cachedRoleId)
            );
            if (snapshotName != null && !snapshotName.isBlank()) {
                return snapshotName;
            }
        }
        return nameResolver.resolveCachedUnloadedDisplayName(record);
    }

    @Nullable
    private static NameSnapshot toNameSnapshot(
            @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
            UUID npcUuid
    ) {
        CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot = stateSnapshotService != null
                ? stateSnapshotService.getSnapshot(npcUuid)
                : null;
        return snapshot == null
                ? null
                : new NameSnapshot(snapshot.customName(), snapshot.displayName(), snapshot.roleId());
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    @FunctionalInterface
    interface NameSnapshotLookup {
        @Nullable
        NameSnapshot find(UUID npcUuid);
    }

    record NameSnapshot(@Nullable String customName,
                        @Nullable String displayName,
                        @Nullable String roleId) {
    }
}
