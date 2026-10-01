package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.api.BondedCompanionLeaseView;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bonded companions as the companion index stores them: the public view of a bonded
 * {@link CompanionRecord}, its family and its family counts. Pure; all times are wall clock.
 *
 * <p>The family is derived, never stored (plan 6 R13): a record belongs to the one family of its
 * roster that allows its role. When no family or more than one allows the role, the record has
 * no family. It is still listed, under {@link #UNRESOLVED_FAMILY_ID} with every action
 * unavailable, so the owner keeps seeing it while the roster config is wrong, and it counts
 * toward no family caps.</p>
 */
public final class BondedRecords {
    /** {@code familyId} of a listed record whose role resolves to no single family. */
    public static final String UNRESOLVED_FAMILY_ID = "tamework:unresolved";
    /** Presentation key: the full length of the cooldown a stored or dead companion waits on. */
    public static final String COOLDOWN_DURATION_MS = "cooldownDurationMs";
    /** {@code roleId} of a listed record whose stored role id is blank; it resolves to no family. */
    public static final String UNKNOWN_ROLE_ID = "unknown";
    /** Tamework's own extension namespace. Public callers may not read or write it. */
    public static final String TAMEWORK_NAMESPACE = "tamework";
    /** The one extension value a caller namespace keeps per bonded companion. */
    public static final String EXTENSION_DATA_KEY = "bonded";
    /** Extension key of the capture evidence written when a companion is captured into storage (plan 6 R17). */
    public static final String CAPTURE_EVIDENCE_KEY = extensionKey(TAMEWORK_NAMESPACE, "bonded-capture");

    /** Finds the roster family that governs a role. */
    @FunctionalInterface
    public interface Families {
        /** @return the policy of the one family of {@code rosterId} allowing {@code roleId}, else null */
        @Nullable
        BondedCompanionPolicy resolve(@Nullable String rosterId, @Nonnull String roleId);
    }

    private BondedRecords() {
    }

    /** Resolves against the registry's current roster generation on every call. */
    @Nonnull
    public static Families families(@Nonnull BondedCompanionRosterRegistry registry) {
        BondedCompanionPolicyResolver resolver = new BondedCompanionPolicyResolver(registry);
        return (rosterId, roleId) -> {
            long revision = registry.snapshot().revision();
            // The resolver fences on the roster generation; a reload between the two reads retries.
            for (int attempt = 0; attempt < 3; attempt++) {
                BondedCompanionPolicyResolver.Resolution resolution =
                        resolver.resolveForRole(rosterId, null, roleId, revision);
                if (resolution.status() != BondedCompanionPolicyResolver.Status.REVISION_CONFLICT) {
                    return resolution.policy();
                }
                revision = resolution.activeRevision();
            }
            return null;
        };
    }

    /**
     * The key of a record extension entry: {@code namespace + "/" + key}, both trimmed. The same
     * layout as profile data, so {@code profileData()} reads a bonded extension value under
     * {@link #EXTENSION_DATA_KEY}.
     */
    @Nonnull
    public static String extensionKey(@Nonnull String namespace, @Nonnull String key) {
        return namespace.trim() + "/" + key.trim();
    }

    /**
     * Whether a public caller may use {@code namespace}. Tamework's own namespaces are reserved,
     * and a namespace with "/" could not be told apart from a shorter namespace with a longer key.
     */
    public static boolean publicNamespace(@Nullable String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return false;
        }
        String normalized = namespace.trim();
        return !normalized.contains("/") && !normalized.equalsIgnoreCase(TAMEWORK_NAMESPACE)
                && !normalized.equalsIgnoreCase("Alechilles:Tamework");
    }

    /**
     * The record's family policy; null when it is not bonded, has a blank role, or its role
     * resolves to no single family.
     */
    @Nullable
    public static BondedCompanionPolicy policy(@Nonnull CompanionRecord record, @Nonnull Families families) {
        return record.bonded() && record.rosterId() != null && !record.roleId().isBlank()
                ? families.resolve(record.rosterId(), record.roleId()) : null;
    }

    /**
     * The public state of a bonded record, or null when it is not listable. STORED (any reason)
     * and LOST are STORED: a body that vanished without a confirmed death went back to storage in
     * 4.x and was never charged a revive. LIVE is ACTIVE and DEAD is DEAD. ITEM, COOP and
     * RELEASED records, records with no owner or roster, and unbonded records are not listable.
     */
    @Nullable
    public static BondedCompanionStateView state(@Nonnull CompanionRecord record) {
        if (!record.bonded() || record.ownerUuid() == null || record.rosterId() == null
                || record.rosterId().isBlank()) {
            return null;
        }
        return switch (record.location().kind()) {
            case STORED, LOST -> BondedCompanionStateView.STORED;
            case LIVE -> BondedCompanionStateView.ACTIVE;
            case DEAD -> BondedCompanionStateView.DEAD;
            case ITEM, COOP, RELEASED -> null;
        };
    }

    /** True when {@code record} is a bonded record that counts toward the caps of {@code family}. */
    public static boolean inFamily(@Nonnull CompanionRecord record, @Nonnull BondedCompanionPolicy family,
                                   @Nonnull Families families) {
        // Cheap checks first: most of an owner's records are not in this roster at all.
        if (!record.bonded() || !record.countsAsOwned() || !family.rosterId().equals(record.rosterId())) {
            return false;
        }
        BondedCompanionPolicy own = policy(record, families);
        return own != null && own.rosterId().equals(family.rosterId()) && own.familyId().equals(family.familyId());
    }

    /** How many of {@code ownerRecords} are LIVE records of {@code family}. */
    public static int activeCount(@Nonnull Collection<CompanionRecord> ownerRecords,
                                  @Nonnull BondedCompanionPolicy family, @Nonnull Families families) {
        int n = 0;
        for (CompanionRecord record : ownerRecords) {
            if (record.isDeployed() && inFamily(record, family, families)) {
                n++;
            }
        }
        return n;
    }

    /**
     * The public view of a bonded record, or null when {@link #state} is null.
     *
     * <p>{@code summonAvailable}: STORED, the Summon feature is on, the summon cooldown has passed
     * and the family has a free active place. {@code storeAvailable}: ACTIVE and the Dismiss
     * feature is on. {@code reviveAvailable}: DEAD, the Revive feature is on, the revive cooldown
     * has passed and the family has a free active place, because a revived companion comes back
     * active. A dead companion whose family allows revival carries a quote.</p>
     *
     * @param ownerRecords     every record of the record's owner, for the family's active count
     * @param presentationData caller-supplied presentation entries; they win over the capacity
     *                         and cooldown entries added here
     */
    @Nullable
    public static BondedCompanionProfileView view(@Nonnull CompanionRecord record,
                                                  @Nonnull Collection<CompanionRecord> ownerRecords,
                                                  @Nonnull Families families, long nowMs,
                                                  @Nonnull Map<String, String> presentationData) {
        BondedCompanionStateView state = state(record);
        if (state == null) {
            return null;
        }
        BondedCompanionPolicy policy = policy(record, families);
        int active = policy == null ? 0 : activeCount(ownerRecords, policy, families);
        boolean activePlace = policy != null && (policy.maximumActive() == 0 || active < policy.maximumActive());
        boolean summon = policy != null && state == BondedCompanionStateView.STORED && policy.features().summon()
                && passed(record.summonCooldownUntilMs(), nowMs) && activePlace;
        boolean store = policy != null && state == BondedCompanionStateView.ACTIVE && policy.features().dismiss();
        BondedCompanionReviveQuote quote = policy == null ? null : reviveQuote(record, policy, nowMs);
        boolean revive = quote != null && quote.cooldownRemainingSeconds() == 0L && activePlace;
        LinkedHashMap<String, String> presentation = new LinkedHashMap<>();
        if (policy != null) {
            presentation.putAll(capacityAttributes(policy, active));
            if (state != BondedCompanionStateView.ACTIVE) {
                long seconds = state == BondedCompanionStateView.DEAD
                        ? policy.reviveCooldownSeconds() : policy.summonCooldownSeconds();
                presentation.put(COOLDOWN_DURATION_MS, Long.toString(millis(seconds)));
            }
        }
        presentation.putAll(Objects.requireNonNull(presentationData, "presentationData"));
        return new BondedCompanionProfileView(record.profileId().toString(), record.ownerUuid(), record.rosterId(),
                policy == null ? UNRESOLVED_FAMILY_ID : policy.familyId(),
                record.roleId().isBlank() ? UNKNOWN_ROLE_ID : record.roleId(), record.displayName(),
                null, null, record.generation(), state, summon, store, revive, presentation,
                state == BondedCompanionStateView.ACTIVE
                        ? lease(record, policy == null ? 0L : millis(policy.sessionDurationSeconds())) : null,
                record.summonCooldownUntilMs(), quote);
    }

    /**
     * The lease of a LIVE record (plan 6 R14): the token is the generation, the expiry is
     * {@code summonedUntilMs} (0 = unlimited). A timed lease started one session length before
     * its expiry; an unlimited one, or one with no known session length, started when the record
     * was last updated.
     *
     * @param sessionDurationMs the length of this companion's session, or 0 when unknown or unlimited
     */
    @Nonnull
    public static BondedCompanionLeaseView lease(@Nonnull CompanionRecord record, long sessionDurationMs) {
        String world = record.location().world();
        if (record.location().kind() != LocationKind.LIVE || world == null) {
            throw new IllegalArgumentException("only a LIVE record has a lease");
        }
        long expiresAt = record.summonedUntilMs();
        long startedAt = expiresAt == 0L ? record.updatedAtMs()
                : sessionDurationMs > 0L ? saturatingSubtract(expiresAt, sessionDurationMs)
                : Math.min(record.updatedAtMs(), expiresAt);
        return new BondedCompanionLeaseView(Long.toString(record.generation()), record.currentNpcUuid(), world,
                startedAt, expiresAt);
    }

    /**
     * The revive price and remaining cooldown of a DEAD record, without owned quantities (the
     * caller fills those from an inventory). Null when the record is not DEAD or the family's
     * Revive feature is off.
     */
    @Nullable
    public static BondedCompanionReviveQuote reviveQuote(@Nonnull CompanionRecord record,
                                                         @Nonnull BondedCompanionPolicy policy, long nowMs) {
        if (record.location().kind() != LocationKind.DEAD || !policy.features().revive()) {
            return null;
        }
        BondedCompanionPolicy.RevivePrice price = policy.revivePriceFor(record.roleId());
        List<BondedCompanionReviveQuote.CostLine> costs = new ArrayList<>();
        if (price != null) {
            for (BondedCompanionReviveCost cost : price.costs()) {
                costs.add(new BondedCompanionReviveQuote.CostLine(cost.itemId(), cost.quantity(), 0));
            }
        }
        long remainingMs = passed(record.reviveAvailableAtMs(), nowMs)
                ? 0L : saturatingSubtract(record.reviveAvailableAtMs(), nowMs);
        return new BondedCompanionReviveQuote(record.profileId().toString(), true, costs,
                remainingMs / 1_000L + (remainingMs % 1_000L == 0L ? 0L : 1L), policy.revision());
    }

    /** Capacity entries the panel shows for a family with an active limit; empty when it has none. */
    private static Map<String, String> capacityAttributes(BondedCompanionPolicy policy, int active) {
        if (policy.maximumActive() <= 0) {
            return Map.of();
        }
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_COUNT, Integer.toString(active));
        attributes.put(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LIMIT,
                Integer.toString(policy.maximumActive()));
        attributes.put(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LABEL, familyLabel(policy.familyId()));
        return attributes;
    }

    /** {@code hydragon:mini_wyvern} reads "Mini Wyvern", as in 4.x. */
    private static String familyLabel(String familyId) {
        String value = familyId.substring(familyId.lastIndexOf(':') + 1);
        StringBuilder label = new StringBuilder();
        for (String word : value.replace('-', '_').split("_")) {
            if (word.isBlank()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.isEmpty() ? familyId : label.toString();
    }

    /** True when a wall-clock deadline is unset (0) or reached. */
    private static boolean passed(long untilMs, long nowMs) {
        return untilMs == 0L || nowMs >= untilMs;
    }

    /** Saturates at {@code Long.MAX_VALUE}. */
    public static long millis(long seconds) {
        return TimeUnit.SECONDS.toMillis(seconds);
    }

    private static long saturatingSubtract(long value, long delta) {
        try {
            return Math.subtractExact(value, delta);
        } catch (ArithmeticException overflow) {
            return delta > 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
