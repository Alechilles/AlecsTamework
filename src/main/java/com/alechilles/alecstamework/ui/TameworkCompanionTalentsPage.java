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
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Simple paged companion talent browser and purchase page.
 */
public final class TameworkCompanionTalentsPage
        extends InteractiveCustomUIPage<TameworkCompanionTalentsPage.EventPayload> {
    public static final String UI_PATH = "TameworkCompanionTalentsPage.ui";
    private static final int PAGE_SIZE = 8;
    private static final String KEY_ACTION = "Action";
    private static final String KEY_TALENT_ID = "TalentId";
    private static final String ACTION_BACK = "Back";
    private static final String ACTION_PREV = "Prev";
    private static final String ACTION_NEXT = "Next";
    private static final String ACTION_RESET = "Reset";
    private static final String ACTION_BUY_PREFIX = "Buy:";

    private final Supplier<PageData> dataSupplier;
    private final Function<String, String> purchaseCallback;
    private final Supplier<String> resetCallback;
    private final Runnable backCallback;
    private boolean navigationPending;
    private boolean handled;
    private int pageIndex;
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
        this.pageIndex = 0;
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
        PageData currentData = getPageData();
        if (ACTION_PREV.equalsIgnoreCase(data.action)) {
            pageIndex = Math.max(0, pageIndex - 1);
            sendRefreshUpdate();
            return;
        }
        if (ACTION_NEXT.equalsIgnoreCase(data.action)) {
            pageIndex = Math.min(Math.max(0, resolvePageCount(currentData) - 1), pageIndex + 1);
            sendRefreshUpdate();
            return;
        }
        if (data.action.startsWith(ACTION_BUY_PREFIX) && purchaseCallback != null) {
            String talentId = data.action.substring(ACTION_BUY_PREFIX.length());
            statusMessage = purchaseCallback.apply(talentId);
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
        int totalPages = resolvePageCount(data);
        int clampedPageIndex = Math.max(0, Math.min(pageIndex, Math.max(0, totalPages - 1)));
        pageIndex = clampedPageIndex;
        int startIndex = clampedPageIndex * PAGE_SIZE;

        commandBuilder.set("#TameworkCompanionTalentsTitle.Text", data.companionName());
        commandBuilder.set("#TameworkCompanionTalentsLevelSummary.Text", data.levelSummary());
        commandBuilder.set("#TameworkCompanionTalentsPointsSummary.Text", data.pointsSummary());
        commandBuilder.set(
                "#TameworkCompanionTalentsStatus.Text",
                statusMessage != null && !statusMessage.isBlank() ? statusMessage : data.statusText()
        );
        commandBuilder.set(
                "#TameworkCompanionTalentsPageIndicator.Text",
                "Page " + (Math.max(0, clampedPageIndex) + 1) + "/" + Math.max(1, totalPages)
        );
        commandBuilder.set("#TameworkCompanionTalentsPrevButton.Visible", totalPages > 1 && clampedPageIndex > 0);
        commandBuilder.set(
                "#TameworkCompanionTalentsNextButton.Visible",
                totalPages > 1 && (clampedPageIndex + 1) < totalPages
        );
        commandBuilder.set("#TameworkCompanionTalentsResetButton.Visible", data.canReset());

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
        if (totalPages > 1 && clampedPageIndex > 0) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#TameworkCompanionTalentsPrevButton",
                    EventData.of(KEY_ACTION, ACTION_PREV),
                    false
            );
        }
        if (totalPages > 1 && (clampedPageIndex + 1) < totalPages) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#TameworkCompanionTalentsNextButton",
                    EventData.of(KEY_ACTION, ACTION_NEXT),
                    false
            );
        }

        List<TalentEntry> entries = data.entries();
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int entryIndex = startIndex + slot;
            TalentEntry entry = entryIndex < entries.size() ? entries.get(entryIndex) : null;
            String rowSelector = "#TalentRow" + slot;
            String nameSelector = rowSelector + " #TalentName" + slot;
            String descriptionSelector = rowSelector + " #TalentDescription" + slot;
            String statusSelector = rowSelector + " #TalentStatus" + slot;
            String buyButtonSelector = rowSelector + " #TalentBuyButton" + slot;
            boolean visible = entry != null;
            commandBuilder.set(rowSelector + ".Visible", visible);
            if (!visible) {
                continue;
            }
            commandBuilder.set(nameSelector + ".Text", entry.displayName());
            commandBuilder.set(descriptionSelector + ".Text", entry.description());
            commandBuilder.set(statusSelector + ".Text", entry.status());
            commandBuilder.set(buyButtonSelector + ".Visible", entry.canPurchase());
            if (entry.canPurchase()) {
                eventBuilder.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        buyButtonSelector,
                        EventData.of(KEY_ACTION, ACTION_BUY_PREFIX + entry.id()),
                        false
                );
            }
        }
    }

    @Nonnull
    private PageData getPageData() {
        PageData provided = dataSupplier != null ? dataSupplier.get() : null;
        if (provided == null) {
            return PageData.empty();
        }
        return provided;
    }

    private int resolvePageCount(@Nonnull PageData data) {
        int entryCount = data.entries().size();
        if (entryCount <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil((double) entryCount / (double) PAGE_SIZE));
    }

    /** Immutable page view model. */
    public record PageData(@Nonnull String companionName,
                           @Nonnull String levelSummary,
                           @Nonnull String pointsSummary,
                           @Nonnull String statusText,
                           boolean canReset,
                           @Nonnull List<TalentEntry> entries) {
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
    }

    /** One row in the paged talent list. */
    public record TalentEntry(@Nonnull String id,
                              @Nonnull String displayName,
                              @Nonnull String description,
                              @Nonnull String status,
                              boolean canPurchase) {
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
