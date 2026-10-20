package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.item.CaptureItemFlows;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.items.CommandCompanionPlacementService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Admin escape hatch for companions Tamework cannot reach (spec 8.14):
 * {@code /tw companions forget|restore <player> <companion> [confirm]}. Without {@code confirm}
 * the command only previews. The companion is a profile id or a display name of one of the
 * player's companions.
 */
public final class TameworkCompanionsCommandGroup extends AbstractCommandCollection {
    private static final String PREFIX = "server.tamework.commands.companions.";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double DEFAULT_SAFE_DISTANCE = 5D;

    /** Both are null when the companion index is not ready; the commands then report that. */
    public TameworkCompanionsCommandGroup(@Nullable CaptureItemFlows flows, @Nullable CompanionQueries companions) {
        super("companions", PREFIX + "description");
        requirePermission(TameworkCommandRoot.ROOT_PERMISSION);
        addSubCommand(new Forget(flows, companions));
        addSubCommand(new Restore(flows, companions));
    }

    /** The parsed {@code <player> <companion> [confirm]}; null owner when the player is unknown. */
    private record Target(@Nullable UUID owner, @Nonnull String ownerArg, @Nonnull String companionArg, boolean confirm) {
    }

    /** Null when the arguments do not fit; the caller prints the usage. */
    @Nullable
    private static Target parse(@Nonnull CommandContext context, @Nonnull String token) {
        String[] args = TameworkCommandInput.argumentsAfter(context.getInputString(), token);
        if (context.getInputString().contains("--") || args.length < 2) {
            return null;
        }
        boolean confirm = args.length > 2 && "confirm".equalsIgnoreCase(args[args.length - 1]);
        String companion = String.join(" ", Arrays.copyOfRange(args, 1, confirm ? args.length - 1 : args.length));
        return new Target(resolveOwner(args[0], context), args[0], companion, confirm);
    }

    /**
     * Finds the owner's companion by profile id or display name. Sends the not found or ambiguous
     * message itself and returns null then.
     */
    @Nullable
    private static CompanionRecord find(@Nonnull CompanionQueries companions, @Nonnull Target target,
                                        @Nonnull CommandContext context) {
        List<CompanionRecord> owned = companions.owned(target.owner());
        List<CompanionRecord> matches = owned.stream()
                .filter(record -> record.profileId().toString().equalsIgnoreCase(target.companionArg()))
                .toList();
        if (matches.isEmpty()) {
            matches = owned.stream()
                    .filter(record -> target.companionArg().equalsIgnoreCase(record.displayName()))
                    .toList();
        }
        if (matches.isEmpty()) {
            context.sendMessage(Message.translation(PREFIX + "notFound")
                    .param("owner", target.ownerArg()).param("companion", target.companionArg()));
            return null;
        }
        if (matches.size() > 1) {
            context.sendMessage(Message.translation(PREFIX + "ambiguous")
                    .param("owner", target.ownerArg()).param("companion", target.companionArg())
                    .param("count", matches.size())
                    .param("ids", String.join(", ", matches.stream().map(r -> r.profileId().toString()).toList())));
            return null;
        }
        return matches.get(0);
    }

    /** The record's display name, or its profile id when it has none. Player-authored names stay as they are. */
    @Nonnull
    private static String name(@Nonnull CompanionRecord record) {
        String name = record.displayName();
        return name == null || name.isBlank() ? record.profileId().toString() : name;
    }

    @Nullable
    private static UUID resolveOwner(@Nonnull String value, @Nonnull CommandContext context) {
        if ("self".equalsIgnoreCase(value)) return context.isPlayer() ? context.sender().getUuid() : null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { }
        var universe = Universe.get();
        if (universe == null) return null;
        // PlayerRef identity metadata only; no live Player components.
        for (var player : universe.getPlayers()) {
            if (value.equalsIgnoreCase(player.getUsername())) return player.getUuid();
        }
        return null;
    }

    /** Makes an ITEM record a FORGOTTEN tombstone, freeing the owner's slot. Works from the console. */
    private static final class Forget extends AbstractTameworkServerCommand {
        @Nullable private final CaptureItemFlows flows;
        @Nullable private final CompanionQueries companions;

        Forget(@Nullable CaptureItemFlows flows, @Nullable CompanionQueries companions) {
            super("forget", PREFIX + "forget.description");
            requirePermission(TameworkCommandRoot.ROOT_PERMISSION);
            setAllowsExtraArguments(true);
            this.flows = flows;
            this.companions = companions;
        }

