package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CommandFamilyRosterApi;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Adapts the authoritative owner/family roster API to timed-summoning membership checks. */
public final class CommandFamilyRosterMembershipPort
        implements CommandTimedSummoningService.RosterMembershipPort {
    private final CommandFamilyRosterApi rosters;

    public CommandFamilyRosterMembershipPort(@Nonnull CommandFamilyRosterApi rosters) {
        this.rosters = Objects.requireNonNull(rosters, "rosters");
    }

    @Override
    public boolean contains(@Nonnull UUID ownerUuid,
                            @Nonnull String commandFamilyId,
                            @Nonnull String profileId) {
        return rosters.getMembership(ownerUuid, commandFamilyId, profileId).isPresent();
    }
}
