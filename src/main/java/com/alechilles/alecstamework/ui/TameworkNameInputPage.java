package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
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
import java.util.function.Supplier;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Minimal naming page with a text field and Apply/Cancel actions.
 */
public final class TameworkNameInputPage extends InteractiveCustomUIPage<TameworkNameInputPage.NameInputEventData> {
    public static final String UI_PATH = "TameworkNameInputPage.ui";
    private static final String KEY_ACTION = "Action";
    private static final String KEY_NAME_INPUT = "@NpcNameInput";
    private static final String ACTION_CANCEL = "Cancel";
    private static final String ACTION_RANDOM = "Random";
    private static final String DEFAULT_TITLE_KEY = "tamework.ui.nameInput.title";
    private static final String DEFAULT_SUBTITLE_KEY = "tamework.ui.nameInput.subtitle";
    private static final String DEFAULT_PLACEHOLDER_KEY = "tamework.ui.nameInput.placeholder";

    private final String title;
    private final String subtitle;
    private final String placeholder;
    private final String initialValue;
    private final int maxLength;
    private final Runnable cancelCallback;
    private final Consumer<String> submitCallback;
    private final Supplier<String> randomNameSupplier;
    private final boolean randomEnabled;
    private boolean handled;

    public TameworkNameInputPage(@Nonnull PlayerRef playerRef,
                                 @Nullable String title,
                                 @Nullable String subtitle,
                                 @Nullable String placeholder,
                                 @Nullable String initialValue,
                                 int maxLength,
                                 @Nonnull Runnable cancelCallback,
                                 @Nonnull Consumer<String> submitCallback,
                                 @Nullable Supplier<String> randomNameSupplier) {
        super(playerRef, CustomPageLifetime.CanDismiss, NameInputEventData.CODEC);
        this.title = normalizeText(title, LocalizedText.resolve(playerRef, DEFAULT_TITLE_KEY));
        this.subtitle = normalizeText(subtitle, LocalizedText.resolve(playerRef, DEFAULT_SUBTITLE_KEY));
        this.placeholder = normalizeText(placeholder, LocalizedText.resolve(playerRef, DEFAULT_PLACEHOLDER_KEY));
        this.initialValue = initialValue != null ? initialValue : "";
        this.maxLength = Math.max(1, maxLength);
        this.cancelCallback = cancelCallback;
        this.submitCallback = submitCallback;
        this.randomNameSupplier = randomNameSupplier;
        this.randomEnabled = randomNameSupplier != null;
        this.handled = false;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append(UI_PATH);
        commandBuilder.set("#TameworkNameInputTitle.Text", title);
        commandBuilder.set("#TameworkNameInputSubtitle.Text", subtitle);
        commandBuilder.set("#TameworkNameInputField.PlaceholderText", placeholder);
        commandBuilder.set("#TameworkNameInputField.Value", initialValue);
        commandBuilder.set("#TameworkNameInputField.MaxLength", maxLength);
        commandBuilder.set("#TameworkNameRandomButton.Visible", randomEnabled);
        commandBuilder.set("#TameworkNameButtonSpacerBeforeApply.Visible", randomEnabled);

        bindEvents(eventBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull NameInputEventData data) {
        if (data == null) {
            handled = true;
            close();
            return;
        }
        if (ACTION_CANCEL.equalsIgnoreCase(data.action)) {
            handled = true;
            close();
            if (cancelCallback != null) {
                cancelCallback.run();
            }
            return;
        }
        if (ACTION_RANDOM.equalsIgnoreCase(data.action)) {
            if (randomNameSupplier == null) {
                return;
            }
            String randomName = randomNameSupplier.get();
            if (randomName == null || randomName.isBlank()) {
                return;
            }
            updateNameInputValue(randomName);
            return;
        }
        if (data.nameInput != null) {
            handled = true;
            close();
            if (submitCallback != null) {
                submitCallback.accept(data.nameInput);
            }
            return;
        }
        handled = true;
        close();
        if (cancelCallback != null) {
            cancelCallback.run();
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (handled) {
            return;
        }
        handled = true;
        if (cancelCallback != null) {
            cancelCallback.run();
        }
    }

    private static String normalizeText(@Nullable String value, @Nonnull String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private void bindEvents(@Nonnull UIEventBuilder eventBuilder) {
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkNameApplyButton",
                EventData.of(KEY_NAME_INPUT, "#TameworkNameInputField.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkNameCancelButton",
                EventData.of(KEY_ACTION, ACTION_CANCEL),
                false
        );
        if (randomEnabled) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#TameworkNameRandomButton",
                    EventData.of(KEY_ACTION, ACTION_RANDOM),
                    false
            );
        }
    }

    private void updateNameInputValue(@Nonnull String value) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        commandBuilder.set("#TameworkNameInputField.Value", value);
        bindEvents(eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    /** Event payload for the name input page. */
    public static final class NameInputEventData {
        public static final BuilderCodec<NameInputEventData> CODEC = BuilderCodec.builder(
                NameInputEventData.class,
                NameInputEventData::new
        )
            .append(
                new KeyedCodec<>(KEY_ACTION, Codec.STRING),
                (data, value) -> data.action = value,
                data -> data.action
            )
            .add()
            .append(
                new KeyedCodec<>(KEY_NAME_INPUT, Codec.STRING),
                (data, value) -> data.nameInput = value,
                data -> data.nameInput
            )
            .add()
            .build();

        private String action;
        private String nameInput;
    }
}
