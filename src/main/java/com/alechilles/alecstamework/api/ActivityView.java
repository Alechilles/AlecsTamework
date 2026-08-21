package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

/** Common contract implemented by each typed Activity API V2 payload. */
public interface ActivityView {
    /** Returns the immutable common header. */
    @Nonnull
    ActivityHeader header();

    /** Returns the stable domain for this payload family. */
    @Nonnull
    ActivityDomain domain();

    /** Returns a payload with the supplied feed-assigned header. */
    @Nonnull
    ActivityView withHeader(@Nonnull ActivityHeader header);
}
