"""Shared access (Tamework 5.11 work): an owner shares all their animals with a friend at a level, marks one private,
and revokes. Two bots: ShareOwner (owns two tamed cows from Rune_Husbandry) and ShareFriend.

Cases (each sets the grant, private flag and settings it needs at its start; none depends on another passing):
  stranger_baseline    no grant: the friend cannot feed or harvest cow A (the owner can: controls; the owner's sword
                       hurts cow A).
  grant_flow           no grant: `/tw share add ShareFriend caretaker` without confirm warns and grants nothing; with
                       confirm it grants, both players are told, `/tw share list` shows it on both sides.
  caretaker            feed works; harvest and the F mode cycle are refused; the friend's sword does no damage.
  handler              a level change from Caretaker needs no confirm; harvest and the mode cycle work; no damage.
  manager              the friend's sword damage equals the owner's (the owner's swing just before is the control).
  private_flag         the friend cannot feed the private cow P but can feed cow A; `off` restores it.
  revoke_and_leave     `/tw share remove` stops feeding at once; after a new grant, `/tw share leave ShareOwner` does too.
  server_settings      sharing off in tamework-settings.json + `/tw config reload` stops feeding and keeps the grant;
                       highestLevel CARETAKER caps a Handler (feed yes, harvest no); the file is restored at the end.
  restart_persistence  a server restart keeps the grant and the private flag.
  offline_owner        owner bot stopped, friend online: `tw debug needs advance 600` counts for the shared cow and
                       not for the private one (72 h grace); after the friend leaves the share, not for cow A either;
                       one short real-time look that cow A's hunger drops by itself. The owner bot comes back at the end.

How it judges: hunger from `tw debug get needs --entity UUID` (console); harvest from the Harvest_Ready alarm (the
console's `tw debug set harvestready` reply names the state before it set it again) and the milk items dropped near
the cow; damage from bot.py hit() (one swing, only with the crosshair on the cow; healthBefore / healthAfter);
command replies from RuneTestKit /messages. Feeding uses a real F with Tw_Feed_Herbivore held (the cow is set not
harvest-ready first, so F cannot pick the harvest); harvest a real F with a Container_Bucket. Needs Rune_Professions'
Husbandry level on both players (set with `/prof admin setlevel`, kept in RuneCore's database), because
Rune_Husbandry's feed and harvest require it. Needs a Tamework build with `tw debug needs advance`.

Run (opens two game windows):
    py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/shared_access.py --env rigtests/shared_access.env.json --stall 400
One case:            ... --case manager
Rerun what failed:   py -3 -I <Rune_MCP>/tools/rigtest.py run <abs path>/rigtests/shared_access.py --in <env id> --failed
"""
import copy, json, os, re, subprocess, sys, time

ABOUT = "Tamework shared access: levels, private flag, revoke, server settings, persistence and offline-owner needs"
OWNER, FRIEND = "ShareOwner", "ShareFriend"
HUB = "survival"
ROLE = "Tamed_Cow"
FEED, BUCKET, SWORD = "Tw_Feed_Herbivore", "Container_Bucket", "Weapon_Sword_Crude"
Y = 81                    # the flat world's ground
BASES = (0, 40, 80)       # x of cow A; cow P stands 16 further. A later base when an earlier one holds only hurt cows
ADVANCE = 600             # RH_Needs_Livestock: HungerPerMinute 1.0, REAL_TIME, so about 10 hunger in 600 s
REAL_WAIT = 75            # the real-time look: at most this long (a needs sweep runs every 2 to 30 s)
HUNGER_RE = re.compile(r"hunger=(-?\d+(?:\.\d+)?)")
HARVEST_RE = re.compile(r"harvest readiness for NPC \S+: (true|false) -> (true|false)")
ADVANCE_RE = re.compile(r"Advanced needs for NPC (\S+): requestedSeconds=(-?\d+(?:\.\d+)?) "
                        r"countedSeconds=(-?\d+(?:\.\d+)?) hunger=(-?\d+(?:\.\d+)?)->(-?\d+(?:\.\d+)?)")
HARVEST_HINT = re.compile(r"harvest|milk", re.I)
PRIVATE_RE = re.compile(r"Animals you marked private: (\d+)")


