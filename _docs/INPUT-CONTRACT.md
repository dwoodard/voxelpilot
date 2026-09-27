# Cmd-K Input Contract

Cmd-K is one composable input surface. Prefixes are operators, not separate modes.

## Operators

| Token | Meaning | Contract |
| --- | --- | --- |
| `/` | Action | Explicit deterministic command/action. Prefer Minecraft/server command truth when available. |
| `@` | Reference | Address a known player, place, entity, waypoint, designation, or other factual object. |
| `?` | Query | Ask for/search factual information. Read-only by default. |
| `#` | Designate | Give a factual object or location a persistent Voxel Pilot identity. |
| none | Intent | Natural-language AI request. AI may resolve references and choose deterministic capabilities. |

Operators may be mixed:

```text
/trigger tpa @Steve
?diamonds near @Mine
Build a road from @Home to @Village
```

## Keyboard contract

```text
Cmd+K     open
Up/Down   navigate visible suggestions
Tab       autocomplete selected suggestion
Enter     select/submit
Esc       back/close
```

Typing narrows. Arrows navigate. Tab completes. Enter commits.

## Suggestion contract

Suggestions should be ranked locally and deterministically:

1. exact/prefix relevance
2. frequency
3. recency
4. provider order/context

Suggestion sources must retain their identity (for example SERVER, PLAYER, VOXEL PILOT, RECENT). A suggestion is not evidence that an action is currently valid unless its provider can establish that fact.

## Truth and authorization

Prefixes do not bypass the Voxel Pilot contract.

- `@` may only expose references supported by actual observed/stored facts.
- Unknown or stale references must not be presented as current facts.
- Server commands should come from the server/Minecraft command tree when that integration is available; do not invent trigger names or argument semantics.
- A prefix is never authorization for a world mutation.
- World changes still require PLAN -> VALIDATE -> PREVIEW -> EXPLICIT CONFIRMATION -> REVALIDATE -> EXECUTE.

## Reference behavior

`@` is composable. Completing a reference replaces only the active `@` token, not the entire input.

Examples:

```text
/trigger tpa @St
How far is @Ho from @Mine?
```

The HUD, Wayfinder, AI, and command palette should eventually consume the same underlying reference identity rather than creating separate target systems.
