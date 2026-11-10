package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.companioncard.CompanionCardFBehaviour;
import com.alechilles.alecstamework.companioncard.CompanionCardPreferences;
import com.alechilles.alecstamework.items.components.TameworkInspectPreferencesComponent;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Your settings: what each player chooses for themselves, opened from the gear in the companions window. It holds
 * what F on a companion does, whether Companion Inspect moves the camera, the companion HUD and card positions and,
 * for players with settings access,
 * the admin settings. Choices apply at once; Done returns to the page that opened it and Esc just closes.
 */
public final class TameworkPlayerSettingsPage extends InteractiveCustomUIPage<TameworkPlayerSettingsPage.EventPayload> {
    public static final String UI_PATH = "TameworkPlayerSettingsPage.ui";

    private static final String STYLES = "TameworkSlateStyles.ui";
    private static final String KEY_ACTION = "Action";
    private static final String ACTION_F_CARD = "f:card";
    private static final String ACTION_F_CYCLE = "f:cycle";
    private static final String ACTION_F_DEFAULT = "f:default";
    private static final String ACTION_HUD = "hud";
    private static final String ACTION_CARD = "card";
    private static final String ACTION_CAMERA = "camera";
    private static final String ACTION_CLOSE_AFTER = "closeAfter";
    private static final String ACTION_SERVER = "server";
    private static final String ACTION_DONE = "done";

    @Nullable
    private final Runnable returnTo;

