package com.alechilles.alecstamework.companion.population.domain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Encodes and validates the exact terminal evidence for domain admission. */
final class PopulationDomainAdmissionSettlement {
    private PopulationDomainAdmissionSettlement() {
    }

    static void validate(
            PopulationDomainAdmissionOperation.Payload payload,
            Set<Integer> ordinals,
            Map<Integer, UUID> children
    ) {
        if (ordinals.isEmpty() && !children.isEmpty()) {
            throw new IllegalArgumentException("Child receipts require settled ordinals");
        }
        boolean batch = !payload.provisionalChildIds().isEmpty();
        boolean childKeysValid = batch
                ? children.keySet().equals(ordinals)
                : children.isEmpty();
        if (!childKeysValid
                || ordinals.stream().anyMatch(ordinal ->
                ordinal == null || ordinal < 0 || ordinal >= payload.requestedCount())
                || children.values().stream().anyMatch(java.util.Objects::isNull)
                || children.values().stream().distinct().count() != children.size()) {
            throw new IllegalArgumentException("Batch settlement evidence is not exact");
        }
    }

    static String encode(
            PopulationDomainAdmissionOperation.Payload payload,
            boolean canceled,
            Set<Integer> ordinals,
            Map<Integer, UUID> children
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("status", canceled ? "CANCELED" : "COMMITTED");
        json.addProperty("requestedCount", payload.requestedCount());
        JsonArray settled = new JsonArray();
        ordinals.stream().sorted().forEach(settled::add);
        json.add("settledOrdinals", settled);
        JsonObject receipts = new JsonObject();
        children.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> receipts.addProperty(
                        Integer.toString(entry.getKey()), entry.getValue().toString()
                ));
        json.add("childReceipts", receipts);
        return json.toString();
    }

    static PopulationDomainAdmissionOperation.SettlementEvidence decode(
            String encoded
    ) {
        JsonObject object = JsonParser.parseString(encoded).getAsJsonObject();
        boolean canceled = "CANCELED".equals(object.get("status").getAsString());
        TreeSet<Integer> ordinals = new TreeSet<>();
        JsonArray array = object.getAsJsonArray("settledOrdinals");
        if (array != null) {
            for (JsonElement value : array) {
                ordinals.add(value.getAsInt());
            }
        }
        LinkedHashMap<Integer, UUID> receipts = new LinkedHashMap<>();
        JsonObject values = object.getAsJsonObject("childReceipts");
        if (values != null) {
            for (String key : values.keySet()) {
                receipts.put(Integer.parseInt(key), UUID.fromString(
                        values.get(key).getAsString()
                ));
            }
        }
        return new PopulationDomainAdmissionOperation.SettlementEvidence(
                canceled, ordinals, receipts,
                object.has("requestedCount")
                        ? object.get("requestedCount").getAsInt() : 0
        );
    }
}
