package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemInventoryPosition;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ArtifactMutationStatus;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ArtifactState;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.lang.reflect.Field;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact quantity-sensitive Hytale slot CAS and idempotent retirement coverage. */
class HytaleCoopCapturedItemInventorySlotTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final HytaleCapturedArtifactAdapter ARTIFACTS =
            new HytaleCapturedArtifactAdapter();

    @Test
    void exactSourceMarksAndRetiresOnlyOnce() {
        CoopCapturedItemSourceEvidence source = source();
        SimpleItemContainer container = new SimpleItemContainer((short) 1);
        container.setItemStackForSlot(
                (short) 0,
                itemStack(source.sourceArtifact())
        );
        HytaleCoopCapturedItemInventorySlot slot =
                new HytaleCoopCapturedItemInventorySlot(
                        container,
                        (short) 0,
                        ARTIFACTS,
                        HytaleCoopCapturedItemInventorySlotTest::itemStack
                );

        HytaleCoopCapturedItemInventorySlot.SlotMutation marked =
                slot.mark(source);
        HytaleCoopCapturedItemInventorySlot.SlotMutation markedReplay =
                slot.mark(source);
        HytaleCoopCapturedItemInventorySlot.SlotMutation retired =
                slot.retireMarked(source);
        HytaleCoopCapturedItemInventorySlot.SlotMutation retiredReplay =
                slot.retireMarked(source);

        assertEquals(
                ArtifactMutationStatus.MARKED,
                marked.mutation().status()
        );
        assertTrue(marked.changedThisCall());
        assertEquals(
                ArtifactMutationStatus.MARKED,
                markedReplay.mutation().status()
        );
        assertFalse(markedReplay.changedThisCall());
        assertEquals(
                ArtifactMutationStatus.ABSENT,
                retired.mutation().status()
        );
        assertTrue(retired.changedThisCall());
        assertEquals(
                ArtifactMutationStatus.ABSENT,
                retiredReplay.mutation().status()
        );
        assertFalse(retiredReplay.changedThisCall());
        assertEquals(ArtifactState.ABSENT, slot.probe(source));
    }

    @Test
    void stackableButDifferentQuantityCannotPassExactCas() {
        CoopCapturedItemSourceEvidence source = source();
        CapturedArtifact expected = source.sourceArtifact();
        CapturedArtifact wrongQuantity = CapturedArtifact.create(
                expected.itemId(),
                expected.quantity() + 1,
                expected.durability(),
                expected.maxDurability(),
                expected.metadataExtendedJson()
        );
        SimpleItemContainer container = new SimpleItemContainer((short) 1);
        container.setItemStackForSlot(
                (short) 0, itemStack(wrongQuantity)
        );
        HytaleCoopCapturedItemInventorySlot slot =
                new HytaleCoopCapturedItemInventorySlot(
                        container,
                        (short) 0,
                        ARTIFACTS,
                        HytaleCoopCapturedItemInventorySlotTest::itemStack
                );

        HytaleCoopCapturedItemInventorySlot.SlotMutation result =
                slot.mark(source);

        assertEquals(ArtifactState.CONFLICT, slot.probe(source));
        assertEquals(
                ArtifactMutationStatus.CONFLICT,
                result.mutation().status()
        );
        assertFalse(result.changedThisCall());
        assertTrue(ARTIFACTS.matches(
                container.getItemStack((short) 0), wrongQuantity
        ));
    }

    private CoopCapturedItemSourceEvidence source() {
        String payload = "{\"version\":\"1\",\"npcUuid\":\"" + ALIAS
                + "\",\"coopId\":null,\"residentSlot\":-1,"
                + "\"roleId\":\"tamed_chicken\",\"capturedAtMs\":-200}";
        CompanionSnapshot capture = new CompanionSnapshot(
                SnapshotId.parse(
                        "40000000-0000-0000-0000-000000000001"
                ),
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                CompanionCaptureRequest.SNAPSHOT_VERSION,
                payload,
                Sha256Hash.ofUtf8(payload),
                new LifecycleRevision(6),
                true,
                -200
        );
        BsonDocument metadata = new BsonDocument()
                .append(
                        TameworkMetadataKeys.TARGET_UUID,
                        new BsonString(ALIAS.toString())
                )
                .append(
                        TameworkMetadataKeys.COMPANION_PROFILE_ID,
                        new BsonString(PROFILE.toString())
                )
                .append(
                        TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                        new BsonString(capture.snapshotId().toString())
                );
        CapturedArtifact exact = CapturedArtifact.create(
                "captured-chicken",
                1,
                0.0D,
                0.0D,
                metadata.toJson()
        );
        metadata.put(
                CoopCapturedItemSourceEvidence.RECEIPT_METADATA_KEY,
                new BsonString("coop-item-receipt")
        );
        CapturedArtifact marked = CapturedArtifact.create(
                exact.itemId(),
                exact.quantity(),
                exact.durability(),
                exact.maxDurability(),
                metadata.toJson()
        );
        return new CoopCapturedItemSourceEvidence(
                ALIAS,
                PROFILE,
                capture,
                UUID.fromString(
                        "30000000-0000-0000-0000-000000000001"
                ),
                "world",
                new CoopCapturedItemInventoryPosition(
                        CoopCapturedItemInventoryPosition.Section.STORAGE,
                        0
                ),
                exact,
                marked,
                "coop-item-receipt"
        );
    }

    private static ItemStack itemStack(CapturedArtifact artifact) {
        try {
            ItemStack stack =
                    (ItemStack) unsafe().allocateInstance(ItemStack.class);
            set(stack, "itemId", artifact.itemId());
            set(stack, "quantity", artifact.quantity());
            set(stack, "durability", artifact.durability());
            set(stack, "maxDurability", artifact.maxDurability());
            set(
                    stack,
                    "metadata",
                    BsonDocument.parse(artifact.metadataExtendedJson())
            );
            return stack;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Unable to construct exact test ItemStack", failure
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