class Ctx:
    def __init__(self, t):
        self.t, self.B = t, t.B

    # ---- players ----
    def use(self, bot):
        self.B.use(bot)

    def say(self, bot, cmd, wait=1.5):
        self.use(bot)
        r = self.t.say(cmd)
        reps = r.get("replies") if isinstance(r, dict) else None
        time.sleep(0 if reps is not None else wait)
        return [m.get("text") or "" for m in reps] if isinstance(reps, list) else []

    def msgs(self, bot, since):
        self.use(bot)
        return [m.get("text") or "" for m in (self.B.messages(since=since) or [])]

    def now(self):
        return self.B._now_ms()

    def give(self, bot, item, qty):
        self.t.console(HUB, f"give {bot} {item} --quantity={qty}")

    # ---- the cows ----
    def cows_near(self, x, r=30, bot=OWNER):
        self.use(bot)
        self.B.tp(x, Y, 0)
        return self.B.entities(r, ROLE)

    def npc(self, nid=None, uuid=None, bot=FRIEND):
        self.use(bot)
        q = f"/npc?uuid={uuid}" if uuid else f"/npc?networkId={nid}&player={bot}"
        r = self.B.tk(q)
        return r if isinstance(r, dict) else {}

    def state(self, nid, bot=FRIEND):
        return self.npc(nid, bot=bot).get("state")

    def hunger(self, uuid):
        out = " ".join(self.t.console(HUB, f"tw debug get needs --entity {uuid}"))
        m = HUNGER_RE.search(out)
        return float(m.group(1)) if m else None

    def set_hunger(self, uuid, value):
        self.t.console(HUB, f"tw debug set hunger {value} --entity {uuid}")

    def set_ready(self, uuid, ready=True):
        """Set Harvest_Ready on or off; returns (before, after) from the reply, or None when it did not say."""
        mode = "true" if ready else "false"
        out = " ".join(self.t.console(HUB, f"tw debug set harvestready --mode={mode} --entity {uuid}"))
        m = HARVEST_RE.search(out)
        return None if not m else (m.group(1) == "true", m.group(2) == "true")

    def advance(self, uuid, seconds=ADVANCE):
        """`tw debug needs advance` on one cow: {requested, counted, before, after, raw}; None fields when the reply
        has no line for that cow."""
        lines = self.t.console(HUB, f"tw debug needs advance {seconds} --entity {uuid}")
        for line in lines:
            m = ADVANCE_RE.search(line)
            if m and m.group(1) == uuid:
                return {"requested": float(m.group(2)), "counted": float(m.group(3)),
                        "before": float(m.group(4)), "after": float(m.group(5)), "raw": line}
        return {"requested": None, "counted": None, "before": None, "after": None, "raw": lines[-3:]}

    def items_near(self, nid, bot=FRIEND):
        self.use(bot)
        e = self.B.find(nid, 64)
        if not e:
            return 0
        rows = self.B.tk(f"/entities?world=default&x={e['x']}&y={e['y']}&z={e['z']}&r=6") or []
        return sum(1 for r in rows if r.get("kind") == "item")

    # ---- actions with real input ----
    def press_f(self, bot, nid, item):
        """F on the cow with `item` held (select confirms it on the server). Returns (select result, interact result)."""
        self.use(bot)
        sel = self.B.select(item)
        r = self.B.interact(nid)
        time.sleep(1.0)
        return sel, r

    def feed(self, bot, nid, uuid, start=50.0):
        """Hunger before and after one F with feed held, from `start`. The cow is set not harvest-ready first: a
        ready cow can take F as the harvest (seen in a failed run)."""
        cleared = self.set_ready(uuid, False)
        self.set_hunger(uuid, start)
        before = self.hunger(uuid)
        sel, r = self.press_f(bot, nid, FEED)
        after = self.hunger(uuid)
        gain = None if before is None or after is None else round(after - before, 2)
        hint = r.get("hint") or ""
        return {"held": bool(sel.get("ok")), "selectTries": sel.get("tries"), "before": before, "after": after,
                "gain": gain, "pressed": bool(r.get("pressedOnTarget")), "landed": r.get("landed"), "hint": hint,
                "notReady": cleared is not None and cleared[1] is False,
                "harvestHint": bool(HARVEST_HINT.search(hint))}

    def harvest(self, bot, nid, uuid):
        """One F with a bucket on a harvest-ready cow. harvested = the Harvest_Ready alarm was used."""
        self.set_ready(uuid, True)
        items0 = self.items_near(nid)
        sel, r = self.press_f(bot, nid, BUCKET)
        time.sleep(0.5)
        items1 = self.items_near(nid)
        again = self.set_ready(uuid, True)      # reads the state before setting it again
        return {"held": bool(sel.get("ok")), "harvested": again is not None and again[0] is False,
                "itemsDropped": items1 - items0, "pressed": bool(r.get("pressedOnTarget")), "hint": r.get("hint")}

    def cycle(self, bot, nid):
        """One F with the sword held (players chose /tw card cycle): state before and after."""
        before = self.state(nid)
        sel, r = self.press_f(bot, nid, SWORD)
        time.sleep(0.5)
        return {"held": bool(sel.get("ok")), "before": before, "after": self.state(nid),
                "pressed": bool(r.get("pressedOnTarget")), "hint": r.get("hint")}

    def swing(self, bot, nid):
        """One deliberate swing with the sword (bot.py hit: only with the crosshair on the cow)."""
        self.use(bot)
        e = self.B.find(nid, 64)
        if e:
            self.B.tp_near(e, gap=2.0)
        r = self.B.hit(nid, slot=SWORD)
        hb, ha = r.get("healthBefore"), r.get("healthAfter")
        return {"ok": bool(r.get("ok")), "landed": r.get("landed"), "before": hb, "after": ha,
                "damage": None if hb is None or ha is None else round(hb - ha, 2), "error": r.get("error")}

    def look_at_cow(self, bot, nid):
        self.use(bot)
        e = self.B.find(nid, 64)
        if not e:
            return False
        self.B.tp_near(e, 2.5)
        return bool(self.B.look_at(nid).get("onTarget"))

    # ---- share state ----
    def lists(self):
        return self.say(OWNER, "/tw share list"), self.say(FRIEND, "/tw share list")

    def grant(self, level):
        """Make the friend's grant `level` (Caretaker / Handler / Manager): a change needs no confirm, a new grant
        does. True when the owner's list shows it."""
        r = self.say(OWNER, f"/tw share add {FRIEND} {level.lower()}")
        if any("confirm to share" in x for x in r):
            self.say(OWNER, f"/tw share add {FRIEND} {level.lower()} confirm")
        lo = self.say(OWNER, "/tw share list")
        return any(f"- {FRIEND}: {level}" in x for x in lo)

    def ungrant(self):
        """No grant to the friend. True when the owner's list says so."""
        self.say(OWNER, f"/tw share remove {FRIEND}")
        lo = self.say(OWNER, "/tw share list")
        return any("do not share your animals with any player" in x for x in lo)

    def set_private(self, nid, on):
        """The owner marks cow `nid` private (on) or shared again (off). Returns (ok, crosshair on it, replies); ok =
        the owner's list shows a private animal (on) or none (off)."""
        aimed = self.look_at_cow(OWNER, nid)
        r = self.say(OWNER, f"/tw share private {'on' if on else 'off'}")
        lo = self.say(OWNER, "/tw share list")
        marked = max([int(m.group(1)) for m in map(PRIVATE_RE.search, lo) if m] or [0])
        return (marked >= 1 if on else marked == 0), aimed, r + lo


