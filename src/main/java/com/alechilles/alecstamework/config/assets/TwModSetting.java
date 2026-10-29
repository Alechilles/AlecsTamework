package com.alechilles.alecstamework.config.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.lookup.StringCodecMapCodec;
import java.math.BigDecimal;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One server setting declared by a mod inside a {@link TwModSettingsConfig} group. The JSON
 * {@code Type} field selects the subtype. Values are exchanged as JSON primitives so Patchwork
 * conditions and {@code $Setting} values see the same representation that is saved on disk.
 */
public abstract class TwModSetting {
    String id;
    String label;
    String description;

    private static final BuilderCodec<TwModSetting> BASE_CODEC = BuilderCodec.abstractBuilder(TwModSetting.class)
            .<String>append(
                    new KeyedCodec<>("Id", Codec.STRING),
                    (setting, value) -> setting.id = value,
                    setting -> setting.id
            )
            .documentation("Unique setting ID that patches use, such as AH_WildDragons. Start it with a short mod prefix so it cannot clash with another mod.")
            .add()
            .<String>append(
                    new KeyedCodec<>("Label", Codec.STRING),
                    (setting, value) -> setting.label = value,
                    setting -> setting.label
            )
            .documentation("Language key for the setting name shown in /tw settings.")
            .add()
            .<String>append(
                    new KeyedCodec<>("Description", Codec.STRING),
                    (setting, value) -> setting.description = value,
                    setting -> setting.description
            )
            .documentation("Optional language key for the tooltip that explains the setting.")
            .add()
            .build();

    private static final BuilderCodec<BooleanSetting> BOOLEAN_CODEC = BuilderCodec.builder(
                    BooleanSetting.class, BooleanSetting::new, BASE_CODEC)
            .<Boolean>append(
                    new KeyedCodec<>("Default", Codec.BOOLEAN),
                    (setting, value) -> setting.defaultValue = value != null && value,
                    setting -> setting.defaultValue
            )
            .documentation("Value used until a server admin changes it.")
            .add()
            .build();

    private static final BuilderCodec<EnumOption> ENUM_OPTION_CODEC = BuilderCodec.builder(EnumOption.class, EnumOption::new)
            .<String>append(
                    new KeyedCodec<>("Value", Codec.STRING),
                    (option, value) -> option.value = value,
                    option -> option.value
            )
            .documentation("Value saved and passed to patches when this option is chosen.")
            .add()
            .<String>append(
                    new KeyedCodec<>("Label", Codec.STRING),
                    (option, value) -> option.label = value,
                    option -> option.label
            )
            .documentation("Language key for the option name shown in the dropdown.")
            .add()
            .build();

    private static final BuilderCodec<EnumSetting> ENUM_CODEC = BuilderCodec.builder(
                    EnumSetting.class, EnumSetting::new, BASE_CODEC)
            .<EnumOption[]>append(
                    new KeyedCodec<>("Options", new ArrayCodec<>(ENUM_OPTION_CODEC, EnumOption[]::new)),
                    (setting, value) -> setting.options = value == null ? new EnumOption[0] : value,
                    setting -> setting.options
            )
            .documentation("Choices shown in the dropdown, in display order.")
            .add()
            .<String>append(
                    new KeyedCodec<>("Default", Codec.STRING),
                    (setting, value) -> setting.defaultValue = value,
                    setting -> setting.defaultValue
            )
            .documentation("Value of the option used until a server admin changes it. Defaults to the first option.")
            .add()
            .build();

