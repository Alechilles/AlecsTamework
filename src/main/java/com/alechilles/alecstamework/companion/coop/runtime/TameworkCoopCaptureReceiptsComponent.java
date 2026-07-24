package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CoopCaptureReceipt;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Persistent bounded receipt state attached to one physical coop block entity.
 *
 * <p>Each resident-slot index has at most one receipt. Replaying or replacing a receipt updates
 * that slot instead of appending history, so repeated recovery cannot grow the component.</p>
 */
public final class TameworkCoopCaptureReceiptsComponent
        implements Component<ChunkStore> {
    private static final ReceiptEntry[] EMPTY = new ReceiptEntry[0];

    private static final BuilderCodec<ReceiptEntry> RECEIPT_CODEC =
            BuilderCodec.builder(ReceiptEntry.class, ReceiptEntry::new)
                    .<String>append(
                            new KeyedCodec<>("OperationId", Codec.STRING),
                            (entry, value) -> entry.operationId = value,
                            entry -> entry.operationId
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("ProfileId", Codec.STRING),
                            (entry, value) -> entry.profileId = value,
                            entry -> entry.profileId
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("SourceAlias", Codec.STRING),
                            (entry, value) -> entry.sourceAlias = value,
                            entry -> entry.sourceAlias
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("SlotKey", Codec.STRING),
                            (entry, value) -> entry.slotKey = value,
                            entry -> entry.slotKey
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("ReceiptKey", Codec.STRING),
                            (entry, value) -> entry.receiptKey = value,
                            entry -> entry.receiptKey
                    ).add()
                    .build();
    private static final ArrayCodec<ReceiptEntry> RECEIPT_ARRAY_CODEC =
            new ArrayCodec<>(RECEIPT_CODEC, ReceiptEntry[]::new);

    public static final BuilderCodec<TameworkCoopCaptureReceiptsComponent> CODEC =
            BuilderCodec.builder(
                    TameworkCoopCaptureReceiptsComponent.class,
                    TameworkCoopCaptureReceiptsComponent::new
            ).<ReceiptEntry[]>append(
                    new KeyedCodec<>("Receipts", RECEIPT_ARRAY_CODEC),
                    TameworkCoopCaptureReceiptsComponent::setReceiptEntries,
                    TameworkCoopCaptureReceiptsComponent::getReceiptEntries
            ).add().build();

    private ReceiptEntry[] receiptEntries = EMPTY;

    public TameworkCoopCaptureReceiptsComponent() {
    }

    private TameworkCoopCaptureReceiptsComponent(ReceiptEntry[] entries) {
        setReceiptEntries(entries);
    }

    /**
     * Reads the receipt for one exact physical slot.
     *
     * @throws IllegalStateException when persisted entries describe another physical coop or
     *                               duplicate resident-slot indexes
     */
    @Nullable
    public CoopCaptureReceipt receiptFor(@Nonnull CoopSlotKey slotKey) {
        validateSlot(slotKey);
        CoopCaptureReceipt found = null;
        for (ReceiptEntry entry : receiptEntries) {
            CoopCaptureReceipt receipt = decode(entry);
            requireSamePhysicalCoop(slotKey, receipt.slotKey());
            if (receipt.slotKey().residentSlot() != slotKey.residentSlot()) {
                continue;
            }
            if (found != null || !receipt.slotKey().equals(slotKey)) {
                throw new IllegalStateException(
                        "Coop receipt resident-slot identity conflict"
                );
            }
            found = receipt;
        }
        return found;
    }

    /** Returns a copy with exactly one receipt for the supplied resident-slot index. */
    @Nonnull
    public TameworkCoopCaptureReceiptsComponent withReceipt(
            @Nonnull CoopCaptureReceipt receipt
    ) {
        if (receipt == null) {
            throw new IllegalArgumentException("Coop capture receipt is required");
        }
        List<ReceiptEntry> updated = new ArrayList<>(receiptEntries.length + 1);
        for (ReceiptEntry entry : receiptEntries) {
            CoopCaptureReceipt existing = decode(entry);
            requireSamePhysicalCoop(receipt.slotKey(), existing.slotKey());
            if (existing.slotKey().residentSlot()
                    != receipt.slotKey().residentSlot()) {
                updated.add(new ReceiptEntry(existing));
            }
        }
        updated.add(new ReceiptEntry(receipt));
        updated.sort(Comparator.comparingInt(
                entry -> decode(entry).slotKey().residentSlot()
        ));
        return new TameworkCoopCaptureReceiptsComponent(
                updated.toArray(ReceiptEntry[]::new)
        );
    }

    /** Returns the number of distinct physical resident slots represented. */
    public int receiptCount() {
        return receiptEntries.length;
    }

    private ReceiptEntry[] getReceiptEntries() {
        ReceiptEntry[] copy = new ReceiptEntry[receiptEntries.length];
        for (int index = 0; index < receiptEntries.length; index++) {
            copy[index] = new ReceiptEntry(decode(receiptEntries[index]));
        }
        return copy;
    }

    private void setReceiptEntries(@Nullable ReceiptEntry[] entries) {
        if (entries == null || entries.length == 0) {
            receiptEntries = EMPTY;
            return;
        }
        List<ReceiptEntry> validated = new ArrayList<>(entries.length);
        CoopSlotKey firstSlot = null;
        java.util.HashSet<Integer> residentSlots = new java.util.HashSet<>();
        for (ReceiptEntry entry : entries) {
            CoopCaptureReceipt receipt = decode(entry);
            if (firstSlot == null) {
                firstSlot = receipt.slotKey();
            } else {
                requireSamePhysicalCoop(firstSlot, receipt.slotKey());
            }
            if (!residentSlots.add(receipt.slotKey().residentSlot())) {
                throw new IllegalArgumentException(
                        "Duplicate coop receipt resident slot"
                );
            }
            validated.add(new ReceiptEntry(receipt));
        }
        validated.sort(Comparator.comparingInt(
                entry -> decode(entry).slotKey().residentSlot()
        ));
        receiptEntries = validated.toArray(ReceiptEntry[]::new);
    }

    private static CoopCaptureReceipt decode(@Nullable ReceiptEntry entry) {
        if (entry == null) {
            throw new IllegalStateException("Coop receipt entry is missing");
        }
        try {
            return new CoopCaptureReceipt(
                    OperationId.parse(entry.operationId),
                    ProfileId.parse(entry.profileId),
                    NpcAlias.parse(entry.sourceAlias),
                    CoopSlotKey.parse(entry.slotKey),
                    entry.receiptKey
            );
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Coop receipt entry is invalid",
                    failure
            );
        }
    }

    private static void validateSlot(@Nullable CoopSlotKey slotKey) {
        if (slotKey == null) {
            throw new IllegalArgumentException("Coop slot key is required");
        }
    }

    private static void requireSamePhysicalCoop(
            CoopSlotKey expected,
            CoopSlotKey actual
    ) {
        if (!expected.worldKey().equals(actual.worldKey())
                || !expected.coopId().equals(actual.coopId())
                || expected.x() != actual.x()
                || expected.y() != actual.y()
                || expected.z() != actual.z()) {
            throw new IllegalStateException(
                    "Coop receipt belongs to another physical coop"
            );
        }
    }

    @Override
    @Nonnull
    public TameworkCoopCaptureReceiptsComponent clone() {
        return new TameworkCoopCaptureReceiptsComponent(getReceiptEntries());
    }

    private static final class ReceiptEntry {
        private String operationId;
        private String profileId;
        private String sourceAlias;
        private String slotKey;
        private String receiptKey;

        private ReceiptEntry() {
        }

        private ReceiptEntry(CoopCaptureReceipt receipt) {
            operationId = receipt.operationId().toString();
            profileId = receipt.profileId().toString();
            sourceAlias = receipt.sourceAlias().toString();
            slotKey = receipt.slotKey().toString();
            receiptKey = receipt.receiptKey();
        }
    }
}