    /** @param returnTo reopens the page this was opened from (Done), or {@code null} to just close */
    public TameworkPlayerSettingsPage(@Nonnull PlayerRef playerRef, @Nullable Runnable returnTo) {
        super(playerRef, CustomPageLifetime.CanDismiss, EventPayload.CODEC);
        this.returnTo = returnTo;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append(UI_PATH);
        boolean admin = canOpenServerSettings();
        commandBuilder.set("#TameworkPlayerSettingsServer.Visible", admin);
        bindChoice(commandBuilder, ref, store);
        if (CompanionCardPreferences.playerChoiceAllowed()) {
            bind(eventBuilder, "#TameworkPlayerSettingsFCard", ACTION_F_CARD);
            bind(eventBuilder, "#TameworkPlayerSettingsFCycle", ACTION_F_CYCLE);
            bind(eventBuilder, "#TameworkPlayerSettingsFDefault", ACTION_F_DEFAULT);
        }
        bind(eventBuilder, "#TameworkPlayerSettingsCamera", ACTION_CAMERA);
        bind(eventBuilder, "#TameworkPlayerSettingsCloseAfter", ACTION_CLOSE_AFTER);
        bind(eventBuilder, "#TameworkPlayerSettingsHudMove", ACTION_HUD);
        bind(eventBuilder, "#TameworkPlayerSettingsCardMove", ACTION_CARD);
        if (admin) {
            bind(eventBuilder, "#TameworkPlayerSettingsServer", ACTION_SERVER);
        }
        bind(eventBuilder, "#TameworkPlayerSettingsDone", ACTION_DONE);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull EventPayload data) {
        String action = data == null || data.action == null ? "" : data.action;
        switch (action) {
            case ACTION_F_CARD -> choose(ref, store, CompanionCardFBehaviour.CARD);
            case ACTION_F_CYCLE -> choose(ref, store, CompanionCardFBehaviour.CYCLE);
            case ACTION_F_DEFAULT -> choose(ref, store, null);
            case ACTION_CAMERA -> {
                TameworkInspectPreferencesComponent.setCameraEnabled(ref, store,
                        !TameworkInspectPreferencesComponent.cameraEnabled(ref, store));
                UICommandBuilder commands = new UICommandBuilder();
                bindChoice(commands, ref, store);
                sendUpdate(commands, new UIEventBuilder(), false);
            }
            case ACTION_CLOSE_AFTER -> {
                TameworkInspectPreferencesComponent.setCloseAfterCommand(ref, store,
                        !TameworkInspectPreferencesComponent.closeAfterCommand(ref, store));
                UICommandBuilder commands = new UICommandBuilder();
                bindChoice(commands, ref, store);
                sendUpdate(commands, new UIEventBuilder(), false);
            }
            case ACTION_HUD -> openPlacement(ref, store, TameworkHudPlacementPage.Mode.PLAYER);
            case ACTION_CARD -> openPlacement(ref, store, TameworkHudPlacementPage.Mode.CARD);
            case ACTION_SERVER -> {
                // The button is only shown with access; the service checks again, as a stale page may remain open.
                String error = TameworkSettingsPageService.openSettingsPage(ref, store, "command_menu", "settings_button",
                        this::reopen);
                if (error != null) {
                    playerRef.sendMessage(Message.raw(error));
                }
            }
            case ACTION_DONE -> {
                if (returnTo != null) {
                    returnTo.run();
                }
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null && player.getPageManager().getCustomPage() == this) {
                    close();
                }
            }
            default -> {
                // Unknown actions are ignored.
            }
        }
    }

    /** Saves the F choice ({@code null} follows the server default). A lock set after the page opened still wins. */
    private void choose(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                        @Nullable CompanionCardFBehaviour behaviour) {
        if (CompanionCardPreferences.playerChoiceAllowed()) {
            CompanionCardPreferences.setOverride(ref, store, behaviour);
        }
        UICommandBuilder commands = new UICommandBuilder();
        bindChoice(commands, ref, store);
        sendUpdate(commands, new UIEventBuilder(), false);
    }

    private void bindChoice(@Nonnull UICommandBuilder commands, @Nonnull Ref<EntityStore> ref,
                            @Nonnull Store<EntityStore> store) {
        boolean allowed = CompanionCardPreferences.playerChoiceAllowed();
        CompanionCardFBehaviour saved = allowed ? CompanionCardPreferences.savedOverride(ref, store) : null;
        choiceButton(commands, "#TameworkPlayerSettingsFCard", allowed, allowed && saved == CompanionCardFBehaviour.CARD);
        choiceButton(commands, "#TameworkPlayerSettingsFCycle", allowed, allowed && saved == CompanionCardFBehaviour.CYCLE);
        choiceButton(commands, "#TameworkPlayerSettingsFDefault", allowed, allowed && saved == null);
        String behaviour = LocalizedText.resolve(playerRef, CompanionCardPreferences.serverDefault() == CompanionCardFBehaviour.CARD
                ? "tamework.commands.card.behaviour.card" : "tamework.commands.card.behaviour.cycle");
        commands.set("#TameworkPlayerSettingsCamera.Style", Value.ref(STYLES,
                TameworkInspectPreferencesComponent.cameraEnabled(ref, store) ? "SwitchOn" : "SwitchOff"));
        commands.set("#TameworkPlayerSettingsCloseAfter.Style", Value.ref(STYLES,
                TameworkInspectPreferencesComponent.closeAfterCommand(ref, store) ? "SwitchOn" : "SwitchOff"));
        commands.set("#TameworkPlayerSettingsFNote.Text", LocalizedText.format(playerRef, allowed
                ? "tamework.ui.playerSettings.f.note.default" : "tamework.ui.playerSettings.f.note.locked", behaviour));
    }

    private static void choiceButton(@Nonnull UICommandBuilder commands, @Nonnull String selector,
                                     boolean enabled, boolean selected) {
        commands.set(selector + ".Style", Value.ref(STYLES, selected ? "PanelButtonPrimary" : "PanelButton"));
        commands.set(selector + ".Disabled", !enabled);
    }

    private boolean canOpenServerSettings() {
        try {
            return TameworkSettingsPageService.hasAccess(playerRef, playerRef);
        } catch (RuntimeException unavailable) {
            // Permission infrastructure may be unavailable during teardown; fail closed.
            return false;
        }
    }

    private void openPlacement(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                               @Nonnull TameworkHudPlacementPage.Mode mode) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, new TameworkHudPlacementPage(playerRef, mode, this::reopen));
        }
    }

    /** Back from the position editor: a fresh page shows any change made meanwhile. */
    private void reopen() {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, new TameworkPlayerSettingsPage(playerRef, returnTo));
        }
    }

    private static void bind(@Nonnull UIEventBuilder events, @Nonnull String selector, @Nonnull String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of(KEY_ACTION, action), false);
    }

    /** Event payload for the settings buttons. */
    public static final class EventPayload {
        public static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(EventPayload.class, EventPayload::new)
                .<String>append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (x, v) -> x.action = v, x -> x.action)
                .add()
                .build();

        private String action;
    }
}
