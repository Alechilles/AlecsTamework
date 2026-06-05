package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Displays the current or last recorded location for a linked companion.
 */
public final class TameworkLinkedNpcLocationPage
        extends InteractiveCustomUIPage<TameworkLinkedNpcLocationPage.EventPayload> {
    public static final String UI_PATH = "TameworkLinkedNpcLocationPage.ui";

    private static final String KEY_ACTION = "Action";
    private static final String ACTION_CLOSE = "Close";

    private final String title;
    private final String worldName;
    private final String coordinates;

    public TameworkLinkedNpcLocationPage(@Nonnull PlayerRef playerRef,
                                         @Nonnull String title,
                                         @Nonnull String worldName,
                                         @Nonnull String coordinates) {
        super(playerRef, CustomPageLifetime.CanDismiss, EventPayload.CODEC);
        this.title = title;
        this.worldName = worldName;
        this.coordinates = coordinates;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        try {
            commandBuilder.append(UI_PATH);
            commandBuilder.set("#TameworkLinkedLocationTitle.Text", title);
            commandBuilder.set("#TameworkLinkedLocationWorldValue.Text", worldName);
            commandBuilder.set("#TameworkLinkedLocationCoordinatesInput.Value", coordinates);
            commandBuilder.set("#TameworkLinkedLocationCoordinatesInput.MaxLength", Math.max(64, coordinates.length() + 16));
            bindEvents(eventBuilder);
        } catch (Throwable throwable) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_build_failed",
                    throwable,
                    TameworkTelemetryContext.uiPage(
                            "TameworkLinkedNpcLocationPage",
                            "command_item",
                            "build",
                            "Failed to build linked NPC location page."
                    ).build()
            );
            throw throwable;
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull EventPayload data) {
        if (data == null || ACTION_CLOSE.equalsIgnoreCase(data.action)) {
            close();
        }
    }

    private void bindEvents(@Nonnull UIEventBuilder eventBuilder) {
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkLinkedLocationCloseButton",
                EventData.of(KEY_ACTION, ACTION_CLOSE),
                false
        );
    }

    /** Event payload for location-page button actions. */
    public static final class EventPayload {
        public static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(EventPayload.class, EventPayload::new)
                .<String>append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (x, v) -> x.action = v, x -> x.action)
                .add()
                .build();

        private String action;
    }
}
