package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Ensures every damage adapter uses canonical live ownership only. */
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
    void commandLinksAndNameCannotSupplyOwnerWithoutCanonicalComponent() {
        UUID linkedOwner = UUID.randomUUID();
        TameworkCommandLinksComponent links = new TameworkCommandLinksComponent();
        links.setOwnerId(linkedOwner);
        assertNull(TamedDamageOwnerPolicyResolver.resolve(
                null, links, null, null
        ).policy().ownerUuid());

        UUID namingOwner = UUID.randomUUID();
        TameworkNpcNameComponent name = new TameworkNpcNameComponent(
                "Companion", namingOwner, 1L, TameworkNpcNameComponent.NameSource.Player
        );
        assertNull(TamedDamageOwnerPolicyResolver.resolve(
                null, null, name, null
        ).policy().ownerUuid());
        assertNull(TamedDamageOwnerPolicyResolver.resolve(
                null, null, null, null
        ).policy().ownerUuid());
    }
}
