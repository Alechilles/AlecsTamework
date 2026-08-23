package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiSession;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Internal extension used only by Tamework's standard controller. */
interface CommandUiHostController<T> extends CommandUiPageController<T> {
    void buildInitial(
            CommandUiOpenContext context,
            CommandUiSession session,
            CommandUiSnapshot snapshot,
            Ref<EntityStore> ref,
            Store<EntityStore> store,
            @Nonnull UICommandBuilder commands,
            @Nonnull UIEventBuilder events
    );

    void handleEvent(
            T event,
            CommandUiSession session,
            CommandUiSnapshot snapshot,
            Ref<EntityStore> ref,
            Store<EntityStore> store,
            @Nonnull UICommandBuilder commands,
            @Nonnull UIEventBuilder events
    );
}
