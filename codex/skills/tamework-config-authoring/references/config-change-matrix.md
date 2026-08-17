# Config Change Matrix

Use this matrix as an impact scan. Mark a row not applicable only after reading
the current family implementation.

| Layer | Inspect | Required evidence |
| --- | --- | --- |
| Schema | Codec, defaults, validation, tooltips | Parsed value and invalid-input behavior |
| Model | Field, accessor, nullability, sanitization | Stable default and public contract |
| Inheritance | Top-level and nested explicit-key handling | Parent value survives when child omits it |
| Resolution | ID, role, item, priority, enabled state | Correct winner for the family key |
| Overrides | `TwConfigFamily` and override manager | Override applies or is explicitly unsupported |
| Runtime | Every reader and effect producer | Field changes observable behavior |
| Cache | Resolver indexes and load/remove hooks | Replacement or removal cannot leave stale data |
| Editor | Schema adapter and field policy | Field is visible, hidden, or read-only by intent |
| Assets | Shipped examples and downstream configs | JSON matches the codec and intended default |
| Docs | Config discovery and family reference | Default, units, inheritance, and reload are clear |

## Common Incomplete Patches

- Codec plus JSON, with no consumer.
- New nested field, with no nested inheritance rule.
- New default that changes old assets after upgrade.
- New reload command branch when normal asset events already invalidate the
  family cache.
- Custom editor code for a field that the codec adapter already exposes.
- A test that only proves a field or asset exists.
