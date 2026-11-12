package com.alechilles.alecstamework.commands;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.DOUBLE;
import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.RELATIVE_BLOCK_POSITION;

import com.alechilles.alecstamework.companion.coop.HytaleCoopResidents;
import com.alechilles.alecstamework.items.HytaleDirectLiveCoopScanner;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeIntPosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * Debug hook: moves one coop's produce clock forward by game seconds, never the world's time of
 * day ({@link HytaleCoopResidents#advanceProduce}). The coop is found by its block position, as the
 * coop schedule finds it, and must be loaded. Runs on the coop's world thread and replies there,
 * with one machine-readable line; works from the console.
 */
public final class TameworkAdvanceCoopCommand extends AbstractWorldCommand {
    /** One game year; keeps the millisecond sums far from overflow. */
    private static final double MAX_SECONDS = 31_536_000.0;

    private final RequiredArg<Double> secondsArg =
            withRequiredArg("seconds", "server.tamework.commands.advanceCoop.argument.seconds", DOUBLE);
    private final OptionalArg<RelativeIntPosition> atArg =
            withOptionalArg("at", "server.tamework.commands.advanceCoop.argument.at", RELATIVE_BLOCK_POSITION);
    private final HytaleDirectLiveCoopScanner scanner = new HytaleDirectLiveCoopScanner();

    public TameworkAdvanceCoopCommand() {
        super("advance", "server.tamework.commands.advanceCoop.description");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
        double seconds = secondsArg.get(context);
        long ms = Double.isFinite(seconds) && seconds > 0.0 && seconds <= MAX_SECONDS ? Math.round(seconds * 1000.0) : 0L;
        if (ms <= 0L) {
            context.sendMessage(Message.raw("refused reason=invalid-seconds max="
                    + TameworkTraitCommandSupport.formatDouble(MAX_SECONDS)));
            return;
        }
        if (!atArg.provided(context)) {
            context.sendMessage(Message.raw("refused reason=position-required option=--at"));
            return;
        }
        RelativeIntPosition at = atArg.get(context);
        Ref<EntityStore> sender = context.isPlayer() ? context.senderAsPlayerRef() : null;
        boolean senderHere = sender != null && sender.isValid() && sender.getStore() == store;
        if (at.isRelative() && !senderHere) {
            // A relative position needs the sender in this world (the engine refuses it for the console).
            context.sendMessage(Message.raw("refused reason=absolute-position-required option=--at"));
            return;
        }
        ChunkStore chunks = world.getChunkStore();
        Store<ChunkStore> chunkStore = chunks == null ? null : chunks.getStore();
        Vector3i block = at.isRelative() ? at.getBlockPosition(sender, store)
                : chunks == null ? null : at.getBlockPosition(new Vector3d(), chunks);
        HytaleCoopResidents residents = HytaleCoopResidents.current();
        if (residents == null || chunkStore == null || block == null) {
            context.sendMessage(Message.raw("refused reason=coops-off world=" + world.getName()));
            return;
        }
        String where = " world=" + world.getName() + " x=" + block.x + " y=" + block.y + " z=" + block.z;
        HytaleDirectLiveCoopScanner.Scan scan = scanner.scan(chunkStore, (coop, time) -> false);
        HytaleDirectLiveCoopScanner.LoadedCoop coop = scan == null ? null : find(scan, block);
        if (coop == null) {
            // Not a managed coop block, or its chunk is not loaded (keep a player or the keep-area near it).
            context.sendMessage(Message.raw("refused reason=no-coop" + where));
            return;
        }
        HytaleCoopResidents.ProduceAdvance result = residents.advanceProduce(world, coop, scan.worldTime(), ms);
        String head = " coop=" + coop.coopId() + where;
        switch (result.status()) {
            case ROAMING_NOW -> context.sendMessage(Message.raw("refused reason=roaming-now" + head));
            case NO_GAME_TIME -> context.sendMessage(Message.raw("refused reason=no-game-time" + head));
            case UNREADABLE -> context.sendMessage(Message.raw("refused reason=coop-unreadable" + head));
            case PRODUCED, NOT_REACHED -> context.sendMessage(Message.raw(okReply(head, ms, result)));
        }
    }

    private static String okReply(String head, long requestedMs, HytaleCoopResidents.ProduceAdvance result) {
        boolean produced = result.status() == HytaleCoopResidents.AdvanceStatus.PRODUCED;
        return "ok" + head
                + " requestedGameSeconds=" + TameworkTraitCommandSupport.formatDouble(requestedMs / 1000.0)
                + " untilRoamGameSeconds=" + TameworkTraitCommandSupport.formatDouble(result.untilRoamMs() / 1000.0)
                + " produced=" + produced
                + (produced ? " producedAtGameMs=" + result.produceAtMs() : "")
                + " residents=" + result.residents()
                + " items=" + result.itemsBefore() + "->" + result.itemsAfter();
    }

    private static HytaleDirectLiveCoopScanner.LoadedCoop find(HytaleDirectLiveCoopScanner.Scan scan, Vector3i block) {
        for (HytaleDirectLiveCoopScanner.LoadedCoop coop : scan.coops()) {
            Vector3i at = coop.block();
            if (at.x == block.x && at.y == block.y && at.z == block.z) {
                return coop;
            }
        }
        return null;
    }
}