# ---- judging helpers ----
def judge_feed(t, f, fed, what):
    """fed=True: the feed must raise hunger by 20 or more; fed=False: it must not raise it. Unjudged when the feed
    was not held, F was never pressed on the cow, or F looks like it chose the harvest."""
    if not f["held"]:
        return t.unjudged(what, f"the feed was not held: {f}")
    gain = f["gain"]
    if fed and gain is not None and gain >= 20:
        return t.check(True, what, f)
    if not f["pressed"]:
        return t.unjudged(what, f"F was never pressed with the crosshair on the cow: {f}")
    if f["harvestHint"] or (fed and not f["notReady"]):
        return t.unjudged(what, f"F may have chosen the harvest, not the feed (hint {f['hint']!r}, cow cleared of "
                                f"harvest-ready {f['notReady']}): {f}")
    if fed:
        return t.check(False, what, f)
    return t.check(gain is not None and gain < 1, what, f)


def judge_harvest(t, h, harvested, what):
    if not h["held"]:
        return t.unjudged(what, f"the bucket was not held: {h}")
    if h["harvested"] == harvested:
        return t.check(True, what, h)
    if not h["pressed"]:
        return t.unjudged(what, f"F was never pressed with the crosshair on the cow: {h}")
    return t.check(False, what, h)


