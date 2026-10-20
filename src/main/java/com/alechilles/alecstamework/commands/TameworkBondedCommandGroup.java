package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.companion.bonded.IndexBondedCompanionApi;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Admin commands for bonded companions: {@code /tw bonded grant <player> <rosterId> <roleId> [name]}
 * gives an online player one stored bonded companion, for testing and support. The grant only
 * writes a companion record, so it works from the console and needs no world thread; the first
 * summon builds the body from the role.
 */
public final class TameworkBondedCommandGroup extends AbstractCommandCollection {
    private static final String PREFIX = "server.tamework.commands.bonded.";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * @param api     the current bonded API; gives null when bonded persistence is not running
     * @param rosters the current bonded roster snapshot, which gives the configured ids for the
     *                typed roster and role; gives null when no rosters are loaded
     */
    public TameworkBondedCommandGroup(@Nonnull Supplier<IndexBondedCompanionApi> api,
                                      @Nonnull Supplier<BondedCompanionRosterRegistry.Snapshot> rosters) {
        super("bonded", PREFIX + "description");
        requirePermission(TameworkCommandRoot.ROOT_PERMISSION);
        addSubCommand(new Grant(api, rosters));
    }

    private static final class Grant extends AbstractTameworkServerCommand {
        private final Supplier<IndexBondedCompanionApi> api;
        private final Supplier<BondedCompanionRosterRegistry.Snapshot> rosters;

        Grant(@Nonnull Supplier<IndexBondedCompanionApi> api,
              @Nonnull Supplier<BondedCompanionRosterRegistry.Snapshot> rosters) {
            super("grant", PREFIX + "grant.description");
            requirePermission(TameworkCommandRoot.ROOT_PERMISSION);
            setAllowsExtraArguments(true);
            this.api = api;
            this.rosters = rosters;
        }

        @Override
        protected void executeServer(@Nonnull CommandContext context) {
            String[] args = TameworkCommandInput.argumentsAfter(context.getInputString(), "grant");
            if (context.getInputString().contains("--") || args.length < 3) {
                context.sendMessage(Message.translation(PREFIX + "grant.usage"));
                return;
            }
            PlayerRef target = onlinePlayer(args[0], context);
            if (target == null) {
                context.sendMessage(Message.translation(PREFIX + "grant.playerNotOnline").param("player", args[0]));
                return;
            }
            IndexBondedCompanionApi bonded = api.get();
            if (bonded == null) {
                context.sendMessage(Message.translation(PREFIX + "unavailable"));
                return;
            }
            // The typed ids match the configured ones ignoring case; the companion stores the configured ids.
            BondedCompanionRosterRegistry.Snapshot snapshot = rosters.get();
            String rosterId = snapshot == null ? null : snapshot.canonicalRosterId(args[1]).orElse(null);
            if (rosterId == null) {
                context.sendMessage(Message.translation(PREFIX + "grant.unknownRoster").param("roster", args[1]));
                return;
            }
            String roleId = snapshot.canonicalRoleId(rosterId, args[2]).orElse(null);
            if (roleId == null) {
                context.sendMessage(Message.translation(PREFIX + "grant.roleNotAllowed")
                        .param("role", args[2]).param("roster", rosterId));
                return;
            }
            // A player-authored name stays as typed; with none the companion shows its role's name.
            String name = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;
            UUID owner = target.getUuid();
            String player = target.getUsername();
            boolean toSelf = context.isPlayer() && owner.equals(context.sender().getUuid());
            // Completes on the companion writer thread; it only sends messages, which is safe there.
            bonded.grantByAdmin(owner, rosterId, roleId, name).whenComplete((result, failure) -> {
                if (failure != null) {
                    LOGGER.at(Level.WARNING).withCause(failure).log(
                            "Admin grant of bonded %s in %s to %s failed", roleId, rosterId, owner);
                }
                BondedCompanionProfileView granted = result == null ? null : result.value();
                if (result == null || !result.successful() || granted == null) {
                    context.sendMessage(refusal(result, player, rosterId, roleId));
                    return;
                }
                LOGGER.at(Level.INFO).log("Admin granted bonded companion %s (%s in %s) to %s",
                        granted.profileId(), roleId, rosterId, owner);
                context.sendMessage(Message.translation(PREFIX + "grant.done")
                        .param("name", name == null ? roleId : name).param("player", player));
                if (!toSelf) {
                    target.sendMessage(name == null
                            ? Message.translation(PREFIX + "grant.received")
                            : Message.translation(PREFIX + "grant.receivedNamed").param("name", name));
                }
            });
        }

        @Nonnull
        private static Message refusal(@Nullable BondedCompanionResult<BondedCompanionProfileView> result,
                                       @Nonnull String player, @Nonnull String rosterId, @Nonnull String roleId) {
            if (result != null && result.code() == BondedCompanionResultCode.UNAVAILABLE) {
                return Message.translation(PREFIX + "unavailable");
            }
            String reason = result == null || result.reason() == null ? "error" : result.reason();
            return switch (reason) {
                case IndexBondedCompanionApi.ROLE_NOT_ALLOWED -> Message.translation(PREFIX + "grant.roleNotAllowed")
                        .param("role", roleId).param("roster", rosterId);
                case IndexBondedCompanionApi.FAMILY_CAPACITY -> Message.translation(PREFIX + "grant.familyLimit")
                        .param("player", player).param("role", roleId);
                case IndexBondedCompanionApi.OWNED_CAPACITY -> Message.translation(PREFIX + "grant.ownedLimit")
                        .param("player", player);
                // The reason is a diagnostic identifier, also written to the server log.
                default -> Message.translation(PREFIX + "grant.failed").param("reason", reason);
            };
        }

        /** The online player named by {@code self}, a UUID or a user name; null when not online. */
        @Nullable
        private static PlayerRef onlinePlayer(@Nonnull String value, @Nonnull CommandContext context) {
            UUID uuid = null;
            if ("self".equalsIgnoreCase(value)) {
                if (!context.isPlayer()) return null;
                uuid = context.sender().getUuid();
            } else {
                try { uuid = UUID.fromString(value); } catch (IllegalArgumentException ignored) { }
            }
            var universe = Universe.get();
            if (universe == null) return null;
            // PlayerRef identity metadata only; no live Player components.
            for (PlayerRef player : universe.getPlayers()) {
                if (uuid != null ? uuid.equals(player.getUuid()) : value.equalsIgnoreCase(player.getUsername())) {
                    return player;
                }
            }
            return null;
        }
    }
}
