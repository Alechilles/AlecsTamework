package com.alechilles.alecstamework.companion.migrate.retired;

import com.alechilles.alecstamework.util.Sha256Hash;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Retired 4.x inventory-operation receipts attached to one player. Nothing reads or writes the
 * receipts any more; the component stays registered, with its codec and id unchanged, so player
 * data saved by 4.x still loads.
 */
public final class TameworkInventoryOperationReceiptsComponent
        implements Component<EntityStore> {
    public static final int MAX_RECEIPTS = 32;
    private static final ReceiptEntry[] EMPTY = new ReceiptEntry[0];
    private static final BuilderCodec<ReceiptEntry> RECEIPT_CODEC =
            BuilderCodec.builder(ReceiptEntry.class, ReceiptEntry::new)
                    .<String>append(
                            new KeyedCodec<>("ReceiptKey", Codec.STRING),
                            (entry, value) -> entry.receiptKey = value,
                            entry -> entry.receiptKey
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("OperationId", Codec.STRING),
                            (entry, value) -> entry.operationId = value,
                            entry -> entry.operationId
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("OperationKind", Codec.STRING),
                            (entry, value) -> entry.operationKind = value,
                            entry -> entry.operationKind
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("PlanHash", Codec.STRING),
                            (entry, value) -> entry.planHash = value,
                            entry -> entry.planHash
                    ).add()
                    .<Long>append(
                            new KeyedCodec<>("InstalledAtMs", Codec.LONG),
                            (entry, value) -> entry.installedAtMs = value,
                            entry -> entry.installedAtMs
                    ).add()
                    .build();
    private static final ArrayCodec<ReceiptEntry> RECEIPT_ARRAY_CODEC =
            new ArrayCodec<>(RECEIPT_CODEC, ReceiptEntry[]::new);

    public static final BuilderCodec<
            TameworkInventoryOperationReceiptsComponent> CODEC =
            BuilderCodec.builder(
                    TameworkInventoryOperationReceiptsComponent.class,
                    TameworkInventoryOperationReceiptsComponent::new
            ).<ReceiptEntry[]>append(
                    new KeyedCodec<>("Receipts", RECEIPT_ARRAY_CODEC),
                    TameworkInventoryOperationReceiptsComponent::setEntries,
                    TameworkInventoryOperationReceiptsComponent::getEntries
            ).add().build();

    private ReceiptEntry[] entries = EMPTY;

    public TameworkInventoryOperationReceiptsComponent() {
    }

    private TameworkInventoryOperationReceiptsComponent(
            ReceiptEntry[] entries
    ) {
        setEntries(entries);
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
        if (values.length > MAX_RECEIPTS) {
            throw new IllegalArgumentException(
                    "Inventory operation receipt capacity is exceeded"
            );
        }
        ArrayList<InventoryOperationReceipt> validated =
                new ArrayList<>(values.length);
        HashSet<String> keys = new HashSet<>();
        for (ReceiptEntry value : values) {
            InventoryOperationReceipt receipt = decode(value);
            if (!keys.add(receipt.receiptKey())) {
                throw new IllegalArgumentException(
                        "Duplicate inventory operation receipt key"
                );
            }
            validated.add(receipt);
        }
        validated.sort(Comparator.naturalOrder());
        entries = new ReceiptEntry[validated.size()];
        for (int index = 0; index < validated.size(); index++) {
            entries[index] = new ReceiptEntry(validated.get(index));
        }
    }

    private static InventoryOperationReceipt decode(ReceiptEntry entry) {
        if (entry == null) {
            throw new IllegalStateException(
                    "Inventory operation receipt entry is missing"
            );
        }
        try {
            return new InventoryOperationReceipt(
                    entry.receiptKey,
                    OperationId.parse(entry.operationId),
                    new OperationKind(entry.operationKind),
                    Sha256Hash.parse(entry.planHash),
                    entry.installedAtMs
            );
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Inventory operation receipt entry is invalid",
                    failure
            );
        }
    }

    @Override
    @Nonnull
    public TameworkInventoryOperationReceiptsComponent clone() {
        return new TameworkInventoryOperationReceiptsComponent(getEntries());
    }

    private static final class ReceiptEntry {
        private String receiptKey;
        private String operationId;
        private String operationKind;
        private String planHash;
        private long installedAtMs;

        private ReceiptEntry() {
        }

        private ReceiptEntry(InventoryOperationReceipt receipt) {
            receiptKey = receipt.receiptKey();
            operationId = receipt.operationId().toString();
            operationKind = receipt.operationKind().toString();
            planHash = receipt.planHash().toString();
            installedAtMs = receipt.installedAtMs();
        }
    }
}
