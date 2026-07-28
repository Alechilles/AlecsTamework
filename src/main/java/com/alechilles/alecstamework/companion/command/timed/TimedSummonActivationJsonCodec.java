package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.google.gson.JsonObject;
import javax.annotation.Nonnull;

/** Canonical JSON codec for optional live timed-session activation evidence. */
public final class TimedSummonActivationJsonCodec {
    private TimedSummonActivationJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(
            @Nonnull TimedSummonActivation activation
    ) {
        if (activation == null) {
            throw new IllegalArgumentException(
                    "Timed summon activation is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty(
                "ownerId", activation.familyKey().ownerId().toString()
        );
        json.addProperty("familyId", activation.familyKey().familyId());
        json.addProperty("slotId", activation.slotId().toString());
        json.addProperty(
                "expectedMembershipRevision",
                activation.expectedMembershipRevision()
        );
        json.add(
                "expectedPreviousLease",
                activation.expectedPreviousLease() == null
                        ? null
                        : TimedSummonLeaseJsonCodec.encode(
                                activation.expectedPreviousLease()
                        )
        );
        json.add(
                "lease",
                TimedSummonLeaseJsonCodec.encode(activation.lease())
        );
        return json;
    }

    @Nonnull
    public static TimedSummonActivation decode(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Timed summon activation JSON is required"
            );
        }
        return new TimedSummonActivation(
                new CommandFamilyKey(
                        OwnerId.parse(json.get("ownerId").getAsString()),
                        json.get("familyId").getAsString()
                ),
                CommandRosterSlotId.parse(
                        json.get("slotId").getAsString()
                ),
                json.get("expectedMembershipRevision").getAsLong(),
                json.get("expectedPreviousLease") == null
                        || json.get("expectedPreviousLease").isJsonNull()
                        ? null
                        : TimedSummonLeaseJsonCodec.decode(
                                json.getAsJsonObject(
                                        "expectedPreviousLease"
                                )
                        ),
                TimedSummonLeaseJsonCodec.decode(
                        json.getAsJsonObject("lease")
                )
        );
    }
}

