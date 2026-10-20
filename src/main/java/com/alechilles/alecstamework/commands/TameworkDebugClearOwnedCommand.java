package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.items.ReleasedBodyRemoval;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Preview and explicit confirmation for clearing a player's ordinary companions. Each record is
 * released through the companion index; loaded bodies are removed on their world thread and
 * unloaded bodies are removed by the fence when they next load.
 */
public final class TameworkDebugClearOwnedCommand extends AbstractTameworkServerCommand {
    private static final String PREFIX = "server.tamework.commands.clearOwned.";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    @Nullable private final ReleaseFlow releaseFlow;
    @Nullable private final CompanionQueries companions;

    public TameworkDebugClearOwnedCommand(@Nullable ReleaseFlow releaseFlow,
                                          @Nullable CompanionQueries companions) {
        super("clear-owned", PREFIX + "description");
        requirePermission(TameworkCommandRoot.ROOT_PERMISSION);
        setAllowsExtraArguments(true);
        this.releaseFlow = releaseFlow;
        this.companions = companions;
    }

    @Override protected void executeServer(@Nonnull CommandContext context) {
        String[] args = TameworkCommandInput.argumentsAfter(context.getInputString(), "clear-owned");
        if (context.getInputString().contains("--") || args.length > 2
                || args.length == 2 && !"confirm".equalsIgnoreCase(args[1])) {
            context.sendMessage(Message.translation(PREFIX + "usage")); return;
        }
        UUID owner = resolveOwner(args.length == 0 ? null : args[0], context);
        if (owner == null) { context.sendMessage(Message.translation(PREFIX + "unknownPlayer")); return; }
        if (releaseFlow == null || companions == null) {
            context.sendMessage(Message.translation(PREFIX + "unavailable")); return;
        }
        try {
            List<CompanionRecord> candidates = new ArrayList<>();
            int protectedCount = 0;
            for (CompanionRecord record : companions.owned(owner)) {
                if (clearable(record)) candidates.add(record);
                else protectedCount++;
            }
            if (args.length < 2) {
                context.sendMessage(Message.translation(PREFIX + "preview")
                        .param("owner", owner.toString()).param("count", candidates.size())
                        .param("skipped", protectedCount));
                return;
            }
            context.sendMessage(Message.translation(PREFIX + "started")
                    .param("owner", owner.toString()).param("count", candidates.size()));
            int cleared = 0;
            int skipped = protectedCount;
            int failed = 0;
            for (CompanionRecord record : candidates) {
                try {
                    ReleaseFlow.Outcome outcome = releaseFlow.release(record.profileId(), null);
                    if (outcome.result() != ReleaseFlow.Result.RELEASED) {
                        skipped++;
                        continue;
                    }
                    cleared++;
                    if (outcome.body() != null && record.currentNpcUuid() != null) {
                        ReleasedBodyRemoval.removeOnBodyWorld(outcome.body(), record.currentNpcUuid());
                    }
                } catch (RuntimeException failure) {
                    failed++;
                    LOGGER.at(Level.WARNING).withCause(failure).log("Could not clear companion %s of %s",
                            record.profileId(), owner);
                }
            }
            context.sendMessage(Message.translation(PREFIX + "completed").param("owner", owner.toString())
                    .param("cleared", cleared).param("skipped", skipped).param("failed", failed));
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Owned animal cleanup failed for %s", owner);
            context.sendMessage(Message.translation(PREFIX + "unavailable"));
        }
    }

    /** Only ordinary companions: captured, cooped, stored, roster-managed and bonded ones are kept. */
    private static boolean clearable(CompanionRecord record) {
        LocationKind kind = record.location().kind();
        return (kind == LocationKind.LIVE || kind == LocationKind.DEAD || kind == LocationKind.LOST)
                && !record.bonded() && record.rosterId() == null;
    }

    @Nullable private static UUID resolveOwner(String value, CommandContext context) {
        if (value == null || "self".equalsIgnoreCase(value)) return context.isPlayer() ? context.sender().getUuid() : null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { }
        var universe = Universe.get();
        if (universe == null) return null;
        // PlayerRef identity metadata only; no live Player components or tick scan.
        for (var player : universe.getPlayers()) {
            if (value.equalsIgnoreCase(player.getUsername())) return player.getUuid();
        }
        return null;
    }
}
