package com.alechilles.alecstamework.persistence.migration;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

/** Strict decoder for the line formats accepted by the public v2.16.1 DAT importer. */
final class LegacyDatLineDecoder extends LegacyDatValueDecoder {
    LegacyDatRows decode(LegacyDatBundleSnapshot source) throws PublicImportException {
        ArrayList<LegacyDatRows.Snapshot> snapshots = new ArrayList<>();
        snapshots.addAll(captures(source.lines(LegacyDatBundleSnapshot.CAPTURES_FILE)));
        snapshots.addAll(deaths(source.lines(LegacyDatBundleSnapshot.DEATHS_FILE)));
        snapshots.addAll(lost(source.lines(LegacyDatBundleSnapshot.LOST_FILE)));
        return new LegacyDatRows(
                snapshots,
                coops(source.lines(LegacyDatBundleSnapshot.COOPS_FILE))
        );
    }

    private List<LegacyDatRows.Snapshot> captures(
            List<LegacyDatBundleSnapshot.SourceLine> lines
    ) throws PublicImportException {
        ArrayList<LegacyDatRows.Snapshot> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (LegacyDatBundleSnapshot.SourceLine line : lines) {
            String[] parts = fields(line, 7, 8);
            String npcUuid = requiredUuid(parts[0], line, "npcUuid");
            requireUnique(seen, npcUuid, "DUPLICATE_LEGACY_DAT_CAPTURE", line);
            String ownerUuid = optionalUuid(parts[1], line, "ownerUuid");
            List<String> toolIds = toolIds(parts[2], line, true);
            String roleId = base64(parts[3], line, "roleId");
            String displayName = base64(parts[4], line, "displayName");
            Vector lastKnown = vector(parts[5], line, "lastKnownPosition");
            Vector home = parts.length == 8
                    ? vector(parts[6], line, "homePosition")
                    : null;
            long capturedAtMs = requiredLong(
                    parts[parts.length == 8 ? 7 : 6], line, "capturedAtMs");
            JsonObject payload = new JsonObject();
            putVector(payload, "lastKnownPosition", lastKnown);
            putVector(payload, "homePosition", home);
            payload.addProperty("capturedAtMs", capturedAtMs);
            putString(payload, "roleId", roleId);
            putString(payload, "displayName", displayName);
            result.add(new LegacyDatRows.Snapshot(
                    "capture", npcUuid, ownerUuid, null, toolIds, roleId,
                    displayName, null, null, payload.toString(), capturedAtMs
            ));
        }
        return List.copyOf(result);
    }

    private List<LegacyDatRows.Snapshot> deaths(
            List<LegacyDatBundleSnapshot.SourceLine> lines
    ) throws PublicImportException {
        ArrayList<LegacyDatRows.Snapshot> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (LegacyDatBundleSnapshot.SourceLine line : lines) {
            String[] parts = fields(line, 12, 46);
            String npcUuid = requiredUuid(parts[0], line, "npcUuid");
            requireUnique(seen, npcUuid, "DUPLICATE_LEGACY_DAT_DEATH", line);
            result.add(death(parts, line, npcUuid));
        }
        return List.copyOf(result);
    }

    private LegacyDatRows.Snapshot death(
            String[] parts,
            LegacyDatBundleSnapshot.SourceLine line,
            String npcUuid
    ) throws PublicImportException {
        String ownerUuid = optionalUuid(parts[1], line, "ownerUuid");
        String ownerName = base64(parts[2], line, "ownerName");
        List<String> tools = toolIds(parts[3], line, false);
        String roleId = base64(parts[4], line, "roleId");
        boolean tamed = requiredBoolean(parts[5], line, "tamed");
        String customName = base64(parts[6], line, "customName");
        String displayName = base64(parts[7], line, "displayName");
        Vector lastKnown = vector(parts[8], line, "lastKnownPosition");
        Vector home = vector(parts[9], line, "homePosition");
        long diedAtMs = requiredLong(parts[10], line, "diedAtMs");
        long respawnAtMs = requiredLong(parts[11], line, "respawnAvailableAtMs");
        JsonObject payload = deathPayload(
                parts, line, ownerUuid, ownerName, roleId, tamed, customName, displayName,
                lastKnown, home, diedAtMs, respawnAtMs
        );
        return new LegacyDatRows.Snapshot(
                "death", npcUuid, ownerUuid, ownerName, tools, roleId, displayName,
                customName, tamed, payload.toString(), diedAtMs
        );
    }

