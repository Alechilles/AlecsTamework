---
title: "World Migration for Server Admins"
order: 12
published: true
draft: false
---
# World Migration for Server Admins

Parent: [Troubleshooting and Glossary](/mod/alecs-tamework/troubleshooting-and-glossary) | [Player Guides](/mod/alecs-tamework/player-guides)

Tamework 5.0 keeps companion data in a new place: plain files under
`universe/Tamework/Companions`. Older versions kept it in SQLite databases under
`universe/Tamework/Data`. This page explains what happens when a world saved by an
older version first starts on 5.0, and what a server admin should check.

Back up the whole world before you update. Tamework never changes the old files,
but a full backup is the only way to go back to the old version.

## Which path applies to your world

| The world was last saved by | What 5.0 does |
| --- | --- |
| Tamework 3.x or 4.x | Imports the companion data by itself at the first start. |
| Tamework 2.x | Does not import. Run Tamework 4.3.x once on the world first. See "2.x worlds" below. |
| No Tamework, or a world that already has a 5.0 store | Nothing. The server starts normally. |

## The first start on a 3.x or 4.x world

The import runs once, while the server starts, before any world loads. You do not
run a command.

- It only reads `tamework-state.sqlite` and `bonded-companions.sqlite`. It never
  changes, moves or deletes them, and it creates no files beside them. Backup files
  with other names are ignored.
- It writes the new store into a temporary folder, `Companions.importing`, checks
  that every file reads back, and then renames that folder to `Companions`. If the
  server is killed part way, the next start throws the temporary folder away and
  imports again from the beginning.
- It is all or nothing. A failed import leaves no `Companions` folder.

### How long it takes

The server does not finish starting until the import is done. A small world takes
about a second. A database of about 1 GB with a few thousand companions took 20 to
40 seconds in testing. Larger files take longer, mostly for the file check.

The import holds the old companion tables in memory. The 1 GB test database needed
about 350 MB of heap. If the server runs out of memory, the import fails cleanly and
the report tells you to raise the maximum heap size (`-Xmx`).

If the old server crashed and left a `tamework-state.sqlite-wal` file with data in
it, the import first copies the database into `Companions.importing`. That needs
free disk space equal to the size of the database.

### Console lines

At the start:

```text
Importing Tamework 3.x/4.x companion data (<file>, <size> bytes). This runs once and can take a few minutes on a large world; the old files are only read.
```

At the end:

```text
Imported <n> companions from Tamework 3.x/4.x data in <ms> ms: LIVE <n>, ITEM <n>, ...; <n> snapshots, <n> old body ids, ... Report: <path>
```

Do not stop the server between these two lines unless you must. Stopping it is safe,
but the import starts over.

### The report file

Each import writes `import-report-<date>-<time>.txt` (UTC) into
`universe/Tamework/Data`. It is plain English text. It lists the files that were
read, with size, modified time and schema, then the totals, then these lists. A list
that is empty shows a count of 0.

| List in the report | What it means for you |
| --- | --- |
| Companions, By location | How many companions were imported and where each one is: `LIVE` (out in a world), `ITEM` (in a capture item), `COOP`, `STORED`, `DEAD`, `LOST`, `RELEASED`. |
| Snapshots | How many saved states were carried over. |
| Old body ids | How many old animal ids Tamework remembers, so it can match animals in the world to their companions later. |
| Provider claims | Limits that another mod enforces do not count an imported companion until that companion next changes, for example on a summon, a store or a capture. |
| Unfinished operations | Work the old version had started and not finished. It is not replayed. Nothing to do. |
| Quarantined profiles | Companions the old version had set aside after an error. They are imported like any other. Check them in game. |
| Skipped rows | Old rows that could not be used. Each line has the table, the key and the reason. A skipped `companion_profile` row is a companion that was not imported. The other skipped rows are extra data, not companions. |
| Live without checkpoint | Companions that were out in a world with no saved copy of their body. See "Companions with no saved stats" below. |
| Live world guessed | The old data named no world for these. They show the most common world until their animal loads. On a server with several worlds the companion panel may show the wrong world until then. |
| Live used history | No saved copy of the body existed, so an older saved state is kept as the fallback. It is used only if the animal is gone and the owner recovers the companion. |
| Live used old death state | As above, but the only older state was from an earlier death. The companion is alive and has no death timer. |
| Checkpoints of dying bodies | The only saved copy was taken as the animal died. The death was removed from the copy, so the companion can be recovered if its animal is gone. |
| Imported lost | The old data named no usable animal or coop slot. The owner can recover these like any lost companion. |
| Npc uuid collisions | Two companions named the same animal. The one changed last kept it. The other was imported as lost. Both are listed. |
| Without state | Stored, dead, lost or coop companions with no readable saved state. They come back from their role at level 1. |
| State in item | Captured companions whose state is held by their capture item, not by the old database. Releasing the item brings them back. If the item was destroyed, the companion cannot be restored. |

