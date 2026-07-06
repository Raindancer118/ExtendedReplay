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
├── extendedreplay-api        Public API (ExtendedReplayApi via Bukkit ServicesManager)
├── extendedreplay-core       Replay model, binary protocol, queues, metrics — pure Java
├── extendedreplay-storage    zstd segment files + SQLite metadata index
├── extendedreplay-transport  WebSocket client/server, batching, spooling, loopback
└── extendedreplay-paper      The actual Paper plugin: capture, playback, GUI, commands
```

The dependency direction is strict: `paper → {api, core, storage, transport}` and
`{storage, transport} → core`. Core knows nothing about Bukkit.

### Recording model — event sourcing

A replay session is **not** a series of world dumps. It consists of:

1. a reference to a **precomputed arena snapshot** (the world as it was at match start),
2. a **20 Hz player frame stream** (compact primitives — no ItemStacks, no NBT, no JSON),
3. **event-based deltas**: block changes, inventory/container snapshots (only when their
   content hash actually changed), equipment changes (versioned, referenced from frames),
   entity/projectile/item events,
4. a **timeline of discrete events** (kills, deaths, chat, custom events, bookmarks),
5. replay-server-generated **keyframes** for fast seeking.

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
/erp record start match-42
... play the match ...
/erp record stop

# on the replay server
/erp sessions              # list stored sessions
/erp play <sessionId>      # open a playback session
/erp events                # browse & jump to kills, chests, custom events
/erp inventory <player>    # inspect an inventory at the current replay time
/erp route render <player> # draw a player's path
```

---

## 🎮 Commands

`/erp` (aliases: `/extendedreplay`, `/replayx`)

<details>
<summary><b>Full command reference</b></summary>

| Group | Commands |
|-------|----------|
| **General** | `/erp` · `gui` · `help` · `status` · `test` |
| **Recording** | `record start <name>` · `record stop` · `sessions` · `session <id>` |
| **Playback** | `play <id>` · `pause` · `resume` · `speed <0.25–8>` · `jump <time>` · `rewind <s>` · `forward <s>` · `close` |
| **Live** | `live` |
| **Events** | `events` · `event <id>` · `jump event <id>` · `bookmark <name>` · `bookmarks` |
| **Scenes** | `scene create <name>` · `scene list` · `scene open <name>` · `scene delete <name>` |
| **Camera** | `freecam` · `follow <player>` · `pov <player>` · `cam save/goto/list/delete <name>` |
| **Inspection** | `inspect player <p>` · `inspect chest` · `inventory <p>` · `container <x> <y> <z>` |
| **Analysis** | `route render <p\|all> [start] [end]` · `route cancel` · `route clear` · `route mode <mode>` · `heatmap <kind>` |
| **Snapshots** | `snapshot create/list/info/verify/delete <name>` |
| **Admin** | `verify <id>` · `reindex <id>` · `delete <id>` · `favorite <id>` · `storage` · `cleanup` |

</details>

Moderators in a replay session also get a **hotbar control UI** — play/pause, timeline,
event browser, player follow, camera, routes, inspection, speed and exit on slots 1–9.

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
| Route rendering (carpet/glass/concrete/particles) | ✅ implemented |
| Live view | ⚠️ trailing playback of the live session (`/erp live`); true streaming mirror in development |
| Arena snapshots (`/erp snapshot …`) | 🚧 in development |
| Heatmaps | 🚧 in development |

`/erp test` runs an end-to-end self-test on any REPLAY/STANDALONE server: it records a
synthetic session through the real pipeline into segment files, reads it back and
verifies every checksum.

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