    private JsonObject deathPayload(
            String[] parts,
            LegacyDatBundleSnapshot.SourceLine line,
            @Nullable String ownerUuid,
            @Nullable String ownerName,
            @Nullable String roleId,
            boolean tamed,
            @Nullable String customName,
            @Nullable String displayName,
            @Nullable Vector lastKnown,
            @Nullable Vector home,
            long diedAtMs,
            long respawnAtMs
    ) throws PublicImportException {
        boolean legacyLifeStageLayout = parts.length <= 30;
        JsonObject payload = new JsonObject();
        putString(payload, "ownerId", ownerUuid);
        putString(payload, "ownerName", ownerName);
        putString(payload, "roleId", roleId);
        payload.addProperty("tamed", tamed);
        putString(payload, "customName", customName);
        putString(payload, "displayName", displayName);
        putVector(payload, "lastKnownPosition", lastKnown);
        putVector(payload, "homePosition", home);
        payload.addProperty("diedAtMs", diedAtMs);
        payload.addProperty("respawnAvailableAtMs", respawnAtMs);
        putString(payload, "breedingConfigId", base64At(parts, 12, line, "breedingConfigId"));
        putNullableDouble(payload, "breedingHappiness",
                nullableDoubleAt(parts, 13, line, "breedingHappiness"));
        payload.addProperty("breedingCooldownUntilMs",
                longAt(parts, 14, 0L, line, "breedingCooldownUntilMs"));
        putString(payload, "breedingLastPartnerUuid",
                optionalUuidAt(parts, 15, line, "breedingLastPartnerUuid"));
        putString(payload, "traitsConfigId", base64At(parts, 16, line, "traitsConfigId"));
        payload.addProperty("traitsRollSeed",
                longAt(parts, 17, 0L, line, "traitsRollSeed"));
        putString(payload, "traitsValues", base64At(parts, 18, line, "traitsValues"));
        putString(payload, "happinessConfigId", base64At(parts, 19, line, "happinessConfigId"));
        putNullableDouble(payload, "happinessValue",
                nullableDoubleAt(parts, 20, line, "happinessValue"));
        payload.addProperty("happinessLastUpdateMs",
                longAt(parts, 21, 0L, line, "happinessLastUpdateMs"));
        putString(payload, "lifeStage", base64At(parts, 22, line, "lifeStage"));
        payload.addProperty("lifeStageBornAtMs",
                longAt(parts, 23, 0L, line, "lifeStageBornAtMs"));
        payload.addProperty("lifeStageAdolescentAtMs",
                longAt(parts, 24, 0L, line, "lifeStageAdolescentAtMs"));
        long adultAt = longAt(parts, 25, 0L, line, "lifeStageAdultAtMs");
        payload.addProperty("lifeStageAdultAtMs", adultAt);
        payload.addProperty("lifeStageFullyGrownAtMs", legacyLifeStageLayout
                ? adultAt
                : longAt(parts, 26, 0L, line, "lifeStageFullyGrownAtMs"));
        addDeathScales(payload, parts, line, legacyLifeStageLayout);
        putString(payload, "attachmentsConfigId",
                base64At(parts, 34, line, "attachmentsConfigId"));
        putString(payload, "attachmentsValues",
                base64At(parts, 35, line, "attachmentsValues"));
        payload.addProperty("breedingEnabled",
                booleanAt(parts, 36, false, line, "breedingEnabled"));
        addDeathTail(payload, parts, line);
        return payload;
    }

