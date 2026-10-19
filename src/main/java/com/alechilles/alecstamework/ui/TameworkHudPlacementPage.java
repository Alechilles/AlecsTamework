package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.CommandHudDirtySink;
import com.alechilles.alecstamework.items.components.TameworkHudPlacementComponent;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.settings.CompanionHudDefaultsStore;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Moves the companion target HUD. In {@link Mode#PLAYER} it changes one player's own screen; in
 * {@link Mode#SERVER_DEFAULT} an admin sets the position used by every player who has not moved theirs. The page
 * shows a sample plate where the HUD will sit. Save stores the position, Cancel and Esc keep the old one. Save and
 * Cancel return to the page it was opened from; Esc just closes. The real HUD stays hidden while this is the open
 * page.
 */
public final class TameworkHudPlacementPage extends InteractiveCustomUIPage<TameworkHudPlacementPage.EventPayload> {
    public static final String UI_PATH = "TameworkHudPlacementPage.ui";

    /** The sample plate's height; real HUD heights vary with the companion's rows. */
    static final int SAMPLE_HEIGHT = 184;
    private static final int[] STEPS = {1, 10, 50};
    private static final int CARD_EDGE = 60;
    private static final int CARD_TOP = 160;
    private static final int CARD_WIDTH = 400;
    private static final int CARD_HEIGHT = 452;
    private static final String STYLES = "TameworkSlateStyles.ui";

    private static final String KEY_ACTION = "Action";
    private static final String ACTION_POINT = "point:";
    private static final String ACTION_STEP = "step:";
    private static final String ACTION_UP = "up";
    private static final String ACTION_DOWN = "down";
    private static final String ACTION_LEFT = "left";
    private static final String ACTION_RIGHT = "right";
    private static final String ACTION_RESET = "reset";
    private static final String ACTION_CANCEL = "cancel";
    private static final String ACTION_SAVE = "save";

    @Nonnull
    private final Mode mode;
    @Nullable
    private final Runnable returnToPanel;
    private CompanionHudPlacement draft;
    private int step = 10;
    private boolean finished;

    public TameworkHudPlacementPage(@Nonnull PlayerRef playerRef, @Nullable Runnable returnToPanel) {
        this(playerRef, Mode.PLAYER, returnToPanel);
    }

    public TameworkHudPlacementPage(@Nonnull PlayerRef playerRef, @Nonnull Mode mode, @Nullable Runnable returnTo) {
        super(playerRef, CustomPageLifetime.CanDismiss, EventPayload.CODEC);
        this.mode = mode;
        this.returnToPanel = returnTo;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        if (draft == null) {
            draft = mode == Mode.SERVER_DEFAULT
                    ? CompanionHudDefaultsStore.current()
                    : TameworkHudPlacementComponent.read(ref, store);
            wakeTargetHud(store);
        }
        commandBuilder.append(UI_PATH);
        if (mode == Mode.SERVER_DEFAULT) {
            commandBuilder.set("#TameworkHudPlacementTitle.Text",
                    LocalizedText.resolve(playerRef, "tamework.ui.hudPlacement.defaultTitle"));
            commandBuilder.set("#TameworkHudPlacementHint.Text",
                    LocalizedText.resolve(playerRef, "tamework.ui.hudPlacement.defaultHint"));
        }
        bindState(commandBuilder);
        bindEvents(eventBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull EventPayload data) {
        String action = data == null || data.action == null ? "" : data.action;
        if (finished || draft == null) {
            return;
        }
        switch (action) {
            case ACTION_SAVE -> {
                // A refused or failed save has already told the player why; going back would only repeat it.
                finish(ref, store, save(ref, store));
                return;
            }
            case ACTION_CANCEL -> {
                finish(ref, store, true);
                return;
            }
            // A player resets to the server default; an admin resets the server default to the built-in one.
            case ACTION_RESET -> draft = mode == Mode.SERVER_DEFAULT
                    ? CompanionHudPlacement.DEFAULT
                    : CompanionHudDefaultsStore.current();
            case ACTION_UP -> draft = draft.moved(0, -step).fitted(SAMPLE_HEIGHT);
            case ACTION_DOWN -> draft = draft.moved(0, step).fitted(SAMPLE_HEIGHT);
            case ACTION_LEFT -> draft = draft.moved(-step, 0);
            case ACTION_RIGHT -> draft = draft.moved(step, 0);
            default -> {
                if (!applyChoice(action)) {
                    return;
                }
            }
        }
        UICommandBuilder commandBuilder = new UICommandBuilder();
        bindState(commandBuilder);
        sendUpdate(commandBuilder, new UIEventBuilder(), false);
    }

    /**
     * Esc, or another page replacing this one. It must not open a page: the page manager is tearing
     * this one down and would close or forget whatever opened here.
     */
    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (finished) {
            return;
        }
        finished = true;
        wakeTargetHud(store);
    }

    /** Saves the draft; returns false when the save was refused or failed and the player was told why. */
    private boolean save(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (mode == Mode.PLAYER) {
            // A player at the server default keeps following it, including later changes to it.
            if (draft.equals(CompanionHudDefaultsStore.current())) {
                TameworkHudPlacementComponent.clear(ref, store);
            } else {
                TameworkHudPlacementComponent.write(ref, store, draft);
            }
            return true;
        }
        if (!TameworkSettingsPageService.hasAccess(playerRef, playerRef)) {
            playerRef.sendMessage(Message.raw(LocalizedText.resolve(playerRef, "tamework.ui.settings.permissionDenied")));
            return false;
        }
        Tamework plugin = Tamework.getInstance();
        if (plugin == null
                || !CompanionHudDefaultsStore.save(CompanionHudDefaultsStore.resolveFile(plugin), draft, plugin.getLogger())) {
            playerRef.sendMessage(Message.raw(LocalizedText.resolve(playerRef, "tamework.ui.hudPlacement.defaultSaveFailed")));
            return false;
        }
        return true;
    }

    /** Leaves through Save or Cancel: reopening the caller replaces this page; otherwise the page closes. */
    private void finish(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, boolean goBack) {
        finished = true;
        wakeTargetHud(store);
        if (goBack && returnToPanel != null) {
            returnToPanel.run();
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null && player.getPageManager().getCustomPage() == this) {
            close();
        }
    }

    private boolean applyChoice(@Nonnull String action) {
        if (action.startsWith(ACTION_POINT)) {
            CompanionHudPlacement.Point point = CompanionHudPlacement.Point.parse(action.substring(ACTION_POINT.length()));
            if (point == null) {
                return false;
            }
            draft = CompanionHudPlacement.snap(point);
            return true;
        }
        if (action.startsWith(ACTION_STEP)) {
            try {
                int requested = Integer.parseInt(action.substring(ACTION_STEP.length()));
                for (int allowed : STEPS) {
                    if (allowed == requested) {
                        step = allowed;
                        return true;
                    }
                }
            } catch (NumberFormatException ignored) {
                // Unknown step values are ignored.
            }
        }
        return false;
    }

    private void bindState(@Nonnull UICommandBuilder commands) {
        commands.setObject("#TameworkHudPlacementLane.Anchor", draft.laneAnchor());
        commands.setObject("#TameworkHudPlacementSample.Anchor", draft.plateAnchor(SAMPLE_HEIGHT));

        Anchor card = new Anchor();
        if (draft.rightOfCentre()) {
            card.setLeft(Value.of(CARD_EDGE));
        } else {
            card.setRight(Value.of(CARD_EDGE));
        }
        card.setTop(Value.of(CARD_TOP));
        card.setWidth(Value.of(CARD_WIDTH));
        card.setHeight(Value.of(CARD_HEIGHT));
        commands.setObject("#TameworkHudPlacementCard.Anchor", card);

        CompanionHudPlacement.Point[] points = CompanionHudPlacement.Point.values();
        for (int index = 0; index < points.length; index++) {
            commands.set("#TameworkHudPlacementPoint" + index + ".Style",
                    Value.ref(STYLES, points[index] == draft.point() ? "PanelButtonPrimary" : "PanelButton"));
        }
        for (int allowed : STEPS) {
            commands.set("#TameworkHudPlacementStep" + allowed + ".Style",
                    Value.ref(STYLES, allowed == step ? "PanelButtonPrimary" : "PanelButton"));
        }
        commands.set("#TameworkHudPlacementStepValue.Text", Integer.toString(step));
        commands.set("#TameworkHudPlacementSampleBreeding.Text",
                LocalizedText.format(playerRef, "tamework.ui.shared.duration.minutesSeconds", 4, 10));
        commands.set("#TameworkHudPlacementSampleHarvest.Text",
                LocalizedText.format(playerRef, "tamework.ui.shared.duration.minutesSeconds", 1, 5));
        commands.set("#TameworkHudPlacementPointName.Text",
                LocalizedText.resolve(playerRef, "tamework.ui.hudPlacement.point." + draft.point().key()));
        commands.set("#TameworkHudPlacementOffset.Text",
                LocalizedText.format(playerRef, "tamework.ui.hudPlacement.offset", draft.x(), draft.y()));
    }

    private void bindEvents(@Nonnull UIEventBuilder events) {
        CompanionHudPlacement.Point[] points = CompanionHudPlacement.Point.values();
        for (int index = 0; index < points.length; index++) {
            bind(events, "#TameworkHudPlacementPoint" + index, ACTION_POINT + points[index].name());
        }
        for (int allowed : STEPS) {
            bind(events, "#TameworkHudPlacementStep" + allowed, ACTION_STEP + allowed);
        }
        bind(events, "#TameworkHudPlacementUp", ACTION_UP);
        bind(events, "#TameworkHudPlacementDown", ACTION_DOWN);
        bind(events, "#TameworkHudPlacementLeft", ACTION_LEFT);
        bind(events, "#TameworkHudPlacementRight", ACTION_RIGHT);
        bind(events, "#TameworkHudPlacementReset", ACTION_RESET);
        bind(events, "#TameworkHudPlacementCancel", ACTION_CANCEL);
        bind(events, "#TameworkHudPlacementSave", ACTION_SAVE);
    }

    private static void bind(@Nonnull UIEventBuilder events, @Nonnull String selector, @Nonnull String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of(KEY_ACTION, action), false);
    }

    /**
     * The target HUD hides while this page is the player's open page (CommandTargetHudService). Waking it makes the
     * HUD hide as the editor opens and come back with the saved position as it closes.
     */
    private void wakeTargetHud(@Nonnull Store<EntityStore> store) {
        Tamework plugin = Tamework.getInstance();
        CommandHudDirtySink sink = plugin == null ? null : plugin.getCommandHudDirtySink();
        UUID playerUuid = playerRef.getUuid();
        if (sink != null && playerUuid != null) {
            sink.markDirty(store, playerUuid);
        }
    }

    /** Whose position the page edits. */
    public enum Mode {
        /** The viewing player's own position. */
        PLAYER,
        /** The server default for players who have not moved theirs; requires settings access to save. */
        SERVER_DEFAULT
    }

    /** Event payload for placement editor buttons. */
    public static final class EventPayload {
        public static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(EventPayload.class, EventPayload::new)
                .<String>append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (x, v) -> x.action = v, x -> x.action)
                .add()
                .build();

        private String action;
    }
}
