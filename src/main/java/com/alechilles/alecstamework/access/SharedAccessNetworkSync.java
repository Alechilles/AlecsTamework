package com.alechilles.alecstamework.access;

import com.alechilles.alecstamework.api.NetworkSettingsDocument;
import com.alechilles.alecstamework.api.NetworkSettingsStore;
import com.alechilles.alecstamework.settings.NetworkSettingsSync;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps shared access grants the same on every server of a network. The grants of all owners
 * are one document, under {@link #DOCUMENT_KEY}, in the store the network registered for
 * settings. Without a store this class does nothing and grants stay in this server's file.
 *
 * <p><b>Merge.</b> The unit is one owner. An owner whose grants changed on this server is
 * <i>pending</i> until a save to the network includes that change; a pending owner keeps this
 * server's grants, every other owner takes the network's, including losing them when the
 * network has none. Two servers that change the same owner at the same moment: the later save
 * wins for that owner. Other owners are never lost, because a refused save reads the newer
 * document and tries again on top of it.</p>
 *
 * <p><b>First contact.</b> A server that never followed a network's grants (its file has no
 * network revision) adds all its owners to the network instead of losing them. A server that
 * did takes the network's grants, so an owner the network removed while it was down stays
 * removed. A store with no document deletes nothing: this server offers what it has.</p>
 *
 * <p><b>Thread.</b> All store calls run on the {@link NetworkSettingsSync} thread, through the
 * task it runs for this document. Pending owners are marked while the service's change lock is
 * held, in the same step as the change, so a document applied at that moment cannot overwrite
 * it. Pending owners are also saved in this server's grants file, by the service's save thread,
 * so a change not yet saved to the network survives a restart. Best effort: a crash before that
 * file save loses the change on a server that followed the network before, because at the next
 * contact the network's value replaces it.</p>
 *
 * <p><b>Lifecycle.</b> No thread and nothing to close. The task stops with the
 * {@link NetworkSettingsSync} it was given to; a later instance replaces this one's hook.</p>
 */
public final class SharedAccessNetworkSync {
    /** The key of the grants document in the network's {@link NetworkSettingsStore}. */
    public static final String DOCUMENT_KEY = "shared-access";

    /** Saves tried in one run when other servers keep saving first. The next check tries again. */
    private static final int MAX_SAVE_ATTEMPTS = 3;

    private final SharedAccessService service;
    private final NetworkSettingsSync sync;
    /**
     * Owners changed here and not yet saved to the network, each with a count of its changes. A
     * save clears an owner only if the count is still the one it read, so a change made during
     * the save stays pending.
     */
    private final Map<UUID, Long> pending = new ConcurrentHashMap<>();

    // Only the network settings thread uses the fields below.
    /** The grants in the last network document read. */
    private Map<UUID, OwnerGrants> network = Map.of();
    /** That document's revision; 0 when the store had none. */
    private long knownRevision;
    /** Whether a network document was applied since this object started. */
    private boolean applied;

    public SharedAccessNetworkSync(@Nonnull SharedAccessService service, @Nonnull NetworkSettingsSync sync) {
        this.service = Objects.requireNonNull(service, "service");
        this.sync = Objects.requireNonNull(sync, "sync");
    }

    /** Starts following the network's grants. Call once, after the service loaded its file. */
    public void start() {
        for (UUID ownerId : service.savedNetworkPending()) {
            pending.merge(ownerId, 1L, Long::sum);
        }
        service.saveNetworkPending(pending.keySet());
        service.setLocalChangeHook(this::onLocalChange);
        sync.follow(DOCUMENT_KEY, this::syncWithNetwork);
    }

    /** Called by the service under its change lock, on the changing thread. Does no I/O. */
    private void onLocalChange(@Nonnull UUID ownerId) {
        // A server that never followed a network has nothing to push: at first contact it offers
        // every owner it stores. Tracking changes before then would only replay old removals.
        if (!sync.active() && service.networkRevision() == 0L) {
            return;
        }
        pending.merge(ownerId, 1L, Long::sum);
        sync.notifyChanged(DOCUMENT_KEY);
    }

    /** Reads the network's grants, then saves this server's pending owners on top of them. */
    private void syncWithNetwork(@Nonnull NetworkSettingsStore store) throws Exception {
        try {
            saveToNetwork(store);
        } finally {
            // Keeps the file's pending owners in step with what this run added or cleared.
            service.saveNetworkPending(pending.keySet());
        }
    }

    private void saveToNetwork(@Nonnull NetworkSettingsStore store) throws Exception {
        apply(store.load(DOCUMENT_KEY));
        for (int attempt = 0; attempt < MAX_SAVE_ATTEMPTS && !pending.isEmpty(); attempt++) {
            Map<UUID, Long> included = Map.copyOf(pending);
            Map<UUID, OwnerGrants> merged = new LinkedHashMap<>(network);
            for (UUID ownerId : included.keySet()) {
                OwnerGrants local = service.grants(ownerId);
                if (local.isEmpty()) {
                    merged.remove(ownerId);
                } else {
                    merged.put(ownerId, local);
                }
            }
            if (merged.equals(network)) {
                // The network already has these owners' grants as they are here.
                included.forEach(pending::remove);
                continue;
            }
            if (store.save(DOCUMENT_KEY, SharedAccessFileStore.toJson(merged), knownRevision)) {
                included.forEach(pending::remove);
            }
            // After a save: learn the new revision. After a refusal: the newer document to retry on.
            apply(store.load(DOCUMENT_KEY));
        }
    }

    private void apply(@Nullable NetworkSettingsDocument document) {
        if (document == null) {
            if (applied) {
                // A store that briefly returns nothing for a document it had. Offering every
                // owner now would let this server's possibly stale copy win the next save.
                return;
            }
            // Nothing saved yet, or the store was emptied. Deleting every grant here would be the
            // wrong reading of that, so this server offers all it has instead.
            network = Map.of();
            knownRevision = 0L;
            markStoredOwnersPending();
            return;
        }
        if (applied && document.revision() == knownRevision) {
            return;
        }
        Map<UUID, OwnerGrants> loaded = SharedAccessFileStore.fromJson(document.json());
        if (!applied && service.networkRevision() == 0L) {
            markStoredOwnersPending();
        }
        applied = true;
        network = loaded;
        knownRevision = document.revision();
        service.applyNetwork(loaded, pending.keySet(), document.revision());
    }

    private void markStoredOwnersPending() {
        for (UUID ownerId : service.storedOwners()) {
            pending.merge(ownerId, 1L, Long::sum);
        }
    }
}
