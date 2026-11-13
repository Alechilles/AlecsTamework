package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.access.OwnerGrants;
import com.alechilles.alecstamework.access.SharedAccessService;
import com.alechilles.alecstamework.api.access.AccessChangeResult;
import com.alechilles.alecstamework.api.access.AccessGroupProvider;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@code /tw share add|remove|list|group|leave|private}: how a player shares their companions
 * with other players and groups, and leaves what others shared with them.
 *
 * <p>Every player may run these: each one changes only the caller's own grants, or the caller's
 * place in someone else's. A first grant to a player or a group asks for a trailing
 * {@code confirm}, because the owner's animals then keep living while those players are online.</p>
 */
public final class TameworkShareCommandGroup extends AbstractCommandCollection {
    static final String KEY = "server.tamework.share.";
    private static final String CONFIRM = "confirm";

    public TameworkShareCommandGroup() {
        super("share", KEY + "description");
        // Open to every player, like /tw card. A command that says nothing gets a generated
        // permission under the /tw admin permission, which would hide sharing from players.
        requireNoPermission();
        addSubCommand(new Add());
        addSubCommand(new Remove());
        addSubCommand(new TameworkShareListCommand());
        addSubCommand(new Group());
        addSubCommand(new Leave());
        addSubCommand(new Private());
    }

    /**
     * A share sub command. It runs on the calling player's world thread, is open to every player,
     * and reads its arguments from the raw input like the other Tamework commands.
     */
    abstract static class Leaf extends AbstractPlayerCommand {
        private final String token;

        Leaf(@Nonnull String token) {
            super(token, KEY + token + ".description");
            requireNoPermission();
            setAllowsExtraArguments(true);
            this.token = token;
        }

        @Nonnull
        final String[] arguments(@Nonnull CommandContext context) {
            return TameworkCommandInput.argumentsAfter(context.getInputString(), token);
        }
    }

    private static final class Add extends Leaf {
        Add() {
            super("add");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull World world) {
            String[] args = arguments(context);
            AccessLevel level = args.length >= 2 ? parseLevel(args[1]) : null;
            if (level == null || args.length > 3 || (args.length == 3 && !CONFIRM.equalsIgnoreCase(args[2]))) {
                context.sendMessage(Message.translation(KEY + "add.usage"));
                return;
            }
            PlayerRef target = onlinePlayer(args[0]);
            if (target == null) {
                context.sendMessage(Message.translation(KEY + "playerNotOnline").param("0", args[0]));
                return;
            }
            SharedAccessService service = SharedAccessService.get();
            UUID owner = playerRef.getUuid();
            UUID targetId = target.getUuid();
            String targetName = target.getUsername();
            boolean isNew = !service.grants(owner).players().containsKey(targetId);
            if (isNew && args.length < 3) {
                // What the confirmed run would refuse is said now, so nobody confirms in vain.
                AccessChangeResult refused = TameworkShareRules.addRefusal(service, owner, targetId, level);
                if (refused != null) {
                    context.sendMessage(refusal(refused, null));
                    return;
                }
                context.sendMessage(Message.translation(KEY + "add.confirm").param("0", targetName)
                        .param("1", levelName(level)).param("2", levelToken(level)));
                context.sendMessage(TameworkShareRules.levelAllows(level));
                return;
            }
            AccessChangeResult result = service.grantPlayer(owner, targetId, targetName, level);
            switch (result) {
                case CHANGED -> {
                    context.sendMessage(Message.translation(KEY + (isNew ? "add.done" : "add.changed"))
                            .param("0", targetName).param("1", levelName(level)));
                    TameworkShareRules.notifyGranted(target, playerRef.getUsername(), level, isNew);
                }
                case UNCHANGED -> context.sendMessage(Message.translation(KEY + "add.unchanged")
                        .param("0", targetName).param("1", levelName(level)));
                default -> context.sendMessage(refusal(result, null));
            }
        }
    }

    private static final class Remove extends Leaf {
        Remove() {
            super("remove");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull World world) {
            String[] args = arguments(context);
            if (args.length != 1) {
                context.sendMessage(Message.translation(KEY + "remove.usage"));
                return;
            }
            SharedAccessService service = SharedAccessService.get();
            UUID owner = playerRef.getUuid();
            OwnerGrants.PlayerGrant grant = namedGrant(service.grants(owner), args[0]);
            if (grant == null || service.revokePlayer(owner, grant.playerId()) != AccessChangeResult.CHANGED) {
                context.sendMessage(Message.translation(KEY + "remove.notShared").param("0", args[0]));
                return;
            }
            context.sendMessage(Message.translation(KEY + "remove.done").param("0", grantName(grant)));
        }
    }

