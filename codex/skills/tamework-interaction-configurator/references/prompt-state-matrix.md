# Prompt/State Matrix Template

| Interaction | Prompt Condition | Action | Target State | Reset/Target-Loss Path | Evidence |
| --- | --- | --- | --- | --- | --- |
| <name> | <sensor/filter> | <action> | <state> | <timeout/state/cleanup> | <static/live/gap> |

Attach project profile, knowledge hash, snapshot, and candidate identity to the
matrix. A row is incomplete when the reset/target-loss path or evidence state
is missing.
