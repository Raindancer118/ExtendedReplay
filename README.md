<div align="center">

# 🎬 ExtendedReplay

**Server-side 3D replay, live mirror & match analysis for Minecraft Paper servers.**

*Not a video recorder. Not a ReplayMod clone. A distributed world-state recorder —
your matches, replayable as a real, walkable 3D world.*

[![Java](https://img.shields.io/badge/Java-21+-orange?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Paper](https://img.shields.io/badge/Paper-1.21.10-blue)](https://papermc.io/)
[![Build](https://img.shields.io/badge/build-Gradle%209-02303A?logo=gradle)](https://gradle.org/)
[![Status](https://img.shields.io/badge/status-in%20development-yellow)](#-project-status)

</div>

---

## 💡 What is this?

ExtendedReplay records everything that happens inside a bounded Minecraft arena —
player movement at **20 Hz**, every block change, every chest, every kill — and streams
it live to a **separate replay server**. There, moderators join a reconstructed 3D copy
of the match: fly around freely, pause and rewind time, jump to any kill, open any chest
*as it was at that moment*, and render player routes for analysis.

```
   GAME SERVER  (PRODUCER)                      REPLAY SERVER  (REPLAY)
 ┌──────────────────────────┐                ┌──────────────────────────────┐
 │  TheHungerGames (or any  │   WebSocket    │  Live mirror of the match    │
 │  other minigame plugin)  │   ══════════▶  │  Playback: ⏪ ⏸ ▶ ⏩ 0.25–8× │
 │                          │   compact      │  Event timeline & browser    │
 │  ExtendedReplay records: │   binary       │  Inventory time machine      │
 │  • 20 Hz player frames   │   protocol,    │  Route rendering & analysis  │
 │  • block/chest deltas    │   batched,     │  Moderator hotbar GUI        │
 │  • kills, events, chat   │   zstd, spool  │                              │
 └──────────────────────────┘                └──────────────────────────────┘
      never blocks gameplay                     moderators fly & inspect
```

**The game server never suffers.** Capture copies only primitives on the main thread;
serialization, compression, disk and network I/O all happen on background workers. If
the replay server goes down, packets spool to disk and are delivered after reconnect —
and if everything fails, the game just keeps running.

---

## ✨ Feature Highlights

| | |
|---|---|
| 🎥 **True 3D replay** | The arena is rebuilt from a pre-match snapshot + event-sourced deltas — walk through the replay like a spectator |
| 📡 **Live mirror** | Watch the running match on the replay server with a configurable delay |
| ⏱️ **20 Hz player frames** | Position, view direction, pose, sneaking/sprinting/gliding, health, held slot — every tick |
| 🎒 **Inventory time machine** | Inspect any player inventory or chest *at any timestamp* — dirty-state snapshots with content hashing |
| 📅 **Event timeline** | Kills, deaths, chest opens, supply drops, custom plugin events — browse, filter, jump |
| 🗺️ **Route rendering** | Async analysis jobs draw player paths as carpet/glass/particles in a dedicated analysis world |
| 🧍 **Mannequin rendering** | Players are rendered as native Paper Mannequin entities (with renderer abstraction for fallbacks) |
| 🔌 **Public API** | Any plugin can start sessions, record custom events and bookmarks via `ExtendedReplayApi` |
| 🛡️ **Integrity built-in** | Checksummed zstd segments, session manifest, `/erp verify`, degradation markers |

---

## 🏗️ Architecture

### Server roles

One plugin, four roles — set `extendedreplay.server-role` in `config.yml`:

| Role | Purpose |
|------|---------|
| `PRODUCER` | Real game server. Captures & streams. Featherweight — never plays back, never renders. |
| `REPLAY` | Replay server. Receives, stores, mirrors, plays back, analyses. |
| `STANDALONE` | Records **and** plays back on one server. For local testing. |
| `DISABLED` | Plugin loads, does nothing. |

### Modules

```
extendedreplay/
├── extendedreplay-api                  Public API (ExtendedReplayApi via ServicesManager)
├── extendedreplay-core                 Replay model, binary protocol, queues, metrics — pure Java
├── extendedreplay-storage              zstd segment files + SQLite index + arena snapshots
├── extendedreplay-transport            WebSocket client/server, batching, spooling, loopback
├── extendedreplay-paper                The actual Paper plugin: capture, playback, GUI, commands
└── extendedreplay-integration-example  Reference integration for minigame plugins
```

The dependency direction is strict: `paper → {api, core, storage, transport}` and
`{storage, transport} → core`. Core knows nothing about Bukkit.

### Recording model — event sourcing

A replay session is **not** a series of world dumps. It consists of:

1. a reference to a **precomputed arena snapshot** (the world as it was at match start —
   palette-compressed `.erpa` files, created in per-tick budgets, applied on the replay
   server before playback),
2. a **20 Hz player frame stream** (compact primitives — no ItemStacks, no NBT, no JSON),
3. **event-based deltas**: block changes, inventory/container snapshots (only when their
   content hash actually changed), equipment changes (versioned, referenced from frames),
   entity/projectile/item events,
4. a **timeline of discrete events** (kills, deaths, chat, custom events, bookmarks),
5. **keyframes** built when a playback session opens, so backward seeks replay only the
   interval since the last checkpoint instead of the whole session.

Under load, the pipeline degrades gracefully: cosmetic data (particle-level frames,
item movement) is dropped first and counted; critical events (kills, inventories, block
changes) are always preserved. Degraded time ranges are marked in the recording.

### Wire format

High-frequency data uses a compact binary protocol — varints, player indexes instead of
UUIDs, angles as single bytes, fixed-point health. Batches are zstd-compressed off the
main thread. Handshake with protocol version + auth token. Unknown packet types are
skipped, so newer producers keep working with older replay servers.

---

## 🚀 Quick Start

### Requirements

- Java **21+**
- Paper **1.21.10** (or newer)
- Two servers for the production setup (one is fine for `STANDALONE` testing)

### Build

```bash
./gradlew build
# → extendedreplay-paper/build/libs/ExtendedReplay-<version>.jar
```

Third-party libraries (SQLite, zstd, WebSocket) are **not** shaded — Paper's plugin
library loader resolves them at startup from Maven Central.

### Install

1. Drop `ExtendedReplay-<version>.jar` into `plugins/` on **both** servers.
2. Start once, then edit `plugins/ExtendedReplay/config.yml`:

**Game server** (`hungergames.example.com`):

```yaml
extendedreplay:
  server-role: PRODUCER

transport:
  host: replay.example.com
  port: 8787
  auth-token: "pick-a-long-random-secret"
```

**Replay server** (`replay.example.com`):

```yaml
extendedreplay:
  server-role: REPLAY

replay-server:
  bind-host: 0.0.0.0
  bind-port: 8787

transport:
  auth-token: "pick-a-long-random-secret"   # must match the producer
```

3. Restart both. The producer connects automatically and reconnects with backoff.

### Record & watch

```
# on the game server (or via the API from your minigame plugin)
/erp record start match-42     # freezes the arena automatically, ships the snapshot
                               # to the replay server, then starts recording
... play the match ...
/erp record stop               # boss bar shows transfer progress until ready

# optional: /erp gui opens the record control panel (radius, start/stop);
# /erp snapshot create arena-v1 200 + /erp record start match-42 arena-v1
# still work for manually managed snapshots

# on the replay server — the snapshot arrived automatically
/erp live                  # watch the running match, delayed live mirror
/erp sessions              # list stored sessions
/erp play <sessionId>      # open a playback session (applies the arena snapshot)
/erp events                # browse & jump to kills, chests, custom events
/erp inventory <player>    # inspect an inventory at the current replay time
/erp route render <player> # draw a player's path
/erp heatmap kills         # intensity-graded kill heatmap
```

Auto-snapshots are on by default (`producer.auto-snapshot`: radius 200 around the
recording anchor, required before any recording starts). The `.erpa` file streams to the
replay server over the existing WebSocket connection — no manual copying.

---

## 🎮 Commands

`/erp` (aliases: `/extendedreplay`, `/replayx`)

<details>
<summary><b>Full command reference</b></summary>

| Group | Commands |
|-------|----------|
| **General** | `/erp` (opens the Replay Control Center) · `menu` · `gui` · `help` · `status` · `test` |
| **Recording** | `record start [name] [snapshot\|-] [world]` (console needs the world arg or uses the default world) · `record stop` · `sessions` · `session <id>` |
| **Playback** | `play <id>` · `connect <id>` (fresh seed-matched world) · `disconnect`/`close` · `pause` · `resume` · `speed <0.05–64>` · `jump <time>` · `rewind <s>` · `forward <s>` |
| **Live** | `live` |
| **Events** | `events` · `event <id>` · `jump event <id>` · `bookmark <name>` · `bookmarks` |
| **Scenes** | `scene create <name>` · `scene list` · `scene open <name>` · `scene delete <name>` |
| **Camera** | `freecam` · `follow <player>` · `pov <player>` · `cam save/goto/list/delete <name>` |
| **Inspection** | `inspect player <p>` · `inspect chest` · `inventory <p>` · `container <x> <y> <z>` |
| **Analysis** | `route render <p\|all> [start] [end]` · `route cancel` · `route clear` · `route mode <mode>` · `heatmap <kind>` |
| **Snapshots** | `snapshot create/list/info/verify/delete <name>` |
| **Admin** | `verify <id>` · `reindex <id>` · `delete <id>` · `favorite <id>` · `storage` · `cleanup` |

</details>

ExtendedReplay is **GUI-first** — `/erp` opens the **Replay Control Center** (sessions,
favorites, live join, last session, recording control, jobs, storage, help) and every
core action works without typing another command.

Moderators in a replay session get the **replay hotbar**: previous/next event,
rewind/fast-forward (10 s, shift 60 s), a dynamic play/pause item, an always-visible
speed item (0.1x–64x plus single-tick stepping while paused), the player panel
(left-click follow, right-click exact POV, shift-left teleport, shift-right live
inventory inspection), a container browser, freecam and a menu item (shift-click leaves
the replay). A persistent **boss-bar HUD** shows session, time, speed, state and the
followed player; inventory and container views **update live** while the replay plays or
seeks.

The paginated **session browser** (filterable by favorites/live) shows the recording
server, world, duration, player count, size and a color-coded integrity state
(`EXACT` / `VERIFIED` / `DEGRADED` / `INCOMPLETE` / `UNKNOWN`); clicking a session opens
a **details panel** with play, live join, verify/reindex as cancellable **background
jobs**, favorite toggle and delete behind a confirmation dialog.

Playback worlds are strictly **read-only**: viewers take no damage, never hunger, can't
pick up items, break/place blocks, open containers directly or be targeted by mobs, and
deterministic gamerules stop all natural ticking — everything visible originates from the
arena baseline and the recorded packets.

---

## 🔌 API for other plugins

ExtendedReplay is fully standalone, but any plugin can integrate through the public API
(registered in Bukkit's `ServicesManager`). This is how e.g. **TheHungerGames** publishes
its match lifecycle — the dependency direction is always `YourPlugin → ExtendedReplay`,
never the other way around.

```java
RegisteredServiceProvider<ExtendedReplayApi> reg =
        Bukkit.getServicesManager().getRegistration(ExtendedReplayApi.class);
if (reg == null) return;                      // ExtendedReplay not installed — fine!
ExtendedReplayApi replay = reg.getProvider();

// start a recording session for your match
ReplaySessionHandle session = replay.startSession(
        ReplaySessionStartRequest.builder("hg-match-42", arenaWorld)
                .externalKey(match.getId())
                .bounds(ReplayBounds.cuboid(-200, 0, -200, 200, 256, 200))
                .metadata("gamemode", "hungergames")
                .build());

// record custom events — they show up in the event browser
session.recordEvent(ReplayEvent.builder("HG_SUPPLY_DROP_SPAWN", ReplayEventCategory.SUPPLY)
        .location(dropLocation)
        .metadata("loot-table", "supply_tier_2")
        .build());

session.createBookmark(ReplayBookmark.now("final-fight"));
session.end(ReplaySessionEndReason.COMPLETED);
```

---

## 📊 Project Status

| Component | State |
|-----------|-------|
| Public API (`extendedreplay-api`) | ✅ implemented, registered via ServicesManager |
| Binary protocol + replay model (`extendedreplay-core`) | ✅ implemented & unit-tested |
| Segment storage + SQLite index (`extendedreplay-storage`) | ✅ implemented & unit-tested |
| WebSocket transport + disk spooling (`extendedreplay-transport`) | ✅ implemented & integration-tested |
| Paper plugin: 20 Hz capture, playback, event browser, inspection, hotbar UI | ✅ implemented, boots & self-tests on Paper 1.21.10 |
| Playback keyframes (fast backward seeking) | ✅ implemented |
| Streaming live mirror with configurable delay (`/erp live`) | ✅ implemented |
| Arena snapshots — create/apply/verify, palette+zstd format (`/erp snapshot …`) | ✅ implemented & unit-tested |
| Route rendering (carpet/glass/concrete/particles) | ✅ implemented |
| Heatmaps — movement/kills/deaths/loot, log-scaled gradient | ✅ implemented |
| Camera slots & POV mode (`/erp cam …`, `/erp pov <player>`) | ✅ implemented |
| Retention & integrity — auto-cleanup, `/erp cleanup`, `/erp verify`, `/erp reindex` | ✅ implemented & unit-tested |
| Entity capture & playback — spawns/despawns + 20 Hz frames of mobs, items, projectiles | ✅ implemented (frozen real entities in playback & live mirror) |
| Minigame integration example (`extendedreplay-integration-example`) | ✅ compiles against the public API |

`/erp test` runs an end-to-end self-test on any REPLAY/STANDALONE server: it records a
synthetic session through the real pipeline into segment files, reads it back and
verifies every checksum.

**Honest caveats:** the storage, transport, protocol and snapshot layers are covered by
automated tests. The distributed pipeline (PRODUCER → WebSocket → REPLAY) has been
verified end-to-end on real infrastructure: two separate Paper 1.21.10 servers, a
console-driven recording with world activity, live segment streaming, clean session end
and a green `/erp verify` (checksums) afterwards. What has *not* been exercised yet is a
full multiplayer match with real players (player frames, PvP events, GUIs under load) —
that part is verified by compilation, server boot and the pipeline self-test only. POV
is an approximation (camera lock to the actor's eye line), not a client-side
first-person reproduction.

**Design principles** (non-negotiable):

- 🚫 The game server never blocks on replay work — no disk, network, JSON or compression
  on the main thread.
- 🚫 No fake features: what's marked implemented works end-to-end.
- ✅ Renderer, transport and storage are abstractions — nothing is hardwired to
  Mannequins, WebSockets or SQLite.
- ✅ Versioned protocol and file format from day one.

---

## 🛠️ Development

```bash
./gradlew build                          # build everything + run all tests
./gradlew test                           # tests only
./gradlew :extendedreplay-core:test      # tests of one module
```

The test suite covers the binary codec (roundtrips for every packet type), the recording
queue's drop policy, the full storage lifecycle (record → seal → verify → corrupt →
detect), and transport end-to-end tests including auth rejection and spool recovery
after a dead replay server comes back up.

---

<div align="center">

**ExtendedReplay** · built for [nak-lan.de](https://nak-lan.de)

</div>
