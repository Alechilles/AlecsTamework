---
title: "Captured Item Display API Reference"
order: 24
published: true
draft: false
---
# Captured Item Display API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference)

Development addition to API `2.0.0`: `TameworkApi.capturedItemDisplay()`.
Check `CAPTURED_ITEM_DISPLAY` and `available()` before registering.
Older or degraded implementations return an unavailable facade. Tamework
does not assign stars or impose a rarity policy on animals.

`register(CapturedItemDisplayProvider)` accepts one provider and returns an
idempotent `AutoCloseable`. Close it when the integrating plugin stops.
A second active provider or registration after shutdown throws
`IllegalStateException`. Tamework also clears the provider at shutdown.

The provider receives `CapturedItemDisplayContext`: the filled item ID,
optional role ID, and optional detached `ProgressionView.TraitsView` with the
same resolved trait definitions used by animal portraits. It runs synchronously
while Tamework prepares a captured item. Do not block, perform I/O, or retain
live entities. The context exposes none.

Return `CapturedItemDisplayContribution(descriptionPrefix, qualityId)`:

- `descriptionPrefix`: optional Hytale `Message`, placed above the normal
  tooltip in both additive and replacement tooltip modes. Use translation
  messages for prose so clients resolve their own language.
- `qualityId`: optional item-quality asset ID, such as `Rare`. Tamework resolves
  its runtime asset index and sets the stack quality. An unknown quality leaves
  the original quality unchanged.

`CapturedItemDisplayContribution.none()`, a null result, a provider runtime
exception, or an unavailable provider leaves the normal display unchanged.
`LinkageError` from an optional integration is also isolated.

The captured artifact retains the contributed description and quality asset ID
in its existing item metadata. Delivery and recovery recreate the quality from
that ID, without depending on the provider still being loaded. The quality index
is presentation data, not capture identity; asset-index changes do not alter the
artifact hash. Release clears the capture display and returns the empty item to
its configured quality. No database schema or API version change is required.

This hook applies when a new capture item is prepared. Existing captured items
retain their saved display until released and captured again. It does not scan
inventories or change an existing capture receipt.

Example (keep and close the returned handle):

```java
AutoCloseable registration = api.capturedItemDisplay().register(context -> {
    if (!"MyFilledCaptureItem".equals(context.itemId())) {
        return CapturedItemDisplayContribution.none();
    }
    return new CapturedItemDisplayContribution(
            Message.translation("server.myPack.capture.special"), "Rare");
});
```

Engine evidence: Workshop `release/0.6.3`, server source
`ItemStack.withQuality`, `ItemStack.getQualityIndex`, `ItemStack.toPacket`, and
`ItemDisplayMetadata`; compiled against the workspace's Hytale `0.6.4` target.
Per-stack quality controls client item and tooltip presentation; it does not
replace the item asset's dropped-item particle configuration.
