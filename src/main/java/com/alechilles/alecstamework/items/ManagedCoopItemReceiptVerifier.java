package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceEvidence.CapturedItemSource;
import com.alechilles.alecstamework.items.ManagedCoopItemRetirementReceiptCodec.DecodeResult;
import com.alechilles.alecstamework.items.ManagedCoopItemRetirementReceiptCodec.Receipt;
import com.alechilles.alecstamework.items.ManagedCoopItemRetirementReceiptCodec.Status;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Pure validation for the exact receipt-only item allowed to finalize a captured-item source. */
final class ManagedCoopItemReceiptVerifier {
    enum VerificationStatus {
        VERIFIED,
        WAITING,
        CONFLICT
    }

    record Verification(@Nonnull VerificationStatus status,
                        @Nullable Receipt receipt,
                        @Nullable String detail) {
        Verification {
            Objects.requireNonNull(status, "status");
        }
    }

    private final ManagedCoopItemRetirementReceiptCodec receipts;

    ManagedCoopItemReceiptVerifier() {
        this(new ManagedCoopItemRetirementReceiptCodec());
    }

    ManagedCoopItemReceiptVerifier(ManagedCoopItemRetirementReceiptCodec receipts) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
    }

    @Nonnull
    Verification verify(@Nonnull RetirementReady ready,
                        @Nonnull CapturedItemSource source,
                        @Nullable String currentItemId,
                        int currentQuantity,
                        @Nullable String rawReceipt,
                        boolean capturedEnvelopePresent,
                        boolean vanillaCapturedMetadataPresent) {
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(source, "source");
        if (currentItemId == null) {
            return waiting("item_retirement_slot_empty");
        }
        if (!source.itemId().equals(currentItemId) || currentQuantity != 1) {
            return waiting("item_retirement_slot_changed");
        }
        if (capturedEnvelopePresent || vanillaCapturedMetadataPresent) {
            return waiting("filled_item_not_yet_retired");
        }
        DecodeResult decoded = receipts.decode(rawReceipt);
        if (decoded.status() == Status.NOT_FOUND) {
            return waiting("item_retirement_receipt_not_found");
        }
        if (decoded.status() != Status.FOUND || decoded.receipt() == null) {
            return conflict(decoded.detail());
        }
        Receipt receipt = decoded.receipt();
        if (!ready.operationId().equals(receipt.operationId())
                || !source.itemFingerprint().equals(receipt.itemFingerprint())) {
            return conflict("item_retirement_receipt_identity_mismatch");
        }
        return new Verification(VerificationStatus.VERIFIED, receipt, null);
    }

    private Verification waiting(String detail) {
        return new Verification(VerificationStatus.WAITING, null, detail);
    }

    private Verification conflict(@Nullable String detail) {
        return new Verification(
                VerificationStatus.CONFLICT,
                null,
                detail == null || detail.isBlank()
                        ? "item_retirement_receipt_invalid" : detail
        );
    }
}
