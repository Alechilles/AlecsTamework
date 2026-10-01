package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Resolves Owned requests without adding a command-item link. */
final class CommandOwnedActionService {
    @Nullable
    private final PersistenceDomainFacades persistence;
    @Nullable
    private final CompanionQueries companions;
    private final CommandToolInventoryService inventory;
    private final CommandPanelPreferenceService preferences;
    private final CommandFeedbackService feedback;
    private final CommandLinkMutationService links;

    CommandOwnedActionService(PersistenceDomainFacades persistence,
            CommandToolInventoryService inventory, CommandPanelPreferenceService preferences,
            CommandFeedbackService feedback, CommandLinkMutationService links) {
        this(persistence, null, inventory, preferences, feedback, links);
    }

    /** Gates Owned requests on the companion index record, read fresh on the owner's world thread. */
    CommandOwnedActionService(CompanionQueries companions,
            CommandToolInventoryService inventory, CommandPanelPreferenceService preferences,
            CommandFeedbackService feedback, CommandLinkMutationService links) {
        this(null, companions, inventory, preferences, feedback, links);
    }

    private CommandOwnedActionService(@Nullable PersistenceDomainFacades persistence,
            @Nullable CompanionQueries companions,
            CommandToolInventoryService inventory, CommandPanelPreferenceService preferences,
            CommandFeedbackService feedback, CommandLinkMutationService links) {
        this.persistence = persistence;
        this.companions = companions;
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
        if (companions != null && owner != null && rowId != null) {
            return requestIndexed(player, owner, stack, toolId, rowId, authority, action, locate);
        }
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

    private boolean requestIndexed(Player player, UUID owner, ItemStack stack, String toolId, UUID rowId,
            Predicate<Player> authority, BiConsumer<Player, LinkedNpcRecord> action, boolean locate) {
        var profileId = new CommandOwnedPanelRecordSource(companions).profileForRow(
                owner, rowId, links.readLinkedNpcRecords(stack));
        if (profileId.isEmpty()) {
            warn(player);
            return true;
        }
        UUID id = profileId.get().value();
        CommandUiCurrentWorldDispatcher.production().dispatch(owner, new CommandUiHostPage.WorldOperation() {
            @Override public void run(Ref<EntityStore> ref, Store<EntityStore> store) {
                Player current = ref == null || !ref.isValid() || store == null
                        ? null : store.getComponent(ref, Player.getComponentType());
                if (current == null || !authority.test(current) || !ownedMode(current, toolId)) return;
                CompanionRecord companion = companions.get(id);
                boolean allowed = locate ? allowsLocate(owner, companion) : allows(owner, companion);
                if (!allowed) {
                    warn(current);
                    return;
                }
                action.accept(current, record(companion, rowId));
            }
        });
        return true;
    }

    private boolean ownedMode(Player player, String toolId) {
        return player != null && inventory.findToolStack(player, toolId) != null;
    }

    static boolean allows(UUID owner, CompanionProfileReadModel profile,
            java.util.Set<com.alechilles.alecstamework.companion.identity.ProfileId> managed,
            java.util.Set<com.alechilles.alecstamework.companion.identity.ProfileId> lagging) {
        return allowsOwnedProfile(owner, profile, managed, lagging)
                && stateAllows(profile.lifecycle().state(), false);
    }

    static boolean allowsLocate(UUID owner, CompanionProfileReadModel profile,
            java.util.Set<com.alechilles.alecstamework.companion.identity.ProfileId> managed,
            java.util.Set<com.alechilles.alecstamework.companion.identity.ProfileId> lagging) {
        return allowsOwnedProfile(owner, profile, managed, lagging)
                && stateAllows(profile.lifecycle().state(), true);
    }

    /** Recall and respawn gate for an index record: the viewer owns it and it is live, dead or lost. */
    static boolean allows(@Nullable UUID owner, @Nullable CompanionRecord companion) {
        return ownedBy(owner, companion) && stateAllows(state(companion), false);
    }

    /** Locate gate for an index record: also open for captured and cooped companions. */
    static boolean allowsLocate(@Nullable UUID owner, @Nullable CompanionRecord companion) {
        return ownedBy(owner, companion) && stateAllows(state(companion), true);
    }

    private static boolean ownedBy(@Nullable UUID owner, @Nullable CompanionRecord companion) {
        return owner != null && companion != null && owner.equals(companion.ownerUuid());
    }

    private static LifecycleState state(CompanionRecord companion) {
        return CommandPersistenceView.from(companion).lifecycleState();
    }

    private static boolean stateAllows(LifecycleState state, boolean locate) {
        return switch (state) {
            case ACTIVE, UNLOADED, DEAD_REVIVABLE, LOST -> true;
            case CAPTURED, COOP -> locate;
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

    static LinkedNpcRecord record(CompanionRecord companion, UUID rowId) {
        String world = companion.location().world() != null ? companion.location().world() : companion.homeWorld();
        String name = companion.summary().customName() != null
                ? companion.summary().customName() : companion.displayName();
        // A LIVE position lets a recall load the companion's chunk before it moves the body.
        Vector3d position = companion.location().kind() == LocationKind.LIVE
                ? new Vector3d(companion.location().x(), companion.location().y(), companion.location().z())
                : null;
        return new LinkedNpcRecord(companion.currentNpcUuid() == null ? rowId : companion.currentNpcUuid(),
                companion.profileId().toString(), position, world, null, name, null, companion.roleId(), null,
                true, false, null);
    }

    private void warn(Player player) {
        feedback.showWarningKey(player, "tamework.ui.notifications.command.execution.none");
    }
}
