package com.alechilles.alecstamework.companion.store;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.RecordScope;
import com.alechilles.alecstamework.companion.index.StoredReason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        if (r.rosterId() != null || r.bonded()) {
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
            b.revision(getLong(d, "Revision", 0)).generation(getLong(d, "Generation", 0));
            b.ownerUuid(getUuid(d, "Owner")).ownerName(getString(d, "OwnerName"));
            b.displayName(getString(d, "Name"));
            String scope = getString(d, "Scope");
            b.scope(scope == null ? RecordScope.WORLD_BOUND : RecordScope.valueOf(scope));
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
                    claims.add(new DomainClaim(requireString(c, "Domain"), (int) getLong(c, "Weight", 0),
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
        putString(d, "Name", s.displayName());
        putString(d, "Role", s.roleId());
        putString(d, "Icon", s.iconId());
        d.put("Level", new BsonInt32(s.level()));
        d.put("Health", new BsonDouble(s.healthFraction()));
        putString(d, "LifeStage", s.lifeStage());
        d.put("Happiness", new BsonInt32(s.happinessBand()));
        d.put("Needs", new BsonInt32(s.needsBand()));
        putString(d, "CommandState", s.commandState());
        d.put("BreedingPending", BsonBoolean.valueOf(s.breedingPending()));
        return d;
    }

    private static CompanionSummary decodeSummary(BsonDocument d) {
        return new CompanionSummary(getString(d, "Name"), getString(d, "Role"), getString(d, "Icon"),
                (int) getLong(d, "Level", 0), d.containsKey("Health") ? (float) getDouble(d, "Health") : 1f,
                getString(d, "LifeStage"), (int) getLong(d, "Happiness", 0), (int) getLong(d, "Needs", 0),
                getString(d, "CommandState"), getBoolean(d, "BreedingPending"));
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

    @Nullable
    private static UUID getUuid(BsonDocument d, String key) {
        String value = getString(d, key);
        return value == null ? null : UUID.fromString(value);
    }

    private static long getLong(BsonDocument d, String key, long fallback) {
        BsonValue v = d.get(key);
        return v != null && v.isNumber() ? v.asNumber().longValue() : fallback;
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
