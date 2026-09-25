# VoxelPilot — Handoff Prompt for the Next AI Agent

You are taking over development of **VoxelPilot**, a Forge mod that adds an AI building layer to local single-player Minecraft.

- Repo: https://github.com/dwoodard/voxelpilot
- Target: Minecraft Java 1.20.1, Forge 47.4.x (currently 47.4.10), Java 17, Parchment mappings `2023.09.03-1.20.1`
- Install model: one client-installed mod JAR. No server plugin, no separate process the user has to start.

> **Update, 2026-09-24 — the plan format is now implemented, and it supersedes §5's op list.**
> The model returns a short **component tree** (`box`, `stairs`, `roof`, `door`, `blocks`; `on` stacks one component on another) in a local frame: x = right, y = up, z = forward, relative to the facing when J was pressed.
> - **Pure-Java core with unit tests:** `plan/` (`PlanRenderer`, `Shapes`, `BlockSpec`).
> - **Minecraft resolution:** `build/Frame`, `BlockSpecResolver` (vanilla `id[prop=value]` syntax, local directions allowed), `PlanResolver` → an immutable `ResolvedPlan` with revision numbers.
> - **Bugs fixed:** B1 through-terrain rendering (`client/XrayRenderType`), B2 confirm now runs on the server thread, B3 real BlockStates, B5 blocks that already match are skipped and door/bed halves are charged once, B6 speed override, B7 auto-correct only within 2 edits and always reported, B8 compact context, and revisions send the current tree instead of chat history, B9 truncation detection. One automatic repair round sends validator errors back to the model.
> - **Offline model eval:** `./bootstrap.sh evalPlans -PevalModel=<id>` (`src/test/.../eval/PlanEval.java`).
> - **Still open:** in-game verification (§9), B4 physics/neighbor updates, B10–B15, and every **[ASK]**.

