package com.alechilles.alecstamework.ui;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Owns the feature-specific row snapshot and command handling kept out of the
 * oversized command selection page.
 */
final class LinkedNpcPanelFeatureController {
    static final String SUMMON_COMMAND_PREFIX = "__roster_summon__:";
    static final String DISMISS_COMMAND_PREFIX = "__roster_dismiss__:";
    private static final String RESPAWN_COMMAND_PREFIX = "__respawn__:";
    private static final String REVIVE_CONFIRM_COMMAND_ID =
            "__revive_confirm__";
    private static final String REVIVE_CANCEL_COMMAND_ID =
            "__revive_cancel__";
    private static final String CLOSE_COMMAND_ID = "__close__";

    private final Supplier<Map<UUID, CommandPanelFeaturePresentation>>
            presentationSupplier;
    private final LinkedNpcPanelFeatureAction summon;
    private final LinkedNpcPanelFeatureAction dismiss;
    private final LinkedNpcPanelFeatureAction revive;
    private final LinkedNpcPanelReviveOverlayState reviveOverlay =
            new LinkedNpcPanelReviveOverlayState();
    private Map<UUID, CommandPanelFeaturePresentation> presentations =
            Map.of();
    private long revision;

    LinkedNpcPanelFeatureController(
            @Nonnull Supplier<Map<UUID, CommandPanelFeaturePresentation>>
                    presentationSupplier,
            @Nonnull Consumer<UUID> summon,
            @Nonnull Consumer<UUID> dismiss,
            @Nonnull Consumer<UUID> revive
    ) {
        this(presentationSupplier,
                (npcUuid, ignoredRef, ignoredStore) -> summon.accept(npcUuid),
                (npcUuid, ignoredRef, ignoredStore) -> dismiss.accept(npcUuid),
                (npcUuid, ignoredRef, ignoredStore) -> revive.accept(npcUuid));
    }

    LinkedNpcPanelFeatureController(
            @Nonnull Supplier<Map<UUID, CommandPanelFeaturePresentation>>
                    presentationSupplier,
            @Nonnull LinkedNpcPanelFeatureAction summon,
            @Nonnull LinkedNpcPanelFeatureAction dismiss,
            @Nonnull LinkedNpcPanelFeatureAction revive
    ) {
        this.presentationSupplier = Objects.requireNonNull(
                presentationSupplier, "Presentation supplier is required"
        );
        this.summon = Objects.requireNonNull(
                summon, "Summon action is required"
        );
        this.dismiss = Objects.requireNonNull(
                dismiss, "Dismiss action is required"
        );
        this.revive = Objects.requireNonNull(
                revive, "Revive action is required"
        );
    }

    void refresh() {
        Map<UUID, CommandPanelFeaturePresentation> resolved;
        try {
            Map<UUID, CommandPanelFeaturePresentation> latest =
                    presentationSupplier.get();
            resolved = latest == null ? Map.of() : Map.copyOf(latest);
        } catch (RuntimeException | LinkageError ignored) {
            resolved = Map.of();
        }
        if (!resolved.equals(presentations)) {
            presentations = resolved;
            revision++;
        }
        if (reviveOverlay.isVisible()) {
            reviveOverlay.refresh(
                    presentations.get(reviveOverlay.npcUuid())
            );
        }
    }

    CommandPanelFeaturePresentation presentation(UUID npcUuid) {
        return npcUuid == null ? null : presentations.get(npcUuid);
    }

    long revision() {
        return revision;
    }

    void applyOverlay(
            com.hypixel.hytale.server.core.ui.builder.UICommandBuilder builder,
            String language
    ) {
        reviveOverlay.applyTo(builder, language);
    }

    void bindEvents(UIEventBuilder events, String eventCommandId) {
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkLinkedPanelReviveCancelButton",
                EventData.of(eventCommandId, REVIVE_CANCEL_COMMAND_ID),
                false
        );
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkLinkedPanelReviveConfirmButton",
                EventData.of(eventCommandId, REVIVE_CONFIRM_COMMAND_ID),
                false
        );
    }

    Outcome handle(
            String commandId, Ref<EntityStore> playerRef,
            Store<EntityStore> store,
            Function<UUID, LinkedNpcEntry> entryResolver
    ) {
        if (REVIVE_CANCEL_COMMAND_ID.equals(commandId)) {
            reviveOverlay.clear();
            return Outcome.REFRESH;
        }
        if (REVIVE_CONFIRM_COMMAND_ID.equals(commandId)) {
            refresh();
            UUID selected = reviveOverlay.consumeIfConfirmed();
            if (selected != null) {
                revive.accept(selected, playerRef, store);
            }
            return Outcome.REFRESH;
        }
        if (CLOSE_COMMAND_ID.equals(commandId)) {
            reviveOverlay.clear();
            return Outcome.NOT_HANDLED;
        }
        if (reviveOverlay.isVisible()) {
            return Outcome.HANDLED;
        }
        if (commandId.startsWith(SUMMON_COMMAND_PREFIX)) {
            return invoke(
                    commandId, playerRef, store, SUMMON_COMMAND_PREFIX,
                    summon, Action.SUMMON
            );
        }
        if (commandId.startsWith(DISMISS_COMMAND_PREFIX)) {
            return invoke(
                    commandId, playerRef, store, DISMISS_COMMAND_PREFIX,
                    dismiss, Action.DISMISS
            );
        }
        if (!commandId.startsWith(RESPAWN_COMMAND_PREFIX)) {
            return Outcome.NOT_HANDLED;
        }
        UUID npcUuid = CommandUiIdParser.parseNpcUuid(
                commandId, RESPAWN_COMMAND_PREFIX
        );
        CommandPanelFeaturePresentation row = presentation(npcUuid);
        if (row == null || !row.managesPaidRevival()) {
            return Outcome.NOT_HANDLED;
        }
        LinkedNpcEntry entry = npcUuid == null
                ? null
                : entryResolver.apply(npcUuid);
        if (entry != null && row.revival() != null
                && row.revival().actionVisible()) {
            reviveOverlay.open(entry, row.revival());
        }
        return Outcome.REFRESH;
    }

    private Outcome invoke(
            String commandId, Ref<EntityStore> playerRef,
            Store<EntityStore> store,
            String prefix,
            LinkedNpcPanelFeatureAction action,
            Action expected
    ) {
        UUID npcUuid = CommandUiIdParser.parseNpcUuid(
                commandId, prefix
        );
        if (npcUuid != null && actionAvailable(
                presentation(npcUuid), expected)) {
            action.accept(npcUuid, playerRef, store);
        }
        return Outcome.REFRESH;
    }

    Outcome handle(
            String commandId,
            Function<UUID, LinkedNpcEntry> entryResolver
    ) {
        return handle(commandId, null, null, entryResolver);
    }

    private boolean actionAvailable(
            CommandPanelFeaturePresentation row,
            Action expected
    ) {
        if (row == null) return false;
        if (row.bonded() != null) {
            BondedCompanionStatusPresentation.Action action =
                    row.bonded().status().action();
            return row.bonded().status().actionEnabled()
                    && (expected == Action.SUMMON
                            ? action == BondedCompanionStatusPresentation.Action.SUMMON
                            : action == BondedCompanionStatusPresentation.Action.DISMISS);
        }
        if (row.roster() == null) return false;
        return expected == Action.SUMMON
                ? row.roster().summonEnabled()
                : row.roster().dismissEnabled();
    }

    private enum Action { SUMMON, DISMISS }

    enum Outcome {
        NOT_HANDLED,
        HANDLED,
        REFRESH
    }
}
