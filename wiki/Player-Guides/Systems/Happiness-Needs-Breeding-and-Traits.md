---
title: "Happiness, Needs, Breeding, and Traits"
order: 8
published: true
draft: false
---
# Happiness, Needs, Breeding, and Traits

Parent: [Systems](/mod/alecs-tamework/systems) | [Player Guides](/mod/alecs-tamework/player-guides)

Many Tamework-powered mods share a cluster of long-term progression systems. The mod decides the exact numbers and compatible creatures, but the framework supplies the common behavior.

## Experience presets

In `/tw settings`, selecting a preset immediately fills the form. Review the
settings and click **Apply** to save them.

- **Simplified (Minecraft-like)** disables adult aging, needs, happiness, passive
  breeding, breeding requirements for happiness and gender, traits, leveling,
  and talents.
- **Easier** enables those systems but freezes adult aging at prime and disables
  needs damage.
- **Full Experience** enables full adult aging and lethal needs damage, with
  starvation at 2% and dehydration at 3% of maximum health per minute. Revives
  and recall teleportation stay enabled; old-age death stays disabled.
- **Hardcore** enables full adult aging and old-age death, disables revives and
  recall teleportation, and raises starvation and dehydration damage to 10%
  and 15% of maximum health per minute respectively. Aging still depends on
  the creature's mod supplying an aging configuration.

When old-age death is enabled, dying of old age is permanent, even if revives
are enabled. This also applies to bonded companions. Changing the revive or
aging settings afterward cannot restore an animal that died of old age.

Every named preset sets animal progression to the owner-online policy with
72 hours of offline grace and a 1× rate afterward. Needs damage uses only the
higher of starvation and dehydration damage, rather than adding both together.
Selecting another preset replaces these settings, including the aging mode,
so values from the previous preset do not linger.

Presets preserve ownership, claims, population limits, telemetry consent, and
resource-search performance settings. New settings default to **Auto Fast**,
which uses normal movement until high resource-search or pathing pressure
triggers direct consumption from valid nearby sources. Explicit saved resource
modes stay unchanged. **Custom** leaves the form unchanged.

## Happiness
- A shared wellbeing value used by other systems
- Often improved by feeding, care, or positive interactions
- Can influence breeding readiness or other companion behavior
- The linked panel and target HUD show a thin red mark at the configured breeding happiness requirement. The mark is hidden when no happiness requirement applies. Reaching it satisfies the happiness check; other breeding rules still apply. Mods can set separate requirements for specific breeding interactions.
- The target HUD shows needs as percentage bars and breeding/harvest timers as progress bars with remaining time or readiness text. Breeding dims when switched off or blocked by low happiness. Random appearance attributes appear as text, such as `Coat: Brown`.
- Some mods also show short-lived active happiness impulses (for example recent feed, pet, or damage effects)

## Needs
- When needs are disabled, happiness treats hunger and thirst as full, including
  eligible care bonuses. Breeding still requires happiness, and other happiness
  factors still apply. This does not refill saved hunger or thirst.
- Hunger and thirst are the most common needs
- Needs can decay over time and may refill from manual feeding, passive sources, or resource-seeking behavior
- Poor needs often reduce happiness or block other behaviors
- Some servers enable needs-driven damage when hunger/thirst stay critical for too long

## Breeding
- Uses readiness rules, cooldowns, and partner matching
- Can require adulthood, tame state, wakefulness, or other gates
- Some mods expose breed toggles or breeding status in the linked panel
- Fertility can intentionally produce a litter of zero through four offspring. Several similar-looking siblings from one pairing are not automatically duplicates.
- Manual and passive pairing use the same nearby-cap rules. Nearby creatures and already-pending offspring count toward `MaxNearbySameType`, so a litter can be reduced or refused when the area is full.
- Each parent can participate in only one active birth job, and the delayed birth can execute only once.

## Life stage
- Offspring can start as babies or juveniles and later grow into adult forms
- Growth timing and model scaling are controlled by the mod's breeding config
- Life stage can affect what interactions or systems are available

## Traits
- Hover over a trait in the command panel to see a description below its value.
  Descriptions explain the effect and its amount, including increases, decreases,
  and flat happiness points. The effect percentage differs from the breeding-range bar.
- Traits are inherited or rolled attributes that can change stats or gameplay behavior
- Mods may expose trait icons, labels, or debug commands for them
- The exact trait pool comes from the mod's trait config

## Related Pages
- [Coops, Feed Troughs, and Shared Systems](/mod/alecs-tamework/coops-feed-troughs-and-shared-systems)
- [Linked Panel Guide](/mod/alecs-tamework/linked-panel-guide)
- [Player Glossary](/mod/alecs-tamework/player-glossary)