def judge_no_damage(t, s, what):
    """The swing was on target and the cow's health did not drop. A swing that never was on target judges nothing."""
    if not s["ok"]:
        return t.unjudged(what, f"no swing on target (landed {s['landed']}): {s}")
    if s["before"] is None or s["after"] is None:
        return t.unjudged(what, f"no health reading around the swing: {s}")
    return t.check(s["after"] >= s["before"], what, s)


def judge_damage(t, s, what):
    """Control: the swing was on target and the server shows the hit (landed true)."""
    if not s["ok"]:
        return t.unjudged(what, f"no swing on target (landed {s['landed']}): {s}")
    return t.check(s["landed"] is True, what, s)


# ---- environment ----
def settings_file():
    return os.path.join(os.environ["RUNE_RIG"], HUB, "universe", "Tamework", "Settings", "tamework-settings.json")


def read_sharing():
    with open(settings_file(), encoding="utf-8") as fh:
        return copy.deepcopy((json.load(fh).get("ownership") or {}).get("sharing"))


def write_sharing(t, sharing):
    """Write ownership.sharing (None removes it) and reload Tamework's config."""
    path = settings_file()
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    own = doc.setdefault("ownership", {})
    if sharing is None:
        own.pop("sharing", None)
    else:
        own["sharing"] = sharing
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=2)
    out = t.console(HUB, "tw config reload --world default")
    time.sleep(2)
    return out


def rig_tool(t, *args, timeout=600):
    here = os.path.dirname(os.path.abspath(t.B.__file__))
    t.progress("rig.py " + " ".join(args))
    t.busy_until = time.time() + timeout + 5
    try:
        r = subprocess.run([sys.executable, "-I", os.path.join(here, "rig.py"), *args], capture_output=True, text=True,
                           cwd=os.path.dirname(here), timeout=timeout)
    finally:
        t.busy_until = 0.0
        t.progress()
    return r.returncode, (r.stdout + r.stderr)[-1500:]


def owner_up(t):
    """Bring the owner bot back (after offline_owner stopped it). True when it is in game with the tester preset."""
    B = t.B
    if OWNER in {b.get("name") for b in B.bots() if b.get("pid")}:
        B.use(OWNER)
        if (B.me() or {}).get("uuid"):
            return True
    t.progress("owner bot back")
    t.busy_until = time.time() + 600
    try:
        res = B.up_many([OWNER], multi=True, preset="tester")
    finally:
        t.busy_until = 0.0
        t.progress()
    if not res.get("ok"):
        t.note(f"owner bot did not come back: {json.dumps(res)[:600]}")
    return bool(res.get("ok"))


def owner_down(t):
    B = t.B
    t.progress("owner bot stopped")
    t.busy_until = time.time() + 60
    try:
        res = B.stop_bot(OWNER)
    finally:
        t.busy_until = 0.0
        t.progress()
    B.use(FRIEND)
    time.sleep(3)           # the server records the owner as offline
    return res


def healthy(c, e):
    n = c.npc(e["networkId"], bot=OWNER)
    if n.get("alive") is False or e.get("alive") is False:
        return False
    hp, mx = n.get("health", e.get("health")), n.get("maxHealth")
    return hp is None or not mx or hp >= 0.5 * mx


def cows(t):
    """At the start of a case: both bots beside the cows, and t.ctx's network ids re-read from the uuids (a restart
    gives the cows new network ids). Returns (c, a, ua, p, up), or None after a FAIL row when a cow is missing."""
    c = Ctx(t)
    base = t.ctx["base"]
    for bot, z in ((FRIEND, 4), (OWNER, -4)):
        c.use(bot)
        t.B.tp(base + 8, Y, z)
    found = {key: t.wait(lambda k=key: c.npc(uuid=t.ctx[k]).get("networkId"), 30) for key in ("ua", "up")}
    if found["ua"] is None or found["up"] is None:
        t.check(False, "both cows are there at the start of the case", f"{found} for {t.ctx}")
        return None
    t.ctx["a"], t.ctx["p"] = found["ua"], found["up"]
    return c, t.ctx["a"], t.ctx["ua"], t.ctx["p"], t.ctx["up"]


