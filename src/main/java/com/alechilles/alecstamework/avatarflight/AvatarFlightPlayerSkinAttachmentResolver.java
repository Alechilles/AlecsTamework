package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.cosmetics.CosmeticRegistry;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPart;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPartTexture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Expands a player skin into concrete model attachments for avatar-flight rider doubles.
 */
public final class AvatarFlightPlayerSkinAttachmentResolver {
    private AvatarFlightPlayerSkinAttachmentResolver() {
    }

    @Nullable
    public static ResolvedAppearance resolve(@Nullable PlayerSkin skin,
                                             @Nonnull String fallbackBodyModel,
                                             @Nonnull String fallbackBodyTexture,
                                             @Nullable String fallbackGradientSet,
                                             @Nullable String fallbackGradientId) {
        CosmeticsModule cosmetics = CosmeticsModule.get();
        if (skin == null || cosmetics == null || cosmetics.getRegistry() == null) {
            return null;
        }
        CosmeticRegistry registry = cosmetics.getRegistry();
        SkinPartId bodyId = SkinPartId.parse(skin.bodyCharacteristic);
        String skinTone = bodyId == null ? fallbackGradientId : bodyId.colorId();
        ModelAttachment body = resolveAttachment(
                registry.getBodyCharacteristics(),
                skin.bodyCharacteristic,
                fallbackBodyModel,
                fallbackBodyTexture,
                fallbackGradientSet,
                fallbackGradientId,
                fallbackBodyModel,
                skinTone
        );
        if (body == null) {
            body = new ModelAttachment(
                    fallbackBodyModel,
                    fallbackBodyTexture,
                    fallbackGradientSet,
                    fallbackGradientId,
                    1.0
            );
        }

        ArrayList<ModelAttachment> attachments = new ArrayList<>();
        append(attachments, registry.getUnderwear(), skin.underwear, skinTone);
        append(attachments, registry.getFaces(), skin.face, skinTone);
        append(attachments, registry.getEars(), skin.ears, skinTone);
        append(attachments, registry.getMouths(), skin.mouth, skinTone);
        append(attachments, registry.getEyes(), skin.eyes, skinTone);
        append(attachments, registry.getEyebrows(), skin.eyebrows, skinTone);
        append(attachments, registry.getSkinFeatures(), skin.skinFeature, skinTone);
        append(attachments, registry.getFacialHairs(), skin.facialHair, skinTone);
        appendHaircut(attachments, registry, skin.haircut, skin.headAccessory, skinTone);
        append(attachments, registry.getPants(), skin.pants, skinTone);
        append(attachments, registry.getOverpants(), skin.overpants, skinTone);
        append(attachments, registry.getUndertops(), skin.undertop, skinTone);
        append(attachments, registry.getOvertops(), skin.overtop, skinTone);
        append(attachments, registry.getShoes(), skin.shoes, skinTone);
        append(attachments, registry.getGloves(), skin.gloves, skinTone);
        append(attachments, registry.getHeadAccessories(), skin.headAccessory, skinTone);
        append(attachments, registry.getFaceAccessories(), skin.faceAccessory, skinTone);
        append(attachments, registry.getEarAccessories(), skin.earAccessory, skinTone);
        append(attachments, registry.getCapes(), skin.cape, skinTone);
        return new ResolvedAppearance(body, attachments.toArray(ModelAttachment[]::new));
    }

    private static void append(@Nonnull List<ModelAttachment> attachments,
                               @Nonnull Map<String, PlayerSkinPart> parts,
                               @Nullable String id,
                               @Nullable String fallbackGradientId) {
        ModelAttachment attachment = resolveAttachment(parts, id, null, null, null, null, null, fallbackGradientId);
        if (attachment != null) {
            attachments.add(attachment);
        }
    }

