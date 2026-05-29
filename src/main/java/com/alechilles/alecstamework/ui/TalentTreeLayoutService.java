package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Computes a scrollable branch/tier tree layout for companion talents.
 */
final class TalentTreeLayoutService {
    static final int VIEWPORT_WIDTH = 820;
    static final int VIEWPORT_HEIGHT = 560;
    static final int ROOT_HEIGHT = 720;
    static final int MIN_VIEWPORT_WIDTH = 704;
    static final int MAX_VIEWPORT_WIDTH = 920;
    static final int DETAIL_PANEL_WIDTH = 260;
    static final int TREE_DETAIL_GAP = 14;
    static final int ROOT_EXTRA_WIDTH = 40;
    static final int CANVAS_PADDING_LEFT = 24;
    static final int CANVAS_PADDING_TOP = 34;
    static final int CANVAS_PADDING_RIGHT = 24;
    static final int CANVAS_PADDING_BOTTOM = 28;
    static final int BRANCH_WIDTH = 117;
    static final int BRANCH_GAP = 20;
    static final int BRANCH_LABEL_HEIGHT = 22;
    static final int NODE_WIDTH = 117;
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
                    resolveViewportWidth(0),
                    VIEWPORT_HEIGHT,
                    null,
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        String resolvedSelectedTalentId = resolveSelectedTalentId(safeEntries, selectedTalentId);
        ArrayList<BranchLayout> branchLayouts = resolveBranchLayouts(safeEntries);
        ArrayList<TalentTreeViewModel.BranchSlot> branchSlots = buildBranchSlots(branchLayouts);
        ArrayList<TalentTreeViewModel.NodeSlot> nodeSlots = new ArrayList<>();
        HashMap<String, TalentTreeViewModel.NodeSlot> slotsByTalentId = new HashMap<>();
        int maxRow = 0;
        for (BranchLayout branchLayout : branchLayouts) {
            for (TameworkCompanionTalentsPage.TreeNodeEntry entry : branchLayout.entries()) {
                if (nodeSlots.size() >= TalentTreeViewModel.MAX_NODE_SLOTS) {
                    break;
                }
                NodePosition position = branchLayout.positionsByTalentId().get(entry.id().toLowerCase(Locale.ROOT));
                if (position == null) {
                    continue;
                }
                maxRow = Math.max(maxRow, position.row());
                int x = branchLayout.left() + (int) Math.round(position.column() * (NODE_WIDTH + BRANCH_GAP));
                int y = CANVAS_PADDING_TOP
                        + BRANCH_LABEL_HEIGHT
                        + 14
                        + position.row() * (NODE_HEIGHT + ROW_GAP);
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
        }

        ArrayList<TalentTreeViewModel.ConnectorSlot> connectors = buildConnectors(nodeSlots, slotsByTalentId);
        int canvasWidth = resolveContentWidth(branchLayouts);
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
    private static ArrayList<BranchLayout> resolveBranchLayouts(
            @Nonnull List<TameworkCompanionTalentsPage.TreeNodeEntry> entries) {
        LinkedHashMap<String, ArrayList<TameworkCompanionTalentsPage.TreeNodeEntry>> entriesByBranch = new LinkedHashMap<>();
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry : entries) {
            String branchKey = normalize(entry.branchName());
            entriesByBranch.computeIfAbsent(branchKey, ignored -> new ArrayList<>()).add(entry);
        }
        ArrayList<BranchLayout> layouts = new ArrayList<>();
        int left = CANVAS_PADDING_LEFT;
        for (Map.Entry<String, ArrayList<TameworkCompanionTalentsPage.TreeNodeEntry>> branch : entriesByBranch.entrySet()) {
            BranchNodeLayout nodeLayout = resolveBranchNodeLayout(branch.getValue());
            int width = Math.max(
                    BRANCH_WIDTH,
                    (int) Math.round(nodeLayout.maxColumn() * (NODE_WIDTH + BRANCH_GAP)) + NODE_WIDTH
            );
            layouts.add(new BranchLayout(
                    branch.getKey(),
                    branch.getValue(),
                    nodeLayout.positionsByTalentId(),
                    left,
                    width
            ));
            left += width + BRANCH_GAP;
        }
        return layouts;
    }

