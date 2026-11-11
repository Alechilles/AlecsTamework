package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.access.OwnerGrants;
import com.alechilles.alecstamework.access.SharedAccessService;
import com.alechilles.alecstamework.access.SharedAccessService.ChangeResult;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The sharing decisions that {@code /tw share} and the Sharing page both make, so the two cannot
 * drift: what a change would refuse before its confirmation, how leaving a share ends, which
 * translation key explains a refusal, and the names shown for owners and grants.
 *
 * <p>Every method reads memory only and is safe on any world thread. The service still checks
 * each change itself when it runs.</p>
 */
public final class TameworkShareRules {
    private TameworkShareRules() {
    }

    /**
     * What sharing with a new player would refuse, said before the confirmation so nobody
     * confirms in vain; {@code null} when the share would go ahead.
     */
    @Nullable
    public static ChangeResult addRefusal(@Nonnull SharedAccessService service, @Nonnull UUID owner,
                                          @Nonnull UUID target, @Nonnull AccessLevel level) {
        return !service.isEnabled() ? ChangeResult.SHARING_DISABLED
                : owner.equals(target) ? ChangeResult.SELF
                : !level.isGrantable() || level.compareTo(service.maxLevel()) > 0 ? ChangeResult.LEVEL_NOT_ALLOWED
                : service.grants(owner).players().size() >= SharedAccessService.MAX_PLAYER_GRANTS_PER_OWNER
                ? ChangeResult.TOO_MANY_PLAYERS : null;
    }

    /**
     * What turning a group on would refuse, said before the confirmation; {@code null} when it
     * would go ahead.
     */
    @Nullable
    public static ChangeResult groupRefusal(@Nonnull SharedAccessService service, @Nonnull String groupId,
                                            @Nonnull AccessLevel level) {
        return !service.isEnabled() ? ChangeResult.SHARING_DISABLED
                : service.groupProvider(groupId) == null ? ChangeResult.UNKNOWN_GROUP
                : !level.isGrantable() || level.compareTo(service.maxLevel()) > 0 ? ChangeResult.LEVEL_NOT_ALLOWED
                : null;
    }

    /**
     * The translation key, without the {@code server.} prefix, that explains a refused change. Its
     * one parameter, where it has one, is the highest level for {@code LEVEL_NOT_ALLOWED}, the
     * player limit for {@code TOO_MANY_PLAYERS} and the group id for {@code UNKNOWN_GROUP}.
     */
    @Nonnull
    public static String refusalKey(@Nonnull ChangeResult result) {
        return "tamework.share.error." + switch (result) {
            case SHARING_DISABLED -> "disabled";
            case SELF -> "self";
            case LEVEL_NOT_ALLOWED -> "levelNotAllowed";
            case TOO_MANY_PLAYERS -> "tooManyPlayers";
            case UNKNOWN_GROUP -> "unknownGroup";
            case CHANGED, UNCHANGED -> "unchanged";
        };
    }

    /** How a player's request to leave an owner's named share ended. */
    public enum LeaveResult {
        /** The player no longer has access to the owner's animals. */
        LEFT,
        /** The named share is gone, but a group still gives the player access. */
        LEFT_STILL_GROUP,
        /** The owner shares with the player only through a group, which cannot be left. */
        GROUP_ONLY;

        /** The reply's translation key, without the {@code server.} prefix; its parameter is the owner's name. */
        @Nonnull
        public String messageKey() {
            return switch (this) {
                case LEFT -> "tamework.share.leave.done";
                case LEFT_STILL_GROUP -> "tamework.share.leave.doneStillGroup";
                case GROUP_ONLY -> "tamework.share.leave.groupOnly";
            };
        }
    }

    /** Gives up {@code player}'s named grant on {@code owner}'s animals. Group membership belongs to the group. */
    @Nonnull
    public static LeaveResult leave(@Nonnull SharedAccessService service, @Nonnull UUID owner, @Nonnull UUID player) {
        if (service.revokePlayer(owner, player) != ChangeResult.CHANGED) {
            return LeaveResult.GROUP_ONLY;
        }
        return service.level(owner, player, null) != AccessLevel.NONE ? LeaveResult.LEFT_STILL_GROUP : LeaveResult.LEFT;
    }

    /** Tells the player an owner just shared with, or whose level changed, as the add command does. */
    public static void notifyGranted(@Nonnull PlayerRef target, @Nonnull String ownerName, @Nonnull AccessLevel level,
                                     boolean isNew) {
        target.sendMessage(Message.translation("server.tamework.share." + (isNew ? "add.notify" : "add.notifyChanged"))
                .param("0", ownerName).param("1", levelName(level)));
    }

    /** The level as typed in a command and used as a page value. */
    @Nonnull
    public static String levelToken(@Nonnull AccessLevel level) {
        return level.name().toLowerCase(Locale.ROOT);
    }

    /** The translation key, without the {@code server.} prefix, of a grantable level's name. */
    @Nonnull
    public static String levelKey(@Nonnull AccessLevel level) {
        return "tamework.share.level." + levelToken(level);
    }

    /** The translated name of a grantable level, for chat. */
    @Nonnull
    public static Message levelName(@Nonnull AccessLevel level) {
        return Message.translation("server." + levelKey(level));
    }

    /** The grantable level typed as {@code caretaker}, {@code handler} or {@code manager}; else null. */
    @Nullable
    public static AccessLevel parseLevel(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        for (AccessLevel level : AccessLevel.values()) {
            if (level.isGrantable() && level.name().equalsIgnoreCase(raw.trim())) {
                return level;
            }
        }
        return null;
    }

    /** The name saved with a grant, or the player's UUID so the grant can still be removed. */
    @Nonnull
    public static String grantName(@Nonnull OwnerGrants.PlayerGrant grant) {
        String name = grant.playerName();
        return name == null || name.isBlank() ? grant.playerId().toString() : name;
    }

    /**
     * A name for an owner: the online player's, else the one saved on one of the owner's companion
     * records, else the start of the UUID. Reads memory only.
     */
    @Nonnull
    public static String ownerName(@Nonnull UUID ownerId) {
        Universe universe = Universe.get();
        PlayerRef online = universe == null ? null : universe.getPlayer(ownerId);
        if (online != null && online.getUsername() != null) {
            return online.getUsername();
        }
        Tamework plugin = Tamework.getInstance();
        CompanionQueries companions = plugin == null ? null : plugin.getCompanionQueries();
        if (companions != null) {
            for (CompanionRecord record : companions.owned(ownerId)) {
                String name = record.ownerName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        return ownerId.toString().substring(0, 8);
    }
}
