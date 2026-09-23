package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Resolves Owned requests without adding a command-item link. */
final class CommandOwnedActionService {
    private final PersistenceDomainFacades persistence;
    private final CommandToolInventoryService inventory;
    private final CommandPanelPreferenceService preferences;
    private final CommandFeedbackService feedback;
    private final CommandLinkMutationService links;

    CommandOwnedActionService(PersistenceDomainFacades persistence,
            CommandToolInventoryService inventory, CommandPanelPreferenceService preferences,
            CommandFeedbackService feedback, CommandLinkMutationService links) {
        this.persistence = persistence;
        this.inventory = inventory;
        this.preferences = preferences;
        this.feedback = feedback;
        this.links = links;
    }

    boolean request(Player player, String toolId, UUID rowId,
            Predicate<Player> authority, BiConsumer<Player, LinkedNpcRecord> action) {
        return requestInternal(player, toolId, rowId, authority, action, false);
    }

    boolean requestLocate(Player player, String toolId, UUID rowId,
            Predicate<Player> authority, BiConsumer<Player, LinkedNpcRecord> action) {
        return requestInternal(player, toolId, rowId, authority, action, true);
    }

    private boolean requestInternal(Player player, String toolId, UUID rowId,
            Predicate<Player> authority, BiConsumer<Player, LinkedNpcRecord> action,
            boolean locate) {
        var stack = inventory.findToolStack(player, toolId);
        if (stack == null) return false;
        UUID owner = player.getUuid();
        if (persistence == null || owner == null || rowId == null) {
            warn(player);
            return true;
        }
        var profileId = new CommandOwnedPanelRecordSource(
                persistence.queries()::projectedProfileSnapshot).profileForRow(
                        owner, rowId, links.readLinkedNpcRecords(stack));
        if (profileId.isEmpty()) {
            warn(player);
            return true;
        }
        persistence.queries().findProfile(profileId.get()).whenComplete((read, failure) -> {
            CompanionProfileReadModel profile = failure == null
                    && read instanceof PersistenceReadResult.Found<CompanionProfileReadModel> found
                    ? found.value() : null;
            CommandUiCurrentWorldDispatcher.production().dispatch(owner, new CommandUiHostPage.WorldOperation() {
                @Override public void run(Ref<EntityStore> ref, Store<EntityStore> store) {
                    Player current = ref == null || !ref.isValid() || store == null
                            ? null : store.getComponent(ref, Player.getComponentType());
                    if (current == null || !authority.test(current) || !ownedMode(current, toolId)) return;
                    boolean allowed = locate
                            ? allowsLocate(owner, profile,
                            persistence.queries().projectedCommandRosterActions().keySet(),
                            persistence.queries().projectedLaggingCommandRosterProfiles())
                            : allows(owner, profile,
                            persistence.queries().projectedCommandRosterActions().keySet(),
                            persistence.queries().projectedLaggingCommandRosterProfiles());
                    if (!allowed) {
                        warn(current);
                        return;
                    }
                    action.accept(current, record(profile, rowId));
                }
            });
        });
        return true;
    }

    private boolean ownedMode(Player player, String toolId) {
        return player != null && inventory.findToolStack(player, toolId) != null;
    }

    static boolean allows(UUID owner, CompanionProfileReadModel profile,
            java.util.Set<com.alechilles.alecstamework.companion.identity.ProfileId> managed,
            java.util.Set<com.alechilles.alecstamework.companion.identity.ProfileId> lagging) {
        if (!allowsOwnedProfile(owner, profile, managed, lagging)) return false;
        return switch (profile.lifecycle().state()) {
            case ACTIVE, UNLOADED, DEAD_REVIVABLE, LOST -> true;
            default -> false;
        };
    }

    static boolean allowsLocate(UUID owner, CompanionProfileReadModel profile,
            java.util.Set<com.alechilles.alecstamework.companion.identity.ProfileId> managed,
            java.util.Set<com.alechilles.alecstamework.companion.identity.ProfileId> lagging) {
        if (!allowsOwnedProfile(owner, profile, managed, lagging)) return false;
        return switch (profile.lifecycle().state()) {
            case ACTIVE, UNLOADED, CAPTURED, COOP, DEAD_REVIVABLE, LOST -> true;
            default -> false;
        };
    }

    private static boolean allowsOwnedProfile(UUID owner, CompanionProfileReadModel profile,
            java.util.Set<com.alechilles.alecstamework.companion.identity.ProfileId> managed,
            java.util.Set<com.alechilles.alecstamework.companion.identity.ProfileId> lagging) {
        if (profile == null || owner == null || profile.lifecycle().ownerId() == null
                || !owner.equals(profile.lifecycle().ownerId().value())) return false;
        return !managed.contains(profile.identity().profileId())
                && !lagging.contains(profile.identity().profileId());
    }

    static LinkedNpcRecord record(CompanionProfileReadModel profile, UUID rowId) {
        return new LinkedNpcRecord(profile.currentAlias() == null ? rowId
                : profile.currentAlias().alias().value(), profile.identity().profileId().toString(),
                null, profile.identity().lastKnownWorldKey(), null,
                profile.identity().displayName(), null, profile.identity().roleId(), null,
                true, false, null);
    }

    private void warn(Player player) {
        feedback.showWarningKey(player, "tamework.ui.notifications.command.execution.none");
    }
}