    /**
     * The owner's grant to the player named by {@code value}: the name saved with the grant, so
     * it works while that player is offline, then the player's UUID, then their current name.
     */
    @Nullable
    static OwnerGrants.PlayerGrant namedGrant(@Nonnull OwnerGrants grants, @Nonnull String value) {
        for (OwnerGrants.PlayerGrant grant : grants.players().values()) {
            if (value.equalsIgnoreCase(grant.playerName()) || value.equalsIgnoreCase(grant.playerId().toString())) {
                return grant;
            }
        }
        PlayerRef online = onlinePlayer(value);
        return online == null ? null : grants.players().get(online.getUuid());
    }

    private static final class Group extends Leaf {
        Group() {
            super("group");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull World world) {
            String[] args = arguments(context);
            boolean off = args.length == 2 && "off".equalsIgnoreCase(args[1]);
            AccessLevel level = args.length >= 2 && !off ? parseLevel(args[1]) : null;
            if ((!off && level == null) || args.length > 3
                    || (args.length == 3 && !CONFIRM.equalsIgnoreCase(args[2]))) {
                context.sendMessage(Message.translation(KEY + "group.usage"));
                return;
            }
            SharedAccessService service = SharedAccessService.get();
            UUID owner = playerRef.getUuid();
            OwnerGrants grants = service.grants(owner);
            String groupId = canonicalGroupId(service, grants, args[0]);
            Message name = groupName(service, groupId);
            if (off) {
                boolean changed = service.setGroup(owner, groupId, null) == AccessChangeResult.CHANGED;
                context.sendMessage(Message.translation(KEY + (changed ? "group.off" : "group.notOn"))
                        .param("0", name));
                return;
            }
            if (!grants.groups().containsKey(groupId) && args.length < 3) {
                // What the confirmed run would refuse is said now, so nobody confirms in vain.
                AccessChangeResult refused = TameworkShareRules.groupRefusal(service, groupId, level);
                if (refused != null) {
                    context.sendMessage(refusal(refused, groupId));
                    return;
                }
                context.sendMessage(Message.translation(KEY + "group.confirm").param("0", name)
                        .param("1", levelName(level)).param("2", groupId).param("3", levelToken(level)));
                context.sendMessage(TameworkShareRules.levelAllows(level));
                return;
            }
            AccessChangeResult result = service.setGroup(owner, groupId, level);
            context.sendMessage(switch (result) {
                case CHANGED -> Message.translation(KEY + "group.on").param("0", name).param("1", levelName(level));
                case UNCHANGED -> Message.translation(KEY + "group.unchanged")
                        .param("0", name).param("1", levelName(level));
                default -> refusal(result, groupId);
            });
        }

        /** The registered or saved group id that {@code value} names, ignoring case; else {@code value}. */
        @Nonnull
        private static String canonicalGroupId(@Nonnull SharedAccessService service, @Nonnull OwnerGrants grants,
                                               @Nonnull String value) {
            for (AccessGroupProvider provider : service.groupProviders()) {
                if (value.equalsIgnoreCase(provider.id())) {
                    return provider.id();
                }
            }
            // A group whose provider is gone can still be turned off.
            for (String saved : grants.groups().keySet()) {
                if (value.equalsIgnoreCase(saved)) {
                    return saved;
                }
            }
            return value;
        }
    }

    private static final class Leave extends Leaf {
        Leave() {
            super("leave");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull World world) {
            String[] args = arguments(context);
            if (args.length != 1) {
                context.sendMessage(Message.translation(KEY + "leave.usage"));
                return;
            }
            SharedAccessService service = SharedAccessService.get();
            if (!service.isEnabled()) {
                context.sendMessage(refusal(AccessChangeResult.SHARING_DISABLED, null));
                return;
            }
            UUID sender = playerRef.getUuid();
            for (UUID owner : service.ownersSharingWith(sender)) {
                String name = ownerName(owner);
                if (!args[0].equalsIgnoreCase(name) && !args[0].equalsIgnoreCase(owner.toString())) {
                    continue;
                }
                // Only a named grant can be given up; group membership belongs to the group.
                TameworkShareRules.LeaveResult left = TameworkShareRules.leave(service, owner, sender);
                context.sendMessage(Message.translation("server." + left.messageKey()).param("0", name));
                return;
            }
            context.sendMessage(Message.translation(KEY + "leave.notFound").param("0", args[0]));
        }
    }

