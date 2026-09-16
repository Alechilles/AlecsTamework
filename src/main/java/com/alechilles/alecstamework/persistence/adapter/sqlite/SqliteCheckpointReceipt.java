package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Compact receipt for one consumed internal entity checkpoint operation. */
final class SqliteCheckpointReceipt {
    static final String NAMESPACE = "Alechilles:Tamework:EntityCheckpoint";
    static final String IDEMPOTENCY_PREFIX = "companion-entity-checkpoint:v1:";
    private static final String MARKER = "checkpoint-operation-receipt";
    private static final int VERSION = 1;

    private SqliteCheckpointReceipt() {
    }

    static String create(String payload) {
        return "{\"receipt\":\"" + MARKER + "\",\"version\":" + VERSION
                + ",\"payloadSha256\":\"" + Sha256Hash.ofUtf8(payload) + "\"}";
    }

    static boolean isReceipt(OperationEnvelope operation) {
        return receiptHash(operation) != null;
    }

    static boolean matchesPayload(OperationEnvelope existing, String requestedPayload) {
        if (existing == null || requestedPayload == null) {
            return false;
        }
        if (existing.payloadJson().equals(requestedPayload)) {
            return true;
        }
        String expectedHash = receiptHash(existing);
        return expectedHash != null && Sha256Hash.ofUtf8(requestedPayload).toString()
                .equals(expectedHash);
    }

    private static String receiptHash(OperationEnvelope operation) {
        if (operation == null
                || operation.phase() != OperationPhase.PUBLISHED
                || !operation.kind().equals(ProfileExtensionMutationDefinition.KIND)
                || !operation.idempotencyKey().toString().startsWith(IDEMPOTENCY_PREFIX)) {
            return null;
        }
        try {
            JsonObject receipt = JsonParser.parseString(operation.payloadJson()).getAsJsonObject();
            if (receipt.size() != 3
                    || !MARKER.equals(string(receipt.get("receipt")))
                    || !Integer.valueOf(VERSION).equals(integer(receipt.get("version")))) {
                return null;
            }
            return Sha256Hash.parse(string(receipt.get("payloadSha256"))).toString();
        } catch (RuntimeException invalidReceipt) {
            return null;
        }
    }

    private static String string(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Checkpoint receipt string is required");
        }
        return element.getAsString();
    }

    private static Integer integer(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Checkpoint receipt version is required");
        }
        return element.getAsInt();
    }
}
