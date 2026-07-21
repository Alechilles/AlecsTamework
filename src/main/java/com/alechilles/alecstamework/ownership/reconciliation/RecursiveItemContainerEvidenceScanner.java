package com.alechilles.alecstamework.ownership.reconciliation;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemStackItemContainer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Scans item containers recursively with explicit depth, allocation, and identity-cycle bounds.
 */
public final class RecursiveItemContainerEvidenceScanner {
    public static final int DEFAULT_MAX_DEPTH = 16;
    public static final int DEFAULT_MAX_CONTAINERS = 4_096;
    public static final int DEFAULT_MAX_STACKS = 262_144;

    private final LegacyCapturedItemEvidenceReader evidenceReader;
    private final int maxDepth;
    private final int maxContainers;
    private final int maxStacks;

    public RecursiveItemContainerEvidenceScanner(
            @Nonnull LegacyCapturedItemEvidenceReader evidenceReader
    ) {
        this(evidenceReader, DEFAULT_MAX_DEPTH, DEFAULT_MAX_CONTAINERS, DEFAULT_MAX_STACKS);
    }

    public RecursiveItemContainerEvidenceScanner(
            @Nonnull LegacyCapturedItemEvidenceReader evidenceReader,
            int maxDepth,
            int maxContainers,
            int maxStacks
    ) {
        this.evidenceReader = Objects.requireNonNull(evidenceReader, "evidenceReader");
        this.maxDepth = requirePositive(maxDepth, "maxDepth");
        this.maxContainers = requirePositive(maxContainers, "maxContainers");
        this.maxStacks = requirePositive(maxStacks, "maxStacks");
    }

    @Nonnull
    public Result scan(@Nullable ItemContainer root,
                       @Nonnull String evidencePrefix,
                       @Nonnull String source) {
        if (root == null) {
            return new Result(List.of(), 0, 0, 0);
        }
        ScanState state = new ScanState();
        scanContainer(root, requireText(evidencePrefix, "evidencePrefix"), requireText(source, "source"), 0, state);
        return new Result(List.copyOf(state.evidence), state.containers, state.stacks, state.cyclesSkipped);
    }

    private void scanContainer(@Nonnull ItemContainer container,
                               @Nonnull String path,
                               @Nonnull String source,
                               int depth,
                               @Nonnull ScanState state) {
        if (depth > maxDepth) {
            throw new IllegalStateException("Nested item container depth exceeded " + maxDepth + ".");
        }
        if (state.seenContainers.put(container, Boolean.TRUE) != null) {
            state.cyclesSkipped++;
            return;
        }
        if (++state.containers > maxContainers) {
            throw new IllegalStateException("Nested item container count exceeded " + maxContainers + ".");
        }
        container.forEach((slot, stack) -> scanStack(container, slot, stack, path, source, depth, state));
    }

    private void scanStack(@Nonnull ItemContainer parent,
                           short slot,
                           @Nullable ItemStack stack,
                           @Nonnull String path,
                           @Nonnull String source,
                           int depth,
                           @Nonnull ScanState state) {
        if (stack == null || ItemStack.isEmpty(stack)) {
            return;
        }
        if (++state.stacks > maxStacks) {
            throw new IllegalStateException("Nested item stack count exceeded " + maxStacks + ".");
        }
        String stackPath = path + "/slot-" + slot;
        state.evidence.addAll(evidenceReader.readAll(stack, stackPath, source));
        ItemStackItemContainer nested = ItemStackItemContainer.getContainer(parent, slot);
        if (nested != null) {
            scanContainer(nested, stackPath + "/nested", source, depth + 1, state);
        }
    }

    private static int requirePositive(int value, @Nonnull String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive.");
        }
        return value;
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }

    public record Result(@Nonnull List<CompanionPopulationEvidence> evidence,
                         int visitedContainers,
                         int visitedStacks,
                         int cyclesSkipped) {
    }

    private static final class ScanState {
        private final Map<ItemContainer, Boolean> seenContainers = new IdentityHashMap<>();
        private final List<CompanionPopulationEvidence> evidence = new ArrayList<>();
        private int containers;
        private int stacks;
        private int cyclesSkipped;
    }
}