# ---- setup and cases ----
def setup(t):
    B = t.B
    c = Ctx(t)
    t.require(bots=[OWNER, FRIEND], preset="tester")
    for bot in (OWNER, FRIEND):
        reply = c.say(bot, "/prof admin setlevel husbandry 40")
        t.note(f"{bot}: {reply}")
        c.give(bot, FEED, 30)
        c.give(bot, BUCKET, 3)
        c.give(bot, SWORD, 1)
        c.say(bot, "/tw card cycle")
    t.untested("rename and cull as Manager", "Rune_Husbandry's rename needs a name item and cull a card or kill path "
               "that this test does not set up")
    t.untested("mount, shoulder ride and flight as Handler", "no mountable role in Rune_Husbandry")
    t.untested("the animal in Defend or Aggressive does not attack the friend", "cows have no combat stance")

    # Two owned cows, 16 blocks apart: /tw share private picks the animal in the owner's view cone, and with two cows
    # side by side it can pick the other one (seen once, 2026-11-11). A rerun in the same environment reuses them.
    for base in BASES:
        near = c.cows_near(base + 8)
        if near:
            fit = [e for e in near if healthy(c, e)]
            if len(fit) < 2:
                t.note(f"base x={base}: {len(near)} cows, {len(fit)} healthy: trying the next base")
                continue
            ea = min(fit, key=lambda e: (e["x"] - base) ** 2 + e["z"] ** 2)
            ep = min((e for e in fit if e is not ea), key=lambda e: (e["x"] - base - 16) ** 2 + e["z"] ** 2)
            a, p, how = ea["networkId"], ep["networkId"], "reused"
        else:
            new = []
            for x in (base, base + 16):
                B.use(OWNER)
                B.tp(x, Y, 0)
                old = {e["networkId"] for e in B.entities(48, ROLE)}
                for _ in range(3):
                    c.say(OWNER, f"/tw npc spawn tamed {ROLE}", wait=3)
                    got = sorted({e["networkId"] for e in B.entities(48, ROLE)} - old)
                    if got:
                        new.append(got[0])
                        break
            if len(new) < 2:
                raise SystemExit(f"the owner could not spawn two tamed cows at x={base} (got {new})")
            (a, p), how = new, "spawned"
        break
    else:
        raise SystemExit(f"no base among x={BASES} with two healthy cows or room for new ones")
    ua, up = c.npc(a, bot=OWNER).get("uuid"), c.npc(p, bot=OWNER).get("uuid")
    if not ua or not up:
        raise SystemExit(f"no uuid for the cows #{a} / #{p}")
    t.ctx.update(base=base, a=a, p=p, ua=ua, up=up)
    t.note(f"cows {how} at x={base}: A #{a} {ua}, P #{p} {up}")
    if how == "reused":     # a reused pair may come back the other way round: neither cow starts private
        c.set_private(a, False)
        ok, aimed, r = c.set_private(p, False)
        t.check(ok, "setup: neither reused cow is private", r)


def stranger_baseline(t):
    got = cows(t)
    if not got:
        return
    c, a, ua, p, up = got
    if not t.check(c.ungrant(), "setup: the friend has no grant"):
        return
    judge_feed(t, c.feed(OWNER, a, ua), True, "control: the owner feeds cow A")
    judge_feed(t, c.feed(FRIEND, a, ua), False, "a stranger cannot feed cow A")
    judge_harvest(t, c.harvest(FRIEND, a, ua), False, "a stranger cannot harvest cow A")
    judge_harvest(t, c.harvest(OWNER, a, ua), True, "control: the owner harvests cow A")
    judge_damage(t, c.swing(OWNER, a), "control: the owner's sword hurts cow A")


