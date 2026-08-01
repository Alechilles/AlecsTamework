package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import java.util.HashSet;
import java.util.UUID;
import javax.annotation.Nullable;

/** Owns bounded panel-cache lifecycle and eager known-roster warming. */
final class BondedCompanionPanelLifecycle implements AutoCloseable {
    @Nullable private final BondedCompanionPanelEntrySourceService readModel;
    private final CommandItemRegistry registry;

    BondedCompanionPanelLifecycle(CommandItemRegistry registry,
                                  @Nullable BondedCompanionPanelEntrySourceService readModel) {
        this.registry = registry;
        this.readModel = readModel;
    }

    void warmForOwner(@Nullable UUID ownerUuid) {
        if (ownerUuid == null || readModel == null) return;
        HashSet<String> rosterIds = new HashSet<>();
        for (TwCommandItemConfig config : registry.snapshot().values()) {
            if (config != null && config.usesBondedCompanionRoster()
                    && config.getBondedRosterId() != null) {
                rosterIds.add(config.getBondedRosterId());
            }
        }
        for (String rosterId : rosterIds) readModel.warm(ownerUuid, rosterId);
    }

    void warm(@Nullable UUID ownerUuid, @Nullable String rosterId) {
        if (readModel != null) readModel.warm(ownerUuid, rosterId);
    }

    AutoCloseable subscribe(UUID ownerUuid, String rosterId, Runnable listener) {
        return readModel == null ? () -> { }
                : readModel.subscribe(ownerUuid, rosterId, listener);
    }

    void evictOwner(@Nullable UUID ownerUuid) {
        if (readModel != null) readModel.evictOwner(ownerUuid);
    }

    void refreshBoundApi() {
        if (readModel != null) readModel.refreshBoundApi();
    }

    @Override
    public void close() {
        if (readModel != null) readModel.close();
    }
}
