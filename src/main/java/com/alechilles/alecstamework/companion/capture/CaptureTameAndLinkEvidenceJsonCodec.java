package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraftJsonCodec;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivationJsonCodec;
import com.alechilles.alecstamework.companion.identity.CompanionIdentityJsonCodec;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleJsonCodec;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import javax.annotation.Nonnull;

/** Canonical JSON translation for in-place tame/link capture evidence. */
public final class CaptureTameAndLinkEvidenceJsonCodec {
    private CaptureTameAndLinkEvidenceJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(
            @Nonnull CaptureTameAndLinkEvidence evidence
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Tame/link capture evidence is required"
            );
        }
        JsonObject json = new JsonObject();
        json.add(
                "expectedIdentity",
                CompanionIdentityJsonCodec.encode(
                        evidence.expectedIdentity()
                )
        );
        json.add(
                "targetIdentity",
                CompanionIdentityJsonCodec.encode(
                        evidence.targetIdentity()
                )
        );
        json.add(
                "expectedLifecycle",
                CompanionLifecycleJsonCodec.encode(
                        evidence.expectedLifecycle()
                )
        );
        json.add(
                "finalLifecycle",
                CompanionLifecycleJsonCodec.encode(
                        evidence.finalLifecycle()
                )
        );
        json.add(
                "ownerPopulation",
                CapturePopulationEvidenceJsonCodec.encodeOwner(
                        evidence.ownerPopulation()
                )
        );
        json.add(
                "populationGroups",
                CapturePopulationEvidenceJsonCodec.encodeGroups(
                        evidence.populationGroups()
                )
        );
        json.addProperty(
                "expectedRosterRevision",
                evidence.expectedRosterRevision()
        );
        json.add(
                "rosterMembership",
                CommandRosterMembershipDraftJsonCodec.encode(
                        evidence.rosterMembership()
                )
        );
        json.add(
                "timedActivation",
                TimedSummonActivationJsonCodec.encode(
                        evidence.timedActivation()
                )
        );
        json.add("live", encodeLive(evidence.live()));
        return json;
    }

    @Nonnull
    public static CaptureTameAndLinkEvidence decode(
            @Nonnull JsonObject json
    ) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Tame/link capture JSON is required"
            );
        }
        return new CaptureTameAndLinkEvidence(
                CompanionIdentityJsonCodec.decode(
                        json.getAsJsonObject("expectedIdentity")
                ),
                CompanionIdentityJsonCodec.decode(
                        json.getAsJsonObject("targetIdentity")
                ),
                CompanionLifecycleJsonCodec.decode(
                        json.getAsJsonObject("expectedLifecycle")
                ),
                CompanionLifecycleJsonCodec.decode(
                        json.getAsJsonObject("finalLifecycle")
                ),
                CapturePopulationEvidenceJsonCodec.decodeOwner(
                        json.getAsJsonObject("ownerPopulation")
                ),
                CapturePopulationEvidenceJsonCodec.decodeGroups(
                        json.getAsJsonObject("populationGroups")
                ),
                json.get("expectedRosterRevision").getAsLong(),
                CommandRosterMembershipDraftJsonCodec.decode(
                        json.getAsJsonObject("rosterMembership")
                ),
                TimedSummonActivationJsonCodec.decode(
                        json.getAsJsonObject("timedActivation")
                ),
                decodeLive(json.getAsJsonObject("live"))
        );
    }

    private static JsonObject encodeLive(
            CaptureTameLiveEvidence live
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("expectedRoleId", live.expectedRoleId());
        nullable(json, "expectedOwnerId", live.expectedOwnerId());
        json.addProperty("expectedTamed", live.expectedTamed());
        json.addProperty(
                "expectedStateHash",
                live.expectedStateHash().toString()
        );
        json.addProperty("targetRoleId", live.targetRoleId());
        json.addProperty(
                "targetOwnerId", live.targetOwnerId().toString()
        );
        json.addProperty(
                "targetOwnerName", live.targetOwnerName()
        );
        json.addProperty(
                "targetStateHash", live.targetStateHash().toString()
        );
        CaptureCommandAccessEvidence access = live.commandAccess();
        JsonObject command = new JsonObject();
        command.addProperty("configId", access.configId());
        command.addProperty(
                "configRevision", access.configRevision()
        );
        command.addProperty(
                "commandFamilyId", access.commandFamilyId()
        );
        JsonArray itemIds = new JsonArray();
        access.accessItemIds().forEach(itemIds::add);
        command.add("accessItemIds", itemIds);
        json.add("commandAccess", command);
        return json;
    }

    private static CaptureTameLiveEvidence decodeLive(JsonObject json) {
        String expectedOwner = text(json, "expectedOwnerId");
        JsonObject command = json.getAsJsonObject("commandAccess");
        ArrayList<String> itemIds = new ArrayList<>();
        for (JsonElement item : command.getAsJsonArray(
                "accessItemIds"
        )) {
            itemIds.add(item.getAsString());
        }
        return new CaptureTameLiveEvidence(
                json.get("expectedRoleId").getAsString(),
                expectedOwner == null
                        ? null
                        : OwnerId.parse(expectedOwner),
                json.get("expectedTamed").getAsBoolean(),
                Sha256Hash.parse(
                        json.get("expectedStateHash").getAsString()
                ),
                json.get("targetRoleId").getAsString(),
                OwnerId.parse(json.get("targetOwnerId").getAsString()),
                json.get("targetOwnerName").getAsString(),
                Sha256Hash.parse(
                        json.get("targetStateHash").getAsString()
                ),
                new CaptureCommandAccessEvidence(
                        command.get("configId").getAsString(),
                        command.get("configRevision").getAsLong(),
                        command.get("commandFamilyId").getAsString(),
                        itemIds
                )
        );
    }

    private static void nullable(
            JsonObject json,
            String name,
            Object value
    ) {
        if (value == null) {
            json.add(name, null);
        } else {
            json.addProperty(name, value.toString());
        }
    }

    private static String text(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsString();
    }
}
