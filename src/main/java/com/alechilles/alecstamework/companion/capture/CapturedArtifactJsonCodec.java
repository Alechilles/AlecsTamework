package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nonnull;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/** Version-one JSON and canonical-hash translation for captured inventory artifacts. */
public final class CapturedArtifactJsonCodec {
    private static final String HASH_DOMAIN =
            "tamework:captured-artifact:v1\n";
    private static final JsonWriterSettings EXTENDED_JSON =
            JsonWriterSettings.builder()
                    .outputMode(JsonMode.EXTENDED)
                    .build();

    private CapturedArtifactJsonCodec() {
    }

    /** Encodes one already validated artifact in a stable field order. */
    @Nonnull
    public static JsonObject encode(@Nonnull CapturedArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("Captured artifact is required");
        }
        JsonObject json = body(
                artifact.itemId(),
                artifact.quantity(),
                artifact.durability(),
                artifact.maxDurability(),
                artifact.metadataExtendedJson()
        );
        json.addProperty("artifactHash", artifact.artifactHash().toString());
        return json;
    }

    /** Decodes and verifies one artifact value. */
    @Nonnull
    public static CapturedArtifact decode(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Captured artifact JSON is required"
            );
        }
        return new CapturedArtifact(
                json.get("itemId").getAsString(),
                json.get("quantity").getAsInt(),
                json.get("durability").getAsDouble(),
                json.get("maxDurability").getAsDouble(),
                json.get("metadataExtendedJson").getAsString(),
                Sha256Hash.parse(json.get("artifactHash").getAsString())
        );
    }

    /**
     * Produces type-preserving BSON Extended JSON with recursively sorted object keys.
     * Array order remains authoritative and is never changed.
     */
    @Nonnull
    static String canonicalizeMetadata(@Nonnull String metadataExtendedJson) {
        if (metadataExtendedJson == null || metadataExtendedJson.isBlank()) {
            throw new IllegalArgumentException(
                    "Captured artifact metadata Extended JSON is required"
            );
        }
        final BsonDocument parsed;
        try {
            parsed = BsonDocument.parse(metadataExtendedJson);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Captured artifact metadata must be a BSON JSON object",
                    failure
            );
        }
        return canonicalize(parsed).asDocument().toJson(EXTENDED_JSON);
    }

    @Nonnull
    static Sha256Hash hash(
            String itemId,
            int quantity,
            double durability,
            double maxDurability,
            String metadataExtendedJson
    ) {
        return Sha256Hash.ofUtf8(
                HASH_DOMAIN + body(
                        itemId,
                        quantity,
                        durability,
                        maxDurability,
                        metadataExtendedJson
                )
        );
    }

    private static JsonObject body(
            String itemId,
            int quantity,
            double durability,
            double maxDurability,
            String metadataExtendedJson
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("itemId", itemId);
        json.addProperty("quantity", quantity);
        json.addProperty("durability", durability);
        json.addProperty("maxDurability", maxDurability);
        json.addProperty("metadataExtendedJson", metadataExtendedJson);
        return json;
    }

    private static BsonValue canonicalize(BsonValue value) {
        if (value.isDocument()) {
            BsonDocument source = value.asDocument();
            BsonDocument sorted = new BsonDocument();
            source.keySet().stream()
                    .sorted(Comparator.naturalOrder())
                    .forEach(key -> sorted.put(
                            key,
                            canonicalize(source.get(key))
                    ));
            return sorted;
        }
        if (value.isArray()) {
            List<BsonValue> values = new ArrayList<>();
            for (BsonValue element : value.asArray()) {
                values.add(canonicalize(element));
            }
            return new BsonArray(values);
        }
        return value;
    }
}
