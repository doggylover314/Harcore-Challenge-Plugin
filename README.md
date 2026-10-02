# HardcoreChallenge

A Paper plugin for shared-fate hardcore runs. A group plays vanilla hardcore together, and the
moment **any** participant dies the run ends: everyone gets a short countdown, then all of them are
alive again in a brand new world with a new random seed. Each run is scored against four bosses
(Ender Dragon, Wither, Elder Guardian, Warden). Kill all of them and the run is won.

Everything happens inside the running server. The plugin never restarts the server, shells out, or
edits `server.properties`, so players connected through a Velocity proxy stay connected through
every reset.

- Minecraft / Paper **26.2**, Java **25**
- Paper plugin (`paper-plugin.yml` with a bootstrapper); `/hcc` is a Brigadier command with tab completion
- All text is MiniMessage and configurable

## Building

Requirements: a JDK 17 or newer to run Gradle. The build compiles with a Java 25 toolchain; if no
JDK 25 is installed, Gradle downloads one (via the foojay toolchain resolver).

```sh
./gradlew build
```

The plugin jar is `build/libs/HardcoreChallenge-<version>.jar`. `./gradlew test` runs the unit
tests on their own.

## Installing

1. Stop the server, copy `HardcoreChallenge-<version>.jar` into `plugins/`, start the server.
2. Edit `plugins/HardcoreChallenge/config.yml` if you want different settings, then `/hcc reload`.
3. Add participants with `/hcc participants add <player>` (or skip this: `/hcc start` adds everyone
   online who has `hardcorechallenge.play` if the list is empty).
4. `/hcc start`.

Server settings worth knowing:

- The server's own `level-name` world is never touched. Use it as a holding area; players who
  join between runs stay wherever they are until a run starts.
- `hardcore=true` in `server.properties` is not required. The plugin enforces one life itself.
- Behind Velocity, nothing special is needed. The plugin does not assume a joining player is at world
  spawn; it moves them to the right place when they join.
- Reset speed depends on how fast the server can generate the first chunks of a new world, which
  runs on Paper's chunk worker threads. See [Performance](#performance).

## How a run works

1. **Start.** `/hcc start` creates `hcc_run_<n>`, `hcc_run_<n>_nether` and `hcc_run_<n>_the_end`
   with a fresh random seed. Participants are wiped (inventory, ender chest, XP, advancements,
   effects, health, hunger, respawn point), teleported to spawn and put in survival.
2. **Death.** When a participant dies in a run world (or anyone, with `reset-on-death-of: anyone`),
   the death is cancelled before the hardcore death screen appears. Chat announces who died, the
   vanilla death message, the coordinates and how long the run lasted. The run is recorded in history.
3. **Reset.** Everyone goes into spectator and a title countdown starts. The next world is created
   **during** the countdown. When both are done, everyone is teleported to the new spawn and
   wiped, and survivors are set to survival. The old run's three worlds are then unloaded and their
   folders deleted on a background thread.
4. **Bosses.** Boss deaths in the run's worlds are credited to the run, not to a player. The checklist
   is shown on a sidebar and a boss bar, and each kill gets a title and a sound.
5. **Victory.** When every configured boss has died, the run is won: a victory title, a chat summary
   with the kill order, a sound and fireworks at each participant. Everyone spectates for
   `victory.freeze-seconds`, then `victory.action` runs: `stop` keeps the world open for
   sightseeing, and `reset` starts the next run. A victory and a death in the same tick count as a victory.

### Players who aren't in the run

- **Non-participants** who join mid-run are put in spectator and sent to the run world.
- **Participants who were offline during a reset** (with `offline-death-grace: true`) are wiped and
  moved into the new world when they return. With `offline-death-grace: false`, a participant
  logging out mid-run forfeits it, which ends the run like a death.
- With `auto-reset-on-death: false`, a participant who dies is out for the rest of the run and
  spectates. The run continues until an admin uses `/hcc reset` or `/hcc stop`.

### Portals