    @Nonnull
    private static BranchNodeLayout resolveBranchNodeLayout(
            @Nonnull List<TameworkCompanionTalentsPage.TreeNodeEntry> entries) {
        HashMap<String, TameworkCompanionTalentsPage.TreeNodeEntry> entriesById = new HashMap<>();
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry : entries) {
            entriesById.put(entry.id().toLowerCase(Locale.ROOT), entry);
        }
        HashMap<String, ArrayList<TameworkCompanionTalentsPage.TreeNodeEntry>> childrenByParentId = new HashMap<>();
        ArrayList<TameworkCompanionTalentsPage.TreeNodeEntry> roots = new ArrayList<>();
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry : entries) {
            String primaryParentId = resolvePrimaryParentId(entry, entriesById);
            if (primaryParentId == null) {
                roots.add(entry);
            } else {
                childrenByParentId.computeIfAbsent(primaryParentId, ignored -> new ArrayList<>()).add(entry);
            }
        }
        for (ArrayList<TameworkCompanionTalentsPage.TreeNodeEntry> children : childrenByParentId.values()) {
            children.sort(entryComparator());
        }
        roots.sort(entryComparator());

        int minTier = entries.stream()
                .mapToInt(TameworkCompanionTalentsPage.TreeNodeEntry::tier)
                .min()
                .orElse(1);
        HashMap<String, NodePosition> positions = new HashMap<>();
        HashSet<String> assignedTalentIds = new HashSet<>();
        double nextColumn = 0.0;
        for (TameworkCompanionTalentsPage.TreeNodeEntry root : roots) {
            nextColumn = assignBranchSubtree(
                    root,
                    nextColumn,
                    minTier,
                    childrenByParentId,
                    positions,
                    assignedTalentIds,
                    new HashSet<>()
            );
        }
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry : entries) {
            if (assignedTalentIds.contains(entry.id().toLowerCase(Locale.ROOT))) {
                continue;
            }
            nextColumn = assignBranchSubtree(
                    entry,
                    nextColumn,
                    minTier,
                    childrenByParentId,
                    positions,
                    assignedTalentIds,
                    new HashSet<>()
            );
        }
        double maxColumn = positions.values().stream()
                .mapToDouble(NodePosition::column)
                .max()
                .orElse(0.0);
        return new BranchNodeLayout(positions, maxColumn);
    }

    @Nullable
    private static String resolvePrimaryParentId(
            @Nonnull TameworkCompanionTalentsPage.TreeNodeEntry entry,
            @Nonnull Map<String, TameworkCompanionTalentsPage.TreeNodeEntry> entriesById) {
        for (String requiredTalentId : entry.requiredTalentIds()) {
            if (requiredTalentId == null || requiredTalentId.isBlank()) {
                continue;
            }
            String normalized = requiredTalentId.toLowerCase(Locale.ROOT);
            if (normalized.equals(entry.id().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (entriesById.containsKey(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private static double assignBranchSubtree(
            @Nonnull TameworkCompanionTalentsPage.TreeNodeEntry entry,
            double startColumn,
            int minTier,
            @Nonnull Map<String, ArrayList<TameworkCompanionTalentsPage.TreeNodeEntry>> childrenByParentId,
            @Nonnull Map<String, NodePosition> positions,
            @Nonnull Set<String> assignedTalentIds,
            @Nonnull Set<String> visitingTalentIds) {
        String talentId = entry.id().toLowerCase(Locale.ROOT);
        if (assignedTalentIds.contains(talentId)) {
            return startColumn;
        }
        if (!visitingTalentIds.add(talentId)) {
            positions.put(talentId, new NodePosition(startColumn, resolveRow(entry, minTier)));
            assignedTalentIds.add(talentId);
            return startColumn + 1.0;
        }

        List<TameworkCompanionTalentsPage.TreeNodeEntry> children = childrenByParentId.getOrDefault(talentId, new ArrayList<>());
        double nextColumn = startColumn;
        Double firstChildColumn = null;
        Double lastChildColumn = null;
        for (TameworkCompanionTalentsPage.TreeNodeEntry child : children) {
            String childId = child.id().toLowerCase(Locale.ROOT);
            if (assignedTalentIds.contains(childId)) {
                continue;
            }
            nextColumn = assignBranchSubtree(
                    child,
                    nextColumn,
                    minTier,
                    childrenByParentId,
                    positions,
                    assignedTalentIds,
                    visitingTalentIds
            );
            NodePosition childPosition = positions.get(childId);
            if (childPosition == null) {
                continue;
            }
            if (firstChildColumn == null) {
                firstChildColumn = childPosition.column();
            }
            lastChildColumn = childPosition.column();
        }

        double column = firstChildColumn == null || lastChildColumn == null
                ? startColumn
                : (firstChildColumn + lastChildColumn) / 2.0;
        positions.put(talentId, new NodePosition(column, resolveRow(entry, minTier)));
        assignedTalentIds.add(talentId);
        visitingTalentIds.remove(talentId);
        return Math.max(nextColumn, startColumn + 1.0);
    }

    private static int resolveRow(@Nonnull TameworkCompanionTalentsPage.TreeNodeEntry entry, int minTier) {
        return Math.max(0, entry.tier() - minTier);
    }

    @Nonnull
    private static ArrayList<TalentTreeViewModel.BranchSlot> buildBranchSlots(@Nonnull List<BranchLayout> branchLayouts) {
        ArrayList<TalentTreeViewModel.BranchSlot> slots = new ArrayList<>();
        for (BranchLayout branch : branchLayouts) {
            if (slots.size() >= TalentTreeViewModel.MAX_BRANCH_SLOTS) {
                break;
            }
            slots.add(new TalentTreeViewModel.BranchSlot(
                    slots.size(),
                    titleCase(branch.branchKey()),
                    buildAnchor(branch.left(), CANVAS_PADDING_TOP, branch.width(), BRANCH_LABEL_HEIGHT)
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

    static int resolveViewportWidth(int branchCount) {
        return resolveViewportWidthForContent(resolveContentWidth(branchCount));
    }

    static int resolveViewportWidthForContent(int contentWidth) {
        int width = Math.max(1, contentWidth) + 16;
        return Math.max(MIN_VIEWPORT_WIDTH, Math.min(MAX_VIEWPORT_WIDTH, width));
    }

    static int resolveRootWidth(int branchCount) {
        return resolveRootWidthForViewport(resolveViewportWidth(branchCount));
    }

    static int resolveRootWidthForViewport(int viewportWidth) {
        return viewportWidth
                + DETAIL_PANEL_WIDTH
                + TREE_DETAIL_GAP
                + ROOT_EXTRA_WIDTH;
    }

    static int resolveContentWidth(int branchCount) {
        int columns = Math.max(1, branchCount);
        return CANVAS_PADDING_LEFT
                + columns * BRANCH_WIDTH
                + Math.max(0, columns - 1) * BRANCH_GAP
                + CANVAS_PADDING_RIGHT;
    }

    private static int resolveContentWidth(@Nonnull List<BranchLayout> branchLayouts) {
        if (branchLayouts.isEmpty()) {
            return resolveContentWidth(0);
        }
        BranchLayout last = branchLayouts.get(branchLayouts.size() - 1);
        return last.left() + last.width() + CANVAS_PADDING_RIGHT;
    }

    @Nonnull
    static Anchor buildAnchor(int left, int top, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(left));
        anchor.setTop(Value.of(top));
        anchor.setWidth(Value.of(Math.max(1, width)));
        anchor.setHeight(Value.of(Math.max(1, height)));
        return anchor;
    }

    @Nonnull
    static Anchor buildSizeAnchor(int width, int height) {
        Anchor anchor = new Anchor();
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

    private record BranchLayout(@Nonnull String branchKey,
                                @Nonnull List<TameworkCompanionTalentsPage.TreeNodeEntry> entries,
                                @Nonnull Map<String, NodePosition> positionsByTalentId,
                                int left,
                                int width) {
        private BranchLayout {
            entries = List.copyOf(entries);
            positionsByTalentId = Map.copyOf(positionsByTalentId);
        }
    }

    private record BranchNodeLayout(@Nonnull Map<String, NodePosition> positionsByTalentId,
                                    double maxColumn) {
        private BranchNodeLayout {
            positionsByTalentId = Map.copyOf(positionsByTalentId);
        }
    }

    private record NodePosition(double column, int row) {
    }
}
