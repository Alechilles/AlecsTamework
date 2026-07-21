package com.alechilles.alecstamework.ownership.groups;

/** Atomic owned/active delta for one population-group bucket. */
public record PopulationGroupCountDelta(int owned, int active) {
    public PopulationGroupCountDelta plus(PopulationGroupCountDelta other) {
        return new PopulationGroupCountDelta(Math.addExact(owned, other.owned), Math.addExact(active, other.active));
    }

    public boolean isZero() { return owned == 0 && active == 0; }
    public boolean hasPositive() { return owned > 0 || active > 0; }
}
