# Interaction Checklist

1. Project profile, knowledge hash, and snapshot identity are recorded.
2. Interaction type ID exists in the current source registration and locked
   profile.
3. Sensor conditions match intended prompt visibility.
4. Prompt text and action path refer to the same target/context.
5. Actions include explicit state reset, cooldown, target-loss, or cancellation
   paths.
6. Command mode cycle values match exact-profile declared options.
7. Declared/effective config inheritance preserves intended fallback behavior.
8. Role/component/parameter references pass affected-scope validation.
9. Error logs show no missing parameters/components in the loaded runtime copy.
10. Positive, denied, cooldown, reset, and ownership verification outcomes are
    reported separately.
11. Cooldown writer and reset reader use the same proven storage/channel; no
    reset sensor is selected from name similarity alone.
