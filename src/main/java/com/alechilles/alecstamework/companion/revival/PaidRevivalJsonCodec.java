package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivationJsonCodec;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacementJsonCodec;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionJsonCodec;
import com.alechilles.alecstamework.companion.restoration.RestorationProjectionJsonCodec;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotJsonCodec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import javax.annotation.Nonnull;

/** Canonical JSON codec for an immutable paid-revival operation payload. */
public final class PaidRevivalJsonCodec {
    private PaidRevivalJsonCodec() {
    }

    @Nonnull
    public static String encode(@Nonnull PaidRevivalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Paid revival request is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty(
                "ownerId", request.familyKey().ownerId().toString()
        );
        json.addProperty("familyId", request.familyKey().familyId());
        json.addProperty("slotId", request.slotId().toString());
        json.addProperty(
                "expectedMembershipRevision",
                request.expectedMembershipRevision()
        );
        json.addProperty(
                "expectedProfileRevision",
                request.expectedProfileRevision()
        );
        json.add(
                "groupAdmission",
                PopulationGroupTransitionAdmissionJsonCodec.encode(
                        request.groupAdmission()
                )
        );
        json.add(
                "sourceSnapshot",
                CompanionSnapshotJsonCodec.encode(request.sourceSnapshot())
        );
        json.add(
                "projection",
                RestorationProjectionJsonCodec.encode(request.projection())
        );
        json.addProperty("targetAlias", request.targetAlias().toString());
        json.add(
                "placement",
                CompanionSpawnPlacementJsonCodec.encode(request.placement())
        );
        nullable(json, "configId", request.configId());
        json.addProperty("configRevision", request.configRevision());
        json.add("exactCost", costs(request));
        json.add("reservations", reservations(request));
        json.addProperty(
                "chargeReceiptKey", request.chargeReceiptKey()
        );
        json.addProperty("spawnReceiptKey", request.spawnReceiptKey());
        json.add(
                "timedActivation",
                request.timedActivation() == null
                        ? JsonNull.INSTANCE
                        : TimedSummonActivationJsonCodec.encode(
                                request.timedActivation()
                        )
        );
        json.addProperty("requestedAtMs", request.requestedAtMs());
        return json.toString();
    }

    @Nonnull
    public static PaidRevivalRequest decode(@Nonnull String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        JsonElement timed = json.get("timedActivation");
        return new PaidRevivalRequest(
                new CommandFamilyKey(
                        OwnerId.parse(json.get("ownerId").getAsString()),
                        json.get("familyId").getAsString()
                ),
                CommandRosterSlotId.parse(
                        json.get("slotId").getAsString()
                ),
                json.get("expectedMembershipRevision").getAsLong(),
                json.get("expectedProfileRevision").getAsLong(),
                PopulationGroupTransitionAdmissionJsonCodec.decode(
                        json.getAsJsonObject("groupAdmission")
                ),
                CompanionSnapshotJsonCodec.decode(
                        json.getAsJsonObject("sourceSnapshot")
                ),
                RestorationProjectionJsonCodec.decode(
                        json.getAsJsonObject("projection")
                ),
                NpcAlias.parse(json.get("targetAlias").getAsString()),
                CompanionSpawnPlacementJsonCodec.decode(
                        json.getAsJsonObject("placement")
                ),
                optionalText(json, "configId"),
                json.get("configRevision").getAsString(),
                readCosts(json.getAsJsonArray("exactCost")),
                readReservations(json.getAsJsonArray("reservations")),
                json.get("chargeReceiptKey").getAsString(),
                json.get("spawnReceiptKey").getAsString(),
                timed == null || timed.isJsonNull()
                        ? null
                        : TimedSummonActivationJsonCodec.decode(
                                timed.getAsJsonObject()
                        ),
                json.get("requestedAtMs").getAsLong()
        );
    }

    private static JsonArray costs(PaidRevivalRequest request) {
        JsonArray result = new JsonArray();
        for (RevivalCostItem item : request.exactCost()) {
            JsonObject row = new JsonObject();
            row.addProperty("itemId", item.itemId());
            row.addProperty("quantity", item.quantity());
            result.add(row);
        }
        return result;
    }

    private static JsonArray reservations(PaidRevivalRequest request) {
        JsonArray result = new JsonArray();
        for (RevivalInventoryReservation reservation
                : request.reservations()) {
            JsonObject row = new JsonObject();
            row.addProperty("costOrdinal", reservation.costOrdinal());
            row.addProperty("stackOrdinal", reservation.stackOrdinal());
            row.addProperty("compartmentId", reservation.compartmentId());
            row.addProperty("slotIndex", reservation.slotIndex());
            row.addProperty("quantity", reservation.quantity());
            row.addProperty(
                    "sourceStackFingerprint",
                    reservation.sourceStackFingerprint()
            );
            row.addProperty(
                    "reservationGeneration",
                    reservation.reservationGeneration()
            );
            result.add(row);
        }
        return result;
    }

    private static ArrayList<RevivalCostItem> readCosts(JsonArray json) {
        ArrayList<RevivalCostItem> result = new ArrayList<>();
        for (JsonElement element : json) {
            JsonObject row = element.getAsJsonObject();
            result.add(new RevivalCostItem(
                    row.get("itemId").getAsString(),
                    row.get("quantity").getAsInt()
            ));
        }
        return result;
    }

    private static ArrayList<RevivalInventoryReservation> readReservations(
            JsonArray json
    ) {
        ArrayList<RevivalInventoryReservation> result =
                new ArrayList<>();
        for (JsonElement element : json) {
            JsonObject row = element.getAsJsonObject();
            result.add(new RevivalInventoryReservation(
                    row.get("costOrdinal").getAsInt(),
                    row.get("stackOrdinal").getAsInt(),
                    row.get("compartmentId").getAsString(),
                    row.get("slotIndex").getAsInt(),
                    row.get("quantity").getAsInt(),
                    row.get("sourceStackFingerprint").getAsString(),
                    row.get("reservationGeneration").getAsLong()
            ));
        }
        return result;
    }

    private static void nullable(
            JsonObject json,
            String name,
            String value
    ) {
        if (value == null) {
            json.add(name, JsonNull.INSTANCE);
        } else {
            json.addProperty(name, value);
        }
    }

    private static String optionalText(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsString();
    }
}
