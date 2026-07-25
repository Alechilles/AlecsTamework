package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Comparator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Persistent bounded source receipts attached to one player.
 *
 * <p>There is exactly one receipt per hotbar slot. A newer admitted operation may replace that
 * slot only after shared operation-scope fencing excludes the older unfinished operation.</p>
 */
public final class TameworkCaptureSourceReceiptsComponent
        implements Component<EntityStore> {
    private static final ReceiptEntry[] EMPTY = new ReceiptEntry[0];
    private static final BuilderCodec<ReceiptEntry> RECEIPT_CODEC =
            BuilderCodec.builder(ReceiptEntry.class, ReceiptEntry::new)
                    .<String>append(
                            new KeyedCodec<>("ReceiptKey", Codec.STRING),
                            (entry, value) -> entry.receiptKey = value,
                            entry -> entry.receiptKey
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("ProfileId", Codec.STRING),
                            (entry, value) -> entry.profileId = value,
                            entry -> entry.profileId
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("TargetAlias", Codec.STRING),
                            (entry, value) -> entry.targetAlias = value,
                            entry -> entry.targetAlias
                    ).add()
                    .<Integer>append(
                            new KeyedCodec<>("Slot", Codec.INTEGER),
                            (entry, value) -> entry.slot = value,
                            entry -> entry.slot
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("SourceItemId", Codec.STRING),
                            (entry, value) -> entry.sourceItemId = value,
                            entry -> entry.sourceItemId
                    ).add()
                    .<Integer>append(
                            new KeyedCodec<>("Quantity", Codec.INTEGER),
                            (entry, value) -> entry.quantity = value,
                            entry -> entry.quantity
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("SourceFingerprint", Codec.STRING),
                            (entry, value) -> entry.sourceFingerprint = value,
                            entry -> entry.sourceFingerprint
                    ).add()
                    .build();
    private static final ArrayCodec<ReceiptEntry> RECEIPT_ARRAY_CODEC =
            new ArrayCodec<>(RECEIPT_CODEC, ReceiptEntry[]::new);

    public static final BuilderCodec<TameworkCaptureSourceReceiptsComponent>
            CODEC = BuilderCodec.builder(
            TameworkCaptureSourceReceiptsComponent.class,
            TameworkCaptureSourceReceiptsComponent::new
    ).<ReceiptEntry[]>append(
            new KeyedCodec<>("Receipts", RECEIPT_ARRAY_CODEC),
            TameworkCaptureSourceReceiptsComponent::setEntries,
            TameworkCaptureSourceReceiptsComponent::getEntries
    ).add().build();

    private ReceiptEntry[] entries = EMPTY;

    public TameworkCaptureSourceReceiptsComponent() {
    }

    private TameworkCaptureSourceReceiptsComponent(
            ReceiptEntry[] entries
    ) {
        setEntries(entries);
    }

    @Nullable
    CaptureSourceReceipt receiptFor(int slot) {
        for (ReceiptEntry entry : entries) {
            CaptureSourceReceipt receipt = decode(entry);
            if (receipt.slot() == slot) {
                return receipt;
            }
        }
        return null;
    }

    @Nonnull
    TameworkCaptureSourceReceiptsComponent withReceipt(
            @Nonnull CaptureSourceReceipt receipt
    ) {
        if (receipt == null) {
            throw new IllegalArgumentException(
                    "Capture source receipt is required"
            );
        }
        ArrayList<ReceiptEntry> updated =
                new ArrayList<>(entries.length + 1);
        for (ReceiptEntry entry : entries) {
            CaptureSourceReceipt existing = decode(entry);
            if (existing.slot() != receipt.slot()) {
                updated.add(new ReceiptEntry(existing));
            }
        }
        updated.add(new ReceiptEntry(receipt));
        updated.sort(Comparator.comparingInt(entry -> entry.slot));
        return new TameworkCaptureSourceReceiptsComponent(
                updated.toArray(ReceiptEntry[]::new)
        );
    }

    private ReceiptEntry[] getEntries() {
        ReceiptEntry[] copy = new ReceiptEntry[entries.length];
        for (int index = 0; index < entries.length; index++) {
            copy[index] = new ReceiptEntry(decode(entries[index]));
        }
        return copy;
    }

    private void setEntries(@Nullable ReceiptEntry[] values) {
        if (values == null || values.length == 0) {
            entries = EMPTY;
            return;
        }
        ArrayList<ReceiptEntry> validated =
                new ArrayList<>(values.length);
        java.util.HashSet<Integer> slots = new java.util.HashSet<>();
        for (ReceiptEntry entry : values) {
            CaptureSourceReceipt receipt = decode(entry);
            if (!slots.add(receipt.slot())) {
                throw new IllegalArgumentException(
                        "Duplicate capture receipt hotbar slot"
                );
            }
            validated.add(new ReceiptEntry(receipt));
        }
        validated.sort(Comparator.comparingInt(entry -> entry.slot));
        entries = validated.toArray(ReceiptEntry[]::new);
    }

    private static CaptureSourceReceipt decode(ReceiptEntry entry) {
        if (entry == null) {
            throw new IllegalStateException(
                    "Capture source receipt entry is missing"
            );
        }
        try {
            return new CaptureSourceReceipt(
                    entry.receiptKey,
                    ProfileId.parse(entry.profileId),
                    NpcAlias.parse(entry.targetAlias),
                    entry.slot,
                    entry.sourceItemId,
                    entry.quantity,
                    Sha256Hash.parse(entry.sourceFingerprint)
            );
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Capture source receipt entry is invalid",
                    failure
            );
        }
    }

    @Override
    @Nonnull
    public TameworkCaptureSourceReceiptsComponent clone() {
        return new TameworkCaptureSourceReceiptsComponent(getEntries());
    }

    private static final class ReceiptEntry {
        private String receiptKey;
        private String profileId;
        private String targetAlias;
        private int slot;
        private String sourceItemId;
        private int quantity;
        private String sourceFingerprint;

        private ReceiptEntry() {
        }

        private ReceiptEntry(CaptureSourceReceipt receipt) {
            receiptKey = receipt.receiptKey();
            profileId = receipt.profileId().toString();
            targetAlias = receipt.targetAlias().toString();
            slot = receipt.slot();
            sourceItemId = receipt.sourceItemId();
            quantity = receipt.quantity();
            sourceFingerprint = receipt.sourceFingerprint().toString();
        }
    }
}
