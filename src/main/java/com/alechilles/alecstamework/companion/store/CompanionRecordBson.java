package com.alechilles.alecstamework.companion.store;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.RecordScope;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Maps {@link CompanionRecord} to the owner-file BSON form and back. Field names are a
 * save format: add fields with defaults, never rename or reuse them. A record that
 * cannot be decoded throws {@link IllegalArgumentException} so the store can keep it
 * byte-for-byte instead of guessing.
 */
public final class CompanionRecordBson {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private CompanionRecordBson() {
    }

    @Nonnull
    public static BsonDocument encode(@Nonnull CompanionRecord r) {
        BsonDocument d = new BsonDocument();
        d.put("ProfileId", new BsonString(r.profileId().toString()));
        d.put("Revision", new BsonInt64(r.revision()));
        d.put("Generation", new BsonInt64(r.generation()));
        putUuid(d, "Owner", r.ownerUuid());
        putString(d, "OwnerName", r.ownerName());
        d.put("Role", new BsonString(r.roleId()));
        putString(d, "Name", r.displayName());
        d.put("Location", encodeLocation(r.location()));
        d.put("Scope", new BsonString(r.scope().name()));
        putString(d, "HomeWorld", r.homeWorld());
        putUuid(d, "NpcUuid", r.currentNpcUuid());
        d.put("Summary", encodeSummary(r.summary()));
        if (r.rosterId() != null || r.bonded() || r.rosterSlot() >= 0) {
            BsonDocument roster = new BsonDocument();
            putString(roster, "Id", r.rosterId());
            roster.put("Slot", new BsonInt32(r.rosterSlot()));
            roster.put("Bonded", BsonBoolean.valueOf(r.bonded()));
            d.put("Roster", roster);
        }
        BsonDocument timers = new BsonDocument();
        timers.put("SummonedUntil", new BsonInt64(r.summonedUntilMs()));
        timers.put("SummonCooldownUntil", new BsonInt64(r.summonCooldownUntilMs()));
        timers.put("ReviveAvailableAt", new BsonInt64(r.reviveAvailableAtMs()));
        timers.put("DiedAt", new BsonInt64(r.diedAtMs()));
        timers.put("LastSnapshotAt", new BsonInt64(r.lastSnapshotAtMs()));
        d.put("Timers", timers);
        if (r.originNamespace() != null) {
            d.put("Origin", new BsonDocument("Namespace", new BsonString(r.originNamespace()))
                    .append("Key", new BsonString(r.originKey())));
        }
        BsonArray tools = new BsonArray();
        r.toolIds().forEach(t -> tools.add(new BsonString(t)));
        d.put("ToolIds", tools);
        BsonDocument extensions = new BsonDocument();
        r.extensions().forEach((key, entry) -> extensions.put(key,
                new BsonDocument("Revision", new BsonInt64(entry.revision())).append("Value", new BsonString(entry.json()))));
        d.put("Extensions", extensions);
        BsonArray claims = new BsonArray();
        for (DomainClaim c : r.domainClaims()) {
            claims.add(new BsonDocument("Domain", new BsonString(c.domainId()))
                    .append("Weight", new BsonInt32(c.weight()))
                    .append("Owned", BsonBoolean.valueOf(c.owned()))
                    .append("Deployable", BsonBoolean.valueOf(c.deployable())));
        }
        d.put("DomainClaims", claims);
        d.put("UpdatedAt", new BsonInt64(r.updatedAtMs()));
        return d;
    }

