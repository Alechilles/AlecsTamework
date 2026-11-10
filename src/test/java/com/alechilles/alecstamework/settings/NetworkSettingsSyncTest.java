package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.api.NetworkSettingsDocument;
import com.alechilles.alecstamework.api.NetworkSettingsStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Network settings through the real main settings store, with an in-memory network store. */
class NetworkSettingsSyncTest {

    @TempDir
    Path tempDir;

    private Path settingsFile;
    private FakeStore store;
    private NetworkSettingsSync sync;

    @BeforeEach
    void setUp() {
        settingsFile = TameworkSettingsStore.resolveGlobalSettingsFile(tempDir.resolve("Tamework"));
        store = new FakeStore();
        NetworkSettingsLayer.useForTests(NetworkSettingsLayer.State.NONE);
        sync = new NetworkSettingsSync(
                Executors.newSingleThreadScheduledExecutor(),
                () -> tempDir.resolve("Tamework").resolve("Settings").resolve(NetworkSettingsLayer.CACHE_FILE_NAME),
                new NetworkSettingsFiles(List.of(new NetworkSettingsFiles.Section(
                        NetworkSettingsLayer.SECTION_MAIN, settingsFile, TameworkSettingsStore::normalize)), null),
                TameworkSettingsStore::invalidateRuntimeGlobalOverridesCache,
                null);
    }

    @AfterEach
    void tearDown() {
        sync.close();
        NetworkSettingsLayer.useForTests(null);
        TameworkSettingsStore.invalidateRuntimeGlobalOverridesCache();
    }

    @Test
    void aServerOverridesSingleSettingsAndInheritsTheRestFromTheNetwork() throws Exception {
        store.put("{'population':{'limitPerPlayerOwnedTotal':5},'traits':{'enabled':false}}");
        join();
        writeLocal("{\"population\":{\"limitPerPlayerOwnedTotal\":8}}");

        ResolvedTameworkSettings settings = load();

        assertEquals(8, settings.populationLimitPerPlayerOwnedTotal());
        assertFalse(settings.traitsEnabled());
        assertEquals(50, settings.commandPanelCardsPerPage());
    }

    @Test
    void aLocalSaveKeepsFollowingTheNetworkForSettingsItDidNotChange() throws Exception {
        store.put("{'population':{'limitPerPlayerOwnedTotal':5},'traits':{'enabled':false}}");
        join();
        writeLocal("{\"population\":{\"limitPerPlayerOwnedTotal\":8}}");
        assertTrue(TameworkSettingsStore.saveGlobalSettings(settingsFile, load().toSnapshot(), null));

        store.put("{'population':{'limitPerPlayerOwnedTotal':5},'traits':{'enabled':true}}");
        check();

        assertTrue(load().traitsEnabled());
        assertEquals(8, load().populationLimitPerPlayerOwnedTotal());
    }

    @Test
    void joiningKeepsChosenValuesAndDropsUntouchedDefaults() throws Exception {
        // A full local file, as every server has today: one chosen value, everything else the default.
        writeLocal(TameworkSettingsStore.normalize(
                "{\"population\":{\"limitPerPlayerOwnedTotal\":8,\"limitPerPlayerDeployedTotal\":0}}"));
        store.put("{'population':{'limitPerPlayerOwnedTotal':5},'commandPanel':{'cardsPerPage':20}}");

        join();

        assertEquals(8, load().populationLimitPerPlayerOwnedTotal());
        assertEquals(20, load().commandPanelCardsPerPage());
    }

    @Test
    void leavingKeepsTheSettingsInEffectAndStopsFollowing() throws Exception {
        store.put("{'population':{'limitPerPlayerOwnedTotal':5},'traits':{'enabled':false}}");
        join();
        writeLocal("{\"commandPanel\":{\"cardsPerPage\":20}}");
        ResolvedTameworkSettings before = load();

        assertTrue(sync.leave().get());
        store.put("{'population':{'limitPerPlayerOwnedTotal':99}}");
        check();

        assertFalse(NetworkSettingsLayer.active());
        assertEquals(before, load());
    }

    @Test
    void publishingSendsThisServersSettingsAndLeavesItWithoutOverrides() throws Exception {
        writeLocal(TameworkSettingsStore.normalize(
                "{\"population\":{\"limitPerPlayerOwnedTotal\":8,\"limitPerPlayerDeployedTotal\":0}}"));
        sync.register(store);

        assertEquals(NetworkSettingsSync.PublishResult.SAVED, sync.publish().get());

        assertEquals(8, TameworkSettingsStore.parse(store.section()).populationLimitPerPlayerOwnedTotal());
        assertEquals(0, sync.localSettingCount().get());
        assertEquals(8, load().populationLimitPerPlayerOwnedTotal());
    }

    @Test
    void aPublishAfterAnotherServerSavedWritesNothingAndReadsTheNewerSettings() throws Exception {
        store.put("{'population':{'limitPerPlayerOwnedTotal':5}}");
        join();
        writeLocal("{\"population\":{\"limitPerPlayerOwnedTotal\":8}}");
        // Another server saves before this one checks again.
        store.put("{'population':{'limitPerPlayerOwnedTotal':6},'traits':{'enabled':false}}");
        String othersDocument = store.document.json();

        assertEquals(NetworkSettingsSync.PublishResult.CONFLICT, sync.publish().get());

        assertEquals(othersDocument, store.document.json());
        assertFalse(load().traitsEnabled());
        assertEquals(8, load().populationLimitPerPlayerOwnedTotal());
    }

    @Test
    void aFailingStoreKeepsTheLastNetworkSettings() throws Exception {
        store.put("{'traits':{'enabled':false}}");
        join();
        store.failing = true;

        check();

        assertTrue(NetworkSettingsLayer.active());
        assertFalse(load().traitsEnabled());
    }

    private void join() throws Exception {
        sync.register(store);
        check();
    }

    /** Runs one check and waits for it: the sync has one thread, so a later task finishes after it. */
    private void check() throws Exception {
        sync.notifyChanged(NetworkSettingsStore.SERVER_KEY);
        sync.localSettingCount().get();
    }

    private ResolvedTameworkSettings load() {
        return TameworkSettingsStore.loadGlobalSettings(settingsFile, null);
    }

    private void writeLocal(String json) throws IOException {
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, json, StandardCharsets.UTF_8);
        TameworkSettingsStore.invalidateRuntimeGlobalOverridesCache();
    }

    /** One document with compare-and-write, like a real store. */
    private static final class FakeStore implements NetworkSettingsStore {
        volatile NetworkSettingsDocument document;
        volatile boolean failing;

        /** Saves a server document whose main section is the full form of {@code mainSettings}, as another server would. */
        void put(String mainSettings) {
            JsonObject sections = new JsonObject();
            sections.add(NetworkSettingsLayer.SECTION_MAIN, JsonParser.parseString(
                    TameworkSettingsStore.normalize(mainSettings.replace('\'', '"'))));
            document = new NetworkSettingsDocument(sections.toString(), document == null ? 1 : document.revision() + 1);
        }

        String section() {
            return JsonParser.parseString(document.json()).getAsJsonObject()
                    .get(NetworkSettingsLayer.SECTION_MAIN).toString();
        }

        @Override
        public NetworkSettingsDocument load(String key) throws Exception {
            if (failing) {
                throw new IOException("store down");
            }
            return document;
        }

        @Override
        public synchronized boolean save(String key, String json, long expectedRevision) {
            long current = document == null ? 0 : document.revision();
            if (current != expectedRevision) {
                return false;
            }
            document = new NetworkSettingsDocument(json, current + 1);
            return true;
        }
    }
}
