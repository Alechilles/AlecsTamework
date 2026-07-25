package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.lookup.StringCodecMapCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.common.util.ArrayUtil;
import javax.annotation.Nonnull;
import org.bson.BsonNull;
import org.bson.BsonValue;

/** Focused polymorphic codecs for command-item NPC-role filters. */
final class TwCommandRoleCodecs {
    private static final Codec<String[]> NPC_ROLE_ARRAY_CODEC =
            new TwSilentCodec<>() {
                @Override
                public String[] decode(
                        @Nonnull BsonValue bsonValue,
                        ExtraInfo extraInfo
                ) {
                    return TwCodecLenient.asStringArrayOrEmpty(bsonValue);
                }

                @Override
                public BsonValue encode(String[] value, ExtraInfo extraInfo) {
                    return value == null
                            ? new BsonNull()
                            : Codec.STRING_ARRAY.encode(value, extraInfo);
                }

                @Nonnull
                @Override
                public Schema toSchema(@Nonnull SchemaContext context) {
                    StringSchema roleSchema = new StringSchema();
                    roleSchema.setHytaleAssetRef("NPCRole");
                    ArraySchema arraySchema = new ArraySchema();
                    arraySchema.setItem(roleSchema);
                    return arraySchema;
                }
            };

    private static final BuilderCodec<TwCommandItemConfig.AllowedRoles>
            BASE_CODEC = BuilderCodec.abstractBuilder(
                    TwCommandItemConfig.AllowedRoles.class
            ).build();
    private static final BuilderCodec<TwCommandItemConfig.AllowAllRoles>
            ALL_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.AllowAllRoles.class,
                    TwCommandItemConfig.AllowAllRoles::new,
                    BASE_CODEC
            ).build();
    private static final BuilderCodec<TwCommandItemConfig.AllowlistRoles>
            ALLOW_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.AllowlistRoles.class,
                    TwCommandItemConfig.AllowlistRoles::new,
                    BASE_CODEC
            )
            .<String[]>append(
                    new KeyedCodec<>("Allowlist", NPC_ROLE_ARRAY_CODEC),
                    (roles, value) -> roles.allowlist = value == null
                            ? ArrayUtil.EMPTY_STRING_ARRAY
                            : value,
                    roles -> roles.allowlist
            )
            .documentation("Role IDs that are allowed.")
            .add()
            .build();
    private static final BuilderCodec<TwCommandItemConfig.DenylistRoles>
            DENY_CODEC = BuilderCodec.builder(
                    TwCommandItemConfig.DenylistRoles.class,
                    TwCommandItemConfig.DenylistRoles::new,
                    BASE_CODEC
            )
            .<String[]>append(
                    new KeyedCodec<>("Denylist", NPC_ROLE_ARRAY_CODEC),
                    (roles, value) -> roles.denylist = value == null
                            ? ArrayUtil.EMPTY_STRING_ARRAY
                            : value,
                    roles -> roles.denylist
            )
            .documentation("Role IDs that are denied.")
            .add()
            .build();

    static final StringCodecMapCodec<
            TwCommandItemConfig.AllowedRoles,
            BuilderCodec<? extends TwCommandItemConfig.AllowedRoles>>
            CODEC = new StringCodecMapCodec<>("Mode") {
            };

    static {
        CODEC.register(
                "AllowAll",
                TwCommandItemConfig.AllowAllRoles.class,
                ALL_CODEC
        );
        CODEC.register(
                "Allowlist",
                TwCommandItemConfig.AllowlistRoles.class,
                ALLOW_CODEC
        );
        CODEC.register(
                "Denylist",
                TwCommandItemConfig.DenylistRoles.class,
                DENY_CODEC
        );
    }

    private TwCommandRoleCodecs() {
    }
}