    @Nonnull
    public static CompanionRecord decode(@Nonnull BsonDocument d) {
        try {
            UUID profileId = UUID.fromString(requireString(d, "ProfileId"));
            CompanionRecord.Builder b = CompanionRecord.builder(profileId, requireString(d, "Role"),
                    decodeLocation(requireDocument(d, "Location")));
            b.revision(getStrictLong(d, "Revision")).generation(getStrictLong(d, "Generation"));
            b.ownerUuid(getUuid(d, "Owner")).ownerName(getString(d, "OwnerName"));
            b.displayName(getString(d, "Name"));
            // The scope is derived from the location; the stored value is still validated so a
            // malformed or future value keeps the record unreadable and preserved.
            String scope = getStrictString(d, "Scope");
            if (scope != null) {
                RecordScope.valueOf(scope);
            }
            b.homeWorld(getString(d, "HomeWorld")).currentNpcUuid(getUuid(d, "NpcUuid"));
            if (d.isDocument("Summary")) {
                b.summary(decodeSummary(d.getDocument("Summary")));
            }
            if (d.isDocument("Roster")) {
                BsonDocument roster = d.getDocument("Roster");
                b.rosterId(getString(roster, "Id")).rosterSlot((int) getLong(roster, "Slot", -1))
                        .bonded(getBoolean(roster, "Bonded"));
            }
            if (d.isDocument("Timers")) {
                BsonDocument t = d.getDocument("Timers");
                b.summonedUntilMs(getLong(t, "SummonedUntil", 0))
                        .summonCooldownUntilMs(getLong(t, "SummonCooldownUntil", 0))
                        .reviveAvailableAtMs(getLong(t, "ReviveAvailableAt", 0))
                        .diedAtMs(getLong(t, "DiedAt", 0))
                        .lastSnapshotAtMs(getLong(t, "LastSnapshotAt", 0));
            }
            if (d.isDocument("Origin")) {
                BsonDocument o = d.getDocument("Origin");
                b.origin(requireString(o, "Namespace"), requireString(o, "Key"));
            }
            if (d.isArray("ToolIds")) {
                List<String> tools = new ArrayList<>();
                for (BsonValue v : d.getArray("ToolIds")) {
                    tools.add(v.asString().getValue());
                }
                b.toolIds(tools);
            }
            if (d.isDocument("Extensions")) {
                Map<String, ExtensionEntry> extensions = new LinkedHashMap<>();
                for (Map.Entry<String, BsonValue> e : d.getDocument("Extensions").entrySet()) {
                    BsonDocument entry = e.getValue().asDocument();
                    extensions.put(e.getKey(), new ExtensionEntry(getLong(entry, "Revision", 0), requireString(entry, "Value")));
                }
                b.extensions(extensions);
            }
            if (d.isArray("DomainClaims")) {
                List<DomainClaim> claims = new ArrayList<>();
                for (BsonValue v : d.getArray("DomainClaims")) {
                    BsonDocument c = v.asDocument();
                    long weight = getLong(c, "Weight", 0);
                    if (weight <= 0 || weight > Integer.MAX_VALUE) {
                        // A claim that cannot count is dropped, so one bad entry does not make the
                        // record (and the rest of the owner's file) unreadable.
                        LOGGER.at(Level.WARNING).log("Dropped domain claim %s of companion %s: weight %d is not valid",
                                c.get("Domain"), profileId, weight);
                        continue;
                    }
                    claims.add(new DomainClaim(requireString(c, "Domain"), (int) weight,
                            getBoolean(c, "Owned"), getBoolean(c, "Deployable")));
                }
                b.domainClaims(claims);
            }
            b.updatedAtMs(getLong(d, "UpdatedAt", 0));
            return b.build();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unreadable companion record: " + e.getMessage(), e);
        }
    }

    private static BsonDocument encodeLocation(CompanionLocation l) {
        BsonDocument d = new BsonDocument("Kind", new BsonString(l.kind().name()));
        putString(d, "World", l.world());
        if (l.kind() == LocationKind.LIVE || l.kind() == LocationKind.COOP) {
            d.put("X", new BsonDouble(l.x()));
            d.put("Y", new BsonDouble(l.y()));
            d.put("Z", new BsonDouble(l.z()));
        }
        if (l.slot() >= 0) {
            d.put("Slot", new BsonInt32(l.slot()));
        }
        if (l.reason() != null) {
            d.put("Reason", new BsonString(l.reason().name()));
        }
        putString(d, "Cause", l.cause());
        return d;
    }

    private static CompanionLocation decodeLocation(BsonDocument d) {
        LocationKind kind = LocationKind.valueOf(requireString(d, "Kind"));
        String reason = getString(d, "Reason");
        return new CompanionLocation(kind, getString(d, "World"), getDouble(d, "X"), getDouble(d, "Y"), getDouble(d, "Z"),
                (int) getLong(d, "Slot", -1), reason == null ? null : StoredReason.valueOf(reason), getString(d, "Cause"));
    }

