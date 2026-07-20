package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemStackItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import java.lang.reflect.Field;
import java.util.UUID;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecursiveItemContainerEvidenceScannerTest {
    @Test
    void scansNestedFilledItemsAndCanonicalSetDeduplicatesCopiedIdentity() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        SimpleItemContainer root = new SimpleItemContainer((short) 2);
        root.setItemStackForSlot((short) 0, captured(npcUuid, ownerUuid));
        root.setItemStackForSlot((short) 1, containerItem("Bag_Test", captured(npcUuid, ownerUuid)));

        RecursiveItemContainerEvidenceScanner.Result result = scanner(null).scan(root, "inventory", "test");
        CompanionPopulationEvidenceSet evidenceSet = new CompanionPopulationEvidenceSet(result.evidence());

        assertEquals(2, result.evidence().size());
        assertEquals(2, result.visitedContainers());
        assertEquals(1, evidenceSet.evidence().size());
        assertEquals(2, evidenceSet.evidence().getFirst().observationCount());
        assertEquals(ownerUuid, evidenceSet.evidence().getFirst().observedOwnerUuid());
    }

    @Test
    void unknownLegacyConfigConservativelyUsesCaptureSourceOwner() {
        UUID npcUuid = UUID.randomUUID();
        UUID sourceOwner = UUID.randomUUID();
        BsonDocument metadata = new BsonDocument()
                .append(TameworkMetadataKeys.TARGET_UUID, new BsonString(npcUuid.toString()))
                .append(TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID, new BsonString(sourceOwner.toString()));
        ItemStack legacy = item("Legacy_State_Filled", metadata);

        CompanionPopulationEvidence evidence = new LegacyCapturedItemEvidenceReader(null)
                .read(legacy, "legacy", "test")
                .orElseThrow();

        assertEquals(sourceOwner, evidence.ownerUuid());
        assertEquals(
                CompanionPopulationEvidence.Kind.CAPTURED_ITEM_LEGACY_OWNER_HINT,
                evidence.kind()
        );
    }

    /** Protects support bundle 6d755cb8: configs are unavailable during the earliest restart scan. */
    @Test
    void explicitClearOutcomeDoesNotReAdoptSourceOwnerBeforeConfigsLoad() {
        UUID npcUuid = UUID.randomUUID();
        UUID sourceOwner = UUID.randomUUID();
        BsonDocument metadata = new BsonDocument()
                .append(TameworkMetadataKeys.CAPTURED, BsonBoolean.TRUE)
                .append(TameworkMetadataKeys.TARGET_UUID, new BsonString(npcUuid.toString()))
                .append(TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID, new BsonString(sourceOwner.toString()))
                .append(TameworkMetadataKeys.CAPTURE_OWNER_CLEARED, BsonBoolean.TRUE);

        CompanionPopulationEvidence evidence = new LegacyCapturedItemEvidenceReader(null)
                .read(item("Spawner_Test_State_Filled", metadata), "restart", "test")
                .orElseThrow();

        assertNull(evidence.ownerUuid());
        assertEquals(CompanionPopulationEvidence.Kind.CAPTURED_ITEM, evidence.kind());
    }

    @Test
    void knownClearOwnerConfigDoesNotReAdoptCaptureSourceOwner() {
        UUID npcUuid = UUID.randomUUID();
        UUID sourceOwner = UUID.randomUUID();
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Known", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .captureClearsOwner(true)
                .build());
        BsonDocument metadata = new BsonDocument()
                .append(TameworkMetadataKeys.TARGET_UUID, new BsonString(npcUuid.toString()))
                .append(TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID, new BsonString(sourceOwner.toString()));
        ItemStack item = item("Known_State_Filled", metadata);

        CompanionPopulationEvidence evidence = new LegacyCapturedItemEvidenceReader(registry)
                .read(item, "known", "test")
                .orElseThrow();

        assertNull(evidence.ownerUuid());
    }

    @Test
    void depthBoundFailsClosedInsteadOfDeclaringPartialCoverageReady() {
        SimpleItemContainer root = new SimpleItemContainer((short) 1);
        root.setItemStackForSlot((short) 0, containerItem(
                "Bag_A",
                containerItem("Bag_B", captured(UUID.randomUUID(), UUID.randomUUID()))
        ));
        RecursiveItemContainerEvidenceScanner bounded = new RecursiveItemContainerEvidenceScanner(
                new LegacyCapturedItemEvidenceReader(null),
                1,
                10,
                10
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> bounded.scan(root, "inventory", "test")
        );

        assertTrue(failure.getMessage().contains("depth"));
    }

    private static RecursiveItemContainerEvidenceScanner scanner(ItemFeatureRegistry registry) {
        return new RecursiveItemContainerEvidenceScanner(new LegacyCapturedItemEvidenceReader(registry));
    }

    private static ItemStack captured(UUID npcUuid, UUID ownerUuid) {
        BsonDocument metadata = new BsonDocument()
                .append(TameworkMetadataKeys.CAPTURED, BsonBoolean.TRUE)
                .append(TameworkMetadataKeys.TARGET_UUID, new BsonString(npcUuid.toString()))
                .append(TameworkMetadataKeys.OWNER_UUID, new BsonString(ownerUuid.toString()));
        return item("Spawner_Test_State_Filled", metadata);
    }

    private static ItemStack containerItem(String itemId, ItemStack... contents) {
        BsonDocument container = new BsonDocument();
        ItemStackItemContainer.CAPACITY_CODEC.put(
                container,
                (short) Math.max(1, contents.length),
                EmptyExtraInfo.EMPTY
        );
        ItemStackItemContainer.ITEMS_CODEC.put(container, contents, EmptyExtraInfo.EMPTY);
        BsonDocument metadata = new BsonDocument();
        ItemStackItemContainer.CONTAINER_CODEC.put(metadata, container, EmptyExtraInfo.EMPTY);
        return item(itemId, metadata);
    }

    private static ItemStack item(String itemId, BsonDocument metadata) {
        try {
            ItemStack stack = (ItemStack) unsafe().allocateInstance(ItemStack.class);
            setField(ItemStack.class, stack, "itemId", itemId);
            setField(ItemStack.class, stack, "quantity", 1);
            setField(ItemStack.class, stack, "metadata", metadata);
            return stack;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void setField(Class<?> type, Object target, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field unsafe = Unsafe.class.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        return (Unsafe) unsafe.get(null);
    }
}
