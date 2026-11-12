package com.alechilles.alecstamework.commands;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING;

import com.alechilles.alecstamework.access.OwnerGrants;
import com.alechilles.alecstamework.access.SharedAccessService;
import com.alechilles.alecstamework.api.access.AccessChangeResult;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Admin forms of {@code /tw share add|remove|list} that name the owner with {@code --owner <uuid|name>}
 * and work from the console, for tests and support. They call the same {@link SharedAccessService}
 * methods as the owner's own commands and skip the owner's confirm step. A grant records no actor, so
 * each change is written to the server log with the admin who made it. One machine-readable line each.
 */
final class TameworkDebugShareCommand extends AbstractCommandCollection {
    private static final String PREFIX = "server.tamework.commands.debugShare.";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    TameworkDebugShareCommand() {
        super("share", PREFIX + "description");
        addSubCommand(new Add());
        addSubCommand(new Remove());
        addSubCommand(new ListGrants());
    }

    /** A share leaf with the {@code --owner} option; refuses when it is missing or names nobody. */
    private abstract static class OwnerLeaf extends AbstractTameworkServerCommand {
        private final OptionalArg<String> ownerArg =
                withOptionalArg("owner", PREFIX + "argument.owner", STRING);

        OwnerLeaf(@Nonnull String name) {
            super(name, PREFIX + name + ".description");
        }

        @Override
        protected final void executeServer(@Nonnull CommandContext context) {
            if (!ownerArg.provided(context)) {
                context.sendMessage(Message.raw("refused reason=owner-required option=--owner"));
                return;
            }
            String raw = ownerArg.get(context);
            TameworkCommandPlayers.Named owner = TameworkCommandPlayers.resolve(raw);
            if (owner == null) {
                context.sendMessage(Message.raw("refused reason=unknown-owner value=" + token(raw)));
                return;
            }
            // The service is safe from any thread: reads are lookups, changes take its own lock.
            execute(context, SharedAccessService.get(), owner);
        }

        abstract void execute(@Nonnull CommandContext context, @Nonnull SharedAccessService service,
                              @Nonnull TameworkCommandPlayers.Named owner);
    }

    private static final class Add extends OwnerLeaf {
        private final RequiredArg<String> playerArg =
                withRequiredArg("player", PREFIX + "argument.player", STRING);
        private final RequiredArg<String> levelArg =
                withRequiredArg("level", PREFIX + "argument.level", STRING);

        Add() {
            super("add");
        }

        @Override
        void execute(@Nonnull CommandContext context, @Nonnull SharedAccessService service,
                     @Nonnull TameworkCommandPlayers.Named owner) {
            TameworkCommandPlayers.Named target = TameworkCommandPlayers.resolve(playerArg.get(context));
            if (target == null) {
                context.sendMessage(Message.raw("refused reason=unknown-player value=" + token(playerArg.get(context))));
                return;
            }
            AccessLevel level = TameworkShareRules.parseLevel(levelArg.get(context));
            if (level == null) {
                context.sendMessage(Message.raw("refused reason=invalid-level allowed=caretaker,handler,manager"));
                return;
            }
            boolean isNew = !service.grants(owner.uuid()).players().containsKey(target.uuid());
            AccessChangeResult result = service.grantPlayer(owner.uuid(), target.uuid(), target.name(), level);
            if (result != AccessChangeResult.CHANGED && result != AccessChangeResult.UNCHANGED) {
                context.sendMessage(Message.raw("refused reason=" + kebab(result) + " owner=" + owner.uuid()
                        + " player=" + target.uuid()));
                return;
            }
            if (result == AccessChangeResult.CHANGED) {
                LOGGER.at(Level.INFO).log("Admin %s shared the companions of %s with %s at %s",
                        context.sender().getUsername(), owner.uuid(), target.uuid(), TameworkShareRules.levelToken(level));
                // The player hears of it as from the owner's own command.
                PlayerRef online = onlinePlayer(target);
                if (online != null) {
                    TameworkShareRules.notifyGranted(online, TameworkShareRules.ownerName(owner.uuid()), level, isNew);
                }
            }
            context.sendMessage(Message.raw("ok owner=" + owner.uuid() + " player=" + target.uuid()
                    + " level=" + TameworkShareRules.levelToken(level) + " result=" + kebab(result)
                    + " new=" + isNew));
        }
    }

    private static final class Remove extends OwnerLeaf {
        private final RequiredArg<String> playerArg =
                withRequiredArg("player", PREFIX + "argument.player", STRING);

        Remove() {
            super("remove");
        }

        @Override
        void execute(@Nonnull CommandContext context, @Nonnull SharedAccessService service,
                     @Nonnull TameworkCommandPlayers.Named owner) {
            String value = playerArg.get(context);
            OwnerGrants.PlayerGrant grant = TameworkShareCommandGroup.namedGrant(service.grants(owner.uuid()), value);
            if (grant == null || service.revokePlayer(owner.uuid(), grant.playerId()) != AccessChangeResult.CHANGED) {
                context.sendMessage(Message.raw("refused reason=not-shared owner=" + owner.uuid()
                        + " value=" + token(value)));
                return;
            }
            LOGGER.at(Level.INFO).log("Admin %s stopped sharing the companions of %s with %s",
                    context.sender().getUsername(), owner.uuid(), grant.playerId());
            context.sendMessage(Message.raw("ok owner=" + owner.uuid() + " player=" + grant.playerId()
                    + " result=removed"));
        }
    }

    private static final class ListGrants extends OwnerLeaf {
        ListGrants() {
            super("list");
        }

        @Override
        void execute(@Nonnull CommandContext context, @Nonnull SharedAccessService service,
                     @Nonnull TameworkCommandPlayers.Named owner) {
            OwnerGrants grants = service.grants(owner.uuid());
            String players = grants.players().values().stream()
                    .map(grant -> grant.playerId() + ":" + TameworkShareRules.levelToken(grant.level()))
                    .sorted()
                    .collect(Collectors.joining(","));
            String groups = grants.groups().entrySet().stream()
                    .map((Map.Entry<String, AccessLevel> group) -> group.getKey() + ":"
                            + TameworkShareRules.levelToken(group.getValue()))
                    .sorted()
                    .collect(Collectors.joining(","));
            context.sendMessage(Message.raw("ok owner=" + owner.uuid()
                    + " players=" + (players.isEmpty() ? "none" : players)
                    + " groups=" + (groups.isEmpty() ? "none" : groups)
                    + " privateCompanions=" + grants.privateCompanions().size()));
        }
    }

    @Nullable
    private static PlayerRef onlinePlayer(@Nonnull TameworkCommandPlayers.Named player) {
        Universe universe = Universe.get();
        return universe == null ? null : universe.getPlayer(player.uuid());
    }

    @Nonnull
    private static String kebab(@Nonnull AccessChangeResult result) {
        return result.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** A typed value as one reply token. */
    @Nonnull
    private static String token(@Nullable String value) {
        return value == null || value.isBlank() ? "-" : value.trim().replaceAll("\\s+", "_");
    }
}
