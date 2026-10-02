package com.alechilles.alecstamework.items.locate;

import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.assetstore.TestItemAssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CapturedItemMetadataTest {
    private java.lang.reflect.Field assetStore;
    private Object previousStore;

    @BeforeEach void items() throws Exception {
        assetStore = Item.class.getDeclaredField("ASSET_STORE");
        assetStore.setAccessible(true);
        previousStore = assetStore.get(null);
        assetStore.set(null, new TestItemAssetStore(new DefaultAssetMap<>(Map.of(
                "Test_Capture", new Item("Test_Capture"), "Test_Ordinary", new Item("Test_Ordinary")))));
    }

    @AfterEach void restoreItems() throws Exception { assetStore.set(null, previousStore); }
    /** Ordinary moves must not trigger scans; removing a capture must invalidate its old holder. */
    @Test void gatesOrdinaryTransactionsAndDetectsCaptureRemoval() {
        var container = new SimpleItemContainer((short) 1);
        var ordinary = container.setItemStackForSlot((short) 0, new ItemStack("Test_Ordinary", 1));
        assertFalse(CapturedItemMetadata.affectsCapture(ordinary));
        container.setItemStackForSlot((short) 0, new ItemStack("Test_Capture", 1)
                .withMetadata(TameworkMetadataKeys.TARGET_UUID, Codec.STRING, UUID.randomUUID().toString()));
        var capture = container.removeItemStackFromSlot((short) 0, 1);
        assertTrue(CapturedItemMetadata.affectsCapture(capture));
        assertFalse(CapturedItemMetadata.affectsCapture(container.removeItemStackFromSlot((short) 0, 1)));
    }
    /** Locate must distinguish recaptures and reject half-written identity metadata. */
    @Test void recognizesExactCaptureAndRejectsPartialIdentity() {
        UUID alias = UUID.randomUUID();
        ItemStack legacy = new ItemStack("Test_Capture", 1)
                .withMetadata(TameworkMetadataKeys.TARGET_UUID, Codec.STRING, alias.toString());
        assertEquals(alias, CapturedItemMetadata.read(legacy).npcUuid());
        ItemStack snapshotOnly = legacy.withMetadata(TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                Codec.STRING, UUID.randomUUID().toString());
        assertNull(CapturedItemMetadata.read(snapshotOnly));
        ItemStack profile = legacy.withMetadata(
                TameworkMetadataKeys.COMPANION_PROFILE_ID, Codec.STRING, UUID.randomUUID().toString());
        // A 4.x item (profile id and snapshot id) is the index item of its profile at generation 0.
        ItemStack fourX = profile.withMetadata(TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                Codec.STRING, UUID.randomUUID().toString());
        assertEquals(CapturedItemMetadata.indexKey(CaptureItemKeys.readIndexItem(profile).profileId(), 0L),
                CapturedItemMetadata.read(fourX));
        assertNull(CapturedItemMetadata.read(new ItemStack("Test_Ordinary", 1)));
    }

    /** A 5.0 item has no alias or snapshot id; a recapture at a newer generation is a different capture. */
    @Test void readsACompanionIndexItemByProfileAndGeneration() {
        UUID profileId = UUID.randomUUID();
        ItemStack item = CaptureItemKeys.write(new ItemStack("Test_Capture", 1), new CaptureItemKeys.Ref(profileId, 3));
        ItemStack recaptured = CaptureItemKeys.write(item, new CaptureItemKeys.Ref(profileId, 4));

        assertNotNull(CapturedItemMetadata.read(item));
        assertNotEquals(CapturedItemMetadata.read(item), CapturedItemMetadata.read(recaptured));
        assertEquals(CapturedItemMetadata.read(item), CapturedItemMetadata.read(
                CaptureItemKeys.write(new ItemStack("Test_Capture", 1), new CaptureItemKeys.Ref(profileId, 3))));
        var container = new SimpleItemContainer((short) 1);
        container.setItemStackForSlot((short) 0, item);
        assertTrue(CapturedItemMetadata.affectsCapture(container.removeItemStackFromSlot((short) 0, 1)));
    }

    /** Items made before generations existed read as generation 0. */
    @Test void aMissingGenerationReadsAsZero() {
        UUID profileId = UUID.randomUUID();
        ItemStack item = new ItemStack("Test_Capture", 1)
                .withMetadata(TameworkMetadataKeys.COMPANION_PROFILE_ID, Codec.STRING, profileId.toString());

        assertEquals(new CaptureItemKeys.Ref(profileId, 0), CaptureItemKeys.readIndexItem(item));
        assertNull(CaptureItemKeys.readIndexItem(new ItemStack("Test_Capture", 1)
                .withMetadata(TameworkMetadataKeys.COMPANION_PROFILE_ID, Codec.STRING, "not-a-uuid")));
    }
}
