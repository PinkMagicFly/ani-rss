package ani.rss.task;

import ani.rss.entity.Ani;
import ani.rss.util.other.AniUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RssTaskTests {
    private TimeZone originalTimeZone;

    @BeforeEach
    void setUp() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void shouldPrioritizeScheduleAndMarkSlotAfterSuccessfulCheck() {
        Ani ani = createScheduledAni();
        ani.setLastRssCheckTime(ts("2026-04-05 23:15:25"));

        long now = ts("2026-04-05 23:32:05");
        RssTask.TriggerDecision triggerDecision = RssTask.resolveTriggerDecision(ani, false, now);

        assertEquals(RssTask.TriggerType.SCHEDULE, triggerDecision.type());

        long successTime = ts("2026-04-05 23:32:08");
        RssTask.markCheckSuccess(ani, triggerDecision, successTime);

        assertEquals(successTime, ani.getLastRssCheckTime());
        assertEquals(ts("2026-04-05 23:32:00"), ani.getLastRssScheduleTriggerTime());
    }

    @Test
    void forceRefreshShouldNotConsumeScheduledSlot() {
        Ani ani = createScheduledAni();
        long now = ts("2026-04-05 23:32:05");

        RssTask.TriggerDecision triggerDecision = RssTask.resolveTriggerDecision(ani, true, now);
        assertEquals(RssTask.TriggerType.FORCE, triggerDecision.type());

        long successTime = ts("2026-04-05 23:32:06");
        RssTask.markCheckSuccess(ani, triggerDecision, successTime);

        assertEquals(successTime, ani.getLastRssCheckTime());
        assertEquals(0L, ani.getLastRssScheduleTriggerTime());
        assertTrue(RssTask.shouldRunBySchedule(ani, ts("2026-04-05 23:32:30")));
    }

    @Test
    void unresolvedScheduledCheckShouldRemainRunnableUntilMarkedSuccessful() {
        Ani ani = createScheduledAni();
        long now = ts("2026-04-05 23:32:05");

        RssTask.TriggerDecision triggerDecision = RssTask.resolveTriggerDecision(ani, false, now);
        assertEquals(RssTask.TriggerType.SCHEDULE, triggerDecision.type());

        assertTrue(RssTask.shouldRunBySchedule(ani, ts("2026-04-05 23:40:00")));
    }

    private Ani createScheduledAni() {
        return AniUtil.createAni()
                .setTitle("火喰鸟 羽州褴褛鸢组")
                .setCustomRssScheduleEnable(true)
                .setRssScheduleWeeks(List.of(1))
                .setRssScheduleTime("23:32")
                .setLastRssScheduleTriggerTime(0L);
    }

    private long ts(String value) {
        return cn.hutool.core.date.DateUtil.parse(value, "yyyy-MM-dd HH:mm:ss").getTime();
    }
}