def grant_flow(t):
    got = cows(t)
    if not got:
        return
    c = got[0]
    if not t.check(c.ungrant(), "setup: the friend has no grant"):
        return
    t1 = c.now()
    r = c.say(OWNER, f"/tw share add {FRIEND} caretaker")
    lo = c.say(OWNER, "/tw share list")
    t.check(any("confirm to share" in x for x in r) and any("do not share your animals with any player" in x for x in lo),
            "add without confirm warns and grants nothing", r + lo)
    r = c.say(OWNER, f"/tw share add {FRIEND} caretaker confirm")
    time.sleep(1)
    fm = c.msgs(FRIEND, t1)
    t.check(any(f"You now share your animals with {FRIEND}. Level: Caretaker" in x for x in r)
            and any(f"{OWNER} shared their animals with you: Caretaker" in x for x in fm),
            "add with confirm grants; the owner gets the reply and the friend the notice", r + fm)
    lo, lf = c.lists()
    t.check(any(f"- {FRIEND}: Caretaker" in x for x in lo) and any(f"- {OWNER}: Caretaker" in x for x in lf),
            "/tw share list shows the grant on both sides", lo + lf)


def caretaker(t):
    got = cows(t)
    if not got:
        return
    c, a, ua, p, up = got
    if not t.check(c.grant("Caretaker"), "setup: the friend is a Caretaker"):
        return
    judge_feed(t, c.feed(FRIEND, a, ua), True, "caretaker: feeding works")
    judge_harvest(t, c.harvest(FRIEND, a, ua), False, "caretaker: harvest is refused")
    m = c.cycle(FRIEND, a)
    if not (m["held"] and m["pressed"]):
        t.unjudged("caretaker: the F mode cycle is refused", f"sword not held or F not pressed on the cow: {m}")
    else:
        t.check(m["before"] == m["after"], "caretaker: the F mode cycle is refused", m)
    judge_no_damage(t, c.swing(FRIEND, a), "caretaker: the friend's sword does no damage")


def handler(t):
    got = cows(t)
    if not got:
        return
    c, a, ua, p, up = got
    if not t.check(c.grant("Caretaker"), "setup: the friend is a Caretaker"):
        return
    t1 = c.now()
    r = c.say(OWNER, f"/tw share add {FRIEND} handler")
    time.sleep(0.5)
    fm = c.msgs(FRIEND, t1)
    t.check(any("now has this level on your animals: Handler" in x for x in r)
            and any("changed your level on their animals: Handler" in x for x in fm),
            "a level change needs no confirm and tells the friend", r + fm)
    judge_harvest(t, c.harvest(FRIEND, a, ua), True, "handler: harvest works")
    m = c.cycle(FRIEND, a)
    if not (m["held"] and m["pressed"]):
        t.unjudged("handler: the F mode cycle works", f"sword not held or F not pressed on the cow: {m}")
    else:
        t.check(m["before"] != m["after"], "handler: the F mode cycle works", m)
    judge_no_damage(t, c.swing(FRIEND, a), "handler: the friend's sword does no damage")


def manager(t):
    got = cows(t)
    if not got:
        return
    c, a, ua, p, up = got
    if not t.check(c.grant("Manager"), "setup: the friend is a Manager"):
        return
    # The owner's control swing is taken now, in the same state: a moving cow can make a swing miss.
    what = "manager: the friend's sword hurts the cow as the owner's does"
    ctl = c.swing(OWNER, a)
    s = c.swing(FRIEND, a)
    note = f"friend {s}; owner just before {ctl}; cow state {c.state(a)}"
    if not (ctl["ok"] and ctl["landed"] is True and (ctl["damage"] or 0) > 0):
        t.unjudged(what, "control failed: the owner's own swing did not hurt the cow in this state; " + note)
    elif not s["ok"]:
        t.unjudged(what, "the friend's swing was never on target; " + note)
    else:
        t.check(s["damage"] is not None and abs(s["damage"] - ctl["damage"]) <= max(2.0, 0.25 * ctl["damage"]),
                what, note)


def private_flag(t):
    got = cows(t)
    if not got:
        return
    c, a, ua, p, up = got
    if not t.check(c.grant("Manager"), "setup: the friend is a Manager"):
        return
    try:
        ok, aimed, r = c.set_private(p, False)      # an earlier case may have left cow P private
        if not t.check(ok, "setup: no animal is private", r):
            return
        ok, aimed, r = c.set_private(p, True)
        if not t.check(ok and aimed and any("now private" in x for x in r), "the owner marks cow P private", r):
            return
        judge_feed(t, c.feed(FRIEND, p, up), False, "private: the manager cannot feed cow P")
        judge_feed(t, c.feed(FRIEND, a, ua), True, "private: the manager still feeds cow A")
        ok, aimed, r = c.set_private(p, False)
        t.note(f"private off: crosshair on cow P {aimed}, list shows none private {ok}, replies {r}")
        if not t.check(ok and any("shared again" in x for x in r), "private off: cow P is shared again", r):
            return
        judge_feed(t, c.feed(FRIEND, p, up), True, "private off: the manager feeds cow P again")
    finally:
        try:
            ok, aimed, r = c.set_private(p, False)
            if not ok:
                t.note(f"cow P may still be private after the case: {r}")
        except Exception as ex:
            t.note(f"could not clear cow P's private flag: {ex}")


