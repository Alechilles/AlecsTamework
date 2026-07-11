package com.alechilles.alecstamework.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Encodes coop source changes into the durable population journal target context. */
final class CoopPopulationLedgerMutationJson {
    private CoopPopulationLedgerMutationJson() {
    }

    @Nonnull
    static String capture(@Nonnull CommandLinkedNpcCoopService coopService,
                          @Nonnull CoopCaptureLedgerTransaction.CaptureRequest request,
                          @Nullable CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot previous) {
        long now = System.currentTimeMillis();
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot = request.stateSnapshot();
        JsonObject mutation = base(
                "CAPTURE",
                request.context(),
                request.npcUuid(),
                null,
                coopService.resolveOwnerId(snapshot, request.ownerId()),
                coopService.resolveToolIds(snapshot, request.toolIds()),
                CommandLinkedNpcCoopService.normalizeRoleId(request.roleId()),
                coopService.resolveDisplayName(snapshot, request.displayName()),
                now,
                0L,
                coopService.serializeStateSnapshot(snapshot),
                null,
                request.npcUuid()
        );
        put(mutation, "expectedHousedNpcUuid", previous == null ? null : previous.housedNpcUuid());
        put(mutation, "expectedLastReleasedNpcUuid",
                previous == null ? null : previous.lastReleasedNpcUuid());
        mutation.addProperty("expectedSlotPresent", previous != null);
        return root(mutation).toString();
    }

    @Nonnull
    static String release(@Nonnull CommandLinkedNpcCoopService coopService,
                          @Nonnull CommandLinkedNpcCoopService.CoopSlotContext context,
                          @Nonnull CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot ledger,
                          @Nonnull UUID currentNpcUuid) {
        JsonObject mutation = base(
                "RELEASE",
                context,
                null,
                currentNpcUuid,
                ledger.ownerId(),
                ledger.toolIds(),
                ledger.roleId(),
                ledger.displayName(),
                ledger.housedAtMs(),
                System.currentTimeMillis(),
                coopService.serializeStateSnapshot(ledger.stateSnapshot()),
                ledger.housedNpcUuid(),
                currentNpcUuid
        );
        return root(mutation).toString();
    }

    @Nonnull
    private static JsonObject root(@Nonnull JsonObject mutation) {
        JsonObject root = new JsonObject();
        root.add("coopLedgerMutation", mutation);
        return root;
    }

    @Nonnull
    private static JsonObject base(
            @Nonnull String mode,
            @Nonnull CommandLinkedNpcCoopService.CoopSlotContext context,
            @Nullable UUID housedNpcUuid,
            @Nullable UUID releasedNpcUuid,
            @Nullable UUID ownerId,
            @Nullable String[] toolIds,
            @Nullable String roleId,
            @Nullable String displayName,
            long housedAtMs,
            long releasedAtMs,
            @Nullable String stateSnapshotJson,
            @Nullable UUID previousNpcUuid,
            @Nullable UUID currentNpcUuid
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("mode", mode);
        put(json, "worldName", context.worldName());
        json.addProperty("coopId", context.coopId());
        json.addProperty("x", context.x());
        json.addProperty("y", context.y());
        json.addProperty("z", context.z());
        json.addProperty("residentSlot", context.residentSlot());
        put(json, "housedNpcUuid", housedNpcUuid);
        put(json, "lastReleasedNpcUuid", releasedNpcUuid);
        put(json, "ownerId", ownerId);
        JsonArray tools = new JsonArray();
        if (toolIds != null) {
            for (String toolId : toolIds) {
                if (toolId != null && !toolId.isBlank()) tools.add(toolId.trim());
            }
        }
        json.add("toolIds", tools);
        put(json, "roleId", roleId);
        put(json, "displayName", displayName);
        json.addProperty("housedAtMs", housedAtMs);
        json.addProperty("releasedAtMs", releasedAtMs);
        put(json, "stateSnapshotJson", stateSnapshotJson);
        put(json, "previousNpcUuid", previousNpcUuid);
        put(json, "currentNpcUuid", currentNpcUuid);
        return json;
    }

    private static void put(JsonObject json, String field, @Nullable Object value) {
        if (value == null) json.add(field, null);
        else json.addProperty(field, value.toString());
    }
}
