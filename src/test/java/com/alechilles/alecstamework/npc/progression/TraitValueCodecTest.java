package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Tests round-trip encoding and decoding for trait metadata payloads. */
class TraitValueCodecTest {

    @Test
    void encodeDecodeRoundTripKeepsValidValues() {
        TameworkTraitsComponent.TraitValue[] values = new TameworkTraitsComponent.TraitValue[] {
                new TameworkTraitsComponent.TraitValue("Trait_Fertility", 1.2),
                new TameworkTraitsComponent.TraitValue("Trait_Disposition", 0.9)
        };

        String encoded = TraitValueCodec.encode(values);
        TameworkTraitsComponent.TraitValue[] decoded = TraitValueCodec.decode(encoded);

        assertNotNull(decoded);
        assertEquals(2, decoded.length);
        assertEquals("Trait_Fertility", decoded[0].getId());
        assertEquals(1.2, decoded[0].getValue(), 0.0001);
        assertEquals("Trait_Disposition", decoded[1].getId());
        assertEquals(0.9, decoded[1].getValue(), 0.0001);
    }
}