def revoke_and_leave(t):
    got = cows(t)
    if not got:
        return
    c, a, ua, p, up = got
    if not t.check(c.grant("Caretaker"), "setup: the friend is a Caretaker"):
        return
    r = c.say(OWNER, f"/tw share remove {FRIEND}")
    f = c.feed(FRIEND, a, ua)
    lf = c.say(FRIEND, "/tw share list")
    t.check(any(f"no longer share your animals with {FRIEND}" in x for x in r)
            and any("No one shares their animals with you" in x for x in lf),
            "/tw share remove: the owner is told and the friend's list is empty", f"{r} {lf}")
    judge_feed(t, f, False, "/tw share remove stops feeding at once")
    if not t.check(c.grant("Caretaker"), "setup: a new Caretaker grant"):
        return
    judge_feed(t, c.feed(FRIEND, a, ua), True, "before leaving, the friend feeds cow A")
    r = c.say(FRIEND, f"/tw share leave {OWNER}")
    f = c.feed(FRIEND, a, ua)
    lo = c.say(OWNER, "/tw share list")
    t.check(any(f"You left the share of {OWNER}" in x for x in r)
            and any("do not share your animals with any player" in x for x in lo),
            f"/tw share leave {OWNER}: the friend is told and the owner's list is empty", f"{r} {lo}")
    judge_feed(t, f, False, f"/tw share leave {OWNER} gives up feeding at once")


def server_settings(t):
    got = cows(t)
    if not got:
        return
    c, a, ua, p, up = got
    if not t.check(c.grant("Handler"), "setup: the friend is a Handler"):
        return
    original = read_sharing()
    t.note(f"settings: sharing was {original}")
    try:
        out = write_sharing(t, {"enabled": False, "highestLevel": "MANAGER"})
        f = c.feed(FRIEND, a, ua)
        lo = c.say(OWNER, "/tw share list")
        t.check(any(f"- {FRIEND}: Handler" in x for x in lo) and any("turned off on this server" in x for x in lo),
                "settings: sharing off keeps the grant and the list says it is off", f"reload {out}; {lo}")
        judge_feed(t, f, False, "settings: sharing off removes feeding")
        out = write_sharing(t, {"enabled": True, "highestLevel": "CARETAKER"})
        t.note(f"reload {out}")
        judge_feed(t, c.feed(FRIEND, a, ua), True, "settings: highestLevel Caretaker still lets a Handler feed")
        judge_harvest(t, c.harvest(FRIEND, a, ua), False, "settings: highestLevel Caretaker caps a Handler's harvest")
        write_sharing(t, {"enabled": True, "highestLevel": "MANAGER"})
        judge_harvest(t, c.harvest(FRIEND, a, ua), True, "settings: back to Manager, the Handler harvests again")
    finally:
        out = write_sharing(t, original)
        t.note(f"settings restored to {original}: {out}")


def restart_persistence(t):
    B = t.B
    got = cows(t)
    if not got:
        return
    c, a, ua, p, up = got
    if not t.check(c.grant("Caretaker"), "setup: the friend is a Caretaker"):
        return
    ok, aimed, r = c.set_private(p, True)
    if not t.check(ok, "setup: cow P is private", r):
        return
    code, out = rig_tool(t, "restart")
    t.note(f"rig.py restart exit {code}: {out[-300:]}")
    for bot in (OWNER, FRIEND):
        t.wait(lambda b=bot: (c.use(b), (B.me() or {}).get("uuid"))[1], 240, what=f"{bot} back on the server")
        c.use(bot)
        B.ensure_ready()
    lo, lf = c.lists()
    t.check(any(f"- {FRIEND}: Caretaker" in x for x in lo) and any("Animals you marked private: 1" in x for x in lo)
            and any(f"- {OWNER}: Caretaker" in x for x in lf), "after a restart the grant and the private flag remain",
            lo + lf)
    got = cows(t)          # new network ids after the restart, found by uuid
    if not got:
        return
    c, a, ua, p, up = got
    judge_feed(t, c.feed(FRIEND, p, up), False, "after a restart cow P is still private (no feeding)")
    judge_feed(t, c.feed(FRIEND, a, ua), True, "after a restart the friend still feeds cow A")


