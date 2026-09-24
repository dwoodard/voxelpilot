# VoxelPilot Implementation Plan

## Objective

Build a client-installed Forge 1.20.1 mod that adds an AI layer to local single-player Minecraft. The AI may inspect the local world, propose deterministic block changes, render them as a preview, and only modify the world after explicit confirmation. Creative mode may use any block. Survival mode must consume legitimate player resources and follow Minecraft's local world rules.

## Product contract

- `J`: anchor/select the block under the crosshair (extended reach up to 100 blocks).
- `J` again: set the opposite corner directly.
- Arrow keys: grow the horizontal cuboid relative to the direction the player faced when anchoring.
- `Shift + Arrow`: shrink that horizontal face.
- `Option + Up`: move the top face upward.
- `Shift + Option + Up`: move the top face downward.
- `Option + Down`: move the bottom face downward.
- `Shift + Option + Down`: move the bottom face upward.
- `Shift + J`: clear the selection.
- `Cmd + K` (Ctrl+K fallback): open the AI command palette. No `/ai` command.
- `Cmd + Shift + K`: toggle the AI Workspace.
- AI actions are always preview-first. `confirm` executes the exact current plan; it never regenerates.
- AI can propose changes outside the user's selected cuboid, but the affected area must be visible before confirmation.
- Selection and preview outlines render through terrain so underground work remains understandable.
- `undo` restores the immediately previous AI world operation.
- Build execution is visible over time; AI chooses a default speed and the user can override `slow`, `normal`, `fast`, or `instant`.
- Player movement is separate from world editing and only happens when explicitly requested or accepted.
- Remote multiplayer world editing is disabled. VoxelPilot is a client-installed mod and only mutates the integrated server of a local single-player world.

## Architecture

```text
Minecraft client
├── Selection + rendering
├── Cmd+K command palette
├── Cmd+Shift+K workspace
├── Ghost preview
├── Build executor
│   ├── creative: unrestricted resources
│   └── survival: consume real inventory/storage resources
└── Local AI bridge (lazy-started)
    ├── provider abstraction
    ├── OpenAI-compatible provider
    ├── Ollama provider
    └── future provider adapters
```

The bridge is logically separate but currently runs inside the same Java process and exposes localhost health/model endpoints. This keeps installation to one mod JAR while retaining a clean boundary that can later be extracted into a separate bundled process if isolation becomes useful.

## AI contract

The model does not get authority to change the world. It only returns a plan.

```json
{
  "title": "Medieval Barn",
  "message": "Barn fitted to the selected slope",
  "mode": "PRESERVE",
  "speed": "normal",
  "changes": [
    {"x": 0, "y": 0, "z": 0, "block": "minecraft:stone_bricks"}
  ],
  "suggestedMove": null
}
```

Coordinates are relative to the selection minimum corner (or current anchor when no cuboid exists). The mod validates block IDs, coordinate limits, change limits, local-world eligibility, and survival resources before execution.

## Internal AI classifications

- `PRESERVE`: build around terrain and player structures.
- `MODIFY`: alter only what is required by the prompt.
- `REPLACE`: removal/rebuild was explicitly requested.

The user does not need to choose these modes. They are shown in the Workspace so the user can see the AI's interpretation.

## Delivery phases

### Phase 1 — interaction shell
- Selection anchor and cuboid editing.
- Through-terrain selection rendering.
- Cmd+K command palette with local command autocomplete.
- Cmd+Shift+K workspace.
- Provider configuration in the Workspace.
- Lazy local bridge startup.

### Phase 2 — AI preview loop
- World context capture.
- Model-agnostic provider interface.
- OpenAI-compatible and Ollama adapters.
- Strict JSON build-plan validation.
- Ghost preview rendering.
- Natural-language revisions replace the current preview.

### Phase 3 — execution
- `confirm`, `cancel`, `pause`, `resume`.
- Slow/normal/fast/instant build speed.
- Bottom-up placement and top-down carving.
- One-operation undo snapshot.
- Creative execution.
- Survival inventory validation and consumption.

### Phase 4 — richer survival
- Authorized storage.
- Craftable-material accounting.
- Crafting/smelting planning.
- Missing-material workflow.

### Phase 5 — world intelligence
- Structure recognition around crosshair.
- Ambiguity candidates highlighted in-world and surfaced in the Workspace.
- Context radius auto-expansion up to user-configured maximum.
- Snapshot library and reusable structures.
- Better textured/translucent ghost block rendering.

## Non-negotiable safety rules

1. AI output is never executed without local validation.
2. AI build plans never execute before user confirmation.
3. `confirm` executes the already-previewed plan, not a newly generated one.
4. Remote multiplayer servers are never mutated by VoxelPilot-specific world editing.
5. Survival placement cannot create resources.
6. Undo data is captured before the first block of an operation is changed.
