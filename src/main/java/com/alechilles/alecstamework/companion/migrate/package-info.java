/**
 * Everything Tamework 5.0 does for a world that was saved by 3.x or 4.x (spec 12, plan 7).
 *
 * <p>The package has two parts with different lifetimes. Keep them apart when the importer is
 * dropped in a later release.</p>
 *
 * <p><b>Importer only.</b> These run once, at the first 5.0 start of an old world, and can be
 * deleted together with the bundled SQLite driver once 3.x/4.x worlds must pass through 5.0.x:
 * {@link com.alechilles.alecstamework.companion.migrate.CompanionImporter},
 * {@link com.alechilles.alecstamework.companion.migrate.LegacySource},
 * {@link com.alechilles.alecstamework.companion.migrate.LegacyReader},
 * {@link com.alechilles.alecstamework.companion.migrate.LegacyRows},
 * {@link com.alechilles.alecstamework.companion.migrate.LegacyMapper},
 * {@link com.alechilles.alecstamework.companion.migrate.ImportResult} and
 * {@link com.alechilles.alecstamework.companion.migrate.ImportReport}.</p>
 *
 * <p><b>Stay while imported worlds exist.</b> These act lazily, as old chunks, items, players and
 * coops load, which can be years after the import. Removing one would turn old bodies into new
 * companions, lose the state held in old items, or strand imported coop residents:</p>
 * <ul>
 *   <li>{@link com.alechilles.alecstamework.companion.migrate.LegacyAliases}: the old NPC UUIDs
 *       of an imported world, read at every start.</li>
 *   <li>{@link com.alechilles.alecstamework.companion.migrate.LegacyBodyResolution}: matches an old
 *       body to its record, or removes it as a stale copy.</li>
 *   <li>{@link com.alechilles.alecstamework.companion.migrate.LegacyBodyLocator},
 *       {@link com.alechilles.alecstamework.companion.migrate.LegacyBodyLocate},
 *       {@link com.alechilles.alecstamework.companion.migrate.SavedChunks} and
 *       {@code LegacyLocateProgress}: the background pass that reads the saved chunks to find the
 *       bodies of imported companions nobody has seen since the import (plan 7 task 13). It runs
 *       only while such companions exist and resumes after a restart, so it must stay as long as
 *       a store can still hold them.</li>
 *   <li>{@link com.alechilles.alecstamework.companion.migrate.LegacyItemAdoption}: old capture
 *       items.</li>
 *   <li>{@link com.alechilles.alecstamework.companion.migrate.LegacyState}: reads the state in a
 *       2.x item for {@code LegacyItemAdoption} and the state of a body found in a saved chunk for
 *       {@code LegacyBodyLocate} (the importer uses it too).</li>
 *   <li>{@link com.alechilles.alecstamework.companion.migrate.RetiredComponentCleanup}: strips
 *       retired 4.x components from bodies and players as they load.</li>
 *   <li>{@link com.alechilles.alecstamework.companion.migrate.EscrowRefund}: returns items held by
 *       an unfinished 4.x revive payment.</li>
 *   <li>{@code CoopImportedResidents} in {@code companion.coop}: puts imported coop residents back
 *       into their block.</li>
 * </ul>
 *
 * <p>The format 0 snapshot ({@code SnapshotEnvelope.FORMAT_IMPORTED_STATE}) and its restore path
 * in {@code HytaleCompanionSpawner} stay for the same reason: an imported companion keeps its
 * format 0 snapshot until its first restore.</p>
 */
package com.alechilles.alecstamework.companion.migrate;
