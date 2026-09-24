# UX Specification

## Selection

`J` uses a long crosshair raycast so the player can select distant blocks without walking to them. The first `J` establishes the anchor and the coordinate frame for arrow-key editing. A second `J` can set the opposite corner directly.

Horizontal arrows operate relative to the player's facing at anchor time:

- Up: forward face outward.
- Down: rear face outward.
- Left: left face outward.
- Right: right face outward.
- Shift reverses the selected face inward.

Vertical face editing:

- Option+Up: top face up.
- Shift+Option+Up: top face down.
- Option+Down: bottom face down.
- Shift+Option+Down: bottom face up.

The anchor block always remains part of the selection. Selection edges render through terrain so a deep underground cuboid remains understandable from the surface.

## Palette

Cmd+K opens a quick window. All text entered there is implicitly directed at VoxelPilot; `/ai` does not exist. Known commands run locally and never spend a model request:

- confirm
- cancel
- pause
- resume
- undo
- speed slow|normal|fast|instant
- clear selection

Everything else is treated as natural-language planning/revision input. Suggestions change according to current state.

## Workspace

Cmd+Shift+K opens the persistent control surface. It is the place for durable feedback and configuration instead of normal chat.

Status view:

- selection dimensions
- preview title / mode / speed / change count
- current execution progress
- bridge status
- later: materials, detected structures, ambiguity candidates, affected bounds

Models view:

- provider type
- base URL
- model
- API key
- test connection
- refresh model list

## Preview and confirmation

Every AI world change becomes a preview first. Revisions replace the preview. `confirm` applies the exact preview. `cancel` removes an unconfirmed preview or stops an active build. `undo` restores the immediately preceding AI operation.

## Survival

The plan can be created even when materials are missing, but confirmation must fail before the first block is placed if the required resources are not available. Current MVP counts the player inventory. Authorized storage and crafting are later phases.