    private static final BuilderCodec<IntegerSetting> INTEGER_CODEC = BuilderCodec.builder(
                    IntegerSetting.class, IntegerSetting::new, BASE_CODEC)
            .<Integer>append(
                    new KeyedCodec<>("Default", Codec.INTEGER),
                    (setting, value) -> setting.defaultValue = value == null ? 0 : value,
                    setting -> setting.defaultValue
            )
            .documentation("Value used until a server admin changes it.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Min", Codec.INTEGER),
                    (setting, value) -> setting.min = value,
                    setting -> setting.min
            )
            .documentation("Optional lowest allowed value.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Max", Codec.INTEGER),
                    (setting, value) -> setting.max = value,
                    setting -> setting.max
            )
            .documentation("Optional highest allowed value.")
            .add()
            .build();

    private static final BuilderCodec<DecimalSetting> DECIMAL_CODEC = BuilderCodec.builder(
                    DecimalSetting.class, DecimalSetting::new, BASE_CODEC)
            .<Double>append(
                    new KeyedCodec<>("Default", Codec.DOUBLE),
                    (setting, value) -> setting.defaultValue = value == null ? 0.0 : value,
                    setting -> setting.defaultValue
            )
            .documentation("Value used until a server admin changes it.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("Min", Codec.DOUBLE),
                    (setting, value) -> setting.min = value,
                    setting -> setting.min
            )
            .documentation("Optional lowest allowed value.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("Max", Codec.DOUBLE),
                    (setting, value) -> setting.max = value,
                    setting -> setting.max
            )
            .documentation("Optional highest allowed value.")
            .add()
            .build();

    static final StringCodecMapCodec<TwModSetting, BuilderCodec<? extends TwModSetting>> CODEC =
            new StringCodecMapCodec<>("Type") {
            };

    static {
        CODEC.register("Boolean", BooleanSetting.class, BOOLEAN_CODEC);
        CODEC.register("Enum", EnumSetting.class, ENUM_CODEC);
        CODEC.register("Integer", IntegerSetting.class, INTEGER_CODEC);
        CODEC.register("Decimal", DecimalSetting.class, DECIMAL_CODEC);
    }

    TwModSetting() {
    }

    @Nullable
    public String getId() {
        return id == null || id.isBlank() ? null : id.trim();
    }

    @Nullable
    public String getLabel() {
        return label == null || label.isBlank() ? null : label.trim();
    }

    @Nullable
    public String getDescription() {
        return description == null || description.isBlank() ? null : description.trim();
    }

    @Nonnull
    public abstract TwModSettingType getType();

    /** Value used when nothing valid is saved. */
    @Nonnull
    public abstract JsonPrimitive defaultValue();

    /**
     * Converts a saved or typed value into this setting's value, clamping numbers into range.
     * Returns null when the value cannot represent this setting at all.
     */
    @Nullable
    public abstract JsonPrimitive coerce(@Nullable JsonElement raw);

    /** Parses admin text input, such as a console argument, then coerces it. */
    @Nullable
    public JsonPrimitive parse(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return coerce(new JsonPrimitive(text.trim()));
    }

    /** True/false setting. Accepts true/false, on/off, yes/no and 1/0 from text input. */
    public static final class BooleanSetting extends TwModSetting {
        boolean defaultValue;

        @Nonnull @Override public TwModSettingType getType() { return TwModSettingType.BOOLEAN; }
        @Nonnull @Override public JsonPrimitive defaultValue() { return new JsonPrimitive(defaultValue); }

        @Nullable
        @Override
        public JsonPrimitive coerce(@Nullable JsonElement raw) {
            if (raw == null || !raw.isJsonPrimitive()) {
                return null;
            }
            JsonPrimitive primitive = raw.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive;
            }
            return switch (primitive.getAsString().trim().toLowerCase(java.util.Locale.ROOT)) {
                case "true", "on", "yes", "1" -> new JsonPrimitive(true);
                case "false", "off", "no", "0" -> new JsonPrimitive(false);
                default -> null;
            };
        }
    }

    /** Fixed list of choices; the saved value is the chosen option's Value. */
    public static final class EnumSetting extends TwModSetting {
        EnumOption[] options = new EnumOption[0];
        String defaultValue;

        @Nonnull @Override public TwModSettingType getType() { return TwModSettingType.ENUM; }

        @Nonnull
        public EnumOption[] getOptions() {
            return options == null ? new EnumOption[0] : options;
        }

        @Nonnull
        @Override
        public JsonPrimitive defaultValue() {
            JsonPrimitive declared = defaultValue == null ? null : coerce(new JsonPrimitive(defaultValue));
            if (declared != null) {
                return declared;
            }
            for (EnumOption option : getOptions()) {
                if (option != null && option.getValue() != null) {
                    return new JsonPrimitive(option.getValue());
                }
            }
            return new JsonPrimitive("");
        }

        @Nullable
        @Override
        public JsonPrimitive coerce(@Nullable JsonElement raw) {
            if (raw == null || !raw.isJsonPrimitive()) {
                return null;
            }
            String text = raw.getAsString().trim();
            for (EnumOption option : getOptions()) {
                if (option != null && option.getValue() != null && option.getValue().equalsIgnoreCase(text)) {
                    return new JsonPrimitive(option.getValue());
                }
            }
            return null;
        }
    }

    /** Whole number, clamped to Min/Max when present. */
    public static final class IntegerSetting extends TwModSetting {
        int defaultValue;
        Integer min;
        Integer max;

        @Nonnull @Override public TwModSettingType getType() { return TwModSettingType.INTEGER; }
        @Nullable public Integer getMin() { return min; }
        @Nullable public Integer getMax() { return max; }
        @Nonnull @Override public JsonPrimitive defaultValue() { return new JsonPrimitive(clamp(defaultValue)); }

        @Nullable
        @Override
        public JsonPrimitive coerce(@Nullable JsonElement raw) {
            BigDecimal number = number(raw);
            if (number == null) {
                return null;
            }
            long rounded = number.setScale(0, java.math.RoundingMode.HALF_UP).longValue();
            return new JsonPrimitive(clamp((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rounded))));
        }

        private int clamp(int value) {
            int result = value;
            if (min != null && result < min) result = min;
            if (max != null && result > max) result = max;
            return result;
        }
    }

    /** Decimal number, clamped to Min/Max when present. */
    public static final class DecimalSetting extends TwModSetting {
        double defaultValue;
        Double min;
        Double max;

        @Nonnull @Override public TwModSettingType getType() { return TwModSettingType.DECIMAL; }
        @Nullable public Double getMin() { return min; }
        @Nullable public Double getMax() { return max; }
        @Nonnull @Override public JsonPrimitive defaultValue() { return new JsonPrimitive(clamp(defaultValue)); }

        @Nullable
        @Override
        public JsonPrimitive coerce(@Nullable JsonElement raw) {
            BigDecimal number = number(raw);
            if (number == null) {
                return null;
            }
            double value = number.doubleValue();
            return Double.isFinite(value) ? new JsonPrimitive(clamp(value)) : null;
        }

        private double clamp(double value) {
            double result = value;
            if (min != null && result < min) result = min;
            if (max != null && result > max) result = max;
            return result;
        }
    }

    /** One dropdown choice of an {@link EnumSetting}. */
    public static final class EnumOption {
        String value;
        String label;

        public EnumOption() {
        }

        @Nullable
        public String getValue() {
            return value == null || value.isBlank() ? null : value.trim();
        }

        @Nullable
        public String getLabel() {
            return label == null || label.isBlank() ? null : label.trim();
        }
    }

    @Nullable
    private static BigDecimal number(@Nullable JsonElement raw) {
        if (raw == null || !raw.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = raw.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return null;
        }
        try {
            return new BigDecimal(primitive.getAsString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
