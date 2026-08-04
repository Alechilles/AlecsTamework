# Bonded Companion Expiry Safety Acceptance

Use a finite bonded companion lease and confirm the owner receives exactly these
built-in notifications, using the companion's custom name when it has one:

- Yellow: `60s`, `30s`, and `10s` remaining.
- Red: `5s`, `4s`, `3s`, `2s`, and `1s` remaining.

Each message must read `<NPC Name> expires in <#>s`. Unlimited leases produce
no expiry notifications.

For an aerial Tamework ride or mounted-glide companion, allow the lease to
expire while the owner is mounted. The forced dismount must cancel only that
player's fall damage until they land, then clear the protection. The fallback
maximum duration is one minute. Manually dismissing, dying, transferring
worlds, or removing another companion must not arm the protection.