    private void addDeathScales(
            JsonObject payload,
            String[] parts,
            LegacyDatBundleSnapshot.SourceLine line,
            boolean legacy
    ) throws PublicImportException {
        double baby = doubleAt(parts, legacy ? 26 : 27, 0.55, line, "lifeStageBabyScale");
        double adolescent = doubleAt(
                parts, legacy ? 27 : 28, 0.80, line, "lifeStageAdolescentScale");
        payload.addProperty("lifeStageBabyScale", baby);
        payload.addProperty("lifeStageAdolescentScale", adolescent);
        payload.addProperty("lifeStageAdolescentSwitchScale", legacy
                ? adolescent
                : doubleAt(parts, 29, 0.80, line, "lifeStageAdolescentSwitchScale"));
        payload.addProperty("lifeStageAdultStartScale", legacy
                ? adolescent
                : doubleAt(parts, 30, 0.80, line, "lifeStageAdultStartScale"));
        double adult = doubleAt(
                parts, legacy ? 28 : 31, 1.00, line, "lifeStageAdultSwitchScale");
        payload.addProperty("lifeStageAdultSwitchScale", adult);
        payload.addProperty("lifeStageAdultScale", legacy
                ? adult
                : doubleAt(parts, 32, 1.00, line, "lifeStageAdultScale"));
        payload.addProperty("lifeStageGrowthScalingEnabled", booleanAt(
                parts, legacy ? 29 : 33, false, line, "lifeStageGrowthScalingEnabled"));
    }

    private void addDeathTail(
            JsonObject payload,
            String[] parts,
            LegacyDatBundleSnapshot.SourceLine line
    ) throws PublicImportException {
        String levelingConfig = null;
        long levelingLevel = 1L;
        double levelingXp = 0.0;
        String talentsConfig = null;
        long spentPoints = 0L;
        String talentIds = null;
        String deathCause = null;
        String deathSource = null;
        if (parts.length >= 45) {
            levelingConfig = base64At(parts, 37, line, "levelingConfigId");
            levelingLevel = longAt(parts, 38, 1L, line, "levelingLevel");
            levelingXp = doubleAt(parts, 39, 0.0, line, "levelingTotalXp");
            talentsConfig = base64At(parts, 40, line, "talentsConfigId");
            spentPoints = longAt(parts, 41, 0L, line, "talentsSpentPoints");
            talentIds = base64At(parts, 42, line, "purchasedTalentIds");
            deathCause = enumAt(parts, 43, line, "deathCauseKind");
            deathSource = base64At(parts, 44, line, "deathSourceName");
        } else if (parts.length >= 43) {
            levelingConfig = base64At(parts, 37, line, "levelingConfigId");
            levelingLevel = longAt(parts, 38, 1L, line, "levelingLevel");
            levelingXp = doubleAt(parts, 39, 0.0, line, "levelingTotalXp");
            talentsConfig = base64At(parts, 40, line, "talentsConfigId");
            spentPoints = longAt(parts, 41, 0L, line, "talentsSpentPoints");
            talentIds = base64At(parts, 42, line, "purchasedTalentIds");
        } else if (parts.length >= 39) {
            deathCause = enumAt(parts, 37, line, "deathCauseKind");
            deathSource = base64At(parts, 38, line, "deathSourceName");
        }
        putString(payload, "levelingConfigId", levelingConfig);
        payload.addProperty("levelingLevel", levelingLevel);
        payload.addProperty("levelingTotalXp", levelingXp);
        putString(payload, "talentsConfigId", talentsConfig);
        payload.addProperty("talentsSpentPoints", spentPoints);
        putString(payload, "purchasedTalentIds", talentIds);
        putString(payload, "deathCauseKind", deathCause);
        putString(payload, "deathSourceName", deathSource);
        putString(payload, "lifeStageGender", base64At(parts, 45, line, "lifeStageGender"));
    }

    private List<LegacyDatRows.Snapshot> lost(
            List<LegacyDatBundleSnapshot.SourceLine> lines
    ) throws PublicImportException {
        ArrayList<LegacyDatRows.Snapshot> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (LegacyDatBundleSnapshot.SourceLine line : lines) {
            String[] parts = fields(line, 6, 8);
            String npcUuid = requiredUuid(parts[0], line, "npcUuid");
            requireUnique(seen, npcUuid, "DUPLICATE_LEGACY_DAT_LOST", line);
            Vector lastKnown = vector(parts[1], line, "lastKnownPosition");
            Vector home = vector(parts[2], line, "homePosition");
            long queuedAt = requiredLong(parts[3], line, "lastRelocationQueuedAtMs");
            long lostAt = requiredLong(parts[4], line, "lostAtMs");
            int retries = requiredInt(parts[5], line, "relocationRetryAttempts");
            if (retries < 0) {
                throw malformed(line, "relocationRetryAttempts");
            }
            String replacement = optionalUuidAt(parts, 6, line, "replacementNpcUuid");
            long recoveredAt = longAt(parts, 7, 0L, line, "recoveredAtMs");
            JsonObject payload = new JsonObject();
            putVector(payload, "lastKnownPosition", lastKnown);
            putVector(payload, "homePosition", home);
            payload.addProperty("lastRelocationQueuedAtMs", queuedAt);
            payload.addProperty("lostAtMs", lostAt);
            payload.addProperty("relocationRetryAttempts", retries);
            putString(payload, "replacementNpcUuid", replacement);
            payload.addProperty("recoveredAtMs", recoveredAt);
            result.add(new LegacyDatRows.Snapshot(
                    "lost", npcUuid, null, null, List.of(), null, null, null, null,
                    payload.toString(), lostAt
            ));
        }
        return List.copyOf(result);
    }

