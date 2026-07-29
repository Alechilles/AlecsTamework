package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Reconstructs released-public captured state from its original split authorities.
 *
 * <p>Canonical profile, alias, owner, and tool links remain authoritative. The exact captured
 * artifact contributes only state that public Tamework historically stored on the filled item.
 * No live assets, clocks, or mutable world state are consulted.</p>
 */
final class LegacyCapturedArtifactFullStateMapper {
    private final LegacyCapturedArtifactProgressionMapper progression =
            new LegacyCapturedArtifactProgressionMapper();

    @Nonnull
    CoopResidentStateSnapshot map(
            @Nonnull CompanionProfileReadModel profile,
            @Nonnull LegacyCaptureV1Payload payload,
            @Nonnull CapturedArtifact artifact
    ) {
        if (profile.currentAlias() == null) {
            throw new IllegalArgumentException(
                    "Legacy captured artifact requires a current alias"
            );
        }
        LegacyCapturedArtifactMetadata metadata =
                LegacyCapturedArtifactMetadata.parse(artifact);
        CompanionProfileProjectionState projected =
                CompanionProfileProjectionState.compose(
                        profile.identity(),
                        profile.currentAlias(),
                        profile.lifecycle(),
                        profile.toolLinks(),
                        profile.currentSnapshots(),
                        profile.currentCoopSlot()
        );
        validateCanonicalEvidence(profile, projected, payload, metadata);
        boolean tamed = resolveTamedState(profile, projected, metadata);
        LegacyCapturedArtifactProgressionMapper.State progressionState =
                progression.map(
                        metadata,
                        payload.capturedAtMs(),
                        profile.identity().roleId()
                );
        OwnerId ownerId = profile.lifecycle().ownerId();
        UUID ownerUuid = ownerId == null ? null : ownerId.value();
        return new CoopResidentStateSnapshot(
                profile.currentAlias().alias().value(),
                null,
                -1,
                profile.identity().roleId(),
                commandLinks(profile, ownerUuid, payload.homePosition()),
                ownerUuid == null ? null : new TameworkOwnerComponent(
                        ownerUuid,
                        projected.ownerName()
                ),
                new TameworkTamedComponent(tamed),
                npcName(metadata, projected, payload.capturedAtMs()),
                progressionState.happiness(),
                progressionState.needs(),
                progressionState.breeding(),
                progressionState.leveling(),
                progressionState.traits(),
                progressionState.talents(),
                progressionState.lifeStage(),
                attachments(metadata),
                progressionState.healthPercent(),
                payload.capturedAtMs()
        );
    }

    private void validateCanonicalEvidence(
            CompanionProfileReadModel profile,
            CompanionProfileProjectionState projected,
            LegacyCaptureV1Payload payload,
            LegacyCapturedArtifactMetadata metadata
    ) {
        String payloadRole = payload.roleId();
        String canonicalRole = profile.identity().roleId();
        if (payloadRole != null && canonicalRole != null
                && !payloadRole.equalsIgnoreCase(canonicalRole)) {
            throw new IllegalArgumentException(
                    "Legacy capture role conflicts with canonical profile"
            );
        }
        UUID itemOwner = metadata.uuid(TameworkMetadataKeys.OWNER_UUID);
        UUID canonicalOwner = profile.lifecycle().ownerId() == null
                ? null
                : profile.lifecycle().ownerId().value();
        if (itemOwner != null && !itemOwner.equals(canonicalOwner)) {
            throw new IllegalArgumentException(
                    "Legacy capture owner conflicts with canonical lifecycle"
            );
        }
    }

    private boolean resolveTamedState(
            CompanionProfileReadModel profile,
            CompanionProfileProjectionState projected,
            LegacyCapturedArtifactMetadata metadata
    ) {
        Boolean itemTamed = metadata.bool(TameworkMetadataKeys.TAMED);
        if (itemTamed == null || itemTamed == projected.tamed()) {
            return projected.tamed();
        }
        if (Boolean.TRUE.equals(itemTamed)
                && canonicalTamedStateMissing(profile)) {
            return true;
        }
        throw new IllegalArgumentException(
                "Legacy capture tamed state conflicts with canonical profile"
        );
    }

