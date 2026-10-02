package io.github.doggylover314.hardcorechallenge.data;

import io.github.doggylover314.hardcorechallenge.core.Boss;
import io.github.doggylover314.hardcorechallenge.core.BossKill;
import io.github.doggylover314.hardcorechallenge.core.DeathRecord;
import io.github.doggylover314.hardcorechallenge.core.Outcome;
import io.github.doggylover314.hardcorechallenge.core.PlayerStats;
import io.github.doggylover314.hardcorechallenge.core.RunLog;
import io.github.doggylover314.hardcorechallenge.core.TimelineEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Converts runs to and from plain maps/lists, which are then written as YAML.
 */
public final class RunLogCodec {
    private RunLogCodec() {
    }

    public static Map<String, Object> encode(RunLog run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("run", run.runNumber());
        map.put("seed", run.seed());
        map.put("world", run.worldName());
        map.put("started-at", run.startedAt());
        if (run.replayOf() != null) {
            map.put("replay-of", run.replayOf());
        }
        if (run.customSeed()) {
            map.put("custom-seed", true);
        }
        map.put("outcome", run.outcome() == null ? "live" : run.outcome().name().toLowerCase(Locale.ROOT));
        map.put("ended-at", run.endedAt());
        map.put("duration-millis", run.durationMillis());
        if (run.reason() != null) {
            map.put("reason", run.reason());
        }

        Map<String, Object> participants = new LinkedHashMap<>();
        run.participants().forEach((id, name) -> participants.put(id.toString(), name));
        map.put("participants", participants);

        List<Map<String, Object>> kills = new ArrayList<>();
        for (BossKill kill : run.bossKills()) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("boss", kill.boss().id());
            k.put("elapsed-millis", kill.elapsedMillis());
            String finalHit = run.finalHit(kill.boss());
            if (finalHit != null) {
                k.put("final-hit", finalHit);
            }
            kills.add(k);
        }
        map.put("boss-kills", kills);

        if (run.death() != null) {
            DeathRecord death = run.death();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("uuid", death.playerId().toString());
            d.put("player", death.playerName());
            d.put("cause", death.cause());
            putIfPresent(d, "killer", death.killer());
            putIfPresent(d, "message", death.message());
            putIfPresent(d, "world", death.world());
            d.put("x", death.x());
            d.put("y", death.y());
            d.put("z", death.z());
            map.put("death", d);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        run.stats().forEach((id, s) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("mobs-killed", s.mobsKilled());
            m.put("damage-dealt", round(s.damageDealt()));
            m.put("boss-damage", round(s.bossDamage()));
            m.put("boss-kills", s.bossKills());
            m.put("distance-cm", s.distanceCm());
            m.put("time-played-millis", s.timePlayedMillis());
            stats.put(id.toString(), m);
        });
        map.put("stats", stats);

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (TimelineEvent event : run.timeline()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("elapsed-millis", event.elapsedMillis());
            e.put("at", event.at());
            e.put("type", event.type().name().toLowerCase(Locale.ROOT));
            putIfPresent(e, "player", event.player());
            putIfPresent(e, "detail", event.detail());
            timeline.add(e);
        }
        map.put("timeline", timeline);
        map.put("firsts", new ArrayList<>(run.firsts()));
        return map;
    }

    public static RunLog decode(Map<?, ?> map) {
        Object replay = map.get("replay-of");
        RunLog run = new RunLog(
                (int) asLong(map.get("run")),
                asLong(map.get("seed")),
                asString(map.get("world")),
                asLong(map.get("started-at")),
                replay == null ? null : (int) asLong(replay),
                Boolean.TRUE.equals(map.get("custom-seed")));

        if (map.get("participants") instanceof Map<?, ?> participants) {
            participants.forEach((id, name) -> run.addParticipant(UUID.fromString(id.toString()), String.valueOf(name)));
        }
        if (map.get("stats") instanceof Map<?, ?> stats) {
            stats.forEach((id, raw) -> {
                if (raw instanceof Map<?, ?> m) {
                    PlayerStats s = new PlayerStats(asString(m.get("name")));
                    s.restore((int) asLong(m.get("mobs-killed")), asDouble(m.get("damage-dealt")), asDouble(m.get("boss-damage")),
                            (int) asLong(m.get("boss-kills")), asLong(m.get("distance-cm")), asLong(m.get("time-played-millis")));
                    run.restoreStats(UUID.fromString(id.toString()), s);
                }
            });
        }
        if (map.get("boss-kills") instanceof List<?> kills) {
            for (Object raw : kills) {
                if (raw instanceof Map<?, ?> k) {
                    Boss.fromId(asString(k.get("boss"))).ifPresent(boss ->
                            run.restoreBossKill(new BossKill(boss, asLong(k.get("elapsed-millis"))), asString(k.get("final-hit"))));
                }
            }
        }
        if (map.get("timeline") instanceof List<?> timeline) {
            for (Object raw : timeline) {
                if (raw instanceof Map<?, ?> e) {
                    try {
                        TimelineEvent.Type type = TimelineEvent.Type.valueOf(asString(e.get("type")).toUpperCase(Locale.ROOT));
                        run.event(new TimelineEvent(asLong(e.get("elapsed-millis")), asLong(e.get("at")), type,
                                asString(e.get("player")), asString(e.get("detail"))));
                    } catch (IllegalArgumentException | NullPointerException ignored) {
                        // Unknown event type from a newer version; skip it.
                    }
                }
            }
        }
        if (map.get("firsts") instanceof List<?> firsts) {
            firsts.forEach(first -> run.restoreFirst(String.valueOf(first)));
        }

        String outcome = asString(map.get("outcome"));
        if (outcome != null && !outcome.equals("live")) {
            DeathRecord death = null;
            if (map.get("death") instanceof Map<?, ?> d) {
                death = new DeathRecord(UUID.fromString(asString(d.get("uuid"))), asString(d.get("player")),
                        asString(d.get("cause")), asString(d.get("killer")), asString(d.get("message")), asString(d.get("world")),
                        (int) asLong(d.get("x")), (int) asLong(d.get("y")), (int) asLong(d.get("z")));
            }
            run.finish(Outcome.valueOf(outcome.toUpperCase(Locale.ROOT)), asLong(map.get("ended-at")),
                    asLong(map.get("duration-millis")), death, asString(map.get("reason")));
        }
        return run;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