        @Override protected void executeServer(@Nonnull CommandContext context) {
            Target target = parse(context, "forget");
            if (target == null) { context.sendMessage(Message.translation(PREFIX + "forget.usage")); return; }
            if (target.owner() == null) { context.sendMessage(Message.translation(PREFIX + "unknownPlayer")); return; }
            if (flows == null || companions == null) { context.sendMessage(Message.translation(PREFIX + "unavailable")); return; }
            CompanionRecord record = find(companions, target, context);
            if (record == null) return;
            String name = name(record);
            // Bonded companions keep their own flows.
            if (record.bonded() || record.location().kind() != LocationKind.ITEM) {
                context.sendMessage(Message.translation(PREFIX + "forget.nothingToDo").param("name", name));
                return;
            }
            if (!target.confirm()) {
                context.sendMessage(Message.translation(PREFIX + "forget.preview").param("name", name)
                        .param("owner", target.ownerArg()).param("id", record.profileId().toString()));
                return;
            }
            CaptureItemFlows.Result result = flows.forget(record.profileId(), null);
            switch (result) {
                case FORGOTTEN -> {
                    LOGGER.at(Level.INFO).log("Admin forgot companion %s of %s", record.profileId(), target.owner());
                    context.sendMessage(Message.translation(PREFIX + "forget.done").param("name", name)
                            .param("owner", target.ownerArg()));
                }
                case NOT_IN_ITEM -> context.sendMessage(Message.translation(PREFIX + "forget.nothingToDo").param("name", name));
                default -> context.sendMessage(Message.translation(PREFIX + "notFound")
                        .param("owner", target.ownerArg()).param("companion", target.companionArg()));
            }
        }
    }

    /**
     * Restores an ITEM, COOP or LOST record next to the admin with the RECOVER reason. Runs on the
     * admin's world thread, which the placement needs; the result arrives later on that thread.
     */
    private static final class Restore extends AbstractPlayerCommand {
        @Nullable private final CaptureItemFlows flows;
        @Nullable private final CompanionQueries companions;
        private final CommandCompanionPlacementService placements = new CommandCompanionPlacementService();

        Restore(@Nullable CaptureItemFlows flows, @Nullable CompanionQueries companions) {
            super("restore", PREFIX + "restore.description");
            requirePermission(TameworkCommandRoot.ROOT_PERMISSION);
            setAllowsExtraArguments(true);
            this.flows = flows;
            this.companions = companions;
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            Target target = parse(context, "restore");
            if (target == null) { context.sendMessage(Message.translation(PREFIX + "restore.usage")); return; }
            if (target.owner() == null) { context.sendMessage(Message.translation(PREFIX + "unknownPlayer")); return; }
            if (flows == null || companions == null) { context.sendMessage(Message.translation(PREFIX + "unavailable")); return; }
            CompanionRecord record = find(companions, target, context);
            if (record == null) return;
            String name = name(record);
            LocationKind kind = record.location().kind();
            // Bonded companions keep their own recovery flow.
            if (record.bonded() || kind != LocationKind.ITEM && kind != LocationKind.COOP && kind != LocationKind.LOST) {
                context.sendMessage(Message.translation(PREFIX + "restore.nothingToDo").param("name", name));
                return;
            }
            if (!target.confirm()) {
                context.sendMessage(Message.translation(PREFIX + "restore.preview").param("name", name)
                        .param("owner", target.ownerArg()).param("id", record.profileId().toString()));
                return;
            }
            CompanionSpawnPlacement placement = placements.computeRestorationPlacement(
                    ref, store, safeDistance(record.roleId()), record.roleId(), null);
            if (placement == null) {
                context.sendMessage(Message.translation(PREFIX + "restore.noPlacement"));
                return;
            }
            context.sendMessage(Message.translation(PREFIX + "restore.started").param("name", name));
            UUID profileId = record.profileId();
            flows.recall(profileId, RestoreFlow.Destination.of(placement)).whenComplete((result, failure) -> {
                if (failure != null) {
                    LOGGER.at(Level.WARNING).withCause(failure).log("Admin restore of companion %s failed", profileId);
                }
                Message message = result == RestoreFlow.Result.RESTORED
                        ? Message.translation(PREFIX + "restore.done").param("name", name)
                        : Message.translation(PREFIX + "restore.failed").param("name", name)
                        .param("result", result == null ? "ERROR" : result.name());
                try {
                    world.execute(() -> context.sendMessage(message));
                } catch (RuntimeException closed) {
                    LOGGER.at(Level.FINE).log("Could not report the restore of %s: %s", profileId, closed);
                }
            });
        }

        private static double safeDistance(@Nullable String roleId) {
            TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.resolveEffectiveForRole(roleId);
            double configured = settings == null ? Double.NaN : settings.getRecallSafeSpawnDistance();
            return Double.isFinite(configured) && configured > 0D ? configured : DEFAULT_SAFE_DISTANCE;
        }
    }
}
