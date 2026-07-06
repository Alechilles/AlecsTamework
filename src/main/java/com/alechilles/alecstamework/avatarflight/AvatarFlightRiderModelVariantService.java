package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.asset.FileCommonAsset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Generates rider-safe attachment model variants so player cosmetics and armor bind to the seated rider.
 */
final class AvatarFlightRiderModelVariantService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String GENERATED_PREFIX = "Tamework/AvatarFlight/Rider/Variants/";
    private static final String LEGACY_EQUIPMENT_PREFIX = "Tamework/AvatarFlight/Rider/Equipment/";
    private static final String GENERATED_PACK = "Alechilles:Alec's Tamework!";
    private static final String GENERATED_ID_PREFIX = "tw_rider_attachment_";
    private static final Set<String> RIDER_SAFE_BIND_NODE_NAMES = Set.of(
            "Origin",
            "Pelvis",
            "Belly",
            "Chest",
            "Neck",
            "L-Eyelid",
            "R-Eyelid",
            "L-Eyelid-Bot",
            "R-Eyelid-Bot",
            "R-Shoulder",
            "R-Arm",
            "R-Forearm",
            "R-Hand",
            "R-Shoulder2",
            "L-Shoulder",
            "L-Arm",
            "L-Forearm",
            "L-Hand",
            "L-Shoulder2",
            "R-Thigh",
            "R-Calf",
            "R-Foot",
            "L-Thigh",
            "L-Calf",
            "L-Foot"
    );
    private static final ConcurrentHashMap<String, String> GENERATED_MODELS = new ConcurrentHashMap<>();

    private AvatarFlightRiderModelVariantService() {
    }

    @Nonnull
    static String resolveForRider(@Nonnull String model) {
        String normalized = normalizeCommonPath(model);
        if (normalized.isBlank() || isGeneratedVariant(normalized)) {
            return normalized;
        }
        String generated = generatedVariantPath(normalized);
        if (CommonAssetRegistry.hasCommonAsset(generated)) {
            return generated;
        }
        return GENERATED_MODELS.computeIfAbsent(normalized, AvatarFlightRiderModelVariantService::generateVariant);
    }

    @Nonnull
    static String generatedVariantPath(@Nonnull String model) {
        String normalized = normalizeCommonPath(model);
        if (isGeneratedVariant(normalized)) {
            return normalized;
        }
        return GENERATED_PREFIX + normalized;
    }

    static boolean isGeneratedVariant(@Nullable String model) {
        String normalized = normalizeCommonPath(model);
        return normalized.startsWith(GENERATED_PREFIX) || normalized.startsWith(LEGACY_EQUIPMENT_PREFIX);
    }

    @Nonnull
    static String rewriteBlockymodelJsonForRider(@Nonnull String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonElement nodes = root.get("nodes");
        if (nodes != null && nodes.isJsonArray()) {
            rewriteNodes(nodes.getAsJsonArray());
        }
        return GSON.toJson(root);
    }

    @Nonnull
    private static String generateVariant(@Nonnull String model) {
        CommonAsset source = CommonAssetRegistry.getByName(model);
        if (source == null) {
            return model;
        }
        String generated = generatedVariantPath(model);
        try {
            byte[] sourceBytes = source.getBlob().join();
            String rewritten = rewriteBlockymodelJsonForRider(new String(sourceBytes, StandardCharsets.UTF_8));
            CommonAsset generatedAsset = new FileCommonAsset(
                    Path.of(generated),
                    generated,
                    rewritten.getBytes(StandardCharsets.UTF_8)
            );
            CommonAssetRegistry.AddCommonAssetResult result =
                    CommonAssetRegistry.addCommonAsset(GENERATED_PACK, generatedAsset);
            CommonAssetModule module = CommonAssetModule.get();
            if (module != null && result.getActiveAsset().equals(result.getNewPackAsset())) {
                module.sendAsset(generatedAsset, false);
            }
            return generated;
        } catch (RuntimeException ex) {
            warnFailedVariant(model, ex);
            return model;
        }
    }

    private static void rewriteNodes(@Nonnull JsonArray nodes) {
        for (JsonElement element : nodes) {
            if (!element.isJsonObject()) {
                continue;
            }
            rewriteNode(element.getAsJsonObject());
        }
    }

    private static void rewriteNode(@Nonnull JsonObject node) {
        JsonElement id = node.get("id");
        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
            String value = id.getAsString();
            if (!value.startsWith(GENERATED_ID_PREFIX)) {
                node.addProperty("id", GENERATED_ID_PREFIX + value);
            }
        }

        JsonElement name = node.get("name");
        if (name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()) {
            String value = name.getAsString();
            if (RIDER_SAFE_BIND_NODE_NAMES.contains(value)) {
                node.addProperty("name", "TameworkRider_" + value);
            }
        }

        JsonElement children = node.get("children");
        if (children != null && children.isJsonArray()) {
            rewriteNodes(children.getAsJsonArray());
        }
    }

    @Nonnull
    private static String normalizeCommonPath(@Nullable String model) {
        if (model == null) {
            return "";
        }
        String normalized = model.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("Common/")) {
            normalized = normalized.substring("Common/".length());
        }
        return normalized;
    }

    private static void warnFailedVariant(@Nonnull String model, @Nonnull RuntimeException ex) {
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.WARNING).withCause(ex)
                    .log("TameworkAvatarFlight: failed to generate rider-safe attachment model for %s", model);
        }
    }
}
