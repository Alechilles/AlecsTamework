package com.alechilles.alecstamework.companion.bonded;

/** Signed-time lease expiry predicate shared by local recovery paths. */
public final class BondedCompanionExpirySystem {
    private BondedCompanionExpirySystem() { }

    /** Zero alone means unlimited; negative finite timestamps remain valid. */
    public static boolean isExpired(long expiresAtMs, long nowMs) {
        return expiresAtMs != 0L && expiresAtMs <= nowMs;
    }
}
