package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.common.util.ArrayUtil;

/** Focused codecs for the bonded-roster asset and its nested policy sections. */
public final class TwBondedCompanionRosterCodecs {
    static final BuilderCodec<
            TwBondedCompanionRosterConfig.RevivePriceDefinition>
            REVIVE_PRICE_CODEC = BuilderCodec.builder(
                    TwBondedCompanionRosterConfig.RevivePriceDefinition.class,
                    TwBondedCompanionRosterConfig.RevivePriceDefinition::new
            )
            .<TwItemCostComponent[]>append(
                    new KeyedCodec<>("Costs", TwItemCostComponent.ARRAY_CODEC),
                    (price, value) -> price.costs =
                            TwItemCostComponent.validateAndCopy(value),
                    price -> price.getCosts()
            )
            .documentation(
                    "Ordered AND item recipe charged for revival. Every cost "
                            + "line is required. Within explicit RevivePrice, "
                            + "an omitted Costs field inherits and an explicit "
                            + "array replaces the parent recipe."
            )
            .add()
            .build();

    static final BuilderCodec<TwBondedCompanionRosterConfig.RoleRevivePriceDefinition>
            ROLE_REVIVE_PRICE_CODEC = BuilderCodec.builder(
                    TwBondedCompanionRosterConfig.RoleRevivePriceDefinition.class,
                    TwBondedCompanionRosterConfig.RoleRevivePriceDefinition::new
            )
            .<String>append(new KeyedCodec<>("RoleId", Codec.STRING),
                    (price, value) -> price.roleId = value,
                    TwBondedCompanionRosterConfig.RoleRevivePriceDefinition::getRoleId)
            .documentation("Exact allowed role ID for this revival recipe.").add()
            .<TwItemCostComponent[]>append(
                    new KeyedCodec<>("Costs", TwItemCostComponent.ARRAY_CODEC),
                    (price, value) -> price.costs = TwItemCostComponent.validateAndCopy(value),
                    TwBondedCompanionRosterConfig.RoleRevivePriceDefinition::getCosts)
            .documentation("Ordered AND item recipe charged for this role's revival.").add()
            .build();

    static final ArrayCodec<TwBondedCompanionRosterConfig.RoleRevivePriceDefinition>
            ROLE_REVIVE_PRICES_CODEC = new ArrayCodec<>(
                    ROLE_REVIVE_PRICE_CODEC,
                    TwBondedCompanionRosterConfig.RoleRevivePriceDefinition[]::new
            );

