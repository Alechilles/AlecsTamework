package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Supported all-or-nothing captured-artifact identity shape tests. */
class SpawnerCapturedArtifactIdentityTest {
    private static final String SOURCE = UUID.randomUUID().toString();
    private static final String PROFILE = UUID.randomUUID().toString();
    private static final String SNAPSHOT = UUID.randomUUID().toString();

    @Test
    void acceptsReleasedPublicTargetOnlyIdentity() {
        assertTrue(SpawnerCapturedArtifactIdentity.isSupported(stack(
                new BsonDocument().append(
                        TameworkMetadataKeys.TARGET_UUID,
                        new BsonString(SOURCE)
                )
        )));
    }

    @Test
    void acceptsCompleteCurrentIdentity() {
        assertTrue(SpawnerCapturedArtifactIdentity.isSupported(stack(
                new BsonDocument()
                        .append(
                                TameworkMetadataKeys.TARGET_UUID,
                                new BsonString(SOURCE)
                        )
                        .append(
                                TameworkMetadataKeys.COMPANION_PROFILE_ID,
                                new BsonString(PROFILE)
                        )
                        .append(
                                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                                new BsonString(SNAPSHOT)
                        )
        )));
    }

    @Test
    void rejectsMixedOrMissingIdentity() {
        assertFalse(SpawnerCapturedArtifactIdentity.isSupported(stack(
                new BsonDocument()
        )));
        assertFalse(SpawnerCapturedArtifactIdentity.isSupported(stack(
                new BsonDocument()
                        .append(
                                TameworkMetadataKeys.TARGET_UUID,
                                new BsonString(SOURCE)
                        )
                        .append(
                                TameworkMetadataKeys.COMPANION_PROFILE_ID,
                                new BsonString(PROFILE)
                        )
        )));
        assertFalse(SpawnerCapturedArtifactIdentity.isSupported(stack(
                new BsonDocument()
                        .append(
                                TameworkMetadataKeys.TARGET_UUID,
                                new BsonString(SOURCE)
                        )
                        .append(
                                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                                new BsonString(SNAPSHOT)
                        )
        )));
        assertFalse(SpawnerCapturedArtifactIdentity.isSupported(stack(
                new BsonDocument()
                        .append(
                                TameworkMetadataKeys.TARGET_UUID,
                                new BsonString(SOURCE)
                        )
                        .append(
                                TameworkMetadataKeys.COMPANION_PROFILE_ID,
                                new BsonInt32(1)
                        )
                        .append(
                                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                                new BsonString(SNAPSHOT)
                        )
        )));
    }

    private com.hypixel.hytale.server.core.inventory.ItemStack stack(
            BsonDocument metadata
    ) {
        return HytaleItemStackTestFixture.stack(
                "capture-device-filled",
                metadata
        );
    }
}
