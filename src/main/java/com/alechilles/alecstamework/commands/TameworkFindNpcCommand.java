package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.compat.HytaleParticleAccess;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionModelScaleService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Command to find an NPC by UUID and optionally mark its location.
 */
public final class TameworkFindNpcCommand extends AbstractWorldCommand {
    private static final String DEFAULT_MARKER_PARTICLE = "Hearts";

    public TameworkFindNpcCommand() {
        super("findnpc", "Find an NPC by UUID and print its current world state.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store) {
        String uuidArg = getArg(commandContext, 2);
        UUID targetUuid = parseUuid(uuidArg);
        if (targetUuid == null) {
            commandContext.sender().sendMessage(Message.raw("Usage: /tw findnpc <uuid> [mark:on|off]"));
            return;
        }

        Ref<EntityStore> targetRef = world.getEntityRef(targetUuid);
        if (targetRef == null || !targetRef.isValid()) {
            commandContext.sender().sendMessage(Message.raw("NPC " + targetUuid + " is not currently valid in-world."));
            return;
        }

        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null) {
            commandContext.sender().sendMessage(Message.raw("Entity " + targetUuid + " exists but has no NPC component."));
            return;
        }

        TransformComponent targetTransform = store.getComponent(targetRef, TransformComponent.getComponentType());
        Ref<EntityStore> senderRef = commandContext.isPlayer() ? commandContext.senderAsPlayerRef() : null;
        TransformComponent playerTransform = senderRef == null
                ? null
                : store.getComponent(senderRef, TransformComponent.getComponentType());
        String roleId = CompanionRoleIdResolver.resolveRoleId(targetRef, store);
        String lifeStage = CompanionLifeStageService.resolveCurrentStage(targetRef, store, roleId);
        double scale = CompanionModelScaleService.resolveCurrentScale(targetRef, store, 1.0);
        double distance = resolveDistance(targetTransform, playerTransform);

        boolean shouldMark = parseOptionalMark(getArg(commandContext, 3));
        if (shouldMark && targetTransform != null) {
            spawnMarker(targetTransform.getPosition(), store);
        }

        String positionText = targetTransform != null
                ? String.format(
                        Locale.ROOT,
                        "(%.2f, %.2f, %.2f)",
                        targetTransform.getPosition().x,
                        targetTransform.getPosition().y,
                        targetTransform.getPosition().z
                )
                : "(unknown)";
        String distanceText = Double.isFinite(distance) ? String.format(Locale.ROOT, "%.2f", distance) : "n/a";
        String roleText = roleId != null && !roleId.isBlank() ? roleId : "(unknown)";

        commandContext.sender().sendMessage(Message.raw(
                "NPC " + targetUuid
                        + " found: role=" + roleText
                        + ", pos=" + positionText
                        + ", distance=" + distanceText
                        + ", stage=" + lifeStage
                        + ", scale=" + String.format(Locale.ROOT, "%.2f", scale)
                        + ", marked=" + shouldMark
                        + "."
        ));
    }

    private static void spawnMarker(Vector3d basePosition, Store<EntityStore> store) {
        Vector3d particlePos = new Vector3d(basePosition);
        particlePos.y += 1.2;
        HytaleParticleAccess.spawn(DEFAULT_MARKER_PARTICLE, particlePos, store);
    }

    private static double resolveDistance(TransformComponent targetTransform, TransformComponent playerTransform) {
        if (targetTransform == null || playerTransform == null) {
            return Double.NaN;
        }
        return targetTransform.getPosition().distance(playerTransform.getPosition());
    }

    private static boolean parseOptionalMark(String arg) {
        if (arg == null || arg.isBlank()) {
            return true;
        }
        String normalized = arg.trim().toLowerCase(Locale.ROOT);
        if ("off".equals(normalized) || "false".equals(normalized) || "0".equals(normalized)) {
            return false;
        }
        if ("on".equals(normalized) || "true".equals(normalized) || "1".equals(normalized)) {
            return true;
        }
        return true;
    }

    private static String getArg(CommandContext commandContext, int index) {
        String input = commandContext.getInputString();
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length <= index) {
            return null;
        }
        return tokens[index];
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