    static final BuilderCodec<
            TwBondedCompanionRosterConfig.FeatureToggles>
            FEATURES_CODEC = BuilderCodec.builder(
                    TwBondedCompanionRosterConfig.FeatureToggles.class,
                    TwBondedCompanionRosterConfig.FeatureToggles::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("Capture", Codec.BOOLEAN),
                    (features, value) -> features.capture =
                            value == null || value,
                    features -> features.capture
            )
            .documentation("Allows capture into this bonded roster; omission inherits.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Provision", Codec.BOOLEAN),
                    (features, value) -> features.provision =
                            value == null || value,
                    features -> features.provision
            )
            .documentation("Allows direct provisioning; omission inherits.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Summon", Codec.BOOLEAN),
                    (features, value) -> features.summon =
                            value == null || value,
                    features -> features.summon
            )
            .documentation("Allows summoning stored profiles; omission inherits.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Dismiss", Codec.BOOLEAN),
                    (features, value) -> features.dismiss =
                            value == null || value,
                    features -> features.dismiss
            )
            .documentation("Allows storing active profiles; omission inherits.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Revive", Codec.BOOLEAN),
                    (features, value) -> features.revive =
                            value == null || value,
                    features -> features.revive
            )
            .documentation("Allows revival of dead profiles; omission inherits.")
            .add()
            .build();

    public static final AssetBuilderCodec<
            String,
            TwBondedCompanionRosterConfig
            > CODEC = AssetBuilderCodec.builder(
                    TwBondedCompanionRosterConfig.class,
                    TwBondedCompanionRosterConfig::new,
                    Codec.STRING,
                    (asset, id) -> asset.id = id,
                    asset -> asset.id,
                    (asset, data) -> asset.data = data,
                    asset -> asset.data
            )
            .documentation(
                    "Separate bonded-companion roster policy for Alec's Tamework."
            )
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value,
                    asset -> asset.priority
            )
            .documentation("Resolver priority. An omitted value inherits.")
            .add()
            .<String>append(
                    new KeyedCodec<>("RosterId", Codec.STRING),
                    (asset, value) -> asset.rosterId = value,
                    asset -> asset.rosterId
            )
            .documentation("Stable namespaced roster ID. An omitted value inherits.")
            .add()
            .<String>append(
                    new KeyedCodec<>("FamilyId", Codec.STRING),
                    (asset, value) -> asset.familyId = value,
                    asset -> asset.familyId
            )
            .documentation("Stable namespaced companion family ID. An omitted value inherits.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("AllowedRoles", Codec.STRING_ARRAY),
                    (asset, value) -> asset.allowedRoles = value == null
                            ? ArrayUtil.EMPTY_STRING_ARRAY
                            : value,
                    asset -> asset.allowedRoles
            )
            .documentation(
                    "Exact allowed role IDs. Omission inherits; an explicit "
                            + "array replaces the parent value (no merge)."
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("MaximumOwned", Codec.INTEGER),
                    (asset, value) -> asset.maximumOwned = value == null
                            ? 0
                            : value,
                    asset -> asset.maximumOwned
            )
            .documentation("Maximum owned profiles; 0 is unlimited. Omission inherits.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("MaximumActive", Codec.INTEGER),
                    (asset, value) -> asset.maximumActive = value == null
                            ? 0
                            : value,
                    asset -> asset.maximumActive
            )
            .documentation("Maximum active profiles; 0 is unlimited. Omission inherits.")
            .add()
            .<Long>append(
                    new KeyedCodec<>("SessionDurationSeconds", Codec.LONG),
                    (asset, value) -> asset.sessionDurationSeconds = value == null
                            ? 0L
                            : value,
                    asset -> asset.sessionDurationSeconds
            )
            .documentation(
                    "Active lease duration in seconds; exactly 0 disables the "
                            + "timer. An omitted value inherits."
            )
            .add()
            .<Long>append(
                    new KeyedCodec<>("SummonCooldownSeconds", Codec.LONG),
                    (asset, value) -> asset.summonCooldownSeconds = value == null
                            ? 0L
                            : value,
                    asset -> asset.summonCooldownSeconds
            )
            .documentation(
                    "Summon cooldown in seconds; exactly 0 disables the timer. "
                            + "An omitted value inherits."
            )
            .add()
            .<Long>append(
                    new KeyedCodec<>("ReviveCooldownSeconds", Codec.LONG),
                    (asset, value) -> asset.reviveCooldownSeconds = value == null
                            ? 0L : value,
                    asset -> asset.reviveCooldownSeconds
            )
            .documentation(
                    "Revive cooldown in seconds after confirmed death; exactly "
                            + "0 disables the timer. An omitted value inherits."
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("SummonAuraEffectId", Codec.STRING),
                    (asset, value) -> asset.summonAuraEffectId = value,
                    asset -> asset.getSummonAuraEffectId()
            )
            .documentation(
                    "Optional EntityEffect applied after a new projection is "
                            + "summoned. Omission inherits; blank disables it."
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("ExpiryWarningEffectId", Codec.STRING),
                    (asset, value) -> asset.expiryWarningEffectId = value,
                    asset -> asset.getExpiryWarningEffectId()
            )
            .documentation(
                    "Optional EntityEffect applied to the live companion at "
                            + "the 30-second expiry warning. Omission inherits; "
                            + "blank disables it."
            )
            .add()
            .<TwBondedCompanionRosterConfig.RevivePriceDefinition>append(
                    new KeyedCodec<>("RevivePrice", REVIVE_PRICE_CODEC),
                    (asset, value) -> asset.revivePrice = value,
                    asset -> asset.revivePrice
            )
            .documentation(
                    "Optional revive price. Omission inherits the parent object; "
                            + "explicit nested fields override and missing nested "
                            + "fields inherit."
            )
            .add()
            .<TwBondedCompanionRosterConfig.RoleRevivePriceDefinition[]>append(
                    new KeyedCodec<>("RevivePriceByRole", ROLE_REVIVE_PRICES_CODEC),
                    (asset, value) -> asset.revivePriceByRole = value == null
                            ? TwBondedCompanionRosterConfig.RoleRevivePriceDefinition.EMPTY_ARRAY : value,
                    asset -> asset.getRevivePriceByRole()
            )
            .documentation(
                    "Optional exact role-specific revive recipes. Omission inherits; "
                            + "an explicit array replaces the parent mapping."
            )
            .add()
            .<TwBondedCompanionRosterConfig.FeatureToggles>append(
                    new KeyedCodec<>("Features", FEATURES_CODEC),
                    (asset, value) -> asset.features = value == null
                            ? new TwBondedCompanionRosterConfig.FeatureToggles()
                            : value,
                    asset -> asset.features
            )
            .documentation(
                    "Bonded feature policy. Omission inherits the parent object; "
                            + "explicit nested fields override and missing nested "
                            + "fields inherit."
            )
            .add()
            .build();

    private TwBondedCompanionRosterCodecs() {
    }
}
