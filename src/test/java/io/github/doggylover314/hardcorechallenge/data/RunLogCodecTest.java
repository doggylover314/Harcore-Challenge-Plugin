package io.github.doggylover314.hardcorechallenge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doggylover314.hardcorechallenge.core.Boss;
import io.github.doggylover314.hardcorechallenge.core.DeathRecord;
import io.github.doggylover314.hardcorechallenge.core.Outcome;
import io.github.doggylover314.hardcorechallenge.core.RunLog;
import io.github.doggylover314.hardcorechallenge.core.TimelineEvent;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class RunLogCodecTest {
    private final UUID steve = UUID.randomUUID();

    private RunLog finishedRun() {
        RunLog run = new RunLog(7, -123456789L, "hcc_run_7", 1_700_000_000_000L, 4, false);
        run.addParticipant(steve, "Steve");
        run.stats(steve, "Steve").addDamage(120.5, true);
        run.stats(steve, "Steve").addMobKill();
        run.stats(steve, "Steve").addDistance(123_456);
        run.stats(steve, "Steve").addTimePlayed(60_000);
        run.first("dim:the_nether");
        run.event(new TimelineEvent(5_000, 1_700_000_005_000L, TimelineEvent.Type.DIMENSION_FIRST, "Steve", "the_nether"));
        run.bossKilled(Boss.WITHER, 30_000, steve, "Steve");
        DeathRecord death = new DeathRecord(steve, "Steve", "lava", null, "Steve tried to swim in lava", "hcc_run_7", 1, 2, 3);
        run.finish(Outcome.DEATH, 1_700_000_090_000L, 90_000, death, null);
        return run;
    }

    @Test
    void roundTripThroughYaml() {
        RunLog original = finishedRun();
        String text = new Yaml().dump(RunLogCodec.encode(original));
        RunLog copy = RunLogCodec.decode((Map<?, ?>) new Yaml().load(text));

        assertEquals(7, copy.runNumber());
        assertEquals(-123456789L, copy.seed());
        assertEquals(4, copy.replayOf());
        assertFalse(copy.customSeed());
        assertEquals(Outcome.DEATH, copy.outcome());
        assertEquals(90_000, copy.durationMillis());
        assertEquals("lava", copy.death().cause());
        assertNull(copy.death().killer());
        assertEquals("Steve", copy.participants().get(steve));
        assertEquals("Steve", copy.finalHit(Boss.WITHER));
        assertEquals(30_000, copy.bossKills().getFirst().elapsedMillis());
        assertEquals(1, copy.stats().get(steve).mobsKilled());
        assertEquals(120.5, copy.stats().get(steve).bossDamage(), 1e-9);
        assertEquals(1, copy.stats().get(steve).bossKills());
        assertEquals(123_456, copy.stats().get(steve).distanceCm());
        assertEquals(60_000, copy.stats().get(steve).timePlayedMillis());
        assertEquals(1, copy.timeline().size());
        assertEquals(TimelineEvent.Type.DIMENSION_FIRST, copy.timeline().getFirst().type());
        assertTrue(copy.hasFirst("dim:the_nether"));
    }

    @Test
    void liveRunStaysLive() {
        RunLog live = new RunLog(8, 1L, "hcc_run_8", 0L, null, true);
        RunLog copy = RunLogCodec.decode(RunLogCodec.encode(live));
        assertTrue(copy.isLive());
        assertTrue(copy.customSeed());
        assertNull(copy.replayOf());
    }

    @Test
    void unknownTimelineEntriesAreSkipped() {
        Map<String, Object> map = RunLogCodec.encode(finishedRun());
        ((java.util.List<Map<String, Object>>) map.get("timeline")).getFirst().put("type", "from_the_future");
        assertEquals(0, RunLogCodec.decode(map).timeline().size());
    }
}
