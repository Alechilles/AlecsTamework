package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ArtifactMutation;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ArtifactState;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Exact one-slot Hytale inventory mutations for captured-item coop intake.
 *
 * <p>Hytale's compare-and-set transaction compares stackability rather than quantity. This
 * helper therefore freezes and compares the complete live stack immediately before every
 * replacement while still on the owning world thread.</p>
 */
final class HytaleCoopCapturedItemInventorySlot {
    private final ItemContainer container;
    private final short slot;
    private final HytaleCapturedArtifactAdapter artifacts;
    private final Function<CapturedArtifact, ItemStack> stackRestoration;

    HytaleCoopCapturedItemInventorySlot(
            ItemContainer container,
            short slot,
            HytaleCapturedArtifactAdapter artifacts
    ) {
        this(container, slot, artifacts, artifacts::toItemStack);
    }

    HytaleCoopCapturedItemInventorySlot(
            ItemContainer container,
            short slot,
            HytaleCapturedArtifactAdapter artifacts,
            Function<CapturedArtifact, ItemStack> stackRestoration
    ) {
        this.container = container;
        this.slot = slot;
        this.artifacts = artifacts;
        this.stackRestoration = stackRestoration;
    }

    ArtifactState probe(CoopCapturedItemSourceEvidence source) {
        try {
            return classify(container.getItemStack(slot), source);
        } catch (RuntimeException | LinkageError failure) {
            return ArtifactState.UNAVAILABLE;
        }
    }

    SlotMutation mark(CoopCapturedItemSourceEvidence source) {
        ItemStack current;
        try {
            current = container.getItemStack(slot);
        } catch (RuntimeException | LinkageError failure) {
            return SlotMutation.retryable(failure);
        }
        ArtifactState before = classify(current, source);
        if (before == ArtifactState.MARKED) {
            return SlotMutation.marked(false);
        }
        if (before != ArtifactState.SOURCE) {
            return before == ArtifactState.UNAVAILABLE
                    ? SlotMutation.retryable(null)
                    : SlotMutation.conflict(null);
        }
        try {
            if (!source.sourceArtifact().equals(
                    artifacts.toArtifact(current)
            )) {
                return SlotMutation.conflict(null);
            }
            ItemStack marked = stackRestoration.apply(
                    source.receiptArtifact()
            );
            boolean replaced = container.replaceItemStackInSlot(
                    slot, current, marked
            ).succeeded();
            ArtifactState after = probe(source);
            if (after == ArtifactState.MARKED) {
                return SlotMutation.marked(true);
            }
            return after == ArtifactState.SOURCE && !replaced
                    ? SlotMutation.retryable(null)
                    : SlotMutation.conflict(null);
        } catch (RuntimeException | LinkageError failure) {
            ArtifactState after = probe(source);
            return after == ArtifactState.MARKED
                    ? SlotMutation.marked(true)
                    : after == ArtifactState.SOURCE
                    ? SlotMutation.retryable(failure)
                    : SlotMutation.conflict(failure);
        }
    }

    SlotMutation retireMarked(CoopCapturedItemSourceEvidence source) {
        ItemStack current;
        try {
            current = container.getItemStack(slot);
        } catch (RuntimeException | LinkageError failure) {
            return SlotMutation.retryable(failure);
        }
        ArtifactState before = classify(current, source);
        if (before == ArtifactState.ABSENT) {
            return SlotMutation.absent(false);
        }
        if (before != ArtifactState.MARKED) {
            return before == ArtifactState.UNAVAILABLE
                    ? SlotMutation.retryable(null)
                    : SlotMutation.conflict(null);
        }
        try {
            if (!source.receiptArtifact().equals(
                    artifacts.toArtifact(current)
            )) {
                return SlotMutation.conflict(null);
            }
            boolean replaced = container.replaceItemStackInSlot(
                    slot, current, ItemStack.EMPTY
            ).succeeded();
            ArtifactState after = probe(source);
            if (after == ArtifactState.ABSENT) {
                return SlotMutation.absent(true);
            }
            return after == ArtifactState.MARKED && !replaced
                    ? SlotMutation.retryable(null)
                    : SlotMutation.conflict(null);
        } catch (RuntimeException | LinkageError failure) {
            ArtifactState after = probe(source);
            return after == ArtifactState.ABSENT
                    ? SlotMutation.absent(true)
                    : after == ArtifactState.MARKED
                    ? SlotMutation.retryable(failure)
                    : SlotMutation.conflict(failure);
        }
    }

    private ArtifactState classify(
            @Nullable ItemStack current,
            CoopCapturedItemSourceEvidence source
    ) {
        if (current == null || current.isEmpty()) {
            return ArtifactState.ABSENT;
        }
        if (artifacts.matches(current, source.receiptArtifact())) {
            return ArtifactState.MARKED;
        }
        if (artifacts.matches(current, source.sourceArtifact())) {
            return ArtifactState.SOURCE;
        }
        return ArtifactState.CONFLICT;
    }

    record SlotMutation(
            ArtifactMutation mutation,
            boolean changedThisCall
    ) {
        static SlotMutation marked(boolean changed) {
            return new SlotMutation(ArtifactMutation.marked(), changed);
        }

        static SlotMutation absent(boolean changed) {
            return new SlotMutation(ArtifactMutation.absent(), changed);
        }

        static SlotMutation retryable(Throwable cause) {
            return new SlotMutation(
                    ArtifactMutation.retryable(cause), false
            );
        }

        static SlotMutation conflict(Throwable cause) {
            return new SlotMutation(
                    ArtifactMutation.conflict(cause), false
            );
        }
    }
}
