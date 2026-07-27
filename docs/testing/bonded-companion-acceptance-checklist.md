# Bonded Companion Fresh-World Acceptance Checklist

Status: manual acceptance pending

Use this checklist only after both clean builds, manifest/dependency checks,
packaged-asset checks, local installation, and installed-jar hash verification
pass. Test in a fresh world; migration from unreleased tester persistence is
not part of this feature.

Run one numbered pass at a time. The implementing agent must stop before
waiting for the tester and state the one requested action, expected visible
result, and failure-export command. Do not leave an implementation goal or
background monitor running while waiting for manual feedback.

## Preconditions

- Fresh test world with the matched Tamework and HyDragon test jars.
- Dragon Horn and enough Draconic Stones/revival ingredients for the selected
  policies.
- At least two eligible full dragons, including one tranquilized target.
- A way to acquire/soul-bond one Miniwyvern.
- One finite-session policy and one `SessionDurationSeconds: 0` policy
  available for the timer pass.
- Hytale logs remain available if a pass fails.

For passes 1–9, run `/tw debugdb export` only if the pass fails, then provide
the reported archive path and relevant server-log excerpt. Pass 10 always runs
all three diagnostic commands as its actual acceptance action.

## 1. Capture a full dragon

- [ ] Capture a tranquilized full dragon with Draconic Stone. Verify one
  completion effect, source removal, consumed stone, and a fully detailed
  stored Horn card.

Expected visible result:

- the channel plays once and completion feedback does not restart or double;
- exactly one Stone is consumed according to the resolved attempt;
- the source NPC is gone;
- one Horn card appears as `STORED`; and
- name, species, gender, health, details, and valid action buttons are visible
  immediately without relogging.

Failure collection: `/tw debugdb export`.

## 2. Summon from the Horn

- [ ] Summon from the Horn. Verify full card details and correct buttons
  immediately, one active projection, correct commands, and no generic
  persistence-evidence error.

Expected visible result:

- exactly one matching dragon appears at a safe player-relative placement;
- the card becomes `ACTIVE` and shows Dismiss plus complete details immediately;
- Follow/Hold/other configured Horn commands affect that exact dragon; and
- no "persistence evidence isn't ready" or "NPC is not linked" message appears.

Failure collection: `/tw debugdb export`.

## 3. Dismiss/store

- [ ] Dismiss/store. Verify the complete snapshot persists, card becomes
  stored, and no live projection remains.

Expected visible result:

- the dragon disappears once;
- the card becomes `STORED` without becoming Unloaded or Lost;
- name, role-derived species, health, and all other available fields remain;
  and
- Summon reflects the configured cooldown/capacity state.

Failure collection: `/tw debugdb export`.

## 4. Capacity and cooldown

- [ ] Summon a different stored dragon when capacity permits. Verify active
  capacity and cooldown behavior match policy.

Expected visible result:

- family `MaximumActive` is enforced without consuming another family's slot;
- the recently stored profile cannot bypass its family cooldown;
- an eligible different profile summons only when the family has capacity; and
- denied clicks do not create a projection, duplicate lease, or generic error.

Failure collection: `/tw debugdb export`.

## 5. Death and paid revival

- [ ] Kill an active bonded dragon. Verify `DEAD`, revive pricing, no automatic
  summon after revive, then successful manual summon.

Expected visible result:

- confirmed death changes the same card to `DEAD`;
- the confirmation shows every exact cost line and owned/required quantity;
- insufficient payment charges nothing;
- successful payment consumes the complete recipe once and changes the card to
  `STORED`;
- no NPC appears until Summon is clicked; and
- manual summon creates one projection of the revived profile.

Failure collection: `/tw debugdb export`.

## 6. Finite and unlimited sessions

- [ ] Test a finite-session policy and a zero-duration policy. Verify
  expiration stores the projection and zero duration never expires.

Expected visible result:

- the finite lease expires once, retires its NPC, stores its complete snapshot,
  and begins the configured cooldown;
- the zero-duration lease remains active past the finite test window; and
- signed world-time values do not make either timer immediately invalid.

Failure collection: `/tw debugdb export`.

## 7. Relog and world transfer

- [ ] Leave/rejoin and transfer worlds with an active projection. Verify it
  becomes stored and never lost/unloaded.

Expected visible result:

- logout/rejoin stores the active profile and removes/cleans the projection;
- leaving one world and entering another stores it instead of copying it; and
- the Horn shows `STORED` with full details, never Lost or Unloaded.

Failure collection: `/tw debugdb export`.

## 8. Soul-bond a Miniwyvern

- [ ] Soul-bond a Miniwyvern. Verify it appears in the same Horn panel, retains
  archetype/ability data across summon/store/relog, and uses the same
  death/revive rule.

Expected visible result:

- one `Tamed_Wyvern_Mini` profile appears in the existing Dragon Horn;
- a second lifetime claim is denied without consuming another acquisition
  source;
- its family-specific timer/cooldown/capacity and revive recipe apply;
- archetype, attunement, ability, and progression extension fields survive
  summon, store, and relog; and
- death produces `DEAD`, revival produces `STORED`, and summon is manual.

Failure collection: `/tw debugdb export`.

## 9. Active full-dragon eligibility

- [ ] Trigger the active-full-dragon encounter/flight eligibility check.
  Verify it succeeds only for a confirmed active full dragon and ignores
  stored/dead profiles.

Expected visible result:

- a profile in family `hydragon:full_dragons` with state `ACTIVE` and a valid
  lease qualifies;
- stored and dead full dragons do not qualify;
- an active Miniwyvern does not qualify; and
- stale NPCs or old generic population evidence do not qualify.

Failure collection: `/tw debugdb export`.

## 10. Diagnostics and redaction

- [ ] Run `/tw debugdb status`, `/tw debugdb detail`, and
  `/tw debugdb export`. Verify aggregate bonded status and a redacted bonded
  bundle entry.

Expected visible result:

- status/detail report bonded readiness and aggregate profile/lease/cleanup
  counts without affecting generic persistence mode;
- export completes and reports the archive path;
- the archive contains `bonded-companions.json`; and
- that member contains only readiness, schema version, stored/active/dead
  counts, active lease count, pending bounded-cleanup count, and a fixed failure
  category—not owner IDs, profile IDs, NPC UUIDs, snapshots, or extension data.

If this step fails, preserve the produced archive (if any) and the relevant
server-log excerpt.

## Completion record

Record the following in the implementation handoff after all ten passes:

- Tamework jar file name and SHA-256;
- HyDragon jar file name and SHA-256;
- installed jar paths and matching SHA-256 values;
- fresh world name;
- date/time and Hytale build;
- each pass result;
- diagnostic archive path for any failed/retried pass; and
- final acceptance decision.

Do not begin release preparation from an incomplete or partially retried
checklist. Fix a failure in its owning subsystem, rerun focused and full tests,
rebuild/reinstall only when requested, then repeat that bounded pass.
