package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Render-ready companion talent tree layout data.
 */
final class TalentTreeViewModel {
    static final int MAX_BRANCH_SLOTS = 8;
    static final int MAX_NODE_SLOTS = 60;
    static final int MAX_CONNECTOR_SLOTS = 80;

    private TalentTreeViewModel() {
    }

    record TreeCanvas(int width,
                      int height,
                      @Nullable String selectedTalentId,
                      @Nonnull List<BranchSlot> branches,
                      @Nonnull List<NodeSlot> nodes,
                      @Nonnull List<ConnectorSlot> connectors) {
        TreeCanvas {
            branches = List.copyOf(branches);
            nodes = List.copyOf(nodes);
            connectors = List.copyOf(connectors);
        }
    }

    record BranchSlot(int slotIndex,
                      @Nonnull String name,
                      @Nonnull Anchor anchor) {
    }

    record NodeSlot(int slotIndex,
                    @Nonnull TameworkCompanionTalentsPage.TreeNodeEntry entry,
                    boolean selected,
                    @Nonnull Anchor anchor,
                    int centerX,
                    int topY,
                    int bottomY) {
    }

    record ConnectorSlot(int slotIndex,
                         @Nonnull String state,
                         @Nonnull Anchor startAnchor,
                         @Nonnull Anchor middleAnchor,
                         @Nonnull Anchor endAnchor,
                         boolean startVisible,
                         boolean middleVisible,
                         boolean endVisible) {
    }
}
