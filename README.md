# ExtendedReplay

**ExtendedReplay** is a server-side 3D replay, live mirror, playback, event timeline and analysis system for Minecraft Paper servers. It records the full state of bounded arena sessions and allows them to be replayed on a separate replay server with full moderator inspection and analysis capabilities.

**Unlike video recording or client-side ReplayMod clones, ExtendedReplay is a distributed server-side world-state recorder and playback system** — it reconstructs a true 3D world from precomputed arena snapshots and event-based deltas, enabling moderators to freely fly around, inspect inventories at any timestamp, render player routes, and analyze match events.

## Core Vision

```
hungergames.nak-lan.de (PRODUCER)
├─ Runs real game with TheHungerGames
├─ ExtendedReplay records 20 Hz player frames + events
└─ Streams deltas to replay server

replay.nak-lan.de (REPLAY)
├─ Receives live replay data
├─ Reconstructs arena from snapshot + events
├─ Renders players as Mannequins/NPCs
├─ Moderators freecam, inspect, analyze
└─ Route rendering, heatmaps, scene creation
```

ExtendedReplay is **independent from TheHungerGames** and works with any server/minigame, but TheHungerGames may optionally integrate via a public API.

## Key Features

### Recording (PRODUCER Mode)
- **20 Hz player frames** with position, rotation, pose, equipment, health
- **Event sourcing** — block changes, kills, deaths, inventory/container snapshots
- **Compact binary protocol** — no JSON in high-frequency data
- **Zero gameplay impact** — non-blocking queues, async storage, adaptive degradation
- **Arena bounds** — optional recording within cuboid/radius constraints
- **Inventory/container tracking** — dirty-state snapshots only on change
- **Custom events** — extensible plugin event support (e.g., supply drops, sponsor rewards)

### Playback & Live Mirror (REPLAY Mode)
- **Live mirror** — watch the current session with configurable 2-second delay
- **Playback controls** — play/pause/resume, jump to timestamp/event, rewind/forward
- **Playback speed** — 0.25x to 8x
- **Player rendering** — native Mannequin entities with fallback renderers (NPC, DisplayEntity)
- **Freecam/spectator** — moderators fly around and explore the arena
- **Hotbar GUI** — in-game moderator controls (play/pause, timeline, events, routes, speed)
- **Inventory/container inspection** — examine what was in a chest or player inventory at any timestamp

### Event Timeline & Analysis
- **Event browser** — categorized session events (kills, loot, supply drops, custom events, admin actions)
- **Smart bookmarking** — auto-bookmark first blood, final kill, deaths, suspicious events
- **Scene creation** — save replay segments with custom camera positions and notes
- **Route rendering** — async analysis jobs to visualize player paths (carpet, glass, concrete, particles)
- **Heatmaps** — movement, kills, deaths, loot interactions
- **Route modes** — full session, time window, before/after event, combat-only, last-60-seconds-before-death

### Snapshot Management
- Precomputed arena snapshots at match start
- Commands to create, verify, and manage snapshots
- Snapshots referenced by replay sessions (not re-serialized)
- Keyframes generated on replay server for efficient playback

### Storage & Integrity
- **Segment files** — 30-second compressed segments, checksummed, append-only during recording
- **Metadata database** — SQLite with sessions, players, events, bookmarks, indexes
- **Replay verification** — integrity checks, degraded range detection, repair commands
- **Retention policies** — max days, max GB, favorite persistence

## Architecture

### Modules

```
extendedreplay-api/           # Public extension API
extendedreplay-core/          # Core event sourcing, replay logic
extendedreplay-paper/         # Paper plugin entry point
extendedreplay-transport/     # WebSocket/TCP/Loopback transports
extendedreplay-storage/       # Segment files, metadata DB
extendedreplay-renderer/      # Player rendering abstraction (Mannequin/NPC/DisplayEntity)
extendedreplay-analysis/      # Route rendering, heatmaps
```

### Design Principles

- **Clean architecture** — clear interfaces, no tight coupling
- **Renderer abstraction** — not hardwired to Mannequins
- **Transport abstraction** — swap WebSocket for TCP, Redis, NATS later
- **Storage abstraction** — segment-based design allows different backends
- **Versioned protocol & format** — forward/backward compatibility path
- **Performance-first producer** — main-thread capture only copies primitives; async for everything else
- **No gameplay impact** — if replay fails, the game continues
- **No fake features** — marked features work end-to-end or don't exist

## Quick Start

### Requirements

