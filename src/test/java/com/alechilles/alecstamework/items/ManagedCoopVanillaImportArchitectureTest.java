package com.alechilles.alecstamework.items;

import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import java.lang.reflect.Field;
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
        assertFalse(source.contains("CompletableFuture"));
        assertFalse(source.contains("CompletionStage"));
    }

    @Test
    void importServiceNeverCapturesLiveReferencesInPersistenceContinuations() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/ManagedCoopVanillaImportService.java"));
        assertFalse(source.contains("thenApply"));
        assertFalse(source.contains("thenCompose"));
        assertFalse(source.contains("whenComplete"));
        assertFalse(source.contains("PlayerRef"));
        assertFalse(source.contains("EntityStore"));
        assertTrue(source.contains("compositeIndexes.refresh()"));
        assertTrue(source.contains("compositeIndexes.isTrusted()"));
    }
}
