# ADR 0007: Replacement Persistence Core Implementation

- Status: Accepted; feature recovery implemented, exact live acceptance pending
- Date: 2026-07-23

## 2026-07-24 feature-scope correction

The shared core and its complexity constraints remain accepted. ADR 0008
supersedes the conclusion that every unreleased capability should remain absent.
Required HyDragon-facing and captured-item-to-coop behavior was rebuilt as
focused participants in this core under ADR 0008. The historical reduced
artifact was not feature-complete; the composed replacement implementation now
awaits exact-artifact live acceptance.

## 2026-08-20 throughput amendment

The persistence core keeps its single-writer and at-least-once delivery
contracts, but it no longer makes every consumer read every outbox row.
Projection subscriptions now route SQL reads by consumer event type. One
loaded batch is acknowledged once after all relevant rows apply. Sequence gaps
from events for other consumers remain valid and do not weaken checkpoint
ordering.

Publication requests merge only when they have the same consumer and
publication context. Live publication, recovery, and rebuild work therefore
cannot hide or acknowledge each other. A schema V2 index supports the routed
read. Startup migrates an exact V1 database to V2 before normal work begins;
the schema history still rejects unknown or changed schema bytes.

Live profile observations and entity checkpoints now use bounded maintenance
coordinators. Profile work permits at most 16 active alias chains. Checkpoint
work permits at most four. Each alias retains the newest routine snapshot,
while unload and destructive-removal checkpoints use a priority lane and must
become durable before later routine work. Unknown identity evidence releases
its permit while a bounded retry timer waits, so it cannot hold the maintenance
queue.

First live observation publishes the canonical profile before its immutable
entity checkpoint. Shutdown drains profile work, checkpoint work, and the core
writer against one shared deadline. Passive diagnostics expose counts, maximum
depth, merge totals, failures, and oldest pending age only. They do not expose
player names, owner IDs, companion UUIDs, or payload JSON.

Deterministic gates cover 10,000 unrelated projection events followed by one
relevant event and 500 distinct live profile observations. A staged server
test must still measure the live p95 spawn and Recall latency target.

## Context

The replacement architecture needs fewer independent mechanisms than the unreleased v5-v9
system, not a second set of abstractions layered over it. The shared core therefore has to make
transaction ownership, lifecycle ownership, recovery, and publication mechanically consistent
before any gameplay feature is ported.

## Decision

The replacement core has one SQLite writer and six connection-bound authorities:

1. companion identity;
2. canonical companion lifecycle;
3. versioned companion snapshots;
4. operation envelopes;
5. incidents and scoped quarantine;
6. the projection outbox.

`SqlitePersistenceTransactionContext` constructs all six over the same caller-owned connection.
The stores do not open connections, commit, roll back, create threads, or call projection
consumers. `SqliteUnitOfWorkRunner` and `SqliteSingleWriter` exclusively own transaction
execution and exact unknown-commit readback.

Every persistence-affecting operation follows one staged path:

1. prepare an idempotent, typed operation;
2. persist `LIVE_APPLYING` before an external live effect;
3. commit canonical state, durable operation evidence, and at least one projection event in one
   transaction;
4. publish projections only after that commit;
5. advance to `PUBLISHED` only after successful publication.

Recovery decodes a versioned payload before leasing the operation. It selects an action from the
shared phase graph and confines an undecodable operation to an operation-scoped incident and
quarantine. Active quarantine does not remove other recoverable operations from the scan.

Projection events use one monotonic SQLite sequence and per-consumer monotonic checkpoints.
Delivery is sequential and at least once; consumers distinguish a newly applied aggregate
revision from a duplicate. Deletion and compaction remain disabled until a canonical rebuild has
been proved equivalent and a later ADR enables retention.

Reconciliation generation is canonical lifecycle evidence. It is not an operation-envelope
coordinate, and zero is valid.

One static feature registry is the catalog for operation definitions, scope
coverage, projection consumers, recovery, readiness, quarantine policy,
shutdown, and diagnostics. Startup derives one dependency graph from that
catalog. The registry is cross-cutting composition, not another gameplay or
lifecycle authority.

## Complexity constraints

- Replacement core classes must remain at or below 500 lines.
- Canonical lifecycle has one SQL update statement and one revision-fenced transition path.
- Durable operation work receives only the transaction context and operation envelope.
- Filesystem, network, ECS, inventory, cache, and projection callbacks cannot run inside a
  canonical database transaction.
- Replacement packages cannot depend on the superseded `persistence.sqlite` package.
- Feature-specific phases, transaction runners, recovery queues, and projection journals are
  prohibited; feature differences belong in registered payload codecs and focused detail ports.

These constraints are enforced by
`ReplacementPersistenceArchitectureGuardTest` and the persistence inventory
gate.

## Verification

The forked-JVM process crash matrix halts at all shared boundaries:

- before prepare commit;
- after prepare before live apply;
- during live apply;
- after live apply before durable commit;
- when commit reports an outcome that may already be durable;
- after durable commit before publication;
- during publication;
- after publication before acknowledgement;
- during compensation;
- during shutdown.

The core tests additionally prove exact unknown-commit readback without duplicate durable work,
outbox replay and rebuild comparison, alias and lifecycle revision fencing, versioned snapshot
decode failure, scoped incident containment, lease takeover, and starvation-free recovery.

## Production composition

`TameworkPersistenceComposition` owns the single production bootstrap, process
lease, startup graph, query facade, operation facade, diagnostics, and bounded
shutdown. `TameworkPersistenceAuthors` composes the released gameplay authors
over that one facade bundle.

The final cutover includes canonical profile snapshots, durable
owner-population/groups, command-family rosters, timed summon/storage,
provisioning, resolved capture/tame-link, filled-spawner capture/release,
direct-live and captured-item coop intake, saved death/destructive-removal
dormancy, free/paid restoration, and transactional profile-extension data.
Dormant state requires positive death, destructive removal, or
delete-on-remove-world evidence; unload, absence, and timeout cannot author it.

The public runtime imports released v2-v4 data, refuses the unreleased v5-v9
lineage unchanged, and never dual-writes. The superseded persistence runtime
and its alternate repositories, queues, recovery authorities, and adapters
have been deleted from production.

ADR 0008 records the completed recovery of owner-population/group,
command-roster, timed-summon, provisioning, resolved capture/tame-link,
paid-revival, and captured-item-to-coop behavior through this core's shared
authorities. July save formats gain no compatibility promise. Companion
inventory remains deferred and bonded vessels remain removed.

## Consequences

- Feature implementations have one place to express canonical mutations and one recovery graph.
- Projection failure cannot roll back or disguise committed canonical state.
- Unknown commit outcomes are resolved by exact evidence instead of blind replay.
- Complexity is reduced structurally and guarded against regrowth before gameplay cutover begins.
