package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.alechilles.alecstamework.ownership.OwnerPopulationCommitResult;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Command to set or clear owner on the targeted NPC.
 */
public final class TameworkSetOwnerCommand extends AbstractPlayerCommand {

    public TameworkSetOwnerCommand() {
        super("setowner", "Set owner of the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            commandContext.sender().sendMessage(Message.raw("No player context."));
            return;
        }

        // Default: set owner to the executing player when no argument is provided.
        String raw = getFirstArg(commandContext);
        UUID newOwner = parseOwner(raw, player.getUuid());
        if (newOwner == null && raw != null && !raw.isBlank() && !isClear(raw)) {
            commandContext.sender().sendMessage(Message.raw("Invalid UUID. Use 'clear' or a valid UUID."));
            return;
        }

        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        String ownerName = null;
        if (newOwner != null && newOwner.equals(player.getUuid())) {
            // Only resolve a name for self; external UUIDs remain anonymous.
            ownerName = OwnerNameUtil.resolve(player);
        }

        Tamework plugin = Tamework.getInstance();
        OwnerMutationScheduler scheduler = plugin == null ? null : plugin.getOwnerMutationScheduler();
        if (scheduler == null) {
            commandContext.sender().sendMessage(Message.raw("Owner mutation service is unavailable."));
            return;
        }

        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent existing = type == null ? null : store.getComponent(candidate.ref, type);
        UUID oldOwner = existing == null ? null : existing.getOwnerId();
        String ownerText = newOwner == null ? "null" : newOwner.toString();
        scheduler.schedule(
                candidate.ref,
                store,
                newOwner,
                ownerName,
                CompanionLifecycleState.ACTIVE,
                resolveOperation(oldOwner, newOwner, true),
                true,
                "setowner-command:" + candidate.npcUuid + ":" + UUID.randomUUID(),
                new OwnerMutationScheduler.MutationCallbacks() {
                    @Override
                    public void onDenied(String reason, OwnerPopulationDecision decision) {
                        playerRef.sendMessage(Message.raw(
                                "Could not set owner for NPC " + candidate.npcUuid + ": " + reason
                        ));
                    }

                    @Override
                    public void onCommitted(OwnerPopulationCommitResult result) {
                        playerRef.sendMessage(Message.raw(
                                "Set owner for NPC " + candidate.npcUuid + " to " + ownerText
                        ));
                    }

                    @Override
                    public void onDurabilityDegraded(String reason) {
                        playerRef.sendMessage(Message.raw(
                                "Owner was applied to NPC " + candidate.npcUuid
                                        + ", but persistence is degraded: " + reason
                        ));
                    }
                }
        );
    }

    static OwnerPopulationOperation resolveOperation(UUID oldOwner, UUID newOwner) {
        return resolveOperation(oldOwner, newOwner, false);
    }

    static OwnerPopulationOperation resolveOperation(UUID oldOwner, UUID newOwner, boolean force) {
        if (force) {
            return OwnerPopulationOperation.ADMIN_FORCE;
        }
        if (newOwner == null) {
            return OwnerPopulationOperation.OWNER_CLEAR;
        }
        if (oldOwner == null) {
            return OwnerPopulationOperation.NEW_OWNERSHIP;
        }
        return oldOwner.equals(newOwner)
                ? OwnerPopulationOperation.LIFECYCLE_CHANGE
                : OwnerPopulationOperation.OWNER_TRANSFER;
    }

    static UUID parseOwner(String raw, UUID defaultOwner) {
        if (raw == null || raw.isBlank()) {
            return defaultOwner;
        }
        if (isClear(raw)) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String getFirstArg(CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\s+");
        if (tokens.length < 3) {
            return null;
        }
        return tokens[2];
    }

    private static boolean isClear(String raw) {
        return "clear".equalsIgnoreCase(raw) || "none".equalsIgnoreCase(raw);
    }
}