Read these completely before changing anything: `AGENTS.md`, `PLAN.md`, `docs/UX.md`, `README.md`, then this file, then every source file (there are ~28 — read all of them, it's small).

This document captures a long design conversation plus a code audit. Treat product decisions here as intentional. Where this file and `PLAN.md` disagree, this file is newer; update `PLAN.md` to match as part of your work.

---

## 0. How to work (owner's rules — follow them)

The owner (Dustin) uses AI to speed up execution, not to outsource understanding. He owns architecture, file boundaries, acceptance criteria, and the merge decision.

Before each change:

1. Restate the goal in one sentence.
2. Identify the smallest useful change.
3. List the files likely to change.
4. Say what could break.
5. Say how you will verify it.

During: one vertical slice at a time, no speculative rewrites, don't touch unrelated files, stop and ask before expanding scope. After: summarize what changed and why per file, what you tested (and what you could not test — you cannot see the game window; say so rather than claiming visual behavior works), and any risky assumptions.

Ask (don't invent) when a choice changes the UX. Decisions explicitly flagged **[ASK]** below need the owner's answer before you implement them.

Commits: conventional commits (`feat(selection): …`, `fix(executor): …`). Only commit when asked.

---

## 1. Product in one paragraph

Look at / select part of the world → tell the AI what you want in a command palette → the AI proposes a **plan** → VoxelPilot expands and validates it locally → the result appears as a **ghost preview** → the user walks around it and revises it conversationally → the user explicitly confirms → Minecraft executes **exactly that preview**, visibly over time → one `undo` restores the whole operation.

> AI proposes. Minecraft validates. Minecraft previews. User confirms. Minecraft executes.

It should feel like an intelligent Minecraft-native design tool, not "ChatGPT pasted into Minecraft" and not "AI WorldEdit". The hard, valuable part is the loop — intent → context → deterministic plan → validation → visible preview → revision → explicit approval → legitimate execution → undo. A 5×5 platform that does all of that perfectly is worth more than a 20,000-block castle that can't be validated, revised, or undone.

---

## 2. Current state of the repo (as of this handoff)

**Builds and launches.** `./bootstrap.sh runClient` works on macOS; `run/logs/latest.log` shows `VoxelPilot loaded`. The Gradle wrapper JAR is now present (staged, not yet committed). There are no tests.

**Uncommitted staged work** (the owner made these; they are intentional):

- Cmd+Shift+K now toggles a **read-only** `InspectorScreen` (selection, preview, build, bridge, AI conversation). Provider/model config moved to `SettingsScreen` on **Cmd+,** (also reachable by typing `settings` in the palette). `WorkspaceScreen.java` was renamed/split accordingly.
- **Cmd+Shift+Enter** confirms the preview when no screen is open.
- `AiConversation`: multi-turn history for the current operation; resets when the preview clears.
- `PaletteHistory`: palette transcript + shell-style Up/Down recall.
- `OpenAiCompatibleProvider` sends `response_format: json_schema`, `temperature 0.2`, `max_tokens 8000`, strips code fences and Harmony-style `<|channel|>` prefixes.
- `AiPlanner` normalizes missing `minecraft:` namespaces and **auto-corrects unknown block IDs by Levenshtein distance**.
- Default base URL is `http://127.0.0.1:1234/v1` (LM Studio). `127.0.0.1`, not `localhost`, on purpose — JDK HttpClient + IPv6 resolution caused `ClosedChannelException`.
- With no selection, plans are relative to the crosshair target.

Docs lag the code: `PLAN.md`, `docs/UX.md`, `README.md`, and `AGENTS.md` still describe a single "Workspace" with model config inside it. The owner's vocabulary says **Workspace = Cmd+Shift+K side UI**. **[ASK]** whether to keep the name "Inspector" in code or rename back to Workspace; either way, update the docs to describe the Inspector/Settings split that now exists.

**First task:** confirm with the owner whether to commit the staged work as-is, then do the verification pass in §9 and record results in `docs/STATUS.md` (a short checklist: item, verified/broken/untested, note). Do not start new features until that exists.

---

## 3. Product contract (preserve these)

### Controls

| Input | Behavior |
| --- | --- |
| `J` (1st) | Anchor A = block under crosshair, up to ~100 blocks reach. Also records the player's horizontal facing — arrow keys are relative to that facing, not world X/Z. |
| `J` (2nd) | Sets opposite corner B directly. |
| `Shift+J` | Clear selection. |
| `↑ ↓ ← →` | Grow forward / backward / left / right face outward (relative to facing at anchor time). |
| `Shift+Arrow` | Move that face inward (shrink). |
| `Option+↑` | Top face up (taller). |
| `Shift+Option+↑` | Top face down. |
| `Option+↓` | Bottom face down (deeper). One press from a single block = anchor + the block beneath it. It does **not** move the whole selection. |
| `Shift+Option+↓` | Bottom face up. |
| `Cmd+K` | Command palette (Ctrl+K also works). |
| `Cmd+Shift+K` | Inspector / Workspace toggle. |
| `Cmd+,` | Settings. |
| `Cmd+Shift+Enter` | Confirm preview. |

Mental model: Option selects vertical-face editing, ↑ means the top face, ↓ means the bottom face, and Shift reverses the direction. The anchor block is always inside the selection. Selection wireframes render through terrain.

### Hard rules

- **No `/ai` command.** Normal chat stays clean. Use action-bar messages for short events and the Inspector for detail.
- **Preview-first.** Every AI world change becomes a ghost preview. Nothing mutates before `confirm`.
- **`confirm` never calls the model.** It executes the exact resolved preview the user saw.
- **Revisions produce a new preview**; only the latest visible one is confirmable.
- **Model output never bypasses local validation.** An invalid plan is rejected with an explanation, never partially executed.
- **Local single-player only.** Mutate only via `mc.getSingleplayerServer()`. On remote servers, world editing is disabled; client-only visuals may remain.
- **Creative vs Survival.** In Creative, anything the player may legitimately do. In Survival, remote placement is allowed (no walking avatar) but every placed item must be consumed from legitimate sources. Never synthesize items. A preview is allowed with missing materials; confirm fails before the first block if they're missing.
- **Undo = one confirmed operation**, captured before each block changes, including block entity NBT. It must work after pause/cancel mid-build.
- **Cancel during a build stops where it is.** No silent rollback; the user can `undo`.
- **Player movement only when asked** ("move me …") or when the user accepts a suggestion. Ordinary builds never make the player wander.
- **Living entities are never killed or moved** by ordinary build/terrain commands.
- **Material substitution is never silent** (spruce → oak must be proposed, not done).
- **Changes outside the selection are allowed** but must be visible: show the selection bounds and the actual affected bounds. "keep everything inside my selection" makes that a hard constraint.
- **Provider logic stays behind `ModelProvider`.** Prefer the OpenAI-compatible adapter (LM Studio, vLLM, hosted APIs); add dedicated adapters (Ollama) only when semantics differ.

### Local commands (never hit the model)

`confirm`, `cancel`, `pause`, `resume`, `undo`, `speed slow|normal|fast|instant`, `clear selection`, `settings`. Extend this list with deterministic parsers for selection edits: `set depth to 50`, `make this 20 blocks tall`, `width 12`. A regex is correct here and an LLM is wrong.

### Edit modes (inferred, shown in the Inspector)

`PRESERVE` (build around terrain/player structures — default), `MODIFY` (alter only what's needed), `REPLACE` (explicit clear/rebuild).

### Speeds

Default chosen per plan, but a user's explicit `speed …` before or during a build wins (see bug B6).

---

## 4. Being realistic about what models can do

This is the most important section. Design around what LLMs — especially the 7–30B local models the owner runs in LM Studio — actually do well.

**Models are good at:**
- Classifying intent (build / modify / carve / move / question / revise).
- Choosing materials, styles, and rough proportions.
- Filling in **parameters** for a known shape: `{"kind":"gable_roof","width":9,"length":12,"pitch":1,"material":"spruce"}`.
- Emitting a short list (roughly ≤ 30) of coarse operations: fill, hollow box, line.
- Editing an existing short parameter list: "make it taller" → `height: 5 → 8`.
- Explaining themselves and asking clarifying questions.

**Models are bad at, and will stay bad at for a while:**
- **Per-block enumeration.** Each `{"x":..,"y":..,"z":..,"block":".."}` entry is ~15–20 tokens. With `max_tokens: 8000`, the real ceiling is about **400–500 blocks**, even though `MAX_CHANGES` is 100,000. Past that the JSON gets truncated and fails to parse. Local models also lose count, skip rows, and duplicate positions well before that point.
- **3D spatial reasoning over coordinate lists.** Spiral staircases, symmetric roofs, circles and domes come out wrong. Asking the model to "enumerate every block" (the current system prompt does) makes this worse.
- **Correct BlockState orientation.** Stair `facing` or `half`, log `axis`, door halves, and which way a torch attaches get guessed and are often wrong.
- **Reading a raw sample dump.** Inferring "the roof" or "this wall" from 4,096 `{x,y,z,block}` samples fails. The current `WorldContextService` sends exactly that, which can mean tens of thousands of tokens on every request.
- **Reliable schema adherence** without grammar-constrained decoding. With `json_schema` it's mostly fine on LM Studio; hosted APIs vary.
- **Revising a large plan faithfully.** Today each revision resends every earlier plan's full JSON as assistant turns, so context grows with every revision and the model regenerates everything from scratch.

**What follows from this:**

1. **The model picks and parameterizes; local code draws.** VoxelPilot owns a library of deterministic **generators**. The model emits generator calls and low-level ops; Java expands them into exact blocks with correct BlockStates. The spiral staircase in the underground example comes from a `spiral_stair` generator, not from the model tracing a helix.
2. **Orientation comes from generators, not the model.** Generators compute `facing`/`half`/`axis`/`shape`. The model gives explicit state only for single `set` ops, and it's validated strictly.
3. **Revisions edit the op list, not the block list.** Send the model the current compact op list (small), have it return a new op list, then re-expand locally. The conversation history holds op lists, never expanded blocks.
4. **Context is summarized, not dumped.** See §6.
5. **Anything deterministic stays deterministic.** Selection edits, speed, confirm/undo, material counts, bounds, sorting, "move me outside the selection", flatten-to-a-y-level.
6. **Plan for failure.** Log raw responses (already done). Show the user a short, specific error ("model output was truncated after 412 changes — try a smaller request or a parametric shape"). Detect truncation explicitly (`finish_reason == "length"`).
7. **Measure models, don't assume.** Build a small offline eval (§8) so "does qwen-14b produce valid plans for these 15 prompts?" has a number.
8. **"No selection, finish this roof"** (structure recognition) is hard. Don't try to solve it with the LLM first. A deterministic heuristic comes first: flood-fill connected non-natural blocks from the crosshair target, capped in size, and use its bounding box as an implicit selection that gets highlighted for the user to accept. If ambiguous, show candidates and don't guess destructively. Treat it as Phase 5, not the next milestone.
9. **Vision models** (screenshots) may help with style or intent later, but they can't give block coordinates. They're optional and out of scope for now.

---

## 5. Target plan architecture

```
user text
  ├─ local command / deterministic parser ──► act directly (no model)
  └─ model
       ↓  compact PlanDraft JSON  (ops + generator calls, BlockSpecs)
     PlanParser            — strict parse; reject unknown fields/ops
       ↓
     OperationValidator    — known op, sane params, size caps BEFORE expansion
       ↓
     PlanExpander          — deterministic; ops/generators → exact changes (pos, BlockState, optional NBT)
       ↓
     ResolvedPlanValidator — world height, loaded chunks, selection constraint, entities in volume,
                             player-trap check, survival materials, dedupe/conflicts, final size cap
       ↓
     ResolvedPlan          — immutable: planId, revision, hash, changes, affected bounds, materials
       ↓
     GhostPreview          — renders exactly ResolvedPlan.changes
       ↓ confirm(revision, hash)
     BuildExecutor         — executes that ResolvedPlan, nothing else
```

Keep AI/provider code out of rendering, selection, validation, and execution. The model contract is the stable boundary: providers and prompts can change without touching the engine.

### BlockSpec (replace bare `block` strings)

```json
{ "id": "minecraft:oak_stairs", "state": { "facing": "east", "half": "bottom" } }
```

- `state` is optional; omitted properties take the block's default.
- Validate locally: the block exists, each property exists on that block, and each value is legal. Build the `BlockState` with `StateDefinition`. **Reject** invalid properties, don't drop them.
- Accept the shorthand string `"minecraft:stone"` too (small models emit it constantly).
- Block entity NBT stays separate and isn't exposed to the model yet. Sign text is the first reasonable candidate, via a narrow typed field, never raw NBT.

### Ops (keep it small; add only on concrete need)

Low-level: `set`, `fill`, `hollow_box` (with optional `floor`/`ceiling` toggles), `line`, `replace` (from-block → to-block within a region), `clear` (fill with air).

Generators (the realistic core — start with 3–4, add more as they prove useful): `platform`, `walls`, `stair_straight`, `spiral_stair`, `gable_roof`, `tunnel`, `pillar`, `flatten`.

Composition: coordinates are relative to the plan origin. `palette` aliases (`"A": BlockSpec`) keep responses short. Don't build a scripting language: no loops, no variables beyond the palette.

Keep the current flat `changes[]` path working as a fallback for tiny plans (under ~100 blocks) while ops land. Migrate, don't big-bang.

### ResolvedPlan identity

`planId`, `revision` (increments on each revision), `hash` of the sorted resolved changes. The Inspector shows `Preview rev 3 · 214 changes · MODIFY`. Confirm executes the revision the Inspector shows. If anything changed in between, refuse and re-preview.

The preview origin is fixed when the plan is resolved. Moving the selection afterwards does not move the preview (current behavior, keep it).

---

## 6. World context (make it small and useful)

Budget the context by tokens (assume the owner's local models have 8–32k context). Target ≤ 3–4k tokens of world context by default.

Send:
- Player position, facing, game mode, dimension. Crosshair target block **and its state**.
- The selection: min/max, dimensions, and the facing at anchor time, so "left" and "front" can be translated. Better still, give the model a local frame (`forward`, `right`) and convert locally.
- A **heightmap summary**: surface Y per column (downsampled if large) plus the top-surface block palette.
- Per-Y-layer block counts inside the selection (a compact "what's underground").
- A notable-blocks list: non-natural blocks (planks, bricks, glass, doors …) with positions, capped.
- For revisions: the current op list, not the expanded blocks.
- In survival: an inventory summary (item → count) only when relevant.

Don't send 4,096 raw samples. Context radius (32 → 64 → 128 → 256 auto-expand, user max in Settings) stays in the design, but only expand when the model explicitly asks via a `needsMoreContext: {radius, reason}` field, and never force-load chunks: sample loaded chunks only and say which areas were unloaded.

---

## 7. Known bugs and gaps from the audit (verify, then fix in small commits)

B1. **X-ray may not actually work.** `ClientEvents.onRenderLevel` calls `RenderSystem.disableDepthTest()` and then draws with `RenderType.lines()`. That RenderType's own depth-test state shard re-enables depth testing when the batch flushes. Selection and ghost outlines probably get hidden by terrain. Fix with a custom `RenderType` using `NO_DEPTH_TEST` (subclass trick to access the protected shards), and verify underground.

B2. **Cross-thread server access.** `BuildExecutor.confirm` runs on the client thread but reads `ServerPlayer` inventory (`MaterialAnalyzer.countInventory`). `undo` correctly uses `server.execute`. Move confirm's server-side checks onto the server thread too (`server.submit(...)` → result back via `mc.execute`).

B3. **BlockState loss.** Execution uses `defaultBlockState()`, so every stair faces north and logs are vertical. Fixed by BlockSpec (§5).

B4. **Multi-block and physics blocks.** `setBlock(pos, state, 3)` on doors/beds/tall plants places half a block. Bottom-up order makes sand and gravel fall when unsupported. Water and lava flow. Plants pop off without support. Flag 3 fires neighbor updates (redstone). Plan: place with flags that suppress drops and updates during the build, handle two-block blocks as a unit (place both halves, charge one item), then do a final neighbor-update pass. Reject fluids until handled explicitly.

B5. **Survival accounting.**
- A same-block target (already `stone`, plan says `stone`) still consumes an item. Skip no-op changes when resolving, which also shrinks the preview.
- Double-block items (doors, beds) get charged per half.
- Blocks whose item differs from the block (`wall_torch` → `torch`, `redstone_wire` → `redstone`) need the right mapping, and `asItem()` returns `Items.AIR` rather than null for itemless blocks.
- **[ASK] Removals in survival:** today a removed block just vanishes (no drops, no tool requirement). Options: give drops to the inventory, drop them in-world, or delete them (current). Also: may survival remove unbreakable/hard blocks (bedrock is obviously no)?
- **[ASK] Undo in survival:** undo restores the world, but items consumed by the build are not refunded, so the player loses materials. Undoing a removal restores blocks that were never paid for. Decide on a refund policy.

B6. **Speed override lost.** `confirm` sets `speed` from the plan, overwriting a `speed fast` the user typed before confirming. A user choice must win.

B7. **Block auto-correct conflicts with "no silent substitution".** Levenshtein fixes like `oak_planes` → `oak_planks` are fine, but the threshold `max(2, len/3)` can map genuinely different blocks. Keep the fix but tighten it (e.g. distance ≤ 2), and **surface every correction** in the preview message and the Inspector.

B8. **Context bloat and revision bloat.** See §4/§6. Stop storing the full plan JSON as assistant turns; store the op list (or a size-capped version).

B9. **No truncation detection.** Check `finish_reason` or `done_reason`. When it's `length`, report truncation instead of "invalid JSON".

B10. **Player trapping and entities.** No check that the player or living entities sit inside the change volume. Minimum for now: warn in the preview and offer "move me outside" when the player intersects placements; refuse to place solid blocks on a living entity's position.

B11. **LAN.** `getSingleplayerServer()` is non-null when the world is opened to LAN with other players connected. **[ASK]** whether editing is allowed then. Probably allowed only while the owner is the sole player.

B12. **Bridge is mostly vestigial.** `BridgeServer` starts an HTTP server on fixed port 8765 exposing `/health` and `/models`, but `plan()` calls the provider directly in-process. That's harmless, but the fixed port can collide, and it's an unauthenticated local endpoint. Don't grow it. The architectural boundary that matters is the `ModelProvider` interface plus the plan contract. **[ASK]** whether to keep the HTTP endpoint for debugging (bind an ephemeral port and show it in the Inspector) or remove it.

B13. **Mod entry registers client classes unconditionally.** Fine for a `clientSideOnly=true` mod on the client, but guard with `DistExecutor`/`FMLEnvironment.dist` so a dedicated server that accidentally loads it doesn't crash.

B14. **Ghost rendering is outlines only, strided above 5,000.** Striding hides part of what the user approves, which conflicts with "see exactly what will change". Prefer merging outlines per face or capping plan size, and say in the UI when rendering is simplified. Translucent textured ghosts come later.

B15. **No tests.** Selection math, BlockSpec validation (needs a bootstrapped registry, or keep a pure-Java layer), op expansion, hashing, and material counting are all unit-testable. Add JUnit 5 for the pure-Java parts.

---

## 8. Model eval harness (small, high leverage)

Create `evals/` with ~15 fixed prompts plus a canned world-context JSON for each: a 3×3 platform, 5×5 hollow room, straight stair down 10, "make it taller" revision, a greeting (must return no changes), "keep inside my selection", an invalid material, and so on. Add a Gradle task or a tiny `main` that sends them to the configured provider **outside Minecraft**, runs PlanParser + OperationValidator + PlanExpander, and prints per prompt: valid JSON, validated, change count, latency, tokens.

This lets you tune the system prompt and op schema against real local models without launching the game, and tells the owner which models are good enough. Put the Minecraft-independent parts (parser, op validation, expansion to `(pos, BlockSpec)`) in a package that doesn't import `net.minecraft` so this works.

---

## 9. Verification pass (do this first, record in `docs/STATUS.md`)

In the dev client (`./bootstrap.sh runClient`), on a Creative superflat world, then a Survival world:

1. J selects the crosshair block at ~50 blocks. The outline is visible **through terrain** (see B1).
2. Arrows grow/shrink relative to the facing at anchor time. Test facing N, E, S, W.
3. Option+↓ once on a single block gives height 2 and keeps the anchor. Shift+Option+↓ undoes it. Same for Option+↑.
4. Shift+J clears. The second J sets corner B.
5. Cmd+K, Cmd+Shift+K, and Cmd+, open and close. Keys don't leak into the game (no J selection while typing). Ctrl+K works.
6. Settings: save, Test, and Refresh models against LM Studio.
7. 3×3 selection → "make a one-block-high stone platform" → ghost only, world unchanged.
8. "make it two blocks high" → new preview. `confirm` → exactly those blocks, no model call (check the log).
9. `undo` → the original world returns, including a chest's contents if a chest was in the volume.
10. Pause, resume, speed change, and cancel mid-build, then undo after cancel.
11. Survival: insufficient stone → confirm refused with a count. Sufficient → the inventory decreases by exactly the placed count.
12. Join a multiplayer server (or simulate one): confirm is refused.

Whatever you can't verify yourself (anything visual), write it down as "needs owner check" instead of guessing.

---

## 10. Priority order

1. Commit the staged work (with the owner's OK) and do the §9 verification → `docs/STATUS.md`. Update docs for the Inspector/Settings split.
2. Fix loop-breaking bugs: B1 x-ray, B2 threading, B6 speed override, B9 truncation, B5 same-block no-op.
3. BlockSpec with full BlockState (B3), with unit tests.
4. Pure-Java plan core: parser → op validator → expander (`set`, `fill`, `hollow_box`, `line`, `replace`), keeping the flat `changes[]` fallback. Unit tests.
5. `ResolvedPlan` with revision and hash. Confirm checks them. The Inspector shows revision, affected bounds vs selection, and material needed/have/missing.
6. Eval harness (§8). Rewrite the system prompt around ops; drop "enumerate every block".
7. Context summarizer (§6) and op-list-based revisions (B8).
8. First generators: `platform`, `walls`, `stair_straight`, `spiral_stair`, `gable_roof`. Test each in-game with undo.
9. Survival correctness: multi-block items, item mapping, and the owner's answers on removals and refunds (B4/B5).
10. Only then: storage authorization, structure recognition, snapshots/templates, translucent ghosts, more generators.

---

## 11. Future features (don't build yet; don't block them)

- **Authorized storage:** "add this chest as storage", optionally named. Never raid unauthorized chests. Later crafting and smelting, as separate permissions (BUILD / CRAFT / STORAGE / GATHER).
- **Missing materials mid-build:** pause, list what's missing, offer resume / replace material (proposed, not silent) / cancel / undo.
- **Snapshots/templates:** "save this as barn" stores relative positions, BlockStates, NBT, bounds, and origin. Then "build barn here facing east", mirror, scale, and material swap. Note that templates are simply another op (`paste_template`), which makes them cheap for the model to use.
- **Structure recognition** with candidate highlighting (see §4.8).
- **Numeric selection editing** in the UI (Down: 50, Up: 8, …). With the Inspector now read-only, **[ASK]** whether this lives in the Inspector (made interactive) or in Settings, or only as palette commands.
- **Quick model switch** from Cmd+K. Separate planner, fast classifier, and vision model roles (one model for everything stays the default).
- **Explicit agent/gather mode** (walk, mine, chop), opt-in only.
- **Disk-backed history.**

---

## 12. Vocabulary (use consistently)

Selection (user cuboid) · Anchor (first J block) · Preview (unconfirmed resolved changes) · Ghost (visual rendering of the preview) · Workspace/Inspector (Cmd+Shift+K) · Palette (Cmd+K) · Bridge (provider boundary) · Operation (one confirmed mutation = one undo unit) · Op (one entry in a compact plan) · Generator (a parametric op that expands locally) · Revision (one version of a preview).

Start by reading the files listed at the top, then report your one-sentence goal and plan for step 1 of §10.
