# Bonded Companion Expiry Safety Acceptance

Use a finite bonded companion lease and confirm the owner receives exactly these
built-in notifications, using the companion's saved display name (never the
`Empty Role` placeholder):

- Yellow: `60s`, `30s`, and `10s` remaining.
- Red: `5s`, `4s`, `3s`, `2s`, and `1s` remaining.

Each message must read `<NPC Name> expires in <#>s`. Unlimited leases produce
no expiry notifications.

For an aerial Tamework ride or mounted-glide companion, allow the lease to
expire while the owner is mounted. The forced dismount must cancel only that
player's fall damage until they land, then clear the protection. While active,
the player must see a non-debuff feather status icon with a one-minute timer.
Repeated expiry-cleanup attempts must not extend that original timer. Manually
dismissing, dying, transferring worlds, or removing another companion must not
arm the protection.
