package com.alechilles.alecstamework.ui;

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
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Login-time popup that explains why server operators should review `/tw settings`.
 */
public final class TameworkSettingsAnnouncementPage
        extends InteractiveCustomUIPage<TameworkSettingsAnnouncementPage.EventPayload> {
    public static final String UI_PATH = "TameworkSettingsAnnouncementPage.ui";

    private static final String KEY_ACTION = "Action";
    private static final String KEY_SUPPRESS = "@SuppressUntilNextAnnouncement";
    private static final String ACTION_REVIEW = "Review";
    private static final String ACTION_LATER = "Later";

    private final String title;
    private final String subtitle;
    private final String bodyText;
    private final String optOutLabel;
    private final Consumer<Boolean> reviewCallback;
    private final Consumer<Boolean> dismissCallback;
    private boolean suppressUntilNextAnnouncement;
    private boolean handled;
    private boolean dismissed;
    private boolean navigationPending;

    public TameworkSettingsAnnouncementPage(@Nonnull PlayerRef playerRef,
                                            @Nullable String title,
                                            @Nullable String subtitle,
                                            @Nullable String bodyText,
                                            @Nullable String optOutLabel,
                                            @Nullable Consumer<Boolean> reviewCallback,
                                            @Nullable Consumer<Boolean> dismissCallback) {
        super(playerRef, CustomPageLifetime.CanDismiss, EventPayload.CODEC);
        this.title = normalize(title, "Important Alec's Tamework Update");
        this.subtitle = normalize(
                subtitle,
                "New features, settings, or updates have been added to Tamework that require your attention."
        );
        this.bodyText = normalize(
                bodyText,
                "As of Alec's Tamework v2.8.0+, many important settings were migrated to the \"/tw settings\" menu."
        );
        this.optOutLabel = normalize(
                optOutLabel,
                "Don't show again until next announcement"
        );
        this.reviewCallback = reviewCallback;
        this.dismissCallback = dismissCallback;
        this.suppressUntilNextAnnouncement = false;
        this.handled = false;
        this.dismissed = false;
        this.navigationPending = false;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append(UI_PATH);
        commandBuilder.set("#TwSettingsAnnouncementTitle.Text", title);
        commandBuilder.set("#TwSettingsAnnouncementSubtitle.Text", subtitle);
        commandBuilder.set("#TwSettingsAnnouncementBody.Text", bodyText);
        commandBuilder.set("#TwSettingsAnnouncementOptOutLabel.Text", optOutLabel);
        commandBuilder.set("#TwSettingsAnnouncementOptOutCheck.Value", suppressUntilNextAnnouncement);
        bindEvents(eventBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull EventPayload data) {
        if (data.suppressUntilNextAnnouncement != null) {
            suppressUntilNextAnnouncement = data.suppressUntilNextAnnouncement;
        }
        if (ACTION_REVIEW.equalsIgnoreCase(data.action)) {
            requestNavigate(reviewCallback);
            return;
        }
        if (ACTION_LATER.equalsIgnoreCase(data.action)) {
            requestClose(dismissCallback);
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (dismissed || handled) {
            return;
        }
        dismissed = true;
        if (dismissCallback != null) {
            dismissCallback.accept(suppressUntilNextAnnouncement);
        }
    }

    private void bindEvents(@Nonnull UIEventBuilder eventBuilder) {
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#TwSettingsAnnouncementOptOutCheck",
                EventData.of(KEY_SUPPRESS, "#TwSettingsAnnouncementOptOutCheck.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TwSettingsAnnouncementLaterButton",
                EventData.of(KEY_ACTION, ACTION_LATER)
                        .append(KEY_SUPPRESS, "#TwSettingsAnnouncementOptOutCheck.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TwSettingsAnnouncementReviewButton",
                EventData.of(KEY_ACTION, ACTION_REVIEW)
                        .append(KEY_SUPPRESS, "#TwSettingsAnnouncementOptOutCheck.Value"),
                false
        );
    }

    private void requestClose(@Nullable Consumer<Boolean> callback) {
        if (dismissed || handled) {
            return;
        }
        handled = true;
        close();
        if (callback != null) {
            callback.accept(suppressUntilNextAnnouncement);
        }
    }

    private void requestNavigate(@Nullable Consumer<Boolean> callback) {
        if (dismissed || handled || navigationPending || callback == null) {
            return;
        }
        handled = true;
        navigationPending = true;
        dispatchAfterUiDrain(() -> {
            try {
                callback.accept(suppressUntilNextAnnouncement);
            } finally {
                navigationPending = false;
            }
        });
    }

    private void dispatchAfterUiDrain(@Nonnull Runnable action) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null || store.getExternalData() == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        world.execute(() -> {
            Ref<EntityStore> activeRef = playerRef.getReference();
            if (activeRef == null || !activeRef.isValid()) {
                return;
            }
            action.run();
        });
    }

    @Nonnull
    private static String normalize(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Event payload for settings announcement page actions. */
    public static final class EventPayload {
        public static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(EventPayload.class, EventPayload::new)
                .<String>append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (x, v) -> x.action = v, x -> x.action).add()
                .<Boolean>append(new KeyedCodec<>(KEY_SUPPRESS, Codec.BOOLEAN),
                        (x, v) -> x.suppressUntilNextAnnouncement = v,
                        x -> x.suppressUntilNextAnnouncement)
                .add()
                .build();

        private String action;
        private Boolean suppressUntilNextAnnouncement;
    }
}
