package com.alechilles.alecstamework.config.assets;

/** Builds mod setting declarations for tests without going through the asset store. */
public final class TwModSettingFixtures {
    private TwModSettingFixtures() {
    }

    public static TwModSetting.BooleanSetting bool(String id, boolean defaultValue) {
        TwModSetting.BooleanSetting setting = new TwModSetting.BooleanSetting();
        setting.id = id;
        setting.defaultValue = defaultValue;
        return setting;
    }

    public static TwModSetting.IntegerSetting integer(String id, int defaultValue, Integer min, Integer max) {
        TwModSetting.IntegerSetting setting = new TwModSetting.IntegerSetting();
        setting.id = id;
        setting.defaultValue = defaultValue;
        setting.min = min;
        setting.max = max;
        return setting;
    }

    public static TwModSettingsConfig group(String id, TwModSetting... settings) {
        TwModSettingsConfig group = new TwModSettingsConfig();
        group.id = id;
        group.settings = settings;
        return group;
    }
}
