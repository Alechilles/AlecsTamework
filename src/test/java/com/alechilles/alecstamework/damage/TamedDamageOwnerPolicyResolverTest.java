package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Locks the shared live damage-owner fallback order used by runtime and the public API. */
class TamedDamageOwnerPolicyResolverTest {
    @Test
    void canonicalOwnerComponentWinsOverDerivedMetadata() {
        UUID owner = UUID.randomUUID();
        TameworkCommandLinksComponent links = new TameworkCommandLinksComponent();
        links.setOwnerId(UUID.randomUUID());
        TameworkNpcNameComponent name = new TameworkNpcNameComponent(
                "Companion", UUID.randomUUID(), 1L, TameworkNpcNameComponent.NameSource.Player
        );

        assertEquals(owner, TamedDamageOwnerPolicyResolver.resolve(
                new TameworkOwnerComponent(owner, "Owner"), links, name, null
        ).policy().ownerUuid());
    }

    @Test
    void commandLinkOwnerWinsOverNpcNameWhenCanonicalOwnerIsMissing() {
        UUID linkedOwner = UUID.randomUUID();
        TameworkCommandLinksComponent links = new TameworkCommandLinksComponent();
        links.setOwnerId(linkedOwner);
        UUID namingOwner = UUID.randomUUID();
        TameworkNpcNameComponent name = new TameworkNpcNameComponent(
                "Companion", namingOwner, 1L, TameworkNpcNameComponent.NameSource.Player
        );

        assertEquals(linkedOwner, TamedDamageOwnerPolicyResolver.resolve(
                new TameworkOwnerComponent(), links, name, null
        ).policy().ownerUuid());
    }

    @Test
    void npcNameOwnerIsLastLiveFallback() {
        UUID namingOwner = UUID.randomUUID();
        TameworkNpcNameComponent name = new TameworkNpcNameComponent(
                "Companion", namingOwner, 1L, TameworkNpcNameComponent.NameSource.Player
        );

        assertEquals(namingOwner, TamedDamageOwnerPolicyResolver.resolve(
                null, new TameworkCommandLinksComponent(), name, null
        ).policy().ownerUuid());
    }

    @Test
    void missingLiveOwnerMetadataRemainsUnowned() {
        assertNull(TamedDamageOwnerPolicyResolver.resolve(
                null, null, null, null
        ).policy().ownerUuid());
    }
}
