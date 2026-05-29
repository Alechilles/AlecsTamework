package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Computes a scrollable branch/tier tree layout for companion talents.
 */
final class TalentTreeLayoutService {
    static final int VIEWPORT_WIDTH = 820;
    static final int VIEWPORT_HEIGHT = 560;
    static final int CANVAS_PADDING_LEFT = 24;
    static final int CANVAS_PADDING_TOP = 34;
    static final int CANVAS_PADDING_RIGHT = 24;
    static final int CANVAS_PADDING_BOTTOM = 28;
    static final int BRANCH_WIDTH = 146;
    static final int BRANCH_GAP = 26;
    static final int BRANCH_LABEL_HEIGHT = 22;
    static final int NODE_WIDTH = 146;
    static final int NODE_HEIGHT = 66;
    static final int ROW_GAP = 38;
    static final int CONNECTOR_THICKNESS = 3;

    private TalentTreeLayoutService() {
    }

    @Nonnull
    static TalentTreeViewModel.TreeCanvas layout(@Nullable List<TameworkCompanionTalentsPage.TreeNodeEntry> entries,
                                                 @Nullable String selectedTalentId) {
        List<TameworkCompanionTalentsPage.TreeNodeEntry> safeEntries = entries == null ? List.of() : entries.stream()
                .filter(entry -> entry != null && entry.id() != null && !entry.id().isBlank())
                .sorted(entryComparator())
                .toList();
        if (safeEntries.isEmpty()) {
            return new TalentTreeViewModel.TreeCanvas(
                    VIEWPORT_WIDTH,
                    VIEWPORT_HEIGHT,
                    null,
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        String resolvedSelectedTalentId = resolveSelectedTalentId(safeEntries, selectedTalentId);
        Map<String, Integer> branchColumns = resolveBranchColumns(safeEntries);
        ArrayList<TalentTreeViewModel.BranchSlot> branchSlots = buildBranchSlots(branchColumns);
        ArrayList<TalentTreeViewModel.NodeSlot> nodeSlots = new ArrayList<>();
        HashMap<String, TalentTreeViewModel.NodeSlot> slotsByTalentId = new HashMap<>();
        HashMap<String, Integer> branchRows = new HashMap<>();
        int maxRow = 0;
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry : safeEntries) {
            if (nodeSlots.size() >= TalentTreeViewModel.MAX_NODE_SLOTS) {
                break;
            }
            String branchKey = normalize(entry.branchName());
            int column = branchColumns.getOrDefault(branchKey, 0);
            int row = branchRows.merge(branchKey, 1, Integer::sum) - 1;
            maxRow = Math.max(maxRow, row);
            int x = CANVAS_PADDING_LEFT + column * (BRANCH_WIDTH + BRANCH_GAP);
            int y = CANVAS_PADDING_TOP + BRANCH_LABEL_HEIGHT + 14 + row * (NODE_HEIGHT + ROW_GAP);
            TalentTreeViewModel.NodeSlot slot = new TalentTreeViewModel.NodeSlot(
                    nodeSlots.size(),
                    entry,
                    entry.id().equalsIgnoreCase(resolvedSelectedTalentId),
                    buildAnchor(x, y, NODE_WIDTH, NODE_HEIGHT),
                    x + NODE_WIDTH / 2,
                    y,
                    y + NODE_HEIGHT
            );
            nodeSlots.add(slot);
            slotsByTalentId.put(entry.id().toLowerCase(Locale.ROOT), slot);
        }

        ArrayList<TalentTreeViewModel.ConnectorSlot> connectors = buildConnectors(nodeSlots, slotsByTalentId);
        int canvasWidth = Math.max(
                VIEWPORT_WIDTH,
                CANVAS_PADDING_LEFT
                        + Math.max(1, branchColumns.size()) * BRANCH_WIDTH
                        + Math.max(0, branchColumns.size() - 1) * BRANCH_GAP
                        + CANVAS_PADDING_RIGHT
        );
        int canvasHeight = Math.max(
                VIEWPORT_HEIGHT,
                CANVAS_PADDING_TOP
                        + BRANCH_LABEL_HEIGHT
                        + 14
                        + (maxRow + 1) * NODE_HEIGHT
                        + maxRow * ROW_GAP
                        + CANVAS_PADDING_BOTTOM
        );
        return new TalentTreeViewModel.TreeCanvas(
                canvasWidth,
                canvasHeight,
                resolvedSelectedTalentId,
                branchSlots,
                nodeSlots,
                connectors
        );
    }

    private static Comparator<TameworkCompanionTalentsPage.TreeNodeEntry> entryComparator() {
        return Comparator.comparing(
                        (TameworkCompanionTalentsPage.TreeNodeEntry entry) -> normalize(entry.branchName())
                )
                .thenComparingInt(TameworkCompanionTalentsPage.TreeNodeEntry::tier)
                .thenComparingInt(entry -> entry.requiredTalentIds().size())
                .thenComparing(TameworkCompanionTalentsPage.TreeNodeEntry::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TameworkCompanionTalentsPage.TreeNodeEntry::id, String.CASE_INSENSITIVE_ORDER);
    }

    @Nonnull
    private static String resolveSelectedTalentId(@Nonnull List<TameworkCompanionTalentsPage.TreeNodeEntry> entries,
                                                  @Nullable String selectedTalentId) {
        if (selectedTalentId != null && !selectedTalentId.isBlank()) {
            for (TameworkCompanionTalentsPage.TreeNodeEntry entry : entries) {
                if (entry.id().equalsIgnoreCase(selectedTalentId.trim())) {
                    return entry.id();
                }
            }
        }
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry : entries) {
            if (TameworkCompanionTalentsPage.STATE_AVAILABLE.equals(entry.state())) {
                return entry.id();
            }
        }
        return entries.get(0).id();
    }

