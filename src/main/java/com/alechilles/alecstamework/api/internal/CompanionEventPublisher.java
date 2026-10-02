package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcCapturedEvent;
import com.alechilles.alecstamework.api.NpcDeathRecordedEvent;
import com.alechilles.alecstamework.api.NpcLostRecordedEvent;
import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.api.ProfileChangeType;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import com.alechilles.alecstamework.companion.index.LocationKind;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Turns companion index changes into public API events (spec 8.10, 11). Register it as an
 * after-unlock listener: it publishes on the thread that made the change, with no index lock held,
 * so subscribers may read the API.
 *
 * <p>Every change that a subscriber can see publishes {@link NpcProfileChangedEvent}; a RELEASED
 * record has no profile view, so a release has no {@code after}. A companion going into a capture
 * item, dying or becoming lost also publishes its own event. Fields the record does not hold keep
 * their absent value: home positions are null, and the lost event's relocation fields are zero.</p>
 */
public final class CompanionEventPublisher implements CompanionIndex.ChangeListener {
    private final TameworkEventBus events;
    private final Function<String, ? extends Collection<String>> groupIdsForRole;
    private final LongSupplier clock;

    /**
     * @param groupIdsForRole the population group ids of a role id; called on the changing thread
     * @param clock           wall clock for {@code emittedAtMs}
     */
    public CompanionEventPublisher(@Nonnull TameworkEventBus events,
                                   @Nonnull Function<String, ? extends Collection<String>> groupIdsForRole,
                                   @Nonnull LongSupplier clock) {
        this.events = Objects.requireNonNull(events, "events");
        this.groupIdsForRole = Objects.requireNonNull(groupIdsForRole, "groupIdsForRole");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onChanged(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        long now = clock.getAsLong();
        LocationKind oldKind = before == null ? null : before.location().kind();
        LocationKind newKind = after.location().kind();
        NpcProfileView beforeView = before == null || oldKind == LocationKind.RELEASED
                ? null : CompanionRecordApiMapper.toProfileView(before);
        NpcProfileView afterView = newKind == LocationKind.RELEASED
                ? null : CompanionRecordApiMapper.toProfileView(after);

        publishProfileChanged(before, after, beforeView, afterView, now);
        if (afterView != null && oldKind != newKind) {
            // An unstamped body captured straight into an item is inserted as ITEM with its NPC UUID.
            UUID npcUuid = before == null ? after.currentNpcUuid() : before.currentNpcUuid();
            if (npcUuid != null) {
                publishHolderChange(before, after, afterView, npcUuid, now);
            }
        }
    }

    private void publishProfileChanged(@Nullable CompanionRecord before, CompanionRecord after,
                                       @Nullable NpcProfileView beforeView, @Nullable NpcProfileView afterView,
                                       long now) {
        if (beforeView == null && afterView == null) {
            return;
        }
        LocationKind oldKind = before == null ? null : before.location().kind();
        LocationKind newKind = after.location().kind();
        EnumSet<ProfileChangeType> types = CompanionProfileApiMapper.diff(beforeView, afterView);
        if (oldKind != newKind) {
            types.add(ProfileChangeType.LOCATION);
        }
        if (afterView == null) {
            types.add(ProfileChangeType.RELEASED);
        }
        boolean claimsChanged = before != null && !before.domainClaims().equals(after.domainClaims());
        if (types.isEmpty() && !claimsChanged) {
            // Position, summary and timer refreshes change nothing a subscriber can see.
            return;
        }
        // A release clears the record's claims; the event names the ones it gave up.
        List<DomainClaim> claims = afterView == null && before != null ? before.domainClaims() : after.domainClaims();
        events.publishProfileChanged(new NpcProfileChangedEvent(
                after.profileId().toString(),
                types,
                beforeView,
                afterView,
                now,
                oldKind == null ? null : oldKind.name(),
                newKind.name(),
                new LinkedHashSet<>(groupIdsForRole.apply(after.roleId())),
                claims(claims)
        ));
    }

    private void publishHolderChange(@Nullable CompanionRecord before, CompanionRecord after,
                                     NpcProfileView afterView, UUID npcUuid, long now) {
        LocationKind oldKind = before == null ? null : before.location().kind();
        Set<String> tools = new LinkedHashSet<>(after.toolIds());
        switch (after.location().kind()) {
            case ITEM -> {
                if (oldKind == null || oldKind == LocationKind.LIVE) {
                    events.publishCaptureRecorded(new NpcCapturedEvent(
                            afterView, npcUuid, after.ownerUuid(), tools, after.roleId(), after.displayName(),
                            before == null ? null : CompanionRecordApiMapper.lastKnownPosition(before), null,
                            after.updatedAtMs(), now));
                }
            }
            case DEAD -> {
                if (before != null) {
                    events.publishDeathRecorded(new NpcDeathRecordedEvent(
                            afterView, npcUuid, after.ownerUuid(), after.ownerName(), tools, after.roleId(),
                            after.displayName(), after.summary().customName(), afterView.tamed(),
                            CompanionRecordApiMapper.lastKnownPosition(before), null,
                            after.diedAtMs(), after.reviveAvailableAtMs(), now));
                }
            }
            case LOST -> {
                if (before != null) {
                    events.publishLostRecorded(new NpcLostRecordedEvent(
                            afterView, npcUuid, CompanionRecordApiMapper.lastKnownPosition(before), null,
                            0L, after.updatedAtMs(), 0, now));
                }
            }
            default -> { }
        }
    }

    /** Claims the public record would reject (blank domain, weight below one) are left out. */
    private static Set<PopulationDomainClaim> claims(List<DomainClaim> claims) {
        Set<PopulationDomainClaim> out = new LinkedHashSet<>();
        for (DomainClaim claim : claims) {
            if (claim.weight() > 0 && !claim.domainId().isBlank()) {
                out.add(new PopulationDomainClaim(claim.domainId(), claim.weight(), claim.owned(), claim.deployable()));
            }
        }
        return out;
    }
}
