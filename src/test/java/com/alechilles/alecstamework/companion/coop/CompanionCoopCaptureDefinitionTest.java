package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Captured-item source variants and backward-compatible coop operation payload coverage. */
class CompanionCoopCaptureDefinitionTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias SOURCE =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final SnapshotId CAPTURE_SNAPSHOT_ID =
            SnapshotId.parse("40000000-0000-0000-0000-000000000001");
    private static final LifecycleRevision EXPECTED =
            new LifecycleRevision(7);
    private static final CoopSlotKey SLOT =
            new CoopSlotKey("world", "coop-chicken", 10, 64, 20, 2);
    private static final String RECEIPT_KEY = "coop-item-receipt";

    @Test
    void capturedItemRoundTripsExactCurrentAndReceiptArtifacts() {
        CompanionCoopCaptureRequest request = capturedItemRequest(
                housedPayload("Henrietta")
        );

        String encoded = CompanionCoopCaptureDefinition.INSTANCE.encode(
                request
        );
        CompanionCoopCaptureRequest decoded =
                CompanionCoopCaptureDefinition.INSTANCE.decode(encoded);

        assertEquals(1, CompanionCoopCaptureDefinition.INSTANCE.payloadVersion());
        assertEquals(request, decoded);
        CoopCapturedItemSourceEvidence source = assertInstanceOf(
                CoopCapturedItemSourceEvidence.class, decoded.source()
        );
        assertEquals(
                CoopCapturedItemInventoryPosition.Section.STORAGE,
                source.inventoryPosition().section()
        );
        assertEquals(RECEIPT_KEY, source.retirementReceiptKey());
    }

    @Test
    void priorLiveEntityPayloadWithoutDiscriminatorRemainsReadable() {
        CompanionCoopCaptureRequest request = liveRequest();
        JsonObject payload = JsonParser.parseString(
                CompanionCoopCaptureDefinition.INSTANCE.encode(request)
        ).getAsJsonObject();
        payload.getAsJsonObject("source").remove("kind");

        CompanionCoopCaptureRequest decoded =
                CompanionCoopCaptureDefinition.INSTANCE.decode(
                        payload.toString()
                );

        assertEquals(request, decoded);
        assertEquals(
                CoopCaptureSource.Kind.LIVE_ENTITY,
                decoded.source().kind()
        );
    }

    @Test
    void targetSnapshotMayChangeOnlyTheExactCoopPlacement() {
        assertThrows(
                IllegalArgumentException.class,
                () -> capturedItemRequest(housedPayload("Different Name"))
        );
        String wrongSlot = portablePayload("Henrietta")
                .replace("\"coopId\":null", "\"coopId\":\"coop-chicken\"")
                .replace("\"residentSlot\":-1", "\"residentSlot\":1");
        assertThrows(
                IllegalArgumentException.class,
                () -> capturedItemRequest(wrongSlot)
        );
    }

    @Test
    void sourceSnapshotMustBeTheExactCurrentLifecycleFence() {
        CompanionSnapshot stale = captureSnapshot(
                new LifecycleRevision(5),
                true
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> capturedItemRequest(
                        stale,
                        sourceArtifact(stale),
                        receiptArtifact(sourceArtifact(stale), RECEIPT_KEY),
                        housedPayload("Henrietta")
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoopCapturedItemSourceEvidence(
                        SOURCE,
                        PROFILE,
                        captureSnapshot(captureRevision(), false),
                        ACTOR,
                        "world",
                        position(),
                        sourceArtifact(captureSnapshot(
                                captureRevision(), false
                        )),
                        receiptArtifact(
                                sourceArtifact(
                                        captureSnapshot(
                                                captureRevision(), false
                                        )
                                ),
                                RECEIPT_KEY
                        ),
                        RECEIPT_KEY
                )
        );
    }

    @Test
    void receiptArtifactMustBeOnlyTheExactlyMarkedSource() {
        CompanionSnapshot capture = captureSnapshot(
                captureRevision(), true
        );
        CapturedArtifact source = sourceArtifact(capture);
        BsonDocument damaged = BsonDocument.parse(
                source.metadataExtendedJson()
        );
        damaged.put(
                CoopCapturedItemSourceEvidence.RECEIPT_METADATA_KEY,
                new BsonString(RECEIPT_KEY)
        );
        damaged.put("unrelated", new BsonString("mutation"));
        CapturedArtifact receipt = CapturedArtifact.create(
                source.itemId(),
                source.quantity(),
                source.durability(),
                source.maxDurability(),
                damaged.toJson()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> capturedItemRequest(
                        capture,
                        source,
                        receipt,
                        housedPayload("Henrietta")
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoopCapturedItemInventoryPosition(
                        CoopCapturedItemInventoryPosition.Section.HOTBAR,
                        -1
                )
        );
    }

    private CompanionCoopCaptureRequest capturedItemRequest(
            String targetPayload
    ) {
        CompanionSnapshot capture = captureSnapshot(
                captureRevision(), true
        );
        CapturedArtifact source = sourceArtifact(capture);
        return capturedItemRequest(
                capture,
                source,
                receiptArtifact(source, RECEIPT_KEY),
                targetPayload
        );
    }

    private CompanionCoopCaptureRequest capturedItemRequest(
            CompanionSnapshot capture,
            CapturedArtifact source,
            CapturedArtifact receipt,
            String targetPayload
    ) {
        return new CompanionCoopCaptureRequest(
                PROFILE,
                EXPECTED,
                SLOT,
                coopSnapshot(targetPayload),
                new CoopCapturedItemSourceEvidence(
                        SOURCE,
                        PROFILE,
                        capture,
                        ACTOR,
                        "world",
                        position(),
                        source,
                        receipt,
                        RECEIPT_KEY
                ),
                -100
        );
    }

    private CompanionCoopCaptureRequest liveRequest() {
        String payload = housedPayload("Henrietta");
        return new CompanionCoopCaptureRequest(
                PROFILE,
                EXPECTED,
                SLOT,
                coopSnapshot(payload),
                new CoopCaptureSourceEvidence(
                        SOURCE, "world", "live-retirement"
                ),
                -100
        );
    }

    private CompanionSnapshot captureSnapshot(
            LifecycleRevision revision,
            boolean current
    ) {
        String payload = portablePayload("Henrietta");
        return new CompanionSnapshot(
                CAPTURE_SNAPSHOT_ID,
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                CompanionCaptureRequest.SNAPSHOT_VERSION,
                payload,
                Sha256Hash.ofUtf8(payload),
                revision,
                current,
                -200
        );
    }

    private LifecycleRevision captureRevision() {
        return new LifecycleRevision(EXPECTED.value() - 1);
    }

    private CompanionSnapshot coopSnapshot(String payload) {
        return new CompanionSnapshot(
                SnapshotId.parse(
                        "40000000-0000-0000-0000-000000000002"
                ),
                PROFILE,
                CompanionCoopCaptureRequest.SNAPSHOT_KIND,
                CompanionCoopCaptureRequest.SNAPSHOT_VERSION,
                payload,
                Sha256Hash.ofUtf8(payload),
                EXPECTED.next(),
                true,
                -100
        );
    }

    private CapturedArtifact sourceArtifact(CompanionSnapshot capture) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty(
                TameworkMetadataKeys.TARGET_UUID, SOURCE.toString()
        );
        metadata.addProperty(
                TameworkMetadataKeys.COMPANION_PROFILE_ID,
                PROFILE.toString()
        );
        metadata.addProperty(
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                capture.snapshotId().toString()
        );
        return CapturedArtifact.create(
                "captured-chicken",
                1,
                0.0D,
                0.0D,
                metadata.toString()
        );
    }

    private CapturedArtifact receiptArtifact(
            CapturedArtifact source,
            String receiptKey
    ) {
        BsonDocument metadata = BsonDocument.parse(
                source.metadataExtendedJson()
        );
        metadata.put(
                CoopCapturedItemSourceEvidence.RECEIPT_METADATA_KEY,
                new BsonString(receiptKey)
        );
        return CapturedArtifact.create(
                source.itemId(),
                source.quantity(),
                source.durability(),
                source.maxDurability(),
                metadata.toJson()
        );
    }

    private CoopCapturedItemInventoryPosition position() {
        return new CoopCapturedItemInventoryPosition(
                CoopCapturedItemInventoryPosition.Section.STORAGE,
                4
        );
    }

    private String portablePayload(String name) {
        return "{\"version\":\"1\",\"npcUuid\":\"" + SOURCE
                + "\",\"coopId\":null,\"residentSlot\":-1,"
                + "\"roleId\":\"tamed_chicken\",\"npcName\":\""
                + name + "\",\"capturedAtMs\":-200}";
    }

    private String housedPayload(String name) {
        return portablePayload(name)
                .replace(
                        "\"coopId\":null",
                        "\"coopId\":\"coop-chicken\""
                )
                .replace("\"residentSlot\":-1", "\"residentSlot\":2");
    }
}