    /**
     * Public v2.16.1 rows could omit state JSON, leaving no canonical tamed value to compare.
     * Malformed metadata remains a conflict rather than becoming an implicit compatibility path.
     */
    private boolean canonicalTamedStateMissing(CompanionProfileReadModel profile) {
        String metadata = profile.identity().metadataJson();
        if (metadata == null || metadata.isBlank()) {
            return true;
        }
        try {
            JsonElement root = JsonParser.parseString(metadata);
            return root.isJsonObject()
                    && (!root.getAsJsonObject().has("tamed")
                    || root.getAsJsonObject().get("tamed").isJsonNull());
        } catch (RuntimeException invalidMetadata) {
            throw new IllegalArgumentException(
                    "Legacy capture canonical tamed state is invalid",
                    invalidMetadata
            );
        }
    }

    @Nullable
    private TameworkCommandLinksComponent commandLinks(
            CompanionProfileReadModel profile,
            @Nullable UUID ownerUuid,
            @Nullable SnapshotVector3 home
    ) {
        String[] toolIds = profile.toolLinks().stream()
                .sorted(Comparator.comparing(
                        link -> link.toolId().toString()
                ))
                .map(CompanionToolLink::toolId)
                .map(UUID::toString)
                .toArray(String[]::new);
        if (toolIds.length == 0) {
            return null;
        }
        return new TameworkCommandLinksComponent(
                ownerUuid,
                toolIds,
                home == null
                        ? null
                        : new Vector3d(home.x(), home.y(), home.z())
        );
    }

    @Nullable
    private TameworkNpcNameComponent npcName(
            LegacyCapturedArtifactMetadata metadata,
            CompanionProfileProjectionState projected,
            long capturedAtMs
    ) {
        metadata.requireCompleteGroup(
                "name",
                new String[]{
                        TameworkMetadataKeys.NPC_NAME,
                        TameworkMetadataKeys.NPC_NAME_UPDATED_MS
                },
                TameworkMetadataKeys.NPC_NAME,
                TameworkMetadataKeys.NPC_NAME_OWNER_UUID,
                TameworkMetadataKeys.NPC_NAME_UPDATED_MS,
                TameworkMetadataKeys.NPC_NAME_SOURCE
        );
        String name = metadata.text(TameworkMetadataKeys.NPC_NAME);
        if (name == null) {
            name = projected.customName();
        }
        if (name == null) {
            return null;
        }
        UUID owner = metadata.uuid(
                TameworkMetadataKeys.NPC_NAME_OWNER_UUID
        );
        Long updated = metadata.integer(
                TameworkMetadataKeys.NPC_NAME_UPDATED_MS
        );
        String sourceText = metadata.text(
                TameworkMetadataKeys.NPC_NAME_SOURCE
        );
        TameworkNpcNameComponent.NameSource source =
                sourceText == null
                        ? TameworkNpcNameComponent.NameSource.Player
                        : enumValue(
                                TameworkNpcNameComponent.NameSource.class,
                                sourceText,
                                TameworkMetadataKeys.NPC_NAME_SOURCE
                        );
        return new TameworkNpcNameComponent(
                name,
                owner,
                updated == null ? capturedAtMs : updated,
                source
        );
    }

    @Nullable
    private TameworkAttachmentsComponent attachments(
            LegacyCapturedArtifactMetadata metadata
    ) {
        String encoded = metadata.text(TameworkMetadataKeys.ATTACHMENTS);
        if (encoded == null) {
            return null;
        }
        final Map<String, String> values;
        try {
            JsonElement root = JsonParser.parseString(encoded);
            if (root == null || !root.isJsonObject()) {
                throw new IllegalArgumentException(
                        "Attachment root must be an object"
                );
            }
            values = attachmentValues(root.getAsJsonObject());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Legacy capture attachments are invalid",
                    failure
            );
        }
        if (values == null) {
            throw new IllegalArgumentException(
                    "Legacy capture attachments are invalid"
            );
        }
        return new TameworkAttachmentsComponent(null, values);
    }

    private Map<String, String> attachmentValues(JsonObject root) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            JsonElement value = entry.getValue();
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(
                        "Attachment selections must be nonblank strings"
                );
            }
            String selection = value.getAsString();
            if (selection == null || selection.isBlank()) {
                throw new IllegalArgumentException(
                        "Attachment selections must be nonblank strings"
                );
            }
            result.put(entry.getKey(), selection);
        }
        return result;
    }

    private <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value,
            String field
    ) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Legacy capture " + field + " is invalid",
                    failure
            );
        }
    }

}