    private static BsonDocument encodeSummary(CompanionSummary s) {
        BsonDocument d = new BsonDocument();
        putString(d, "CustomName", s.customName());
        putString(d, "NameKey", s.nameKey());
        putString(d, "Role", s.roleId());
        putString(d, "Icon", s.iconId());
        d.put("HealthCurrent", new BsonDouble(s.healthCurrent()));
        d.put("HealthMax", new BsonDouble(s.healthMax()));
        putString(d, "HappinessConfig", s.happinessConfigId());
        d.put("Happiness", new BsonDouble(s.happiness()));
        putString(d, "NeedsConfig", s.needsConfigId());
        d.put("Hunger", new BsonDouble(s.hunger()));
        d.put("Thirst", new BsonDouble(s.thirst()));
        d.put("BreedingPresent", BsonBoolean.valueOf(s.breedingPresent()));
        d.put("BreedingEnabled", BsonBoolean.valueOf(s.breedingEnabled()));
        d.put("BreedingCooldownUntil", new BsonInt64(s.breedingCooldownUntilMs()));
        d.put("BreedingCooldownStartedAt", new BsonInt64(s.breedingCooldownStartedAtMs()));
        d.put("BreedingCooldownDuration", new BsonInt64(s.breedingCooldownDurationMs()));
        d.put("HarvestAlarmUntil", new BsonInt64(s.harvestAlarmUntilMs()));
        putString(d, "LevelingConfig", s.levelingConfigId());
        d.put("Level", new BsonInt32(s.level()));
        d.put("CurrentXp", new BsonDouble(s.currentXp()));
        d.put("TotalXp", new BsonDouble(s.totalXp()));
        d.put("TalentPointsSpent", new BsonInt32(s.talentPointsSpent()));
        BsonDocument traits = new BsonDocument();
        s.traits().forEach((id, value) -> traits.put(id, new BsonDouble(value)));
        d.put("Traits", traits);
        d.put("ObservedAt", new BsonInt64(s.observedAtMs()));
        d.put("HarvestAlarmStartedAt", new BsonInt64(s.harvestAlarmStartedAtMs()));
        d.put("HarvestAlarmDuration", new BsonInt64(s.harvestAlarmDurationMs()));
        putString(d, "TraitsConfig", s.traitsConfigId());
        putString(d, "TalentsConfig", s.talentsConfigId());
        if (s.progression() != null) {
            d.put("Progression", encodeProgression(s.progression()));
        }
        return d;
    }

    private static BsonDocument encodeProgression(CompanionSummary.Progression p) {
        BsonDocument d = new BsonDocument();
        putString(d, "Stage", p.stage());
        d.put("BornAt", new BsonInt64(p.bornAtMs()));
        d.put("AdolescentAt", new BsonInt64(p.adolescentAtMs()));
        d.put("AdultAt", new BsonInt64(p.adultAtMs()));
        d.put("GrowthScaling", BsonBoolean.valueOf(p.growthScalingEnabled()));
        d.put("AgeProgress", new BsonDouble(p.ageProgressMs()));
        putString(d, "ProgressionOwner", p.progressionOwnerId());
        d.put("ProgressionClock", new BsonInt64(p.progressionClockMs()));
        d.put("ProgressionInitialized", BsonBoolean.valueOf(p.progressionInitialized()));
        d.put("LastProgressionWorld", new BsonInt64(p.lastProgressionWorldMs()));
        d.put("LifecycleNow", new BsonInt64(p.lifecycleNowMs()));
        d.put("JuvenileClockInitialized", BsonBoolean.valueOf(p.juvenileClockInitialized()));
        d.put("ProgressionPaused", BsonBoolean.valueOf(p.progressionPaused()));
        d.put("ActiveProgress", new BsonInt64(p.activeProgressMs()));
        return d;
    }

    private static CompanionSummary.Progression decodeProgression(BsonDocument d) {
        return new CompanionSummary.Progression(getString(d, "Stage"), getLong(d, "BornAt", 0),
                getLong(d, "AdolescentAt", 0), getLong(d, "AdultAt", 0), getBoolean(d, "GrowthScaling"),
                getDouble(d, "AgeProgress"), getString(d, "ProgressionOwner"), getLong(d, "ProgressionClock", 0),
                getBoolean(d, "ProgressionInitialized"), getLong(d, "LastProgressionWorld", 0),
                getLong(d, "LifecycleNow", 0), getBoolean(d, "JuvenileClockInitialized"),
                getBoolean(d, "ProgressionPaused"), getLong(d, "ActiveProgress", 0));
    }

