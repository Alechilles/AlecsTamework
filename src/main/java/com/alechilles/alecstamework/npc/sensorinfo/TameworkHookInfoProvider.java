package com.alechilles.alecstamework.npc.sensorinfo;

import com.hypixel.hytale.server.npc.sensorinfo.InfoProviderBase;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.PositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.parameterproviders.ParameterProvider;
import javax.annotation.Nullable;

/**
 * Info provider for Tamework hook sensors.
 */
public final class TameworkHookInfoProvider extends InfoProviderBase {
    private final TameworkHookInfo hookInfo;
    private final PositionProvider positionProvider;

    public TameworkHookInfoProvider(@Nullable ParameterProvider parameterProvider, TameworkHookInfo hookInfo) {
        super(parameterProvider, hookInfo);
        this.hookInfo = hookInfo;
        this.positionProvider = new PositionProvider(parameterProvider, hookInfo);
    }

    @Override
    public IPositionProvider getPositionProvider() {
        refreshPositionProvider();
        return positionProvider;
    }

    @Override
    public boolean hasPosition() {
        refreshPositionProvider();
        return positionProvider.hasPosition();
    }

    private void refreshPositionProvider() {
        if (hookInfo != null && hookInfo.hasTargetPosition()) {
            positionProvider.setTarget(hookInfo.getTargetX(), hookInfo.getTargetY(), hookInfo.getTargetZ());
        } else {
            positionProvider.clear();
        }
    }
}
