# VoxelPilot Agent Instructions

Read `PLAN.md` before changing behavior.

## Invariants

- Minecraft target is 1.20.1 Forge 47.4.x and Java 17.
- This is installed as a client mod. VoxelPilot-specific mutations are only permitted against the local integrated single-player server.
- Do not add `/ai`; Cmd+K is the primary prompt interface.
- Do not execute a newly generated plan on `confirm`. Confirm must apply the exact current preview.
- Never let model output bypass local validation.
- Preserve creative/survival rule differences.
- Survival must not synthesize items.
- Keep provider logic behind `ModelProvider`.
- Prefer OpenAI-compatible endpoints as the generic adapter; add dedicated adapters only when semantics differ materially.
- Keep normal Minecraft chat free of verbose AI feedback. Use action-bar notices for brief events and the Workspace for details.

## UX vocabulary

Use these terms consistently:

- Selection: user-defined cuboid.
- Anchor: first selected block.
- Preview: unconfirmed proposed world changes.
- Ghost: visual representation of preview changes.
- Workspace: Cmd+Shift+K side UI.
- Palette: Cmd+K quick prompt/command UI.
- Bridge: local model/provider boundary.
- Operation: one confirmed AI world mutation, and therefore one undo unit.
