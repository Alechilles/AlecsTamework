package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;

/**
 * Codec base that avoids the noisy default Codec#decodeJson stderr print.
 */
abstract class TwSilentCodec<T> implements Codec<T> {
    @Override
    public T decodeJson(RawJsonReader rawJsonReader, ExtraInfo extraInfo) throws IOException {
        return decode(RawJsonReader.readBsonValue(rawJsonReader), extraInfo);
    }
}
