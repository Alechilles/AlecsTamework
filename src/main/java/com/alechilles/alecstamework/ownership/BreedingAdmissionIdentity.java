package com.alechilles.alecstamework.ownership;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Derives replay-stable child identities from one durable breeding attempt key. */
final class BreedingAdmissionIdentity {
    private static final String NAMESPACE = "alecs-tamework:breeding-admission:";

    private BreedingAdmissionIdentity() {
    }

    @Nonnull
    static String profileId(@Nonnull String attemptKey, @Nonnull String childKey) {
        return derive("profile", attemptKey, childKey).toString();
    }

    @Nonnull
    static UUID npcUuid(@Nonnull String attemptKey, @Nonnull String childKey) {
        return derive("npc", attemptKey, childKey);
    }

    @Nonnull
    private static UUID derive(@Nonnull String kind,
                               @Nonnull String attemptKey,
                               @Nonnull String childKey) {
        String material = NAMESPACE + kind + ":"
                + Objects.requireNonNull(attemptKey, "attemptKey") + ":"
                + Objects.requireNonNull(childKey, "childKey");
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}
