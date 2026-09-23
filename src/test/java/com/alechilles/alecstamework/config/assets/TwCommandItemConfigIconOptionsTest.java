package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TwCommandItemConfigIconOptionsTest {
    @Test
    void parsesMoreThanEightChoicesAndRespectsArrayReplacement() {
        StringBuilder json = new StringBuilder("{\"IconOptions\":[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) json.append(',');
            json.append("{\"State\":\"Choice").append(i)
                    .append("\",\"LabelKey\":\"server.test.icon.").append(i).append("\"}");
        }
        TwCommandItemConfig parent = decode(json.append("]}").toString());
        TwCommandItemConfig inherited = decode("{}");
        inherited.inheritMissingTopLevelFrom(parent, Set.of());
        TwCommandItemConfig disabled = decode("{\"IconOptions\":[]}");
        disabled.inheritMissingTopLevelFrom(parent, Set.of("IconOptions"));

        assertEquals(10, inherited.getIconOptions().length);
        assertEquals("Choice9", inherited.getIconOptions()[9].getState());
        assertEquals("server.test.icon.9", inherited.getIconOptions()[9].getLabelKey());
        assertEquals(0, disabled.getIconOptions().length);
    }

    private static TwCommandItemConfig decode(String json) {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse(json), new ExtraInfo());
    }
}