- **Java 17+**
- **Paper 1.20.1+** (or compatible version)
- Maven 3.8+

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/Raindancer118/ExtendedReplay.git
   cd ExtendedReplay
   ```

2. **Build the plugin**
   ```bash
   mvn clean package
   ```

3. **Copy to your servers**
   ```bash
   # Producer (game server)
   cp extendedreplay-paper/target/extendedreplay-*.jar /path/to/game-server/plugins/

   # Replay (replay server)
   cp extendedreplay-paper/target/extendedreplay-*.jar /path/to/replay-server/plugins/
   ```

4. **Configure roles** — Edit `plugins/ExtendedReplay/config.yml` on each server:

   **Producer (hungergames.nak-lan.de)**
   ```yaml
   extendedreplay:
     server-role: PRODUCER
     producer:
       enabled: true
       player-frame-rate-hz: 20
       capture-inventories: true
       capture-containers: true
       capture-block-changes: true
     transport:
       type: WEBSOCKET
       host: replay.nak-lan.de
       port: 8787
       auth-token: "change-me"
       reconnect: true
   ```

   **Replay (replay.nak-lan.de)**
   ```yaml
   extendedreplay:
     server-role: REPLAY
     replay-server:
       bind-host: 0.0.0.0
       bind-port: 8787
     snapshots:
       path: plugins/ExtendedReplay/snapshots
       require-precomputed-snapshots: true
   ```

5. **Restart servers** and run `/erp status` to verify

## Commands

### Basic

```
/erp                    # Main help
/erp status            # Check recording/replay state
/erp gui               # Open moderator control GUI (REPLAY mode)
```

### Recording (Producer)

```
/erp record start <name>       # Start a new session
/erp record stop               # End the current session
/erp sessions                  # List all sessions
```

### Playback (Replay)

```
/erp play <sessionId>          # Play a session
/erp pause                     # Pause playback
/erp resume                    # Resume
/erp speed <0.25|0.5|1|2|4|8>  # Set playback speed
/erp time <timestamp>          # Jump to timestamp (ms)
/erp jump <timestamp>          # Jump (shorthand)
/erp rewind <seconds>          # Rewind N seconds
/erp forward <seconds>         # Forward N seconds
/erp live                      # Watch live mirror of current session
```

### Events & Analysis

```
/erp events                    # Open event browser
/erp event <eventId>           # Show event details
/erp jump event <eventId>      # Jump to event
/erp bookmark <name>           # Create bookmark at current timestamp
/erp bookmarks                 # List bookmarks
/erp scene create <name>       # Save a scene
/erp scene list                # List scenes
/erp route render <player>     # Start route rendering async job
/erp route clear               # Remove rendered routes
/erp route mode <mode>         # Set rendering mode (carpet, glass, concrete, particles, heatmap)
```

### Camera & Navigation

```
/erp freecam                   # Toggle freecam
/erp follow <player>           # Follow a player
/erp pov <player>              # First-person view approximation
/erp cam save <name>           # Save camera position
/erp cam goto <name>           # Load camera position
```

### Inspection

```
/erp inspect player <player>   # Show player stats
/erp inventory <player>        # Inspect player inventory at current timestamp
/erp container <x> <y> <z>     # Inspect container contents
```

### Snapshots

```
/erp snapshot create <name>    # Create a world snapshot
/erp snapshot list             # List snapshots
/erp snapshot verify <name>    # Verify snapshot integrity
```

### Admin

```
/erp verify <sessionId>        # Verify replay integrity
/erp delete <sessionId>        # Delete a session
/erp favorite <sessionId>      # Mark as favorite
/erp storage                   # Check storage usage
/erp cleanup                   # Run retention policy
```

## Permissions

```
extendedreplay.use                 # Access plugin
extendedreplay.moderator           # Record/playback control
extendedreplay.director            # Full playback access
extendedreplay.admin               # Admin commands
extendedreplay.inventory.view      # Inspect inventories
extendedreplay.container.view      # Inspect containers
extendedreplay.route.render        # Render routes
extendedreplay.snapshot            # Manage snapshots
```

## Integration: TheHungerGames

ExtendedReplay is **independent** — it does not depend on TheHungerGames. However, TheHungerGames can optionally publish events via the ExtendedReplay API:

```java
ExtendedReplayApi api = Bukkit.getServicesManager()
    .load(ExtendedReplayApi.class);