    private List<LegacyDatRows.CoopSlot> coops(
            List<LegacyDatBundleSnapshot.SourceLine> lines
    ) throws PublicImportException {
        ArrayList<LegacyDatRows.CoopSlot> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (LegacyDatBundleSnapshot.SourceLine line : lines) {
            String[] parts = fields(line, 15, 15);
            if (!"2".equals(parts[0])) {
                throw malformed(line, "ledgerVersion");
            }
            String world = normalize(base64(parts[1], line, "worldName"));
            if (world == null) {
                world = "<unknown>";
            }
            int x = requiredInt(parts[2], line, "x");
            int y = requiredInt(parts[3], line, "y");
            int z = requiredInt(parts[4], line, "z");
            int slot = requiredInt(parts[5], line, "residentSlot");
            if (slot < 0) {
                throw malformed(line, "residentSlot");
            }
            String coopId = normalize(base64(parts[6], line, "coopId"));
            if (coopId == null) {
                throw malformed(line, "coopId");
            }
            String housed = optionalUuid(parts[7], line, "housedNpcUuid");
            String released = optionalUuid(parts[8], line, "lastReleasedNpcUuid");
            String owner = optionalUuid(parts[9], line, "ownerUuid");
            List<String> tools = toolIds(parts[10], line, false);
            String role = normalize(base64(parts[11], line, "roleId"));
            String display = base64(parts[12], line, "displayName");
            long housedAt = requiredLong(parts[13], line, "housedAtMs");
            long releasedAt = requiredLong(parts[14], line, "releasedAtMs");
            LegacyDatRows.CoopSlot decoded = new LegacyDatRows.CoopSlot(
                    world, coopId, x, y, z, slot, housed, released, owner, tools,
                    role, display, housedAt, releasedAt
            );
            requireUnique(seen, decoded.sourceKey(), "DUPLICATE_LEGACY_DAT_COOP_SLOT", line);
            result.add(decoded);
        }
        return List.copyOf(result);
    }

    private List<String> toolIds(
            String raw,
            LegacyDatBundleSnapshot.SourceLine line,
            boolean required
    ) throws PublicImportException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (!raw.isBlank()) {
            for (String token : raw.split(";", -1)) {
                String decoded = base64(token, line, "toolIds");
                if (decoded == null) {
                    throw malformed(line, "toolIds");
                }
                result.add(requiredUuid(decoded, line, "toolIds"));
            }
        }
        if (required && result.isEmpty()) {
            throw malformed(line, "toolIds");
        }
        return List.copyOf(result);
    }

    private void requireUnique(
            Set<String> seen,
            String key,
            String code,
            LegacyDatBundleSnapshot.SourceLine line
    ) throws PublicImportException {
        if (!seen.add(key)) {
            throw new PublicImportException(code, code + ": " + line.evidence(key));
        }
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private void putString(JsonObject target, String field, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            target.addProperty(field, value);
        }
    }

    private void putNullableDouble(JsonObject target, String field, @Nullable Double value) {
        if (value != null) {
            target.addProperty(field, value);
        }
    }

    private void putVector(JsonObject target, String field, @Nullable Vector value) {
        if (value == null) {
            return;
        }
        JsonObject encoded = new JsonObject();
        encoded.addProperty("x", value.x());
        encoded.addProperty("y", value.y());
        encoded.addProperty("z", value.z());
        target.add(field, encoded);
    }

}