    private static CompanionSummary decodeSummary(BsonDocument d) {
        Map<String, Double> traits = new LinkedHashMap<>();
        if (d.isDocument("Traits")) {
            for (Map.Entry<String, BsonValue> e : d.getDocument("Traits").entrySet()) {
                if (e.getValue().isNumber()) {
                    traits.put(e.getKey(), e.getValue().asNumber().doubleValue());
                }
            }
        }
        return new CompanionSummary(getString(d, "CustomName"), getString(d, "NameKey"), getString(d, "Role"),
                getString(d, "Icon"), (float) getDouble(d, "HealthCurrent"), (float) getDouble(d, "HealthMax"),
                getString(d, "HappinessConfig"), getDouble(d, "Happiness"), getString(d, "NeedsConfig"),
                getDouble(d, "Hunger"), getDouble(d, "Thirst"), getBoolean(d, "BreedingPresent"),
                getBoolean(d, "BreedingEnabled"),
                getLong(d, "BreedingCooldownUntil", 0), getLong(d, "BreedingCooldownStartedAt", 0),
                getLong(d, "BreedingCooldownDuration", 0), getLong(d, "HarvestAlarmUntil", 0),
                getString(d, "LevelingConfig"), (int) getLong(d, "Level", 0), getDouble(d, "CurrentXp"),
                getDouble(d, "TotalXp"), (int) getLong(d, "TalentPointsSpent", 0), traits,
                getLong(d, "ObservedAt", 0),
                getLong(d, "HarvestAlarmStartedAt", 0), getLong(d, "HarvestAlarmDuration", 0),
                getString(d, "TraitsConfig"), getString(d, "TalentsConfig"),
                d.isDocument("Progression") ? decodeProgression(d.getDocument("Progression")) : null);
    }

    private static void putString(BsonDocument d, String key, @Nullable String value) {
        if (value != null) {
            d.put(key, new BsonString(value));
        }
    }

    private static void putUuid(BsonDocument d, String key, @Nullable UUID value) {
        if (value != null) {
            d.put(key, new BsonString(value.toString()));
        }
    }

    private static String requireString(BsonDocument d, String key) {
        if (!d.isString(key)) {
            throw new IllegalArgumentException("missing " + key);
        }
        return d.getString(key).getValue();
    }

    private static BsonDocument requireDocument(BsonDocument d, String key) {
        if (!d.isDocument(key)) {
            throw new IllegalArgumentException("missing " + key);
        }
        return d.getDocument(key);
    }

    @Nullable
    private static String getString(BsonDocument d, String key) {
        return d.isString(key) ? d.getString(key).getValue() : null;
    }

    /**
     * Like {@link #getString} for fields whose value decides identity or placement: a missing
     * key means the default, but a value of another type makes the record unreadable.
     */
    @Nullable
    private static String getStrictString(BsonDocument d, String key) {
        if (d.containsKey(key) && !d.isString(key)) {
            throw new IllegalArgumentException(key + " is not a string");
        }
        return getString(d, key);
    }

    @Nullable
    private static UUID getUuid(BsonDocument d, String key) {
        String value = getStrictString(d, key);
        return value == null ? null : UUID.fromString(value);
    }

    private static long getLong(BsonDocument d, String key, long fallback) {
        BsonValue v = d.get(key);
        return v != null && v.isNumber() ? v.asNumber().longValue() : fallback;
    }

    /**
     * For revision and generation, which the fence and the compare-and-set rely on: a missing key
     * means 0, but a value of another type makes the record unreadable instead of resetting it.
     */
    private static long getStrictLong(BsonDocument d, String key) {
        if (d.containsKey(key) && !d.get(key).isNumber()) {
            throw new IllegalArgumentException(key + " is not a number");
        }
        return getLong(d, key, 0);
    }

    private static double getDouble(BsonDocument d, String key) {
        BsonValue v = d.get(key);
        return v != null && v.isNumber() ? v.asNumber().doubleValue() : 0.0;
    }

    private static boolean getBoolean(BsonDocument d, String key) {
        BsonValue v = d.get(key);
        return v != null && v.isBoolean() && v.asBoolean().getValue();
    }
}
