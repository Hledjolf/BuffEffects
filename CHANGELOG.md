# Changelog

Newest first.

## 1.0.0 (2026-11-08), Hytale 0.7.6

First release.
- A status-effect engine: timed or permanent effects on players and NPCs. Other mods apply, remove and cure effects through it; BuffEffects owns the timers, stacking and removal, and tells the mod when an effect ends.
- The effect format (buffeffects.effects): each mod ships its effects in a sidecar, Effects/<Group>_<Name>.effects.json, validated at load. A mistake refuses only that effect, naming the file, the effect and the field.
- Parts: stat maximums, instant heals, regeneration (broken by damage unless set otherwise), damage over time, a single hit through resistances, resistances, damage dealt, speed, movement locks, disabled actions, knockback, invulnerable, intangible (always with can't-attack), light, and messages on apply, refresh, wear-off, cure, removal, death and while active.
- Damage types: the game's own, plus shadow, holy, arcane, mind, sonic, disease and chaos, each with its own damage colour.
- Looks on Hytale: tints, particles, model effects, screen overlays, icons, sounds, animations, model changes, and a see-through look.
- The game's own effects, such as Burn, can be applied by id, and are described and rated like any other.
- Durations on run time: effects pause while a player is offline, survive a restart, and follow each effect's rule on death (clear, keep or restart).
- Reapplying: refresh, extend, stack or ignore. Stacking categories, where only the strongest counts. Cures by harmful, tag or effect.
- Caps, set by the server owner in Mod Settings Manager: resistance per damage type, invulnerable, stun and root durations with diminishing returns, and regeneration. The game's own effects applied by id pass unchanged.
- Power ratings, shown in the window and /bfx info. Information only in 1.0.
- Commands: /bfx (the window), list, info, show, apply, remove, cure, check (a dry run), help. Permissions: buffeffects.editor, apply, remove, cure, show.others, and a node per effect.
- The editor window: Effects, Editor, Active and Test tabs. Build an effect, save it to the server's default mod or as an override, test it on yourself, remove effects from the Active tab, and dry-run any effect.
- Server overrides replace or disable another mod's effect without editing it.
- For mod authors: the Java API (buffeffects.api) with apply, remove, cure, check, has, active, describe and events; callers can re-word an effect's messages per application. Every effect is registered as a Hytale effect named BFX_<mod id>_<id>, so NPC roles can name it.
- Requires Mod Settings Manager and CommandDeck 1.2.0 or later.
