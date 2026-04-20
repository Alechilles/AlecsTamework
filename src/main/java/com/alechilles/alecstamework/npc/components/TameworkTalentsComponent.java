package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Stores purchased passive talents and spent talent points for a companion.
 */
public final class TameworkTalentsComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkTalentsComponent> CODEC = BuilderCodec.builder(
            TameworkTalentsComponent.class,
            TameworkTalentsComponent::new
    )
            .append(
                    new KeyedCodec<>("ConfigId", Codec.STRING),
                    TameworkTalentsComponent::setConfigId,
                    TameworkTalentsComponent::getConfigId
            )
            .add()
            .append(
                    new KeyedCodec<>("SpentPoints", Codec.INTEGER),
                    TameworkTalentsComponent::setSpentPoints,
                    TameworkTalentsComponent::getSpentPoints
            )
            .add()
            .append(
                    new KeyedCodec<>("PurchasedTalentIds", Codec.STRING_ARRAY),
                    TameworkTalentsComponent::setPurchasedTalentIds,
                    TameworkTalentsComponent::getPurchasedTalentIds
            )
            .add()
            .build();

    private String configId;
    private int spentPoints;
    private String[] purchasedTalentIds = ArrayUtil.EMPTY_STRING_ARRAY;

    public TameworkTalentsComponent() {
    }

    public TameworkTalentsComponent(String configId, int spentPoints, String[] purchasedTalentIds) {
        this.configId = configId;
        setSpentPoints(spentPoints);
        setPurchasedTalentIds(purchasedTalentIds);
    }

    public static ComponentType<EntityStore, TameworkTalentsComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getTalentsComponentType() : null;
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public int getSpentPoints() {
        return spentPoints;
    }

    public void setSpentPoints(int spentPoints) {
        this.spentPoints = Math.max(0, spentPoints);
    }

    public String[] getPurchasedTalentIds() {
        return purchasedTalentIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : purchasedTalentIds;
    }

    public void setPurchasedTalentIds(String[] purchasedTalentIds) {
        this.purchasedTalentIds = sanitizePurchasedTalentIds(purchasedTalentIds);
    }

    public boolean hasPurchasedTalent(String talentId) {
        if (talentId == null || talentId.isBlank()) {
            return false;
        }
        for (String purchasedTalentId : getPurchasedTalentIds()) {
            if (talentId.equalsIgnoreCase(purchasedTalentId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public TameworkTalentsComponent clone() {
        return new TameworkTalentsComponent(
                configId,
                spentPoints,
                purchasedTalentIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : purchasedTalentIds.clone()
        );
    }

    private static String[] sanitizePurchasedTalentIds(String[] values) {
        if (values == null || values.length == 0) {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            unique.add(value.trim());
        }
        return unique.isEmpty() ? ArrayUtil.EMPTY_STRING_ARRAY : unique.toArray(new String[0]);
    }
}
