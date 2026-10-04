# HardcoreChallenge

Shared-fate hardcore for Paper 26.2. One person dies, everyone starts over in a new world.
Kill the Ender Dragon, Wither, Elder Guardian and Warden in one run to win.

## Features

- Instant in-process reset to a new random seed, no server restart
- Runs start in the morning with clear weather
- Run ends on the first death, or when a share of players is dead (`reset-when`)
- Old run worlds are unloaded and deleted (or archived)
- Boss checklist on the sidebar and boss bar
- Victory sequence with fireworks
- Disconnecting never ends a run; the run timer pauses while no participant is online
- Every run is saved forever with stats, boss final hits and a timeline
- Replay any past run's seed, or start on a seed of your choice
- Preload a list of seeds for the next runs: play each one once, or cycle through them
- Works behind Velocity

## Requirements

- Paper 26.2
- Java 25

## Download

Grab [`dist/HardcoreChallenge-1.1.0.jar`](dist/HardcoreChallenge-1.1.0.jar), or build it yourself.

## Building

```
./gradlew build
```

The jar ends up in `build/libs/`. Gradle downloads JDK 25 if you don't have it.

## Installing

1. Drop the jar in `plugins/` and restart the server.
2. Run `/hcc start`.

## Who's playing

- Everyone online when you run `/hcc start` is in the challenge.
- Anyone who joins while a challenge is going is added and dropped into the current run.
- Players without the `hardcorechallenge.play` permission only spectate.
- Spectator-only account: negate `hardcorechallenge.play` for it with a permissions plugin
  such as LuckPerms. It can still use `/hcc status` and the run browsing commands.

## Commands

| Command | Who | Description |
| --- | --- | --- |
| `/hcc` | everyone | Help |
| `/hcc start` | admin | Start a new run |
| `/hcc start replay <run>` | admin | Start a new run on a past run's seed |
| `/hcc start seed <seed>` | admin | Start a new run on a specific seed |
| `/hcc reset [reason]` | admin | End the current run and start a new one |
| `/hcc stop` | admin | End the run, everyone spectates |
| `/hcc status` | everyone | Current run, time, bosses, players |
| `/hcc runs [page]` | everyone | All runs, newest first |
| `/hcc runs <wins\|deaths\|resets\|stopped> [page]` | everyone | Filter by outcome |
| `/hcc runs player <name> [page]` | everyone | Runs a player was in |
| `/hcc run <number>` | everyone | Details for one run |
| `/hcc run <number> timeline [page]` | everyone | What happened, in order |
| `/hcc run <number> delete` | admin | Remove a run from the list (asks to confirm) |
| `/hcc resetwhen <first-death\|N%>` | admin | Change when deaths end the run |
| `/hcc seeds` | admin | Show the seed list and which seed is next |
| `/hcc seeds add <seed>` | admin | Add a seed to the end of the list |
| `/hcc seeds remove <number>` | admin | Remove a seed by its number in the list |
| `/hcc seeds clear` | admin | Empty the list |
| `/hcc seeds mode <once\|cycle>` | admin | Play each seed once (default), or cycle through the list |
| `/hcc revive <player>` | admin | Bring back a player who is out |
| `/hcc reload` | admin | Reload the config |

`/hcc history` works as an alias for `/hcc runs`. Lines in the runs list are clickable.

## Permissions

- `hardcorechallenge.admin` (op): everything
- `hardcorechallenge.play` (everyone): be in the challenge; `/hcc status` and run browsing need no permission

## What gets saved per run

- Seed, outcome, duration, start date
- Participants
- Bosses in the order they died, plus who landed the final hit
- The death that ended it (who, the death message, where)
- Per player: boss damage share, boss kills, mobs killed, damage dealt, distance, time played
- Timeline: joins and leaves, first into the Nether and End, first iron, diamonds, blaze rod
  and eye of ender, first stronghold, monument and ancient city, boss fights starting, boss kills, death

Runs are stored in `plugins/HardcoreChallenge/runs/`, one file per run.

## Config

See `config.yml`. All of it can be reloaded with `/hcc reload`. Main options:

- `reset-when`: `first-death`, or a percentage like `50%`. A dead player is out and spectates; the
  run ends when that share of everyone who has been in the run is dead (rounded up, at least one)
- `reset-countdown-seconds`: countdown before the new world
- `seed-list`: seeds to use for the next runs instead of random ones. `mode: once` uses each seed one
  time, then goes back to random seeds; `mode: cycle` starts over at the end. `/hcc start seed` and
  `/hcc start replay` ignore the list
- `spawn-protection-seconds`: no damage for this long after entering a run (0 = off)
- `announce`: chat, title, bossbar and sound toggles
- `sounds`: sound for each event
- `keep-old-worlds`: how many old run folders to archive (0 deletes them)
- `bosses`: which bosses count
- `victory`: whether bosses win the run, and what happens after
- `messages`: every message, in MiniMessage format

## Notes

- Runs are always on hard difficulty with the hardcore flag set.
- Run worlds are named `hcc_run_<n>`, `hcc_run_<n>_nether` and `hcc_run_<n>_the_end`.
  The server's main world is never touched.
- New worlds skip vanilla's spawn point search, because it freezes the whole server for several
  seconds. The plugin picks dry land near 0,0 instead, without blocking the server.
- How fast a reset is depends on how fast the server can generate a new world.
  More chunk worker threads (`chunk-system.worker-threads` in `paper-global.yml`) help.
