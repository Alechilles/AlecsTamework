package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiSession;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdate;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;

/** Adapts Tamework's standard command menu to the shared host lifecycle. */
public final class StandardCommandUiController
        implements CommandUiHostController<CommandSelectionEventData> {
    private final Delegate delegate;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates the standard controller around Tamework's existing renderer. */
    public StandardCommandUiController(
            @Nonnull TameworkCommandSelectionPage page) {
        this((Delegate) page);
    }

    StandardCommandUiController(@Nonnull Delegate delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Nonnull
    @Override
    public BuilderCodec<CommandSelectionEventData> eventCodec() {
        return CommandSelectionEventData.CODEC;
    }

    @Override
    public void buildInitial(
            CommandUiOpenContext context,
            CommandUiSession session,
            CommandUiSnapshot snapshot,
            Ref<EntityStore> ref,
            Store<EntityStore> store,
            UICommandBuilder commands,
            UIEventBuilder events
    ) {
        if (closed.get()) return;
        delegate.configureDefaultDecorations(snapshot);
        delegate.configureHostPacketSender((partialCommands, partialEvents) ->
                session.updateSink().submit(
                        partialCommands, partialEvents, false));
        delegate.build(ref, commands, events, store);
    }

    @Override
    public void update(CommandUiUpdate update, UICommandBuilder commands,
                       UIEventBuilder events) {
        if (closed.get()) return;
        delegate.updateDefaultDecorations(update.snapshot(), commands);
    }

    @Override
    public void handleEvent(
            CommandSelectionEventData event,
            CommandUiSession session,
            CommandUiSnapshot snapshot,
            Ref<EntityStore> ref,
            Store<EntityStore> store,
            UICommandBuilder commands,
            UIEventBuilder events
    ) {
        if (closed.get()) return;
        delegate.handleDataEvent(ref, store, event);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) delegate.closeForHost();
    }

    /** Narrow adapter that keeps the legacy renderer behind the controller. */
    interface Delegate {
        default void configureDefaultDecorations(CommandUiSnapshot snapshot) {
        }

        default void updateDefaultDecorations(CommandUiSnapshot snapshot,
                                              UICommandBuilder commands) {
        }

        void configureHostPacketSender(LinkedNpcPanelPacketSender sender);

        void build(Ref<EntityStore> ref,
                   UICommandBuilder commands,
                   UIEventBuilder events,
                   Store<EntityStore> store);

        void handleDataEvent(Ref<EntityStore> ref,
                             Store<EntityStore> store,
                             CommandSelectionEventData event);

        void closeForHost();
    }
}