    @Nonnull
    private static Map<String, Integer> resolveBranchColumns(
            @Nonnull List<TameworkCompanionTalentsPage.TreeNodeEntry> entries) {
        LinkedHashMap<String, Integer> branchColumns = new LinkedHashMap<>();
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry : entries) {
            String branchKey = normalize(entry.branchName());
            if (branchColumns.containsKey(branchKey)) {
                continue;
            }
            branchColumns.put(branchKey, branchColumns.size());
        }
        return branchColumns;
    }

    @Nonnull
    private static ArrayList<TalentTreeViewModel.BranchSlot> buildBranchSlots(@Nonnull Map<String, Integer> branchColumns) {
        ArrayList<TalentTreeViewModel.BranchSlot> slots = new ArrayList<>();
        for (Map.Entry<String, Integer> branch : branchColumns.entrySet()) {
            if (slots.size() >= TalentTreeViewModel.MAX_BRANCH_SLOTS) {
                break;
            }
            int x = CANVAS_PADDING_LEFT + branch.getValue() * (BRANCH_WIDTH + BRANCH_GAP);
            slots.add(new TalentTreeViewModel.BranchSlot(
                    slots.size(),
                    titleCase(branch.getKey()),
                    buildAnchor(x, CANVAS_PADDING_TOP, BRANCH_WIDTH, BRANCH_LABEL_HEIGHT)
            ));
        }
        return slots;
    }

    @Nonnull
    private static ArrayList<TalentTreeViewModel.ConnectorSlot> buildConnectors(
            @Nonnull List<TalentTreeViewModel.NodeSlot> nodeSlots,
            @Nonnull Map<String, TalentTreeViewModel.NodeSlot> slotsByTalentId) {
        ArrayList<TalentTreeViewModel.ConnectorSlot> connectors = new ArrayList<>();
        for (TalentTreeViewModel.NodeSlot child : nodeSlots) {
            for (String requiredTalentId : child.entry().requiredTalentIds()) {
                if (requiredTalentId == null || requiredTalentId.isBlank()) {
                    continue;
                }
                TalentTreeViewModel.NodeSlot parent = slotsByTalentId.get(requiredTalentId.toLowerCase(Locale.ROOT));
                if (parent == null) {
                    continue;
                }
                if (connectors.size() >= TalentTreeViewModel.MAX_CONNECTOR_SLOTS) {
                    return connectors;
                }
                connectors.add(buildConnector(connectors.size(), parent, child));
            }
        }
        return connectors;
    }

    @Nonnull
    private static TalentTreeViewModel.ConnectorSlot buildConnector(int slotIndex,
                                                                    @Nonnull TalentTreeViewModel.NodeSlot parent,
                                                                    @Nonnull TalentTreeViewModel.NodeSlot child) {
        int parentX = parent.centerX();
        int childX = child.centerX();
        int startY = parent.bottomY();
        int endY = child.topY();
        int midY = startY + Math.max(CONNECTOR_THICKNESS, (endY - startY) / 2);
        Anchor startAnchor = buildLineAnchor(parentX, startY, parentX, midY);
        Anchor middleAnchor = buildLineAnchor(parentX, midY, childX, midY);
        Anchor endAnchor = buildLineAnchor(childX, midY, childX, endY);
        boolean hasMiddle = Math.abs(parentX - childX) > CONNECTOR_THICKNESS;
        String state = TameworkCompanionTalentsPage.STATE_PURCHASED.equals(parent.entry().state())
                ? child.entry().state()
                : TameworkCompanionTalentsPage.STATE_LOCKED;
        return new TalentTreeViewModel.ConnectorSlot(
                slotIndex,
                state,
                startAnchor,
                middleAnchor,
                endAnchor,
                startAnchor != null,
                hasMiddle,
                endAnchor != null
        );
    }

    @Nonnull
    static Anchor buildAnchor(int left, int top, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(Math.max(0, left)));
        anchor.setTop(Value.of(Math.max(0, top)));
        anchor.setWidth(Value.of(Math.max(1, width)));
        anchor.setHeight(Value.of(Math.max(1, height)));
        return anchor;
    }

    @Nonnull
    private static Anchor buildLineAnchor(int x1, int y1, int x2, int y2) {
        int left = Math.min(x1, x2);
        int top = Math.min(y1, y2);
        int width = Math.abs(x2 - x1);
        int height = Math.abs(y2 - y1);
        if (width <= CONNECTOR_THICKNESS) {
            width = CONNECTOR_THICKNESS;
            left -= CONNECTOR_THICKNESS / 2;
        }
        if (height <= CONNECTOR_THICKNESS) {
            height = CONNECTOR_THICKNESS;
            top -= CONNECTOR_THICKNESS / 2;
        }
        return buildAnchor(left, top, width, height);
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? "general" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static String titleCase(@Nullable String value) {
        String normalized = normalize(value);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