    private static final class Private extends Leaf {
        Private() {
            super("private");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull World world) {
            String[] args = arguments(context);
            boolean on = args.length == 1 && "on".equalsIgnoreCase(args[0]);
            if (args.length != 1 || (!on && !"off".equalsIgnoreCase(args[0]))) {
                context.sendMessage(Message.translation(KEY + "private.usage"));
                return;
            }
            TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
            if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
                context.sendMessage(Message.translation(KEY + "private.noTarget"));
                return;
            }
            // The private flag is the owner's own choice, so this compares identity, not access.
            UUID owner = playerRef.getUuid();
            ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
            TameworkOwnerComponent ownerComponent = ownerType == null ? null : store.getComponent(candidate.ref, ownerType);
            if (ownerComponent == null || !owner.equals(ownerComponent.getOwnerId())) {
                context.sendMessage(Message.translation(KEY + "private.notOwner"));
                return;
            }
            ComponentType<EntityStore, TameworkCompanionComponent> companionType =
                    TameworkCompanionComponent.getComponentType();
            TameworkCompanionComponent companion = companionType == null
                    ? null : store.getComponent(candidate.ref, companionType);
            UUID profileId = companion == null ? null : companion.getProfileId();
            if (profileId == null) {
                context.sendMessage(Message.translation(KEY + "private.unavailable"));
                return;
            }
            SharedAccessService service = SharedAccessService.get();
            if (!service.grants(owner).privateCompanions().contains(profileId) && service.isPrivate(owner, profileId)) {
                // Never shared whatever the flag says (a bonded companion), so the flag would mislead.
                context.sendMessage(Message.translation(KEY + "private.neverShared"));
                return;
            }
            boolean changed = service.setPrivate(owner, profileId, on) == AccessChangeResult.CHANGED;
            context.sendMessage(Message.translation(KEY + "private."
                    + (changed ? (on ? "on" : "off") : (on ? "alreadyOn" : "alreadyOff"))));
        }
    }

    /** The grantable level typed as {@code caretaker}, {@code handler} or {@code manager}; else null. */
    @Nullable
    static AccessLevel parseLevel(@Nonnull String raw) {
        return TameworkShareRules.parseLevel(raw);
    }

    /** The level as typed in a command. */
    @Nonnull
    static String levelToken(@Nonnull AccessLevel level) {
        return TameworkShareRules.levelToken(level);
    }

    /** The translated name of a grantable level. */
    @Nonnull
    static Message levelName(@Nonnull AccessLevel level) {
        return TameworkShareRules.levelName(level);
    }

    /** The reply for a change the service refused. {@code groupId} is only read for an unknown group. */
    @Nonnull
    static Message refusal(@Nonnull AccessChangeResult result, @Nullable String groupId) {
        Message message = Message.translation("server." + TameworkShareRules.refusalKey(result));
        return switch (result) {
            case LEVEL_NOT_ALLOWED -> message.param("0", levelName(SharedAccessService.get().maxLevel()));
            case TOO_MANY_PLAYERS -> message.param("0", SharedAccessService.MAX_PLAYER_GRANTS_PER_OWNER);
            case UNKNOWN_GROUP -> message.param("0", groupId == null ? "" : groupId);
            default -> message;
        };
    }

    /** The group's translated name when its provider is registered, else its id. */
    @Nonnull
    static Message groupName(@Nonnull SharedAccessService service, @Nonnull String groupId) {
        AccessGroupProvider provider = service.groupProvider(groupId);
        return provider == null ? Message.raw(groupId) : Message.translation(provider.displayNameKey());
    }

    /** The name saved with a grant, or the player's UUID so the grant can still be removed. */
    @Nonnull
    static String grantName(@Nonnull OwnerGrants.PlayerGrant grant) {
        return TameworkShareRules.grantName(grant);
    }

    /** A name for an owner; see {@link TameworkShareRules#ownerName}. Reads memory only. */
    @Nonnull
    static String ownerName(@Nonnull UUID ownerId) {
        return TameworkShareRules.ownerName(ownerId);
    }

    /** The online player with this name, ignoring case; null when not online. */
    @Nullable
    private static PlayerRef onlinePlayer(@Nonnull String name) {
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        // PlayerRef identity metadata only; no live Player components.
        for (PlayerRef player : universe.getPlayers()) {
            if (name.equalsIgnoreCase(player.getUsername())) {
                return player;
            }
        }
        return null;
    }
}
