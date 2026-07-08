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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Generates transformed-owner model variants whose node ids cannot be reused by local player equipment visuals.
 */
final class AvatarFlightOwnerModelVariantService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String GENERATED_PREFIX = "Tamework/AvatarFlight/Owner/Variants/";
    private static final String GENERATED_PACK = "Alechilles:Alec's Tamework!";
    private static final String GENERATED_ID_PREFIX = "tw_avatar_owner_";
    private static final ConcurrentHashMap<String, String> GENERATED_MODELS = new ConcurrentHashMap<>();

    private AvatarFlightOwnerModelVariantService() {
    }

    @Nonnull
    static String resolveForOwner(@Nonnull String model) {
        String normalized = normalizeCommonPath(model);
        if (normalized.isBlank() || isGeneratedVariant(normalized)) {
            return normalized;
        }
        String generated = generatedVariantPath(normalized);
        if (CommonAssetRegistry.hasCommonAsset(generated)) {
            return generated;
        }
        return GENERATED_MODELS.computeIfAbsent(normalized, AvatarFlightOwnerModelVariantService::generateVariant);
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
        return normalizeCommonPath(model).startsWith(GENERATED_PREFIX);
    }

    @Nonnull
    static String rewriteBlockymodelJsonForOwner(@Nonnull String json) {
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
            String rewritten = rewriteBlockymodelJsonForOwner(new String(sourceBytes, StandardCharsets.UTF_8));
            CommonAsset generatedAsset = new AvatarFlightGeneratedCommonAsset(
                    generated,
                    rewritten.getBytes(StandardCharsets.UTF_8)
            );
            registerGeneratedAsset(generatedAsset);
            return generated;
        } catch (RuntimeException ex) {
            warnFailedVariant(model, ex);
            return model;
        }
    }

    private static void registerGeneratedAsset(@Nonnull CommonAsset generatedAsset) {
        CommonAssetModule module = CommonAssetModule.get();
        if (module != null) {
            module.addCommonAsset(GENERATED_PACK, generatedAsset, false);
            return;
        }
        CommonAssetRegistry.addCommonAsset(GENERATED_PACK, generatedAsset);
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
                    .log("TameworkAvatarFlight: failed to generate owner-safe transformed model for %s", model);
        }
    }
}
