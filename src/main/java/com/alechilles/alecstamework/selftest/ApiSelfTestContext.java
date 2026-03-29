package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkApi;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record ApiSelfTestContext(@Nonnull Tamework plugin,
                                 @Nonnull TameworkApi api,
                                 @Nonnull Player player,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull Ref<EntityStore> playerRef,
                                 @Nonnull World world,
                                 @Nullable ApiSelfTestFixtureSet fixtureSet) {
}