The report ends with the steps to repeat the import.

### The receipt in meta.json

`universe/Tamework/Companions/meta.json` marks the store as created. After an import
it holds an `Import` section: the time, each source file with its path, size,
modified time and schema, and the counts. While `meta.json` exists, Tamework never
imports again and never reads the old databases.

Do not delete `legacy-aliases.json` in the same folder. Tamework uses it to match old
animals and old command links to their companions.

## What to check afterwards

1. Read the last console line and open the report. Compare the companion count with
   what you expect.
2. Look at **Skipped rows**. Any `companion_profile` line is a companion that did not
   come over.
3. Confirm that the old database files have the same size and modified time as
   before.
4. Join the server. Open a companion panel and check that names, levels and owners
   look right, including dead and lost companions.
5. Walk to a place where companions were left out. Each one should be there once.
6. Restart once. The console must not show a second import.

Keep the old database files. Tamework does not use them again, but they are your only
source if the import must be repeated. Do not run a 3.x or 4.x server on the same
universe while 5.0 imports from it.

## Animals in the world

Animals are not touched during the import. Each one is handled when its chunk loads.

- **The real animal is matched.** The animal that the old data named as the
  companion's current body is linked to its imported record. Its position, level,
  traits and name then come from the animal itself.
- **Leftover copies are removed.** Older versions sometimes left a second copy of a
  companion in a chunk that was not loaded. When such a copy loads and Tamework can
  prove the companion is held somewhere else, the copy is removed. This does not kill
  the companion and drops nothing. The console logs these removals.
- **When in doubt, nothing is removed.** An animal that cannot be proved to be a
  leftover is left alone.
- A player who tames or claims a leftover copy sees a message that it was removed and
  that the real companion is safe. A leftover copy cannot be captured.
- **Released animals can be tamed again.** An animal that 4.x had released is wild.
  Taming it makes a new companion.
- A companion that was imported as lost because the old data was unclear goes back to
  normal by itself when its animal loads.

## Capture items and command items

- **4.x capture items** work. Each item releases its companion once. A copy of the
  same item is refused after that.
- **2.x capture items** that were never used on 3.x or 4.x hold the animal's state in
  the item. The first release restores the animal from the item. Copies are refused.
- **Tamed animals in items with no owner** become owned by the player who releases
  them. That player's companion limits apply. A coop that requires a tamed or owned
  animal refuses such an item until it has been released once.
- **Command items** keep their links, including links that name an animal's old id.

## Coops

Old versions kept coop residents in the database, not on the coop block.

- When a coop loads, its imported residents are put back into its slots.
- If the coop block is there but its coop config is missing or disabled, for example
  because the coop's pack did not load, the residents wait. Nothing is released.
  Install the pack and they rejoin.
- If the coop block is gone, or the coop is full, the residents are released beside
  where the block was. If that is not possible they become lost, and their owners can
  recover them.

## Bonded revive items

4.x could hold the items of an unfinished bonded revive payment on the player. 5.0
does not use that holder. When the player enters a world, items from an unfinished
payment go back to the inventory, and anything that does not fit drops at the
player's feet. The player sees a message. A payment that was already spent or already
refunded returns nothing.

## Companions with no saved stats

Early Tamework versions did not save a copy of an animal when its chunk unloaded.
An animal tamed back then that has sat in an unloaded chunk ever since has no stats in
the old database. Its level, traits and inventory exist only on the animal, in the
world save. On a long-running server this can be a large share of the companions that
are out in the world. The report lists them under **Live without checkpoint**.