def offline_owner(t):
    B = t.B
    got = cows(t)
    if not got:
        return
    c, a, ua, p, up = got
    if not t.check(c.grant("Caretaker"), "setup: the friend is a Caretaker"):
        return
    ok, aimed, r = c.set_private(p, True)
    if not t.check(ok, "setup: cow P is private", r):
        return
    for u in (ua, up):
        c.set_hunger(u, 80)
    try:
        owner_down(t)
        c.use(FRIEND)
        e = B.find(a, 64)
        if e:
            B.tp_near(e, gap=4.0)
        # fast-forward: the presence gate decides how much of the 600 s counts
        ra, rp = c.advance(ua), c.advance(up)
        t.note(f"owner offline, friend online, advance {ADVANCE} s: shared cow A {ra}; private cow P {rp}")
        what = "owner offline, friend online: the shared cow's needs count (advance)"
        if ra["counted"] is None:
            t.unjudged(what, f"no advance reply for cow A (needs a build with tw debug needs advance): {ra['raw']}")
        else:
            t.check(ra["counted"] >= ADVANCE - 1 and ra["before"] - ra["after"] >= 5, what,
                    f"counted {ra['counted']} s, hunger {ra['before']} -> {ra['after']}")
        what = "owner offline, friend online: the private cow's needs do not count (grace)"
        if rp["counted"] is None:
            t.unjudged(what, f"no advance reply for cow P: {rp['raw']}")
        else:
            t.check(rp["counted"] < 1 and abs(rp["before"] - rp["after"]) < 0.05, what,
                    f"counted {rp['counted']} s, hunger {rp['before']} -> {rp['after']}")

        # one real-time look: the shared cow's hunger drops by itself (a sweep runs every 2 to 30 s)
        h0, t0 = c.hunger(ua), time.time()
        h1 = t.wait(lambda: (lambda h: h if h is not None and h0 is not None and h0 - h >= 0.15 else None)(
            c.hunger(ua)), REAL_WAIT, every=5)
        waited = round(time.time() - t0)
        what = "owner offline, friend online: the shared cow's hunger drops in real time"
        if h0 is None:
            t.unjudged(what, "no hunger reading for cow A")
        else:
            t.check(h1 is not None, what, f"{h0} -> {h1 if h1 is not None else c.hunger(ua)} in {waited} s")

        # control: no grant at all, owner offline. The friend leaves (the owner is offline and cannot remove it).
        r = c.say(FRIEND, f"/tw share leave {OWNER}")
        lf = c.say(FRIEND, "/tw share list")
        left = any(f"You left the share of {OWNER}" in x for x in r) \
            and any("No one shares their animals with you" in x for x in lf)
        what = "control: with no grant and the owner offline, needs do not count"
        if not left:
            t.note(f"the friend could not leave while the owner is offline ({r} {lf}): the owner removes it")
            if not owner_up(t):
                t.unjudged(what, "the owner bot did not come back to remove the grant")
                return
            left = c.ungrant()
            owner_down(t)
        rc = c.advance(ua)
        if not left:
            t.unjudged(what, f"the grant could not be removed: {r} {lf}")
        elif rc["counted"] is None:
            t.unjudged(what, f"no advance reply for cow A: {rc['raw']}")
        else:
            t.check(rc["counted"] < 1 and abs(rc["before"] - rc["after"]) < 0.05, what,
                    f"counted {rc['counted']} s, hunger {rc['before']} -> {rc['after']}")
    finally:
        if not owner_up(t):
            t.check(False, "the owner bot is back after the case", "bot.py up_many did not bring it in game")
        c.use(FRIEND)


CASES = [stranger_baseline, grant_flow, caretaker, handler, manager, private_flag, revoke_and_leave, server_settings,
         restart_persistence, offline_owner]
