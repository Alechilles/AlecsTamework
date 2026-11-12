package com.alechilles.alecstamework.activity;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.ActivityParticipantView;
import com.alechilles.alecstamework.api.HusbandryToolContext;
import com.alechilles.alecstamework.debug.CompanionXpEventDebugLogService;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Writes one line per published activity while {@code /tw debug log xp-events} is on, in one format for
 * every activity publisher (activity, companion, owner, actor). Does nothing, and builds no text, while
 * the switch is off. Call after the activity was published.
 */
public final class ActivityDebugLog {
    private ActivityDebugLog() {
    }

    /** An activity with companion participants; ids are comma-joined when there are several. */
    static void participants(@Nonnull String actionId,
                             @Nullable UUID operationId,
                             @Nonnull List<ActivityParticipantView> participants,
                             @Nullable UUID actorId,
                             @Nullable String profileId,
                             @Nullable HusbandryToolContext tool) {
        CompanionXpEventDebugLogService debugLog = active();
        if (debugLog == null) {
            return;
        }
        StringJoiner companions = new StringJoiner(",");
        StringJoiner owners = new StringJoiner(",");
        StringJoiner roles = new StringJoiner(",");
        for (ActivityParticipantView participant : participants) {
            companions.add(participant.companionId().toString());
            owners.add(text(participant.ownerId()));
            roles.add(participant.roleId() == null ? "<null>" : participant.roleId());
        }
        debugLog.logActivity(actionId, operationId, companions.toString(), owners.toString(), actorId,
                profileId, roles.toString(), tool != null && tool.present() ? tool.itemId() : null);
    }

    /** An activity that names only the player who acted. */
    static void player(@Nonnull String actionId, @Nullable UUID operationId,
                       @Nullable UUID playerId, @Nullable String profileId, @Nullable String roleId) {
        CompanionXpEventDebugLogService debugLog = active();
        if (debugLog != null) {
            debugLog.logActivity(actionId, operationId, null, null, playerId, profileId, roleId, null);
        }
    }

    /**
     * An activity about one or two entities (companions, or a combat source and target), with their
     * owners in the same order.
     */
    public static void entities(@Nonnull String actionId,
                                @Nullable UUID operationId,
                                @Nullable UUID firstId,
                                @Nullable UUID firstOwnerId,
                                @Nullable UUID secondId,
                                @Nullable UUID secondOwnerId,
                                @Nullable UUID actorId,
                                @Nullable String profileId,
                                @Nullable String roleId) {
        CompanionXpEventDebugLogService debugLog = active();
        if (debugLog == null) {
            return;
        }
        boolean pair = secondId != null;
        String companions = pair ? text(firstId) + "," + text(secondId) : text(firstId);
        String owners = pair ? text(firstOwnerId) + "," + text(secondOwnerId) : text(firstOwnerId);
        debugLog.logActivity(actionId, operationId, companions, owners, actorId, profileId, roleId, null);
    }

    @Nonnull
    private static String text(@Nullable UUID id) {
        return id == null ? "<null>" : id.toString();
    }

    @Nullable
    private static CompanionXpEventDebugLogService active() {
        Tamework instance = Tamework.getInstance();
        CompanionXpEventDebugLogService debugLog =
                instance == null ? null : instance.getCompanionXpEventDebugLogService();
        return debugLog != null && debugLog.isEnabled() ? debugLog : null;
    }
}
