package com.alechilles.alecstamework.persistence.runtime.player;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
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
 * Bounded persistent inventory-operation receipts attached to one player.
 *
 * <p>Entries are operation-neutral. Paid revival and captured-item coop
 * intake share this receipt-first durability boundary while their immutable
 * operation payloads remain the sole source of exact stack evidence.</p>
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

    /** Returns the exact durable receipt or {@code null} when absent. */
    @Nullable
    public InventoryOperationReceipt receiptFor(@Nonnull String receiptKey) {
        String key = requireKey(receiptKey);
        for (ReceiptEntry entry : entries) {
            InventoryOperationReceipt receipt = decode(entry);
            if (receipt.receiptKey().equals(key)) {
                return receipt;
            }
        }
        return null;
    }

    /**
     * Installs one receipt without evicting unresolved operation evidence.
     *
     * <p>An exact replay is idempotent; a same-key conflict fails closed.</p>
     */
    @Nonnull
    public TameworkInventoryOperationReceiptsComponent withReceipt(
            @Nonnull InventoryOperationReceipt receipt
    ) {
        if (receipt == null) {
            throw new IllegalArgumentException(
                    "Inventory operation receipt is required"
            );
        }
        ArrayList<InventoryOperationReceipt> updated =
                new ArrayList<>(entries.length + 1);
        boolean present = false;
        for (ReceiptEntry entry : entries) {
            InventoryOperationReceipt existing = decode(entry);
            if (existing.receiptKey().equals(receipt.receiptKey())) {
                if (!existing.equals(receipt)) {
                    throw new IllegalStateException(
                            "Inventory operation receipt key conflicts"
                    );
                }
                present = true;
            }
            updated.add(existing);
        }
        if (!present) {
            if (updated.size() >= MAX_RECEIPTS) {
                throw new IllegalStateException(
                        "Inventory operation receipt capacity is exhausted"
                );
            }
            updated.add(receipt);
        }
        return from(updated);
    }

    /** Removes only the exact completed operation's receipt key. */
    @Nonnull
    public TameworkInventoryOperationReceiptsComponent withoutReceipt(
            @Nonnull String receiptKey
    ) {
        String key = requireKey(receiptKey);
        ArrayList<InventoryOperationReceipt> updated =
                new ArrayList<>(entries.length);
        for (ReceiptEntry entry : entries) {
            InventoryOperationReceipt receipt = decode(entry);
            if (!receipt.receiptKey().equals(key)) {
                updated.add(receipt);
            }
        }
        return from(updated);
    }

    private static TameworkInventoryOperationReceiptsComponent from(
            ArrayList<InventoryOperationReceipt> receipts
    ) {
        receipts.sort(Comparator.naturalOrder());
        ReceiptEntry[] encoded = new ReceiptEntry[receipts.size()];
        for (int index = 0; index < receipts.size(); index++) {
            encoded[index] = new ReceiptEntry(receipts.get(index));
        }
        return new TameworkInventoryOperationReceiptsComponent(encoded);
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

    private static String requireKey(String receiptKey) {
        if (receiptKey == null || receiptKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Inventory operation receipt key is required"
            );
        }
        return receiptKey.trim();
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