- These companions are imported with an empty state and position 0,0,0.
- When a player loads the chunk, the animal is matched and its real stats fill in.
  Nothing is lost.
- If the owner uses **Recover** on such a companion before its animal has been seen,
  Tamework has nothing to restore from. The owner gets a fresh animal of that role.
  If the original animal loads later, it is removed as a leftover copy, and its level
  and traits are gone.

Tell players to visit their animals before they use Recover on a companion that shows
no level or stats. The import report lists these companions under "Live without
checkpoint", so you can see how many your world has.

## If the import fails

Nothing is written, the old files are unchanged, and the server still starts.
Companion saving, capture, recall and the companion panel stay off.

- The console logs a warning with the reason.
- Operators who join see a **World needs conversion** notice. It says the import
  failed, names the report file, and offers `/tw persistence start-fresh`.
- `universe/Tamework/Data/import-report-failed.txt` holds the reason and the steps.
  Each failed start overwrites it. It is not removed after a later success, so check
  the time inside it.

Common reasons:

| Reason | What to do |
| --- | --- |
| The file is damaged | Restore the old database from a backup. |
| The file is not from a known Tamework version | The world may have been saved by an unreleased test build. Restore a backup made by a public release. |
| Not enough free disk space | Free space equal to the database size, then restart. |
| Not enough memory | Raise the maximum heap size (`-Xmx`), then restart. |
| The file could not be read | Check file permissions, and see "Supported platforms" below. |
| `Companions` is a link or junction | See below. |
| `Companions` holds files but no `meta.json` | Move that folder away, or restore its `meta.json`, then restart. |

**To retry:** fix the cause and restart. The import runs at every start until it
succeeds.

**To import again after a success:** stop the server and delete
`universe/Tamework/Companions` while the old database files are still in place. The
next start imports again. Everything that happened to companions since the first
import is lost.

### A Companions folder that is a link or junction

The import finishes by renaming a folder to `Companions`. That cannot replace a
symbolic link or a Windows junction, so the import refuses to run. Remove the link,
let the import run, then move the folder and link it again.

## 2.x worlds

Tamework 5.0 cannot read 2.x data (`tamework.sqlite` or the old `.dat` files). The
server starts with companion features off, and operators see a notice. You have two
choices:

1. Stop the server. Install Tamework 4.3.x and start the world once so it converts
   the data. Then install 5.0. The first 5.0 start imports the result as described
   above.
2. Give up the old companion data with `/tw persistence start-fresh`.

If a world has both 2.x files and 3.x or 4.x files, the 3.x or 4.x data is imported
and the 2.x files are listed in the report as ignored.

## Starting fresh

`/tw persistence start-fresh [confirm]`

Use this only when you accept losing the old companion data. It is available only
while old data blocks the world: a 2.x world, or a 3.x or 4.x world whose import
failed. At any other time it answers that there is nothing to do. It needs Tamework's
admin command permission and works from the console.

- Without `confirm` it only explains what it will do.
- With `confirm` it creates a new empty companion store with a `FreshStart` receipt
  in `meta.json`.
- It does not change or delete the old files.
- It does not turn companion features on in the running server. **Restart the
  server.**
- It does not import anything later. Animals in the world that were tamed are picked
  up as new companions when their chunks load. Stored, dead and captured companions
  from the old data do not come back.

To undo it, stop the server and delete `universe/Tamework/Companions` while the old
files are still there.

## The importer is temporary

A later Tamework release will remove the importer. After that, a 3.x or 4.x world
must run Tamework 5.0.x once before it can move to the newer version. Update old
worlds while 5.0.x is current, and keep a copy of the 5.0.x jar if you have worlds in
storage.

## Supported platforms

The import needs the SQLite library bundled in the Tamework jar. The jar includes it
for:

- Windows x64
- Linux x64 and ARM64
- Alpine and other musl-based Linux, x64 and ARM64
- macOS x64 and ARM64

On any other platform, such as 32-bit systems, Windows on ARM or FreeBSD, the import
fails with "the file could not be read" and changes nothing. Run the first 5.0 start
on a supported machine, then move the world. A world that is already on the 5.0 store
runs on any platform.

## Related Pages
- [Troubleshooting for Players](/mod/alecs-tamework/troubleshooting-for-players)
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)
