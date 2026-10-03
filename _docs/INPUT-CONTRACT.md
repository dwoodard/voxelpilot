# Cmd-K Input Contract

Cmd-K is one composable input surface. Prefixes are operators, not separate modes.

## Operators

| Token | Meaning | Contract |
| --- | --- | --- |
| `/` | Action | Explicit deterministic command/action. Prefer Minecraft/server command truth when available. |
| `@` | Reference | Resolve an addressable Voxel Pilot object and expose its known facts. |
| `?` | Query | Ask for/search factual information. Read-only by default. |
| `#` | Designate | Give a factual object or location a persistent Voxel Pilot identity. |
| none | Intent | Natural-language request. AI may interpret the request, but deterministic operations remain deterministic. |

Operators may be mixed:

```text
/trigger tpa @Steve
?diamonds near @Mine
Where is @Steve?
Build a road from @Home to @Village
```

## Keyboard contract

```text
Cmd+K     open
Up/Down   navigate visible suggestions
Right     open actions/details for selected item
Left      go back one level
Tab       autocomplete selected suggestion
Enter     select/submit/execute
Esc       back; close at root
```

Typing narrows. Arrows navigate. Tab completes. Enter commits. Esc escapes.

## Suggestion contract

Suggestions should be ranked locally and deterministically:

1. exact/prefix relevance
2. frequency
3. recency
4. provider order/context

Suggestion sources must retain their identity (for example SERVER, PLAYER, VOXEL PILOT, RECENT). A suggestion is not evidence that an action is currently valid unless its provider can establish that fact.

Suggestions should expose useful factual information while the user is typing when that information is already known.

Example:

```text
Where is @st

> @Steve     PLAYER · online · 83m · NW
  @Steven    PLAYER · online · position unknown
```

Showing this information is deterministic and does not require AI.

## Reference contract

`@` is a Minecraft context primitive, not an AI feature.

> **`@` resolves an addressable Voxel Pilot object and exposes its relevant known facts.**

A reference may represent a player, place, entity, waypoint, designation, event/location, or another factual object Voxel Pilot can identify.

The same resolved reference must be reusable by every consumer rather than creating separate target systems:

```text
                  @Steve
                     |
              ReferenceResolver
                     |
       +-------------+-------------+
       |             |             |
     Cmd-K          HUD        Wayfinder
       |             |             |
       +-------------+-------------+
                     |
              deterministic
                 commands
                     |
                     +
                     |
                    AI
```

The consumer determines what happens with the reference. Resolving `@Steve` does not itself invoke AI, navigate, execute a command, or mutate the world.

### While typing

Resolving an active `@` token should immediately provide useful deterministic facts when available.

For example:

```text
@STEVE
PLAYER · ONLINE
83m · NW
Last observed: now
```

If a fact is unavailable, say so rather than infer it:

```text
@STEVE
PLAYER · ONLINE
Position: UNKNOWN
```

### Inside natural language

References embedded in natural-language input provide grounded context.

```text
Where is @Steve?
Is @Steve closer to @Home or @Village?
What's around @Mine?
```

Before AI reasoning, Voxel Pilot resolves each reference and supplies only supported structured facts, conceptually:

```text
PROMPT:
Where is @Steve?

RESOLVED REFERENCES:
@Steve
  type: PLAYER
  online: true
  dimension: minecraft:overworld
  position: [142, 67, -318]
  distance: 83
  bearing: NW
  observed_at: current
  evidence: DIRECT
```

If position is not known:

```text
@Steve
  type: PLAYER
  online: true
  position: UNKNOWN
```

The AI must not turn UNKNOWN into a guessed location.

### Composition

Completing a reference replaces only the active `@` token, not the entire input.

```text
/trigger tpa @St
How far is @Ho from @Mine?
```

Multiple references may appear in one input and must resolve independently.

## Deterministic vs AI

Explicit deterministic operations stay deterministic. AI is not a hidden replacement for capabilities that already have exact implementations.

```text
/trigger home
→ deterministic command

@Steve
→ deterministic reference/details

? distance @Home
→ deterministic query when an exact capability exists

Where is @Steve?
→ natural-language request grounded with resolved @Steve facts
```

Natural-language reasoning may use AI, but the factual Minecraft context supplied to it comes from deterministic observation and reference resolution.

AI is a consumer of Voxel Pilot facts, not the source of those facts.

## Truth and authorization

Prefixes do not bypass the Voxel Pilot contract.

- `@` may only expose references supported by actual observed/stored facts.
- Unknown or stale references must not be presented as current facts.
- Existence of a reference does not imply every property of that reference is known.
- Facts should retain provenance/status such as DIRECT, INFERRED, STALE, or UNKNOWN where relevant.
- Server commands should come from the server/Minecraft command tree when that integration is available; do not invent trigger names or argument semantics.
- A prefix is never authorization for a world mutation.
- AI cannot authorize a world mutation.
- World changes still require PLAN -> VALIDATE -> PREVIEW -> EXPLICIT CONFIRMATION -> REVALIDATE -> EXECUTE.
