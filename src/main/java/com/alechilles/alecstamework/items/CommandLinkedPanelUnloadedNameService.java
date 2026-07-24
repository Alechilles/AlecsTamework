package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;

/** Resolves ordinary unloaded panel names from the newest last-live companion snapshot. */
final class CommandLinkedPanelUnloadedNameService {
    private final CommandNpcNameResolver nameResolver;
    private final NameSnapshotLookup snapshotLookup;
    private final ProfileNameLookup profileLookup;

    CommandLinkedPanelUnloadedNameService(
            CommandNpcNameResolver nameResolver,
            @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
            @Nullable CommandPersistenceView persistenceView
    ) {
        this(
                nameResolver,
                npcUuid -> toNameSnapshot(stateSnapshotService, npcUuid),
                record -> toProfileNameSnapshot(persistenceView, record)
        );
    }

    CommandLinkedPanelUnloadedNameService(
            CommandNpcNameResolver nameResolver,
            NameSnapshotLookup snapshotLookup
    ) {
        this(nameResolver, snapshotLookup, ignored -> null);
    }

    CommandLinkedPanelUnloadedNameService(
            CommandNpcNameResolver nameResolver,
            NameSnapshotLookup snapshotLookup,
            ProfileNameLookup profileLookup
    ) {
        this.nameResolver = Objects.requireNonNull(nameResolver, "nameResolver");
        this.snapshotLookup = Objects.requireNonNull(snapshotLookup, "snapshotLookup");
        this.profileLookup = Objects.requireNonNull(profileLookup, "profileLookup");
    }

    @Nullable
    String resolve(LinkedNpcRecord record) {
        if (record == null || record.npcUuid == null) {
            return null;
        }
        NameSnapshot snapshot = snapshotLookup.find(record.npcUuid);
        if (snapshot == null) {
            snapshot = resolveProfileName(record);
        }
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
    private NameSnapshot resolveProfileName(LinkedNpcRecord record) {
        return profileLookup.find(record);
    }

    @Nullable
    private static NameSnapshot toNameSnapshot(
            @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
            UUID npcUuid
    ) {
        CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot = stateSnapshotService != null
                ? stateSnapshotService.getSnapshot(npcUuid)
                : null;
        return snapshot == null
                ? null
                : new NameSnapshot(snapshot.customName(), snapshot.displayName(), snapshot.roleId());
    }

    @Nullable
    private static NameSnapshot toProfileNameSnapshot(
            @Nullable CommandPersistenceView persistenceView,
            LinkedNpcRecord record
    ) {
        if (persistenceView == null || record == null) {
            return null;
        }
        return persistenceView.find(record)
                .map(profile -> new NameSnapshot(
                        profile.customName(),
                        profile.displayName(),
                        profile.roleId()
                ))
                .orElse(null);
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

    @FunctionalInterface
    interface ProfileNameLookup {
        @Nullable
        NameSnapshot find(LinkedNpcRecord record);
    }

    record NameSnapshot(@Nullable String customName,
                        @Nullable String displayName,
                        @Nullable String roleId) {
    }
}
