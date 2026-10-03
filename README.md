# HardcoreChallenge

Shared-fate hardcore for Paper 26.2. One person dies, everyone starts over in a new world.
Kill the Ender Dragon, Wither, Elder Guardian and Warden in one run to win.

## Features

- Instant in-process reset to a new random seed, no server restart
- Old run worlds are unloaded and deleted (or archived)
- Boss checklist on the sidebar and boss bar
- Victory sequence with fireworks
- Disconnecting never ends a run; the run timer pauses while no participant is online
- Every run is saved forever with stats, boss final hits and a timeline
- Replay any past run's seed, or start on a seed of your choice
- Works behind Velocity

## Requirements

- Paper 26.2
- Java 25

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

## Commands

| Command | Description |
| --- | --- |
| `/hcc start` | Start a new run |
| `/hcc start replay <run>` | Start a new run on a past run's seed |
| `/hcc start seed <seed>` | Start a new run on a specific seed |
| `/hcc reset [reason]` | End the current run and start a new one |
| `/hcc stop` | End the run, everyone spectates |
| `/hcc status` | Current run, time, bosses, players |
| `/hcc runs [page]` | All runs, newest first |
| `/hcc runs wins\|deaths\|resets\|stopped` | Filter by outcome |
| `/hcc runs player <name>` | Runs a player was in |
| `/hcc run <number>` | Details for one run |
| `/hcc run <number> timeline` | What happened, in order |
| `/hcc run <number> delete` | Remove a run from the list |
| `/hcc reload` | Reload the config |

`/hcc history` works as an alias for `/hcc runs`. Lines in the runs list are clickable.

## Permissions

- `hardcorechallenge.admin` (op): everything
- `hardcorechallenge.play` (everyone): be in the challenge and use `/hcc status`

## What gets saved per run

- Seed, outcome, duration, start date
- Participants
- Bosses in the order they died, plus who landed the final hit
- The death that ended it (who, what killed them, where)
- Per player: boss damage share, boss kills, mobs killed, damage dealt, distance, time played
- Timeline: joins and leaves, first into the Nether and End, first iron, diamonds, blaze rod
  and eye of ender, first stronghold, monument and ancient city, boss fights starting, boss kills, death

Runs are stored in `plugins/HardcoreChallenge/runs/`, one file per run.

## Config

See `config.yml`. All of it can be reloaded with `/hcc reload`. Main options:

- `auto-reset-on-death`: reset when someone dies, or just take them out of the run
- `reset-countdown-seconds`: countdown before the new world
- `reset-on-death-of`: `participants` or `anyone`
- `keep-old-worlds`: how many old run folders to archive (0 deletes them)
- `bosses`: which bosses count
- `victory`: what happens when the run is won
- `webhook-url`: optional POST on run start and end (Discord webhooks work)
- `messages`: every message, in MiniMessage format

## Notes

- Runs are always on hard difficulty with the hardcore flag set.
- Run worlds are named `hcc_run_<n>`, `hcc_run_<n>_nether` and `hcc_run_<n>_the_end`.
  The server's main world is never touched.
- New worlds skip vanilla's spawn point search, because it freezes the whole server for several
  seconds. The plugin picks dry land near 0,0 instead, without blocking the server.
- How fast a reset is depends on how fast the server can generate a new world.
  More chunk worker threads (`chunk-system.worker-threads` in `paper-global.yml`) help.
