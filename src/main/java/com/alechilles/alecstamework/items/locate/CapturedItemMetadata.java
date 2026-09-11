package com.alechilles.alecstamework.items.locate;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.CaptureKey;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.*;
import java.util.UUID;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/** Capture identity parsing and a cheap gate before any inventory rescan. */
public final class CapturedItemMetadata {
    private CapturedItemMetadata() { }

    @Nullable
    public static CaptureKey read(@Nullable ItemStack stack) {
        if (!marked(stack)) return null;
        BsonDocument metadata = stack.getMetadata();
        try {
            UUID alias = UUID.fromString(metadata.getString(TameworkMetadataKeys.TARGET_UUID).getValue());
            boolean profile = metadata.containsKey(TameworkMetadataKeys.COMPANION_PROFILE_ID);
            boolean snapshot = metadata.containsKey(TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID);
            if (profile != snapshot) return null;
            return new CaptureKey(profile ? UUID.fromString(metadata.getString(
                    TameworkMetadataKeys.COMPANION_PROFILE_ID).getValue()).toString() : null,
                    snapshot ? metadata.getString(TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID).getValue() : null, alias);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean marked(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getMetadata() != null
                && stack.getMetadata().containsKey(TameworkMetadataKeys.TARGET_UUID);
    }

    /** Only affected stacks are inspected; ordinary item changes allocate nothing here. */
    public static boolean affectsCapture(@Nullable Transaction transaction) {
        if (transaction == null || !transaction.succeeded()) return false;
        if (transaction instanceof SlotTransaction slot) {
            return marked(slot.getSlotBefore()) || marked(slot.getSlotAfter()) || marked(slot.getOutput());
        }
        if (transaction instanceof MoveTransaction<?> move) {
            return affectsCapture(move.getRemoveTransaction()) || affectsCapture(move.getAddTransaction());
        }
        if (transaction instanceof ItemStackTransaction stack) {
            if (marked(stack.getQuery())) return true;
            for (SlotTransaction slot : stack.getSlotTransactions()) if (affectsCapture(slot)) return true;
            return false;
        }
        if (transaction instanceof ListTransaction<?> list) {
            for (Transaction nested : list.getList()) if (affectsCapture(nested)) return true;
            return false;
        }
        if (transaction instanceof ClearTransaction clear) {
            for (ItemStack stack : clear.getItems()) if (marked(stack)) return true;
            return false;
        }
        // A future transaction format may have moved a capture. Reconcile this holder once.
        return true;
    }
}
