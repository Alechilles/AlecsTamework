package com.alechilles.alecstamework.companion.runtime;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.companion.store.MemoryCompanionFileIo;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPersistenceModuleTest {
    private static final Path ROOT = Path.of("universe", "Tamework", "Companions");
    private static final Path DATA = Path.of("universe", "Tamework", "Data");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static CompanionTransitions.BodyFacts body(UUID npc) {
        return new CompanionTransitions.BodyFacts(npc, OWNER, "Alec", "Tamed_Sheep", null, "default", 1, 2, 3,
                List.of(), CompanionSummary.EMPTY);
    }

    @Test
    void recordsSurviveAShutdownAndReopen() throws Exception {
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();
        CompanionPersistenceModule first = CompanionPersistenceModule.open(ROOT, List.of(DATA), p -> false, io,
                System::currentTimeMillis, "test");
        UUID profile = UUID.randomUUID();
        first.index().insert(CompanionTransitions.newLive(profile, 0, body(UUID.randomUUID())));
        assertTrue(first.shutdown(System.currentTimeMillis() + 5_000L));

        CompanionPersistenceModule second = CompanionPersistenceModule.open(ROOT, List.of(DATA), io::exists, io,
                System::currentTimeMillis, "test");

        assertEquals(CompanionPersistenceModule.State.READY, second.state());
        assertNotNull(second.queries().get(profile));
        assertEquals(1, second.queries().owned(OWNER).size());
        second.shutdown(System.currentTimeMillis() + 5_000L);
    }

    @Test
    void oldSavesLeaveTheStoreUntouched() {
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();

        CompanionPersistenceModule module = CompanionPersistenceModule.open(ROOT, List.of(DATA),
                p -> p.equals(DATA.resolve("tamework-state.sqlite")), io, System::currentTimeMillis, "test");

        assertEquals(CompanionPersistenceModule.State.MIGRATION_REQUIRED, module.state());
        assertTrue(io.writtenPaths().isEmpty(), "nothing may be written, not even meta.json");
        module.shutdown(System.currentTimeMillis() + 1_000L);
    }

    @Test
    void anUnreadableStoreFailsWithoutWriting() {
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();
        io.failReads(true);

        CompanionPersistenceModule module = CompanionPersistenceModule.open(ROOT, List.of(DATA), p -> false, io,
                System::currentTimeMillis, "test");

        assertEquals(CompanionPersistenceModule.State.FAILED, module.state());
        assertNotNull(module.failure());
        assertTrue(io.writtenPaths().isEmpty());
        module.shutdown(System.currentTimeMillis() + 1_000L);
    }

    @Test
    void aFreshStoreWritesItsMetaFile() {
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();

        CompanionPersistenceModule module = CompanionPersistenceModule.open(ROOT, List.of(DATA), p -> false, io,
                System::currentTimeMillis, "5.0.0-test");

        assertTrue(io.exists(CompanionStorage.metaFile(ROOT)));
        module.shutdown(System.currentTimeMillis() + 1_000L);
    }
}
