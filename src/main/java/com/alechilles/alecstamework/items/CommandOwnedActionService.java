package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
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
    private final CompanionQueries companions;
    private final CommandToolInventoryService inventory;
    private final CommandPanelPreferenceService preferences;
    private final CommandFeedbackService feedback;
    private final CommandLinkMutationService links;

    /**
     * Gates Owned requests on the companion index record, read fresh on the owner's world thread.
     * Without {@code companions} (index not ready) every request warns and does nothing.
     */
    CommandOwnedActionService(@Nullable CompanionQueries companions,
            CommandToolInventoryService inventory, CommandPanelPreferenceService preferences,
            CommandFeedbackService feedback, CommandLinkMutationService links) {
        this.companions = companions;
        this.inventory = inventory;
        this.preferences = preferences;
        this.feedback = feedback;
        this.links = links;
    }

    /** Which locations an owned action may reach. */
    private enum Gate { ACTION, LOCATE, RECOVER }

    boolean request(Player player, String toolId, UUID rowId,
            Predicate<Player> authority, BiConsumer<Player, LinkedNpcRecord> action) {
        return requestInternal(player, toolId, rowId, authority, action, Gate.ACTION);
    }

    boolean requestLocate(Player player, String toolId, UUID rowId,
            Predicate<Player> authority, BiConsumer<Player, LinkedNpcRecord> action) {
        return requestInternal(player, toolId, rowId, authority, action, Gate.LOCATE);
    }

    /** Revive and Recover; also open for a companion in a capture item (spec 8.14) or a coop. */
    boolean requestRecover(Player player, String toolId, UUID rowId,
            Predicate<Player> authority, BiConsumer<Player, LinkedNpcRecord> action) {
        return requestInternal(player, toolId, rowId, authority, action, Gate.RECOVER);
    }

    private boolean requestInternal(Player player, String toolId, UUID rowId,
            Predicate<Player> authority, BiConsumer<Player, LinkedNpcRecord> action,
            Gate gate) {
        var stack = inventory.findToolStack(player, toolId);
        if (stack == null) return false;
        UUID owner = player.getUuid();
        if (companions != null && owner != null && rowId != null) {
            return requestIndexed(player, owner, stack, toolId, rowId, authority, action, gate);
        }
        warn(player);
        return true;
    }

    private boolean requestIndexed(Player player, UUID owner, ItemStack stack, String toolId, UUID rowId,
            Predicate<Player> authority, BiConsumer<Player, LinkedNpcRecord> action, Gate gate) {
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
                boolean allowed = switch (gate) {
                    case ACTION -> allows(owner, companion);
                    case LOCATE -> allowsLocate(owner, companion);
                    case RECOVER -> allowsRecover(owner, companion);
                };
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

    /** Recall and respawn gate for an index record: the viewer owns it and it is live, dead or lost. */
    static boolean allows(@Nullable UUID owner, @Nullable CompanionRecord companion) {
        return ownedBy(owner, companion) && stateAllows(state(companion), Gate.ACTION);
    }

    /** Revive and Recover gate for an index record: also open for a captured or cooped companion. */
    static boolean allowsRecover(@Nullable UUID owner, @Nullable CompanionRecord companion) {
        return ownedBy(owner, companion) && stateAllows(state(companion), Gate.RECOVER);
    }

    /** Locate gate for an index record: also open for captured and cooped companions. */
    static boolean allowsLocate(@Nullable UUID owner, @Nullable CompanionRecord companion) {
        return ownedBy(owner, companion) && stateAllows(state(companion), Gate.LOCATE);
    }

    /**
     * The viewer owns the record and a generic item may act on it. A command-family roster member
     * (a roster id, not bonded) is read-only here: only its family item's panel acts on it.
     */
    private static boolean ownedBy(@Nullable UUID owner, @Nullable CompanionRecord companion) {
        return owner != null && companion != null && owner.equals(companion.ownerUuid())
                && !(companion.rosterId() != null && !companion.bonded());
    }

    private static LifecycleState state(CompanionRecord companion) {
        return CommandPersistenceView.from(companion).lifecycleState();
    }

    private static boolean stateAllows(LifecycleState state, Gate gate) {
        return switch (state) {
            case ACTIVE, UNLOADED, DEAD_REVIVABLE, LOST -> true;
            case CAPTURED -> gate != Gate.ACTION;
            case COOP -> gate != Gate.ACTION;
            default -> false;
        };
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
