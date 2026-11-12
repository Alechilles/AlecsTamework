package com.alechilles.alecstamework.commands;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.DOUBLE;
import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING;

import com.alechilles.alecstamework.npc.progression.OwnerPresenceTimelineService;
import com.alechilles.alecstamework.npc.progression.SharedPresenceService;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Debug hook: moves an offline owner's offline start back in both owner clocks (the needs presence
 * timeline and the progression clock), so a test reaches the offline grace without waiting. The clocks
 * are shared by all of that owner's animals, and the progression clock is checkpointed to disk. When the
 * owner shares animals, the owner's shared presence id (it drives the shared ones) moves too while it is
 * offline. Works from the console; one machine-readable line.
 */
public final class TameworkOwnerOfflineAgeCommand extends AbstractTameworkServerCommand {
    /** One year; keeps the millisecond sums far from overflow. */
    private static final double MAX_SECONDS = 31_536_000.0;

    private final RequiredArg<String> ownerArg =
            withRequiredArg("owner", "server.tamework.commands.ownerOfflineAge.argument.owner", STRING);
    private final RequiredArg<Double> secondsArg =
            withRequiredArg("seconds", "server.tamework.commands.ownerOfflineAge.argument.seconds", DOUBLE);

    public TameworkOwnerOfflineAgeCommand() {
        super("offline-age", "server.tamework.commands.ownerOfflineAge.description");
    }

    @Override
    protected void executeServer(@Nonnull CommandContext context) {
        UUID owner = parseUuid(ownerArg.get(context));
        if (owner == null) {
            context.sendMessage(Message.raw("refused reason=invalid-owner"));
            return;
        }
        double seconds = secondsArg.get(context);
        if (!Double.isFinite(seconds) || seconds <= 0.0 || seconds > MAX_SECONDS) {
            context.sendMessage(Message.raw("refused reason=invalid-seconds max=" + format(MAX_SECONDS)));
            return;
        }
        long ms = Math.round(seconds * 1000.0);
        OwnerPresenceTimelineService.OfflineAge aged =
                OwnerPresenceTimelineService.get().ageOfflineStartForDebug(owner, ms);
        if (aged == null) {
            context.sendMessage(Message.raw("refused reason=owner-online owner=" + owner));
            return;
        }
        StringBuilder reply = new StringBuilder("ok owner=").append(owner)
                .append(" movedSeconds=").append(format(ms / 1000.0))
                .append(" presenceOfflineSeconds=").append(format(aged.presenceOfflineMs() / 1000.0))
                .append(" progressionOfflineSeconds=").append(format(aged.progressionOfflineMs() / 1000.0));
        UUID sharedId = SharedPresenceService.get().presenceIdFor(owner, null);
        if (sharedId != null && !sharedId.equals(owner)) {
            OwnerPresenceTimelineService.OfflineAge shared =
                    OwnerPresenceTimelineService.get().ageOfflineStartForDebug(sharedId, ms);
            reply.append(" sharedId=").append(sharedId);
            if (shared == null) {
                // A player the owner shares with is online, so the shared animals count time anyway.
                reply.append(" shared=online");
            } else {
                reply.append(" sharedPresenceOfflineSeconds=").append(format(shared.presenceOfflineMs() / 1000.0))
                        .append(" sharedProgressionOfflineSeconds=")
                        .append(format(shared.progressionOfflineMs() / 1000.0));
            }
        }
        context.sendMessage(Message.raw(reply.toString()));
    }

    @Nullable
    private static UUID parseUuid(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", Double.isFinite(value) ? value : 0.0);
    }
}
