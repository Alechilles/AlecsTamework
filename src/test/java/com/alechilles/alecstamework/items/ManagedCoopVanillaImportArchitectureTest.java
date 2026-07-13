package com.alechilles.alecstamework.items;

import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static guardrails for the owning-thread import seam and async live-reference boundary. */
class ManagedCoopVanillaImportArchitectureTest {
    @Test
    void behaviorRetainsServicesOnlyAndRevalidatesEveryExactBlockLayer() throws Exception {
        for (Field field : ManagedCoopVanillaImportBehavior.class.getDeclaredFields()) {
            assertFalse(field.getType() == CoopBlock.class);
            assertFalse(field.getType() == World.class);
            assertFalse(Store.class.isAssignableFrom(field.getType()));
            assertFalse(Ref.class.isAssignableFrom(field.getType()));
        }
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/ManagedCoopVanillaImportBehavior.java"));
        for (String required : Arrays.asList(
                "getBlockComponentEntity", "BlockStateInfo.getComponentType",
                "CoopBlock.getComponentType", "ItemContainerBlock.getComponentType",
                "matchesBlockInfo", "authorityResolver.resolve", "matchesExact",
                "isManagedAuthorityEnabled")) {
            assertTrue(source.contains(required), required);
        }
        assertFalse(source.contains("coop.getClass() != CoopBlock.class"));
        assertFalse(source.contains("CompletableFuture"));
        assertFalse(source.contains("CompletionStage"));
    }

    @Test
    void importServiceNeverCapturesLiveReferencesInPersistenceContinuations() throws Exception {
        for (Class<?> serviceType : Arrays.asList(
                ManagedCoopVanillaImportService.class,
                ManagedCoopImportSessionProcessor.class,
                ManagedCoopImportWriteCoordinator.class)) {
            for (Field field : serviceType.getDeclaredFields()) {
                assertFalse(field.getType() == CoopBlock.class, field.getName());
                assertFalse(field.getType() == World.class, field.getName());
                assertFalse(Store.class.isAssignableFrom(field.getType()), field.getName());
                assertFalse(Ref.class.isAssignableFrom(field.getType()), field.getName());
            }
        }
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/ManagedCoopVanillaImportService.java"));
        String writes = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/ManagedCoopImportWriteCoordinator.java"));
        assertFalse(source.contains("thenApply"));
        assertFalse(source.contains("thenCompose"));
        assertFalse(source.contains("whenComplete"));
        assertFalse(source.contains("PlayerRef"));
        assertTrue(source.contains("ManagedCoopImportSessionProcessor"));
        assertTrue(source.contains("ManagedCoopImportWriteCoordinator"));
        assertTrue(writes.contains("compositeIndexes.refresh()"));
        assertTrue(writes.contains("compositeIndexes.isTrusted()"));
    }

    @Test
    void managedImportCollaboratorsStayBelowGodClassLimit() throws Exception {
        for (String file : Arrays.asList(
                "ManagedCoopVanillaImportService.java",
                "ManagedCoopImportSessionProcessor.java",
                "ManagedCoopImportWriteCoordinator.java",
                "VanillaCoopImportAuditPreparer.java",
                "VanillaCoopImportEnvelopeFactory.java")) {
            Path path = Path.of("src/main/java/com/alechilles/alecstamework/items", file);
            assertTrue(Files.readAllLines(path).size() <= 500, file + " exceeds 500 lines");
        }
    }

    @Test
    void publishedImportInspectionContainsPortableValuesOnly() {
        for (Class<?> reportType : Arrays.asList(
                ManagedCoopVanillaImportInspectionService.ImportInspection.class,
                ManagedCoopVanillaImportInspectionService.SourceSummary.class)) {
            for (RecordComponent component : reportType.getRecordComponents()) {
                Class<?> type = component.getType();
                assertFalse(type == CoopBlock.class, component.getName());
                assertFalse(type == World.class, component.getName());
                assertFalse(Store.class.isAssignableFrom(type), component.getName());
                assertFalse(Ref.class.isAssignableFrom(type), component.getName());
            }
        }
    }
}
