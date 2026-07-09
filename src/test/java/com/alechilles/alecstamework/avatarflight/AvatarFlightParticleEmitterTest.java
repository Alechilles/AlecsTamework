package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AvatarFlightParticleEmitterTest {

    @Test
    void validOwnerIsAddedOnceWhenSpatialRecipientsAreEmpty() {
        Ref<EntityStore> ownerRef = new Ref<>(null, 7);
        List<Ref<EntityStore>> recipients = new ArrayList<>();

        AvatarFlightParticleEmitter.ensureOwnerRecipient(recipients, ownerRef);
        AvatarFlightParticleEmitter.ensureOwnerRecipient(recipients, ownerRef);

        assertEquals(1, recipients.size());
        assertSame(ownerRef, recipients.getFirst());
    }

    @Test
    void invalidOwnerIsNotAdded() {
        Ref<EntityStore> invalidOwnerRef = new Ref<>(null);
        List<Ref<EntityStore>> recipients = new ArrayList<>();

        AvatarFlightParticleEmitter.ensureOwnerRecipient(recipients, invalidOwnerRef);

        assertEquals(0, recipients.size());
    }
}
