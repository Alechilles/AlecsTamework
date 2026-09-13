package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandLocateDescriptionTest {
    @Test
    void namesBothTheCaptureItemAndContainer() {
        assertEquals("Soul Lantern in Wooden Chest.", describe(Kind.CONTAINER, "Wooden_Chest", "Soul_Lantern"));
    }

    @Test
    void namesThePlayerOrGroundWithoutInventingContainerDetails() {
        assertEquals("Soul Lantern carried by Alec.", describe(Kind.PLAYER, "Alec", "Soul_Lantern"));
        assertEquals("Soul Lantern on the ground.", describe(Kind.DROPPED, "", "Soul_Lantern"));
    }

    @Test
    void olderSightingsKeepGenericWording() {
        assertEquals("Capture item in a storage container.", describe(Kind.CONTAINER, "", null));
    }

    private String describe(Kind kind, String name, String item) {
        return CommandLinkedNpcLocateService.describeSighting(null, new Sighting(
                new CaptureKey("profile", "snapshot", UUID.randomUUID()),
                new Holder(kind, "world", "holder", name, 1, 2, 3), 123, true, item));
    }
}