    private static void appendHaircut(@Nonnull List<ModelAttachment> attachments,
                                      @Nonnull CosmeticRegistry registry,
                                      @Nullable String haircutId,
                                      @Nullable String headAccessoryId,
                                      @Nullable String fallbackGradientId) {
        String resolvedHaircutId = haircutId;
        if (haircutId != null && headAccessoryId != null) {
            SkinPartId hair = SkinPartId.parse(haircutId);
            SkinPartId accessory = SkinPartId.parse(headAccessoryId);
            if (hair != null && accessory != null) {
                PlayerSkinPart hairPart = registry.getHaircuts().get(hair.partId());
                PlayerSkinPart accessoryPart = registry.getHeadAccessories().get(accessory.partId());
                if (hairPart != null
                        && accessoryPart != null
                        && hairPart.doesRequireGenericHaircut()
                        && accessoryPart.getHeadAccessoryType() == PlayerSkinPart.HeadAccessoryType.HalfCovering) {
                    PlayerSkinPart generic = registry.getHaircuts().get("Generic" + hairPart.getHairType());
                    if (generic != null) {
                        resolvedHaircutId = generic.getId() + "." + hair.colorId();
                    }
                }
            }
        }
        append(attachments, registry.getHaircuts(), resolvedHaircutId, fallbackGradientId);
    }

    @Nullable
    private static ModelAttachment resolveAttachment(@Nonnull Map<String, PlayerSkinPart> parts,
                                                     @Nullable String id,
                                                     @Nullable String fallbackModel,
                                                     @Nullable String fallbackTexture,
                                                     @Nullable String fallbackGradientSet,
                                                     @Nullable String fallbackGradientId,
                                                     @Nullable String modelOverride,
                                                     @Nullable String defaultGradientId) {
        SkinPartId partId = SkinPartId.parse(id);
        if (partId == null) {
            return null;
        }
        PlayerSkinPart part = parts.get(partId.partId());
        if (part == null) {
            return null;
        }

        String model = modelOverride != null ? modelOverride : part.getModel();
        String texture = null;
        String gradientSet = part.getGradientSet();
        String gradientId = partId.colorId() == null ? defaultGradientId : partId.colorId();
        PlayerSkinPart.Variant variant = partId.variantId() == null || part.getVariants() == null
                ? null
                : part.getVariants().get(partId.variantId());

        if (variant != null) {
            model = variant.getModel() == null ? model : variant.getModel();
            if (variant.getTextures() != null && partId.colorId() != null) {
                PlayerSkinPartTexture textureVariant = variant.getTextures().get(partId.colorId());
                texture = textureVariant == null ? null : textureVariant.getTexture();
            }
            if (texture == null) {
                texture = variant.getGreyscaleTexture();
            }
        }
        if (texture == null && part.getTextures() != null && partId.colorId() != null) {
            PlayerSkinPartTexture textureVariant = part.getTextures().get(partId.colorId());
            texture = textureVariant == null ? null : textureVariant.getTexture();
        }
        if (texture == null) {
            texture = part.getGreyscaleTexture();
        }
        if (model == null || model.isBlank()) {
            model = fallbackModel;
        }
        if (texture == null || texture.isBlank()) {
            texture = fallbackTexture;
        }
        if (gradientSet == null || gradientSet.isBlank()) {
            gradientSet = fallbackGradientSet;
        }
        if (gradientId == null || gradientId.isBlank()) {
            gradientId = fallbackGradientId;
        }
        if (model == null || model.isBlank() || texture == null || texture.isBlank()) {
            return null;
        }
        if (part.getTextures() != null || variant != null && variant.getTextures() != null) {
            gradientSet = null;
            gradientId = null;
        }
        return new ModelAttachment(model, texture, gradientSet, gradientId, 1.0);
    }

    public record ResolvedAppearance(@Nonnull ModelAttachment body,
                                     @Nonnull ModelAttachment[] attachments) {
    }

    private record SkinPartId(@Nonnull String partId,
                              @Nullable String colorId,
                              @Nullable String variantId) {
        @Nullable
        static SkinPartId parse(@Nullable String id) {
            if (id == null || id.isBlank()) {
                return null;
            }
            String[] parts = id.split("\\.", -1);
            if (parts.length == 0 || parts[0].isBlank()) {
                return null;
            }
            String color = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
            String variant = parts.length > 2 && !parts[2].isBlank() ? parts[2] : null;
            return new SkinPartId(parts[0], color, variant);
        }
    }
}
