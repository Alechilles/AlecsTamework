package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.function.Function;

/** Routes capability-gated auxiliary controls on bonded companion cards. */
final class BondedCompanionAuxiliaryCommandHandler {
    private BondedCompanionAuxiliaryCommandHandler() {
    }

    static boolean handleFlight(
            String commandId, boolean bondedRoster,
            Function<UUID, CommandPanelFeaturePresentation> presentations,
            LinkedNpcPanelFeatureAction action, Ref<EntityStore> playerRef,
            Store<EntityStore> store) {
        return handle(commandId,
                CommandSelectionPageEventBinder.BONDED_FLIGHT_TOGGLE_COMMAND_PREFIX,
                BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE,
                bondedRoster, presentations, action, playerRef, store);
    }

    static boolean handleShoulderRide(
            String commandId, boolean bondedRoster,
            Function<UUID, CommandPanelFeaturePresentation> presentations,
            LinkedNpcPanelFeatureAction action, Ref<EntityStore> playerRef,
            Store<EntityStore> store) {
        return handle(commandId,
                CommandSelectionPageEventBinder.BONDED_SHOULDER_RIDE_COMMAND_PREFIX,
                BondedCompanionPresentationAttributes.SHOULDER_RIDE_AVAILABLE,
                bondedRoster, presentations, action, playerRef, store);
    }

    static boolean handleLinkedFlight(
            String commandId, boolean bondedRoster,
            Function<UUID, LinkedNpcEntry> entries,
            LinkedNpcPanelFeatureAction action, Ref<EntityStore> playerRef,
            Store<EntityStore> store) {
        String prefix = CommandSelectionPageEventBinder
                .LINKED_FLIGHT_TOGGLE_COMMAND_PREFIX;
        if (!commandId.startsWith(prefix)) return false;
        UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, prefix);
        LinkedNpcEntry entry = npcUuid == null ? null : entries.apply(npcUuid);
        if (!bondedRoster && entry != null && entry.linked() && entry.loaded()
                && entry.flightToggleAvailable()) {
            action.accept(npcUuid, playerRef, store);
        }
        return true;
    }

    static boolean handleLinkedShoulderRide(
            String commandId, boolean bondedRoster,
            Function<UUID, LinkedNpcEntry> entries,
            LinkedNpcPanelFeatureAction action, Ref<EntityStore> playerRef,
            Store<EntityStore> store) {
        String prefix = CommandSelectionPageEventBinder
                .LINKED_SHOULDER_RIDE_COMMAND_PREFIX;
        if (!commandId.startsWith(prefix)) return false;
        UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, prefix);
        LinkedNpcEntry entry = npcUuid == null ? null : entries.apply(npcUuid);
        if (!bondedRoster && entry != null && entry.linked() && entry.loaded()
                && entry.shoulderRideAvailable()) {
            action.accept(npcUuid, playerRef, store);
        }
        return true;
    }

    private static boolean handle(
            String commandId, String prefix, String availabilityAttribute,
            boolean bondedRoster,
            Function<UUID, CommandPanelFeaturePresentation> presentations,
            LinkedNpcPanelFeatureAction action, Ref<EntityStore> playerRef,
            Store<EntityStore> store) {
        if (!commandId.startsWith(prefix)) return false;
        UUID cardUuid = CommandUiIdParser.parseNpcUuid(commandId, prefix);
        CommandPanelFeaturePresentation feature = cardUuid == null ? null
                : presentations.apply(cardUuid);
        if (bondedRoster && feature != null && feature.bonded() != null
                && Boolean.parseBoolean(feature.bonded().attributes().get(
                availabilityAttribute))) {
            action.accept(cardUuid, playerRef, store);
        }
        return true;
    }
}
