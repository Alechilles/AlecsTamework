package com.alechilles.alecstamework.npc.sensorinfo;

import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProviderBase;
import com.hypixel.hytale.server.npc.sensorinfo.PositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.parameterproviders.ParameterProvider;
import javax.annotation.Nullable;

/**
 * Info provider that exposes a dynamic world-space target from TameworkTargetPositionInfo.
 */
public final class TameworkTargetPositionInfoProvider extends InfoProviderBase {
    private final TameworkTargetPositionInfo positionInfo;
    private final PositionProvider positionProvider;

    public TameworkTargetPositionInfoProvider(@Nullable ParameterProvider parameterProvider,
                                              TameworkTargetPositionInfo positionInfo) {
        super(parameterProvider, positionInfo);
        this.positionInfo = positionInfo;
        this.positionProvider = new PositionProvider(parameterProvider, positionInfo);
    }

    @Override
    public IPositionProvider getPositionProvider() {
        refresh();
        return positionProvider;
    }

    @Override
    public boolean hasPosition() {
        refresh();
        return positionProvider.hasPosition();
    }

    private void refresh() {
        if (positionInfo != null && positionInfo.hasPosition()) {
            positionProvider.setTarget(
                    positionInfo.getTargetX(),
                    positionInfo.getTargetY(),
                    positionInfo.getTargetZ()
            );
            return;
        }
        positionProvider.clear();
    }
}
