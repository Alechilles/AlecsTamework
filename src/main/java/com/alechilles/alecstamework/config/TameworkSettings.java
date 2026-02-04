package com.alechilles.alecstamework.config;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.simple.BooleanCodec;

/**
 * Settings model and codec for tamework-settings.json.
 */
public final class TameworkSettings {
    public static final String BLOCK_OWNER_DAMAGE_TAG = "BlockOwnerDamage";
    public static final String BLOCK_ALL_PLAYER_DAMAGE_IF_OWNED_TAG = "BlockAllPlayerDamageIfOwned";
    public static final String INVULNERABLE_IF_OWNED_TAG = "InvulnerableIfOwned";

    public static final BuilderCodec<TameworkSettings> CODEC = BuilderCodec.builder(
            TameworkSettings.class,
            TameworkSettings::new
    ).append(
            new KeyedCodec<>(BLOCK_OWNER_DAMAGE_TAG, new BooleanCodec()),
            TameworkSettings::setBlockOwnerDamage,
            TameworkSettings::isBlockOwnerDamage
    ).add().append(
            new KeyedCodec<>(BLOCK_ALL_PLAYER_DAMAGE_IF_OWNED_TAG, new BooleanCodec()),
            TameworkSettings::setBlockAllPlayerDamageIfOwned,
            TameworkSettings::isBlockAllPlayerDamageIfOwned
    ).add().append(
            new KeyedCodec<>(INVULNERABLE_IF_OWNED_TAG, new BooleanCodec()),
            TameworkSettings::setInvulnerableIfOwned,
            TameworkSettings::isInvulnerableIfOwned
    ).add().build();

    private boolean blockOwnerDamage = true;
    private boolean blockAllPlayerDamageIfOwned = false;
    private boolean invulnerableIfOwned = false;

    public TameworkSettings() {
    }

    public TameworkSettings(boolean blockOwnerDamage,
                            boolean blockAllPlayerDamageIfOwned,
                            boolean invulnerableIfOwned) {
        this.blockOwnerDamage = blockOwnerDamage;
        this.blockAllPlayerDamageIfOwned = blockAllPlayerDamageIfOwned;
        this.invulnerableIfOwned = invulnerableIfOwned;
    }

    public boolean isBlockOwnerDamage() {
        return blockOwnerDamage;
    }

    public void setBlockOwnerDamage(boolean blockOwnerDamage) {
        this.blockOwnerDamage = blockOwnerDamage;
    }

    public boolean isBlockAllPlayerDamageIfOwned() {
        return blockAllPlayerDamageIfOwned;
    }

    public void setBlockAllPlayerDamageIfOwned(boolean blockAllPlayerDamageIfOwned) {
        this.blockAllPlayerDamageIfOwned = blockAllPlayerDamageIfOwned;
    }

    public boolean isInvulnerableIfOwned() {
        return invulnerableIfOwned;
    }

    public void setInvulnerableIfOwned(boolean invulnerableIfOwned) {
        this.invulnerableIfOwned = invulnerableIfOwned;
    }
}
