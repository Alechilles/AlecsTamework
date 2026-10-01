package com.alechilles.alecstamework.api;

import java.util.EnumSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A companion profile changed. Published for every change that affects an owner's counts:
 * created or tamed, released or culled, owner changed, and every change of where the companion is.
 *
 * <p>{@code before} is absent for a new profile and {@code after} is absent for a released one.
 * The location kinds are {@code LIVE}, {@code ITEM}, {@code COOP}, {@code STORED}, {@code DEAD},
 * {@code LOST} or {@code RELEASED}; {@code oldLocationKind} is null for a new profile.
 * {@code groupIds} are the population groups of the profile's role. {@code domainClaims} are the
 * admission-provider claims the profile holds after the change; for a release they are the claims
 * it gave up. Events arrive after the change is applied, on the thread that made it; a change that
 * is undone is followed by an event for the compensating change.</p>
 */
public record NpcProfileChangedEvent(@Nonnull String profileId,
                                     @Nonnull EnumSet<ProfileChangeType> changeTypes,
                                     @Nullable NpcProfileView before,
                                     @Nullable NpcProfileView after,
                                     long emittedAtMs,
                                     @Nullable String oldLocationKind,
                                     @Nullable String newLocationKind,
                                     @Nonnull Set<String> groupIds,
                                     @Nonnull Set<PopulationDomainClaim> domainClaims) implements TameworkEvent {
    public NpcProfileChangedEvent {
        changeTypes = changeTypes.clone();
        groupIds = Set.copyOf(groupIds);
        domainClaims = Set.copyOf(domainClaims);
    }

    /** The event shape before 3.0.0: no location kinds, groups or claims. */
    public NpcProfileChangedEvent(@Nonnull String profileId,
                                  @Nonnull EnumSet<ProfileChangeType> changeTypes,
                                  @Nullable NpcProfileView before,
                                  @Nullable NpcProfileView after,
                                  long emittedAtMs) {
        this(profileId, changeTypes, before, after, emittedAtMs, null, null, Set.of(), Set.of());
    }
}
