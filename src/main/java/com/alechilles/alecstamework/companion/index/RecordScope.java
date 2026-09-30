package com.alechilles.alecstamework.companion.index;

/**
 * Where a record lives on server networks (spec 13.2). World-bound records stay on
 * the server whose world holds the companion; portable records follow the owner.
 */
public enum RecordScope { WORLD_BOUND, PORTABLE }