if (api != null) {
    ReplayEvent killEvent = new ReplayEvent(
        "HG_KILL",
        UUID.randomUUID(),
        Bukkit.getServer().getCurrentTick(),
        Map.of(
            "killer", killer.getName(),
            "victim", victim.getName(),
            "weapon", victim.getKiller().getInventory().getItemInMainHand().getType().toString()
        )
    );
    api.recordEvent(sessionId, killEvent);
}
```

See `extendedreplay-integration-example` for a complete adapter.

## Configuration Example

```yaml
extendedreplay:
  server-role: PRODUCER  # or REPLAY, STANDALONE, DISABLED

  commands:
    main-command: erp
    aliases:
      - extendedreplay
      - replayx

  producer:
    enabled: true
    player-frame-rate-hz: 20
    capture-inventories: true
    capture-containers: true
    capture-block-changes: true
    capture-entities: true
    capture-projectiles: true
    capture-chat: true
    capture-world-border: true
    capture-weather-time: true

  performance:
    main-thread-capture-target-ms: 0.25
    main-thread-capture-hard-budget-ms: 1.0
    adaptive-degradation: true
    max-queue-size: 100000
    drop-cosmetic-frames-under-pressure: true
    never-block-game-server: true

  transport:
    type: WEBSOCKET          # or TCP
    host: replay.nak-lan.de
    port: 8787
    auth-token: change-me
    reconnect: true
    batch-interval-ms: 50
    spool-on-disconnect: true
    max-spool-size-mb: 1024

  replay-server:
    bind-host: 0.0.0.0
    bind-port: 8787
    live-delay-seconds: 2
    allow-multiple-playback-sessions: true
    max-playback-sessions: 5

  storage:
    path: plugins/ExtendedReplay/replays
    metadata-database: sqlite
    compression: zstd
    segment-length-seconds: 30
    max-days: 30
    max-gb: 100
    keep-favorites: true

  snapshots:
    path: plugins/ExtendedReplay/snapshots
    require-precomputed-snapshots: true
    keyframe-interval-seconds: 300
    create-keyframes-on-replay-server: true

  renderer:
    preferred-player-renderer: MANNEQUIN
    fallback-player-renderer: NPC
    show-nametags: true
    show-health: true
    show-held-items: true
    show-armor: true

  analysis:
    route-rendering: true
    heatmaps: true
    max-block-changes-per-tick: 1000
    default-route-mode: CARPET
    render-in-analysis-world: true
    restore-original-blocks: true
```

## Development

### Building

```bash
mvn clean package -DskipTests
```

### Testing Locally

```bash
# Start local single-server mode (STANDALONE)
# 1. Create snapshot of your test arena
#    /erp snapshot create test-arena

# 2. Start recording
#    /erp record start test-match

# 3. Move players around, open chests, kill mobs
# 4. Stop recording
#    /erp record stop

# 5. Play back the session
#    /erp play <session-id>

# 6. Test commands
#    /erp events
#    /erp inventory <player>
#    /erp route render <player>
#    /erp verify <session-id>
```

### Acceptance Criteria

The implementation is production-ready when this works end-to-end:

1. ✅ Install on producer and replay servers, configure roles
2. ✅ Create snapshot
3. ✅ Start session, move players, change blocks, open chests, kill player, end session
4. ✅ Join replay server, open sessions list, play session
5. ✅ See player Mannequins with correct skins/equipment
6. ✅ Jump to events (start, kill, end) via `/erp events`
7. ✅ Inspect player inventory and chest at any timestamp
8. ✅ Render player routes
9. ✅ Verify replay integrity
10. ✅ Game server did not block on replay operations

## Performance Guarantees

- **Main-thread capture** — target <0.25ms, hard budget 1.0ms per tick
- **Never blocks gameplay** — queue, network, storage, compression all async
- **Zero JSON in 20 Hz frames** — compact binary protocol
- **Adaptive degradation** — drops cosmetic data under load, preserves critical events
- **Configurable frame rate** — default 20 Hz, adjustable
- **Producer spool** — queues data locally if replay server disconnects

## Non-Goals (Phase 1)

- Video rendering / `.mcpr` export
- Web dashboard
- Full anti-cheat integration
- Perfect client POV reproduction
- Dependency on TheHungerGames

## Roadmap

### Phase 1: Plugin Skeleton & Config ✅
### Phase 2: Local Recording MVP
### Phase 3: Producer/Replay Transport
### Phase 4: Live Mirror World
### Phase 5: Events & GUI
### Phase 6: Inventories & Containers
### Phase 7: Block/Entity Deltas
### Phase 8: Persistent Playback
### Phase 9: Route Rendering
### Phase 10: Polish & Reliability

## License

TODO — Add appropriate license

## Contributing

Contributions welcome! Please ensure:
- Code follows existing style conventions
- Features are complete and tested (no stubs/TODOs)
- Documentation is updated
- Commits follow conventional style

## Support & Issues

Report bugs and feature requests on [GitHub Issues](https://github.com/Raindancer118/ExtendedReplay/issues).

---

**ExtendedReplay** — Redefining Minecraft replay analysis. 🎮
