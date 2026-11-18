"""A player's own Tamework preference follows them between the servers of a network.

One bot (PrefsBot) on two servers that share Rune_Professions' Tamework settings store (RuneCore's database).
Rune_Husbandry is in the environment only so Tamework has content and its modules become active. The
preference under test is F on a companion (`/tw card`), one of the six player preferences Tamework now saves itself;
flute groups and views use the same document. The second server has never seen the player, so only the network's
`player/<uuid>` document can carry the choice there.

Cases:
  hop_carries_choice   choose "Cycle modes" on survival, move to dungeon: `/tw card` there reports it as the player's
                       own choice.
  hop_back_takes_network
                       choose "Inspect" on dungeon, move back to survival: survival reports Inspect, not the "Cycle
                       modes" its own copy of the player still had.

Run (opens one game window):
    py -3 -I <Rune_MCP>/tools/rigtest.py run rigtests/player_preferences.py --env rigtests/player_preferences.env.json --stall 300
"""
import time

ABOUT = "Tamework player preferences follow the player between network servers"
BOT = "PrefsBot"
HOME, AWAY = "survival", "dungeon"
CYCLE, INSPECT = "Cycle modes", "Inspect the companion"


def _server_ids():
    import json
    import os
    from pathlib import Path
    rig = Path(os.environ["RUNE_RIG"])
    sides = json.loads((rig / "rig.json").read_text(encoding="utf-8-sig"))["sides"]
    return {s["name"]: s["serverId"] for s in sides}


def _replies(t, cmd):
    """The chat lines the bot got for one command."""
    t.B.use(BOT)
    since = t.B._now_ms()
    r = t.say(cmd)
    lines = r.get("replies") if isinstance(r, dict) else None
    if not isinstance(lines, list):
        time.sleep(2)
        lines = t.B.messages(since=since) or []
    return [m.get("text") or "" for m in lines]


def _travel(t, dest):
    t.B.use(BOT)
    target = t.ctx["ids"][dest]

    def arrived():
        player = t.B.tk(f"/player?name={BOT}", side=dest)
        return isinstance(player, dict) and player.get("world")

    sent = {}
    for _ in range(2):
        t.B.ensure_ready()
        sent = t.say(f"/server {target}")
        if sent.get("ok") and t.wait(arrived, 90):
            # After a first hop the client keeps loading for a while and drops chat commands; wait until one
            # gets a reply (any reply; the checks ask again).
            # The tester preset ops the bot on the first server only.
            t.console(dest, f"op add {BOT}")

            def answers():
                t.B.ensure_ready()  # also closes a welcome window that opens a few seconds after a first join
                return _replies(t, "/tw card")

            answered = t.wait(answers, 90, every=3)
            return t.check(bool(answered), f"The bot arrived on {dest} and its commands reach the server")
    return t.check(False, f"The bot arrived on {dest}", sent)


def _choice_on(t, side, expected):
    lines = _replies(t, "/tw card")
    t.note(f"{side}: {lines}")
    own = [line for line in lines if "your choice" in line]
    return t.check(any(expected in line for line in own),
                   f"{side} reports '{expected}' as the player's own F choice", lines)


def setup(t):
    t.require(bots=[BOT], preset="tester")
    t.ctx = {"ids": _server_ids()}
    if t.B.side_of_bot() != HOME:
        _travel(t, HOME)


def hop_carries_choice(t):
    if t.B.side_of_bot() != HOME:
        _travel(t, HOME)
    lines = _replies(t, "/tw card cycle")
    if not t.check(any(CYCLE in line for line in lines), "survival saved Cycle modes", lines):
        return
    time.sleep(2)  # the save goes out on the settings thread
    if _travel(t, AWAY):
        _choice_on(t, AWAY, CYCLE)


def hop_back_takes_network(t):
    if t.B.side_of_bot() != AWAY:
        if not _travel(t, AWAY):
            return
    lines = _replies(t, "/tw card card")
    if not t.check(any(INSPECT in line for line in lines), "dungeon saved Inspect", lines):
        return
    time.sleep(2)
    if _travel(t, HOME):
        _choice_on(t, HOME, INSPECT)
    rows = t.sql("SELECT doc_key FROM tamework_network_settings WHERE doc_key LIKE 'player/%'")
    t.note(f"player documents in the store: {rows}")


CASES = [hop_carries_choice, hop_back_takes_network]