Nether and End portals always stay inside the current run: overworld ⇄ `_nether` (with the normal
8:1 scale), overworld/nether → `_the_end` (arrival on the usual obsidian platform at 100, 48, 0), and
back out of the End to the run's overworld (the player's bed if it is in the run, otherwise spawn).
The plugin sets these destinations itself instead of relying on the server to link custom worlds.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/hcc start` | admin | Begin a new run in a fresh world (boss progress reset). Refused while a run is active. |
| `/hcc reset [reason]` | admin | End the current run now (recorded as a reset) and start the next one. |
| `/hcc stop` | admin | End the run; everyone goes into spectator. The world is kept until the next `/hcc start`. |
| `/hcc status` | play | Run number, phase, elapsed time, seed, boss checklist, participants. |
| `/hcc participants add <player>` | admin | Add a participant (online, or offline but known to the server). Added mid-run, they join the run immediately. |
| `/hcc participants remove <player>` | admin | Remove a participant (they spectate for the rest of the run). |
| `/hcc participants list` | admin | List participants: green online, grey offline, struck-through out of the run. |
| `/hcc history [n]` | admin | The last `n` runs (default 5, max 50): seed, duration, bosses in kill order, who died to what and where. |
| `/hcc reload` | admin | Reload `config.yml`. Every setting is hot-reloadable. |

`/hcc` with no arguments shows help to admins and status to players.

## Permissions

| Permission | Default | Grants |
| --- | --- | --- |
| `hardcorechallenge.admin` | op | Every subcommand (includes `hardcorechallenge.play`). |
| `hardcorechallenge.play` | true | Being auto-added on `/hcc start`, and `/hcc status`. |

## Configuration

`plugins/HardcoreChallenge/config.yml`, reloadable with `/hcc reload`:

```yaml
auto-reset-on-death: true
reset-countdown-seconds: 10
reset-on-death-of: participants     # participants | anyone
offline-death-grace: true           # a player who logs out does not stall the run
difficulty: hard
keep-old-worlds: 0                  # how many past run folders to archive
bosses: [ender_dragon, wither, elder_guardian, warden]
announce: { chat: true, title: true, bossbar: true, sound: true }
webhook-url: ""                     # optional POST on run start/end
victory:
  enabled: true
  require-all-bosses: true          # false = victory on any single boss
  action: stop                      # stop | reset
  freeze-seconds: 30                # spectator celebration before action runs
  fireworks: true
```

Notes:

- **difficulty**: with `hard`, the run worlds also get the vanilla hardcore flag. Paper documents
  that hardcore worlds are locked to hard difficulty, so for any other difficulty the worlds are
  created without that flag. The one-life rule applies either way.
- **keep-old-worlds**: `0` deletes old run folders. `N > 0` moves them to
  `plugins/HardcoreChallenge/archive/hcc_run_<n>/` and keeps only the newest `N` runs there.
- **webhook-url**: receives JSON on `run_start` and `run_end` (outcome, duration, seed, boss kills,
  death details). The body also has a `content` field, so a Discord webhook URL works directly.
- **sounds** and **messages**: every sound key and message (chat, titles, sidebar, boss bar, status,
  history) is configurable. Messages are [MiniMessage](https://docs.papermc.io/adventure/minimessage/format);
  the default file lists the placeholders each one supports.

## Data files

All in `plugins/HardcoreChallenge/`:

| File | Contents |
| --- | --- |
| `state.yml` | Current run (phase, number, world, seed, elapsed time, boss kills in order), world folder paths, participants, who is out, and which run each participant last synced to. |
| `history.yml` | Finished runs (newest 500): outcome, seed, duration, boss kill order, the fatal death. |
| `pending-deletions.yml` | Old world folders that could not be deleted yet; retried on the next start. |

A restart mid-run resumes the run: its worlds are loaded again, boss progress is kept, and the
clock continues (server downtime is not counted). A restart during a reset finishes the reset on
startup. Files are written on a background thread via a temp file and an atomic rename.

## World cleanup safety

Every deletion goes through one guard, and a folder is deleted only if:

- its name is `hcc_run_<n>`, `hcc_run_<n>_nether` or `hcc_run_<n>_the_end`, and
- it is not, and does not contain, a loaded world, the current run's world, the server's main
  level, the world container or the plugin's archive.

Worlds are unloaded with `Bukkit.unloadWorld(world, false)` on the main thread. Folders are removed
on a separate thread after a short delay, with retries. Anything that still can't be removed is
recorded in `pending-deletions.yml` and retried at the next start. A folder is queued before its
deletion begins, so a shutdown halfway through a deletion is also retried. New runs never reuse a
run number whose folders are still queued.

In Paper 26.x, plugin-created worlds are stored under the main world, e.g.
`world/dimensions/minecraft/hcc_run_3`. The plugin always uses the path Paper reports
(`World#getWorldPath`) and never guesses it.

## Performance

Creating a world with `Bukkit.createWorld` normally includes vanilla's spawn-point search, which
generates chunks on the main thread and can stall the server for several seconds. To avoid that,
this plugin:

- creates the three dimensions on consecutive ticks, each with a forced spawn position, so creation
  itself takes well under a second of main-thread time;
- chooses the real spawn afterwards: it samples biome noise for the nearest land (a few ms), then
  generates only those candidate chunks with `getChunkAtAsync`, on Paper's chunk worker threads;
- runs this during the countdown, so a reset takes roughly
  `max(reset-countdown-seconds, time to generate the new world's first chunks)`.

A brand new world's first chunk is expensive, and the cost is CPU-bound. More chunk workers help:
see `chunk-system.worker-threads` in `config/paper-global.yml` (Paper chooses a default from the
core count).

## Development

- `src/main/java/.../core`: the run state machine and value types. No Bukkit imports; this is what the
  unit tests cover (`src/test/java`).
- `ChallengeManager`: turns events and commands into state machine transitions and carries out the
  side effects.
- `world/`: world creation, spawn finding, unloading, deletion and archiving.
- `listener/`: deaths, boss kills, joins/quits, portals.
