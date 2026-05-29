package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Branch/tier companion talent tree browser and purchase page.
 */
public final class TameworkCompanionTalentsPage
        extends InteractiveCustomUIPage<TameworkCompanionTalentsPage.EventPayload> {
    public static final String UI_PATH = "TameworkCompanionTalentsPage.ui";
    private static final String BRANCH_SLOT_UI_PATH = "TameworkCompanionTalentTreeBranch.ui";
    private static final String NODE_SLOT_UI_PATH = "TameworkCompanionTalentTreeNode.ui";
    private static final String CONNECTOR_SLOT_UI_PATH = "TameworkCompanionTalentTreeConnector.ui";
    private static final String KEY_ACTION = "Action";
    private static final String ACTION_BACK = "Back";
    private static final String ACTION_RESET = "Reset";
    private static final String ACTION_SELECT_PREFIX = "Select:";
    private static final String ACTION_BUY_SELECTED = "BuySelected";
    public static final String STATE_PURCHASED = "Purchased";
    public static final String STATE_LOCKED = "Locked";
    public static final String STATE_UNAFFORDABLE = "Unaffordable";
    public static final String STATE_AVAILABLE = "Available";

    private final Supplier<PageData> dataSupplier;
    private final Function<String, String> purchaseCallback;
    private final Supplier<String> resetCallback;
    private final Runnable backCallback;
    private boolean navigationPending;
    private boolean handled;
    private String selectedTalentId;
    private String statusMessage;

    public TameworkCompanionTalentsPage(@Nonnull PlayerRef playerRef,
                                        @Nonnull Supplier<PageData> dataSupplier,
                                        @Nonnull Function<String, String> purchaseCallback,
                                        @Nonnull Supplier<String> resetCallback,
                                        @Nonnull Runnable backCallback) {
        super(playerRef, CustomPageLifetime.CanDismiss, EventPayload.CODEC);
        this.dataSupplier = dataSupplier;
        this.purchaseCallback = purchaseCallback;
        this.resetCallback = resetCallback;
        this.backCallback = backCallback;
        this.navigationPending = false;
        this.handled = false;
        this.selectedTalentId = null;
        this.statusMessage = null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        try {
            commandBuilder.append(UI_PATH);
            bindPage(commandBuilder, eventBuilder);
        } catch (Throwable throwable) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_build_failed",
                    throwable,
                    "page=TameworkCompanionTalentsPage"
            );
            throw throwable;
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull EventPayload data) {
        if (data == null || data.action == null || data.action.isBlank()) {
            return;
        }
        if (navigationPending) {
            return;
        }
        if (ACTION_BACK.equalsIgnoreCase(data.action)) {
            handled = true;
            navigationPending = true;
            navigateBackOnWorldThread();
            return;
        }
        if (ACTION_RESET.equalsIgnoreCase(data.action) && resetCallback != null) {
            statusMessage = resetCallback.get();
            sendRefreshUpdate();
            return;
        }
        if (data.action.startsWith(ACTION_SELECT_PREFIX)) {
            selectedTalentId = data.action.substring(ACTION_SELECT_PREFIX.length());
            sendRefreshUpdate();
            return;
        }
        if (ACTION_BUY_SELECTED.equalsIgnoreCase(data.action) && purchaseCallback != null) {
            String talentId = selectedTalentId;
            if (talentId == null || talentId.isBlank()) {
                statusMessage = "Choose a talent first.";
            } else {
                statusMessage = purchaseCallback.apply(talentId);
            }
            sendRefreshUpdate();
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (handled) {
            return;
        }
        handled = true;
        navigationPending = true;
        navigateBackOnWorldThread();
    }

    private void sendRefreshUpdate() {
        if (handled || navigationPending) {
            return;
        }
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        bindPage(commandBuilder, eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    private void navigateBackOnWorldThread() {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            navigationPending = false;
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null || store.getExternalData() == null) {
            navigationPending = false;
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            navigationPending = false;
            return;
        }
        world.execute(() -> {
            try {
                Ref<EntityStore> activeRef = playerRef.getReference();
                if (activeRef == null || !activeRef.isValid()) {
                    return;
                }
                if (backCallback != null) {
                    backCallback.run();
                }
            } finally {
                navigationPending = false;
            }
        });
    }

    private void bindPage(@Nonnull UICommandBuilder commandBuilder,
                          @Nonnull UIEventBuilder eventBuilder) {
        PageData data = getPageData();
        TalentTreeViewModel.TreeCanvas canvas = TalentTreeLayoutService.layout(data.entries(), selectedTalentId);
        selectedTalentId = canvas.selectedTalentId();
        TreeNodeEntry selectedEntry = data.findEntry(selectedTalentId);
        int branchCount = canvas.branches().size();
        int viewportWidth = TalentTreeLayoutService.resolveViewportWidth(branchCount);

        commandBuilder.setObject(
                "#TameworkCompanionTalentsRoot.Anchor",
                TalentTreeLayoutService.buildSizeAnchor(
                        TalentTreeLayoutService.resolveRootWidth(branchCount),
                        TalentTreeLayoutService.ROOT_HEIGHT
                )
        );
        commandBuilder.set("#TameworkCompanionTalentsTitle.Text", data.companionName());
        commandBuilder.set("#TameworkCompanionTalentsLevelSummary.Text", data.levelSummary());
        commandBuilder.set("#TameworkCompanionTalentsPointsSummary.Text", data.pointsSummary());
        commandBuilder.set(
                "#TameworkCompanionTalentsStatus.Text",
                statusMessage != null && !statusMessage.isBlank() ? statusMessage : data.statusText()
        );
        commandBuilder.set("#TameworkCompanionTalentsResetButton.Visible", data.canReset());
        commandBuilder.setObject(
                "#TalentTreeCanvas.Anchor",
                TalentTreeLayoutService.buildAnchor(0, 0, Math.max(canvas.width(), viewportWidth), canvas.height())
        );
        commandBuilder.setObject(
                "#TalentTreeViewport.Anchor",
                TalentTreeLayoutService.buildAnchor(0, 0, viewportWidth, TalentTreeLayoutService.VIEWPORT_HEIGHT)
        );
        commandBuilder.clear("#TalentConnectorLayer");
        commandBuilder.clear("#TalentBranchLayer");
        commandBuilder.clear("#TalentNodeLayer");

        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkCompanionTalentsBackButton",
                EventData.of(KEY_ACTION, ACTION_BACK),
                false
        );
        if (data.canReset()) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#TameworkCompanionTalentsResetButton",
                    EventData.of(KEY_ACTION, ACTION_RESET),
                    false
            );
        }
        bindBranchSlots(commandBuilder, canvas.branches());
        bindConnectorSlots(commandBuilder, canvas.connectors());
        bindNodeSlots(commandBuilder, eventBuilder, canvas.nodes());
        bindSelectedTalent(commandBuilder, eventBuilder, selectedEntry);
    }

    @Nonnull
    private PageData getPageData() {
        PageData provided = dataSupplier != null ? dataSupplier.get() : null;
        if (provided == null) {
            return PageData.empty();
        }
        return provided;
    }

    private void bindBranchSlots(@Nonnull UICommandBuilder commandBuilder,
                                 @Nonnull List<TalentTreeViewModel.BranchSlot> branchSlots) {
        for (TalentTreeViewModel.BranchSlot branch : branchSlots) {
            commandBuilder.append("#TalentBranchLayer", BRANCH_SLOT_UI_PATH);
            String selector = "#TalentBranchLayer[" + branch.slotIndex() + "]";
            commandBuilder.setObject(selector + ".Anchor", branch.anchor());
            commandBuilder.set(selector + " #TalentBranchName.Text", branch.name());
        }
    }

    private void bindNodeSlots(@Nonnull UICommandBuilder commandBuilder,
                               @Nonnull UIEventBuilder eventBuilder,
                               @Nonnull List<TalentTreeViewModel.NodeSlot> nodeSlots) {
        for (TalentTreeViewModel.NodeSlot node : nodeSlots) {
            commandBuilder.append("#TalentNodeLayer", NODE_SLOT_UI_PATH);
            String selector = "#TalentNodeLayer[" + node.slotIndex() + "]";
            TreeNodeEntry entry = node.entry();
            commandBuilder.setObject(selector + ".Anchor", node.anchor());
            bindNodeState(commandBuilder, selector, entry.state());
            commandBuilder.set(selector + " #TalentNodeSelected.Visible", node.selected());
            commandBuilder.set(selector + " #TalentNodeName.Text", entry.displayName());
            commandBuilder.set(selector + " #TalentNodeCost.Text", entry.pointCost() + " pt");
            commandBuilder.set(selector + " #TalentNodeState.Text", entry.state());
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    selector + " #TalentNodeButton",
                    EventData.of(KEY_ACTION, ACTION_SELECT_PREFIX + entry.id()),
                    false
            );
        }
    }

    private void bindConnectorSlots(@Nonnull UICommandBuilder commandBuilder,
                                    @Nonnull List<TalentTreeViewModel.ConnectorSlot> connectorSlots) {
        for (TalentTreeViewModel.ConnectorSlot connector : connectorSlots) {
            commandBuilder.append("#TalentConnectorLayer", CONNECTOR_SLOT_UI_PATH);
            String selector = "#TalentConnectorLayer[" + connector.slotIndex() + "]";
            bindConnectorSegment(commandBuilder, selector + " #TalentConnectorStart", connector.startAnchor(), connector.startVisible());
            bindConnectorSegment(commandBuilder, selector + " #TalentConnectorMiddle", connector.middleAnchor(), connector.middleVisible());
            bindConnectorSegment(commandBuilder, selector + " #TalentConnectorEnd", connector.endAnchor(), connector.endVisible());
        }
    }

    private void bindConnectorSegment(@Nonnull UICommandBuilder commandBuilder,
                                      @Nonnull String selector,
                                      @Nonnull Anchor anchor,
                                      boolean visible) {
        commandBuilder.set(selector + ".Visible", visible);
        commandBuilder.setObject(selector + ".Anchor", anchor);
    }

    private void bindSelectedTalent(@Nonnull UICommandBuilder commandBuilder,
                                    @Nonnull UIEventBuilder eventBuilder,
                                    @Nullable TreeNodeEntry selectedEntry) {
        boolean hasSelection = selectedEntry != null;
        commandBuilder.set("#TalentDetailEmpty.Visible", !hasSelection);
        commandBuilder.set("#TalentDetailContent.Visible", hasSelection);
        commandBuilder.set("#TalentDetailUnlockButton.Visible", hasSelection && selectedEntry.canPurchase());
        if (!hasSelection) {
            return;
        }
        commandBuilder.set("#TalentDetailName.Text", selectedEntry.displayName());
        commandBuilder.set("#TalentDetailBranch.Text", selectedEntry.branchName() + " - Tier " + selectedEntry.tier());
        commandBuilder.set("#TalentDetailDescription.Text", selectedEntry.description());
        commandBuilder.set("#TalentDetailStatus.Text", selectedEntry.status());
        commandBuilder.set("#TalentDetailRequirements.Text", resolveRequirementText(selectedEntry));
        commandBuilder.set("#TalentDetailEffects.Text", selectedEntry.effectSummary());
        if (selectedEntry.canPurchase()) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#TalentDetailUnlockButton",
                    EventData.of(KEY_ACTION, ACTION_BUY_SELECTED),
                    false
            );
        }
    }

    @Nonnull
    private String resolveRequirementText(@Nonnull TreeNodeEntry entry) {
        String level = "Requires Level " + entry.minLevel();
        if (entry.requiredTalentIds().isEmpty()) {
            return level;
        }
        return level + " and " + entry.requiredTalentIds().size() + " prerequisite";
    }

    private void bindNodeState(@Nonnull UICommandBuilder commandBuilder,
                               @Nonnull String selector,
                               @Nonnull String state) {
        commandBuilder.set(selector + " #TalentNodeLockedBackground.Visible", STATE_LOCKED.equals(state));
        commandBuilder.set(selector + " #TalentNodeUnaffordableBackground.Visible", STATE_UNAFFORDABLE.equals(state));
        commandBuilder.set(selector + " #TalentNodeAvailableBackground.Visible", STATE_AVAILABLE.equals(state));
        commandBuilder.set(selector + " #TalentNodePurchasedBackground.Visible", STATE_PURCHASED.equals(state));
    }

    /** Immutable page view model. */
    public record PageData(@Nonnull String companionName,
                           @Nonnull String levelSummary,
                           @Nonnull String pointsSummary,
                           @Nonnull String statusText,
                           boolean canReset,
                           @Nonnull List<TreeNodeEntry> entries) {
        public PageData {
            entries = List.copyOf(entries);
        }

        public static PageData empty() {
            return new PageData(
                    "Companion Talents",
                    "Companion unavailable",
                    "Talent Points: 0 available",
                    "No companion data is available.",
                    false,
                    List.of()
            );
        }

        @Nullable
        TreeNodeEntry findEntry(@Nullable String talentId) {
            if (talentId == null || talentId.isBlank()) {
                return null;
            }
            String normalized = talentId.trim().toLowerCase(Locale.ROOT);
            for (TreeNodeEntry entry : entries) {
                if (entry != null && entry.id().toLowerCase(Locale.ROOT).equals(normalized)) {
                    return entry;
                }
            }
            return null;
        }
    }

    /** One node in the branch/tier talent tree. */
    public record TreeNodeEntry(@Nonnull String id,
                                @Nonnull String branchName,
                                int tier,
                                @Nonnull String state,
                                @Nonnull String displayName,
                                @Nonnull String description,
                                @Nonnull String status,
                                int pointCost,
                                int minLevel,
                                @Nonnull List<String> requiredTalentIds,
                                @Nonnull String effectSummary,
                                boolean canPurchase) {
        public TreeNodeEntry {
            requiredTalentIds = requiredTalentIds == null ? List.of() : List.copyOf(requiredTalentIds);
            effectSummary = effectSummary == null || effectSummary.isBlank() ? "No passive effects" : effectSummary;
        }
    }

    /** UI payload sent from the page. */
    public static final class EventPayload {
        public static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(
                EventPayload.class,
                EventPayload::new
        )
                .append(
                        new KeyedCodec<>(KEY_ACTION, Codec.STRING),
                        (payload, value) -> payload.action = value,
                        payload -> payload.action
                )
                .add()
                .build();

        private String action;
    }
}
