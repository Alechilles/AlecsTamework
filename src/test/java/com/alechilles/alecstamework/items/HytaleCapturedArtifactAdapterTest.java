package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.lang.reflect.Field;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact Hytale item-to-captured-artifact boundary contracts. */
class HytaleCapturedArtifactAdapterTest {
    private Field itemAssetStore;
    private Object previousItemAssetStore;

    @org.junit.jupiter.api.BeforeEach
    void initializeItemAssets() throws Exception {
        var itemType = com.hypixel.hytale.server.core.asset.type.item.config.Item.class;
        itemAssetStore = itemType.getDeclaredField("ASSET_STORE");
        itemAssetStore.setAccessible(true);
        previousItemAssetStore = itemAssetStore.get(null);
        itemAssetStore.set(null, new com.hypixel.hytale.assetstore.TestItemAssetStore(
                new com.hypixel.hytale.assetstore.map.DefaultAssetMap<>(java.util.Map.of(
                        "capture-device-filled", new com.hypixel.hytale.server.core.asset.type.item.config.Item(
                                "capture-device-filled")))));
    }

    @org.junit.jupiter.api.AfterEach
    void restoreItemAssets() throws Exception {
        itemAssetStore.set(null, previousItemAssetStore);
    }
    private static final HytaleCapturedArtifactAdapter ADAPTER =
            new HytaleCapturedArtifactAdapter(
                    HytaleCapturedArtifactAdapterTest::itemStack
            );

    @Test
    void roundTripPreservesEveryStackFieldAndBsonNumericType() {
        BsonDocument metadata = new BsonDocument()
                .append("long", new BsonInt64(9_007_199_254_740_993L))
                .append("int", new BsonInt32(7))
                .append("double", new BsonDouble(1.25D))
                .append("nested", new BsonDocument()
                        .append("value", new BsonInt64(-500L)))
                .append("array", new BsonArray(java.util.List.of(
                        new BsonInt32(1),
                        new BsonInt64(1L),
                        new BsonDouble(1.0D)
                )))
                .append(
                        TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                        new BsonString(
                                "50000000-0000-0000-0000-000000000001"
                        )
                );
        ItemStack source = itemStack(
                "capture-device-filled",
                3,
                7.25D,
                20.0D,
                metadata
        );

        CapturedArtifact artifact = ADAPTER.toArtifact(source);
        ItemStack restored = ADAPTER.toItemStack(artifact);
        BsonDocument restoredMetadata = restored.getMetadata();

        assertEquals(source.getItemId(), restored.getItemId());
        assertEquals(source.getQuantity(), restored.getQuantity());
        assertEquals(source.getDurability(), restored.getDurability());
        assertEquals(source.getMaxDurability(), restored.getMaxDurability());
        assertEquals(metadata, restoredMetadata);
        assertTrue(restoredMetadata.get("long").isInt64());
        assertTrue(restoredMetadata.get("int").isInt32());
        assertTrue(restoredMetadata.get("double").isDouble());
        assertEquals(artifact, ADAPTER.toArtifact(restored));
        assertTrue(ADAPTER.matches(restored, artifact));
    }

    @Test
    void bsonNumericTypeParticipatesInTheArtifactHash() {
        ItemStack intValue = stackWithMetadata(
                new BsonDocument("value", new BsonInt32(5))
        );
        ItemStack longValue = stackWithMetadata(
                new BsonDocument("value", new BsonInt64(5L))
        );

        CapturedArtifact intArtifact = ADAPTER.toArtifact(intValue);
        CapturedArtifact longArtifact = ADAPTER.toArtifact(longValue);

        assertNotEquals(
                intArtifact.metadataExtendedJson(),
                longArtifact.metadataExtendedJson()
        );
        assertNotEquals(intArtifact.artifactHash(), longArtifact.artifactHash());
    }

    @Test
    void nullMetadataUsesTheCanonicalEmptyDocument() {
        ItemStack source = itemStack(
                "capture-device-filled",
                1,
                0.0D,
                0.0D,
                null
        );

        CapturedArtifact artifact = ADAPTER.toArtifact(source);
        ItemStack restored = ADAPTER.toItemStack(artifact);

        assertEquals("{}", artifact.metadataExtendedJson());
        assertEquals(new BsonDocument(), restored.getMetadata());
        assertEquals(artifact, ADAPTER.toArtifact(restored));
    }

    @Test
    void nullAndEmptyInputsFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ADAPTER.toArtifact(null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ADAPTER.toArtifact(ItemStack.EMPTY)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ADAPTER.toItemStack(null)
        );
        CapturedArtifact artifact = ADAPTER.toArtifact(stackWithMetadata(
                new BsonDocument()
        ));
        assertFalse(
                ADAPTER.matches(ItemStack.EMPTY, artifact)
        );
        assertFalse(
                ADAPTER.matches(stackWithMetadata(
                        new BsonDocument("other", new BsonString("value"))
                ), artifact)
        );
    }

    @Test
    void captureQualitySurvivesArtifactRecreationAndReceiptMetadataWithoutChangingIdentity() {
        var adapter = new HytaleCapturedArtifactAdapter(
                HytaleCapturedArtifactAdapterTest::itemStack, id -> "Rare".equals(id) ? 17 : -1);
        var source = stackWithMetadata(new BsonDocument(
                TameworkMetadataKeys.CAPTURE_ITEM_QUALITY_ID, new BsonString("Rare")));
        var artifact = adapter.toArtifact(source);
        var restored = adapter.toItemStack(artifact);
        assertEquals(17, restored.getQualityIndex());
        assertTrue(adapter.matches(restored, artifact));
        var receipt = adapter.withMetadata(restored, new BsonDocument("receipt", new BsonString("ok")));
        assertEquals(17, receipt.getQualityIndex());
        assertEquals("ok", receipt.getMetadata().getString("receipt").getValue());

        var missingQuality = new HytaleCapturedArtifactAdapter(
                HytaleCapturedArtifactAdapterTest::itemStack, id -> -1);
        assertTrue(missingQuality.matches(missingQuality.toItemStack(artifact), artifact));
    }

    private ItemStack stackWithMetadata(BsonDocument metadata) {
        return itemStack(
                "capture-device-filled",
                1,
                0.0D,
                0.0D,
                metadata
        );
    }

    private static ItemStack itemStack(
            String itemId,
            int quantity,
            double durability,
            double maxDurability,
            BsonDocument metadata
    ) {
        try {
            ItemStack stack =
                    (ItemStack) unsafe().allocateInstance(ItemStack.class);
            set(stack, "itemId", itemId);
            set(stack, "quantity", quantity);
            set(stack, "durability", durability);
            set(stack, "maxDurability", maxDurability);
            set(stack, "metadata", metadata);
            return stack;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Unable to construct exact test ItemStack",
                    failure
            );
        }
    }

    private static void set(ItemStack stack, String name, Object value)
            throws ReflectiveOperationException {
        Field field = ItemStack.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(stack, value);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
