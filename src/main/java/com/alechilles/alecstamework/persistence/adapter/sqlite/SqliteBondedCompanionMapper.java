package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPayload;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.util.Base64;
import java.util.Map;

/** Translates domain records to strict SQLite adapter representations. */
final class SqliteBondedCompanionMapper {
    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type POLICY_TYPE =
            new TypeToken<Map<String, String>>() {}.getType();

    SqliteBondedCompanionProfileRow toRow(BondedCompanionRecord.Profile value) {
        return new SqliteBondedCompanionProfileRow(
                value.profileId(), value.ownerUuid(), value.rosterId(),
                value.familyId(), value.roleId(), value.state(), value.revision(),
                snapshotJson(value.snapshot()), value.createdAtMs(),
                value.updatedAtMs(), GSON.toJson(value.policy()),
                value.displayName(), value.species(), value.gender(),
                value.diedAtMs(), value.reviveCooldownUntilMs(),
                value.reviveCount(), value.quarantineReason(),
                value.quarantinedAtMs());
    }

    BondedCompanionRecord.Profile toDomain(SqliteBondedCompanionProfileRow row) {
        Map<String, String> policy = GSON.fromJson(row.policyJson(), POLICY_TYPE);
        if (policy == null) throw new IllegalArgumentException("policy must be an object");
        return new BondedCompanionRecord.Profile(
                row.profileId(), row.ownerUuid(), row.rosterId(), row.familyId(),
                row.roleId(), row.state(), row.revision(), snapshot(row.snapshotJson()),
                row.createdAtMs(), row.updatedAtMs(), policy, row.displayName(),
                row.species(), row.gender(), row.diedAtMs(),
                row.reviveCooldownUntilMs(), row.reviveCount(),
                row.quarantineReason(), row.quarantinedAtMs());
    }

    SqliteBondedCompanionLeaseRow toRow(BondedCompanionRecord.Lease value) {
        return new SqliteBondedCompanionLeaseRow(
                value.profileId(), value.leaseToken(), value.liveNpcUuid(),
                value.worldKey(), value.startedAtMs(), value.expiresAtMs(),
                value.projectionState().name());
    }

    BondedCompanionRecord.Lease toDomain(SqliteBondedCompanionLeaseRow row) {
        return new BondedCompanionRecord.Lease(
                row.profileId(), row.leaseToken(), row.liveNpcUuid(), row.worldKey(),
                row.startedAtMs(), row.expiresAtMs(),
                BondedCompanionRecord.ProjectionState.valueOf(row.projectionState()));
    }

    private String snapshotJson(BondedCompanionPayload payload) {
        JsonObject json = new JsonObject();
        json.addProperty("encoding", "base64");
        json.addProperty("payload", Base64.getEncoder().encodeToString(payload.bytes()));
        return GSON.toJson(json);
    }

    private BondedCompanionPayload snapshot(String json) {
        JsonObject object = GSON.fromJson(json, JsonObject.class);
        if (object == null || !object.has("encoding") || !object.has("payload")
                || !"base64".equals(object.get("encoding").getAsString())) {
            throw new IllegalArgumentException("invalid complete snapshot envelope");
        }
        byte[] bytes = Base64.getDecoder().decode(object.get("payload").getAsString());
        return BondedCompanionPayload.of(bytes);
    }
}
