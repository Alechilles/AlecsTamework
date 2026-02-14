package com.alechilles.alecstamework.npc.sensorinfo;

import com.hypixel.hytale.server.npc.sensorinfo.InfoProviderBase;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.parameterproviders.ParameterProvider;
import javax.annotation.Nullable;

/**
 * Info provider for Tamework hook sensors.
 */
public final class TameworkHookInfoProvider extends InfoProviderBase {
    public TameworkHookInfoProvider(@Nullable ParameterProvider parameterProvider, TameworkHookInfo hookInfo) {
        super(parameterProvider, hookInfo);
    }

    @Override
    public IPositionProvider getPositionProvider() {
        return null;
    }

    @Override
    public boolean hasPosition() {
        return false;
    }
}
