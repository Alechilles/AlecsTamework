package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.bson.BsonNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class TwCodecLenientTest {
    @Test
    void scalarAssetReferencesKeepLenientDecodingAndNullEncoding() {
        Codec<String> codec = TwCodecLenient.assetReferenceCodec("Item");

        assertEquals(
                "Items:Apple",
                codec.decode(BsonDocument.parse("{ \"Asset\": \"Items:Apple\" }"), new ExtraInfo())
        );
        assertNull(codec.decode(new BsonNull(), new ExtraInfo()));
        assertInstanceOf(BsonNull.class, codec.encode(null, new ExtraInfo()));
    }
}
