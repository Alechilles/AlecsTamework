package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandHudDirtySinkTest {
    @Test
    void fanOutMarksEachIndependentTracker() {
        UUID playerUuid = UUID.fromString("c5b0ce9e-75c0-41b0-a66d-5de54ebe5466");
        CommandTargetHudActivationTracker target = new CommandTargetHudActivationTracker();
        CommandTargetHudActivationTracker hotswap = new CommandTargetHudActivationTracker();
        CommandHudDirtySink sink = CommandHudDirtySink.fanOut(target, hotswap);

        sink.markDirty(playerUuid);

        Assertions.assertEquals(List.of(playerUuid), target.selectCandidateBatch(1).playerUuids());
        Assertions.assertEquals(List.of(playerUuid), hotswap.selectCandidateBatch(1).playerUuids());
    }
}
