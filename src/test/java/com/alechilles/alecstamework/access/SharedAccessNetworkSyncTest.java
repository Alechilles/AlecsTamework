package com.alechilles.alecstamework.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.NetworkSettingsDocument;
import com.alechilles.alecstamework.api.NetworkSettingsStore;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.settings.NetworkSettingsFiles;
import com.alechilles.alecstamework.settings.NetworkSettingsSync;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How this server's grants merge with a network's, through the real service and network sync
 * and an in-memory network store that another server also writes to.
 */
class SharedAccessNetworkSyncTest {

    @TempDir
    Path tempDir;

    private final SharedAccessService service = SharedAccessService.get();
    private final UUID owner = UUID.randomUUID();
    private final UUID otherOwner = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();

    private FakeStore store;
    private NetworkSettingsSync settingsSync;

    @BeforeEach
    void setUp() {
        service.clearForTests();
        store = new FakeStore();
        settingsSync = newSettingsSync();
    }

    @AfterEach
    void tearDown() {
        settingsSync.close();
        service.clearForTests();
    }

    @Test
    void aSaveKeepsWhatAnotherServerSavedForADifferentOwnerAtTheSameMoment() throws Exception {
        join();
        OwnerGrants others = sharedWith(friend, AccessLevel.HANDLER);
        // The other server saves after this one read the document and before this one saves.
        store.beforeNextSave = () -> store.saveAsAnotherServer(Map.of(otherOwner, others));

        service.grantPlayer(owner, friend, "Friend", AccessLevel.CARETAKER);
        waitForSync();

        assertEquals(Map.of(owner, sharedWith(friend, AccessLevel.CARETAKER), otherOwner, others), store.owners());
        assertEquals(AccessLevel.CARETAKER, service.level(owner, friend, null));
        assertEquals(others, service.grants(otherOwner));
    }

    @Test
    void aServerThatNeverSyncedAddsItsOwnersToTheNetworksGrants() throws Exception {
        // Granted before this server knew a network.
        service.grantPlayer(owner, friend, "Friend", AccessLevel.HANDLER);
        OwnerGrants others = sharedWith(friend, AccessLevel.CARETAKER);
        store.saveAsAnotherServer(Map.of(otherOwner, others));

        join();

        assertEquals(Map.of(owner, sharedWith(friend, AccessLevel.HANDLER), otherOwner, others), store.owners());
        assertEquals(AccessLevel.HANDLER, service.level(owner, friend, null));
        assertEquals(others, service.grants(otherOwner));
    }

    @Test
    void aServerThatSyncedBeforeDropsAnOwnerTheNetworkDeleted() throws Exception {
        OwnerGrants kept = sharedWith(friend, AccessLevel.HANDLER);
        Map<UUID, OwnerGrants> both = Map.of(owner, kept, otherOwner, sharedWith(friend, AccessLevel.CARETAKER));
        // This server applied revision 1, then was down while the other owner stopped sharing.
        store.saveAsAnotherServer(both);
        assertTrue(SharedAccessFileStore.save(tempDir, both, 1L, Set.of()));
        service.start(tempDir);
        store.saveAsAnotherServer(Map.of(owner, kept));

        join();

        assertEquals(OwnerGrants.EMPTY, service.grants(otherOwner));
        assertEquals(kept, service.grants(owner));
        assertEquals(Map.of(owner, kept), store.owners());
    }

    @Test
    void aRevocationMadeWhileTheStoreIsDownIsPushedAfterARestart() throws Exception {
        OwnerGrants shared = sharedWith(friend, AccessLevel.HANDLER);
        store.saveAsAnotherServer(Map.of(owner, shared));
        service.start(tempDir);
        join();
        store.down = true;
        service.revokePlayer(owner, friend);
        waitForSync();

        // The server stops before the store comes back, then starts again.
        settingsSync.close();
        service.close();
        settingsSync = newSettingsSync();
        service.start(tempDir);
        store.down = false;
        join();

        assertEquals(OwnerGrants.EMPTY, service.grants(owner));
        assertEquals(Map.of(), store.owners());
    }

    private NetworkSettingsSync newSettingsSync() {
        return new NetworkSettingsSync(
                Executors.newSingleThreadScheduledExecutor(),
                () -> tempDir.resolve("tamework-network-settings.json"),
                new NetworkSettingsFiles(List.of(), null),
                () -> { },
                null);
    }

    /** Follows the network's grants, registers the store and waits for the first read. */
    private void join() throws Exception {
        new SharedAccessNetworkSync(service, settingsSync).start();
        settingsSync.register(store);
        waitForSync();
    }

    /** The sync has one thread, so a task submitted now finishes after the work already queued. */
    private void waitForSync() throws Exception {
        settingsSync.localSettingCount().get(10L, TimeUnit.SECONDS);
    }

    private static OwnerGrants sharedWith(UUID playerId, AccessLevel level) {
        return OwnerGrants.EMPTY.withPlayer(new OwnerGrants.PlayerGrant(playerId, "Friend", level));
    }

    /** One grants document with compare-and-write, like a real store. Other keys hold nothing. */
    private static final class FakeStore implements NetworkSettingsStore {
        private NetworkSettingsDocument document;
        volatile Runnable beforeNextSave;
        /** While true, this server cannot reach the store. */
        volatile boolean down;

        synchronized void saveAsAnotherServer(Map<UUID, OwnerGrants> owners) {
            document = new NetworkSettingsDocument(SharedAccessFileStore.toJson(owners),
                    document == null ? 1L : document.revision() + 1L);
        }

        synchronized Map<UUID, OwnerGrants> owners() {
            return document == null ? Map.of() : SharedAccessFileStore.fromJson(document.json());
        }

        @Override
        public synchronized NetworkSettingsDocument load(String key) throws IOException {
            if (down) {
                throw new IOException("store unreachable");
            }
            return SharedAccessNetworkSync.DOCUMENT_KEY.equals(key) ? document : null;
        }

        @Override
        public synchronized boolean save(String key, String json, long expectedRevision) throws IOException {
            if (down) {
                throw new IOException("store unreachable");
            }
            Runnable other = beforeNextSave;
            beforeNextSave = null;
            if (other != null) {
                other.run();
            }
            long current = document == null ? 0L : document.revision();
            if (current != expectedRevision) {
                return false;
            }
            document = new NetworkSettingsDocument(json, current + 1L);
            return true;
        }
    }
}
