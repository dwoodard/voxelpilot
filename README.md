# VoxelPilot

VoxelPilot is an AI-native building companion for Minecraft Java Edition 1.20.1 (Forge). It adds a keyboard-first AI layer over a local single-player world: select space with the crosshair, describe what you want, inspect a ghost preview, revise it conversationally, and explicitly confirm the exact plan.

## Why VoxelPilot

The name describes the product without tying it to one model provider: a copilot for a voxel world. The architecture is intentionally provider-agnostic.

## Controls

| Input | Action |
| --- | --- |
| `J` | Set anchor A under the crosshair; press again to set B |
| `Shift + J` | Clear selection |
| Arrow keys | Grow a horizontal face relative to your original facing |
| `Shift + Arrow` | Shrink that horizontal face |
| `Option + ↑` | Raise the top face |
| `Shift + Option + ↑` | Lower the top face |
| `Option + ↓` | Lower the bottom face / dig deeper |
| `Shift + Option + ↓` | Raise the bottom face / reduce depth |
| `Cmd + K` | AI command palette (`Ctrl+K` also works) |
| `Cmd + Shift + K` | AI Workspace |

Examples in Cmd+K:

```text
build a medieval barn in this area
make the roof taller
move me somewhere I can see the whole build
confirm
pause
resume
speed fast
cancel
undo
```

## Current MVP

Implemented in the initial scaffold:

- 100-block crosshair selection reach.
- View-relative cuboid editing.
- X-ray selection outline.
- Cmd+K palette with context-aware suggestions.
- Cmd+Shift+K Workspace with selection, preview, build, bridge, and model configuration status.
- Lazy localhost bridge startup on first Cmd+K.
- Model-provider abstraction.
- OpenAI-compatible provider support (including LM Studio-style endpoints).
- Ollama provider support.
- World/selection context capture.
- Strict model-to-build JSON contract.
- Preview state and visible ghost voxel outlines.
- Explicit `confirm` / `cancel`.
- Build speeds: slow, normal, fast, instant.
- One-operation undo snapshot, including block entity NBT where available.
- Creative execution.
- Survival inventory preflight and real item consumption.
- Remote multiplayer editing is rejected.

The first preview renderer uses through-terrain voxel outlines. Textured translucent ghost blocks are the next visual refinement.

## Development setup

Requirements:

- Minecraft Java 1.20.1
- Forge 47.4.10
- Java 17

The repository includes a lightweight `bootstrap.sh`. Because the binary Gradle wrapper JAR is not checked into this initial generated bundle, bootstrap downloads that one standard wrapper file, then delegates to Gradle.

```bash
./bootstrap.sh genIntellijRuns
./bootstrap.sh runClient
```

To build:

```bash
./bootstrap.sh build
```

The mod JAR will be in `build/libs/`.

## Model configuration

Open `Cmd+Shift+K` → **Models**.

For LM Studio / other OpenAI-compatible services:

```text
Provider: openai-compatible
Base URL: http://localhost:1234/v1
Model: <your loaded model>
API Key: optional
```

For Ollama:

```text
Provider: ollama
Base URL: http://localhost:11434
Model: <your model>
```

Use **Test** and **Refresh models** from the Workspace.

## Design docs

- [PLAN.md](PLAN.md) — implementation contract for coding agents.
- [docs/UX.md](docs/UX.md) — interaction details.
- [AGENTS.md](AGENTS.md) — repository rules for AI coding agents.
