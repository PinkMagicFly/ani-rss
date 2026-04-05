package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RSS
 */
@Slf4j
@Component
public class RssTask implements BaseTask {
    public static final AtomicBoolean download = new AtomicBoolean(false);
    private static final Object SLEEP_MONITOR = new Object();

    enum TriggerType {
        NONE,
        FORCE,
        SCHEDULE,
        INTERVAL
    }

    record TriggerDecision(TriggerType type, long scheduleSlotTime) {
        boolean shouldRun() {
            return type != TriggerType.NONE;
        }
    }

    public static void download(AtomicBoolean loop) {
        download(loop, false);
    }

    public static void download(AtomicBoolean loop, boolean force) {
        DownloadService downloadService = SpringUtil.getBean(DownloadService.class);
        boolean sync = false;

        try {
            if (!TorrentUtil.login()) {
                return;
            }
            for (Ani ani : AniUtil.ANI_LIST) {
                if (!loop.get()) {
                    return;
                }

                if (!AniUtil.ANI_LIST.contains(ani)) {
                    continue;
                }

                String title = ani.getTitle();
                Boolean enable = ani.getEnable();
                if (!enable) {
                    log.debug("{} 未启用", title);
                    continue;
                }
                long now = System.currentTimeMillis();
                TriggerDecision triggerDecision = resolveTriggerDecision(ani, force, now);
                if (!triggerDecision.shouldRun()) {
                    log.debug("{} 未达到RSS触发条件", title);
                    continue;
                }
                try {
                    logTriggerStart(ani, triggerDecision);
                    DownloadService.DownloadAniResult result = downloadService.downloadAni(ani);
                    markCheckSuccess(ani, triggerDecision, System.currentTimeMillis());
                    sync = true;
                    logTriggerResult(ani, triggerDecision, result);
                } catch (Exception e) {
                    String message = ExceptionUtils.getMessage(e);
                    log.error("{} RSS检查失败 {}", title, message);
                    log.error(message, e);
                }
                // 避免短时间频繁请求导致流控
                ThreadUtil.sleep(500);
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        } finally {
            if (sync) {
                AniUtil.sync();
            }
            download.set(false);
        }
    }

    public static boolean shouldCheckNow(Ani ani) {
        return resolveTriggerDecision(ani, false, System.currentTimeMillis()).shouldRun();
    }

    public static boolean shouldRunByGlobalInterval(Ani ani) {
        return shouldRunByGlobalInterval(ani, System.currentTimeMillis());
    }

    static boolean shouldRunByGlobalInterval(Ani ani, long now) {
        Config config = ConfigUtil.CONFIG;
        Long lastRssCheckTime = ani.getLastRssCheckTime();
        if (lastRssCheckTime == null || lastRssCheckTime <= 0) {
            return true;
        }
        long nextCheckTime = lastRssCheckTime + Math.max(1, config.getRssSleepMinutes()) * 60_000L;
        return now >= nextCheckTime;
    }

    public static boolean shouldRunBySchedule(Ani ani) {
        return shouldRunBySchedule(ani, System.currentTimeMillis());
    }

    static boolean shouldRunBySchedule(Ani ani, long now) {
        TriggerDecision triggerDecision = resolveTriggerDecision(ani, false, now);
        return triggerDecision.type() == TriggerType.SCHEDULE;
    }

    static TriggerDecision resolveTriggerDecision(Ani ani, boolean force, long now) {
        if (force) {
            return new TriggerDecision(TriggerType.FORCE, -1L);
        }
        long slotTime = currentSlotTime(now, ani);
        if (slotTime > 0 && shouldRunByScheduleWindow(ani, now, slotTime)) {
            return new TriggerDecision(TriggerType.SCHEDULE, slotTime);
        }
        if (shouldRunByGlobalInterval(ani, now)) {
            return new TriggerDecision(TriggerType.INTERVAL, -1L);
        }
        return new TriggerDecision(TriggerType.NONE, -1L);
    }

    static void markCheckSuccess(Ani ani, TriggerDecision triggerDecision, long now) {
        ani.setLastRssCheckTime(now);
        if (triggerDecision.type() == TriggerType.SCHEDULE && triggerDecision.scheduleSlotTime() > 0) {
            ani.setLastRssScheduleTriggerTime(triggerDecision.scheduleSlotTime());
        }
    }

    private static boolean shouldRunByScheduleWindow(Ani ani, long now, long slotTime) {
        if (!Boolean.TRUE.equals(ani.getCustomRssScheduleEnable())) {
            return false;
        }
        List<Integer> weeks = ani.getRssScheduleWeeks();
        if (weeks == null || weeks.isEmpty()) {
            return false;
        }
        int dayOfWeek = DateUtil.dayOfWeek(new Date(now));
        if (!weeks.contains(dayOfWeek)) {
            return false;
        }
        if (now < slotTime) {
            return false;
        }
        Long lastTriggerTime = ani.getLastRssScheduleTriggerTime();
        return lastTriggerTime == null || lastTriggerTime < slotTime;
    }

    private static long currentSlotTime(long now, Ani ani) {
        if (!Boolean.TRUE.equals(ani.getCustomRssScheduleEnable())) {
            return -1;
        }
        List<Integer> weeks = ani.getRssScheduleWeeks();
        if (weeks == null || weeks.isEmpty()) {
            return -1;
        }
        String scheduleTime = ani.getRssScheduleTime();
        if (StrUtil.isBlank(scheduleTime)) {
            return -1;
        }
        String[] hm = scheduleTime.split(":");
        if (hm.length != 2) {
            return -1;
        }
        int hour;
        int minute;
        try {
            hour = Integer.parseInt(hm[0]);
            minute = Integer.parseInt(hm[1]);
        } catch (Exception e) {
            return -1;
        }
        return DateUtil.date(now)
                .setField(DateField.HOUR_OF_DAY, hour)
                .setField(DateField.MINUTE, minute)
                .setField(DateField.SECOND, 0)
                .setField(DateField.MILLISECOND, 0)
                .getTime();
    }

    private static void logTriggerStart(Ani ani, TriggerDecision triggerDecision) {
        String title = ani.getTitle();
        if (triggerDecision.type() == TriggerType.SCHEDULE) {
            log.info("{} RSS计划触发，开始检查", title);
            return;
        }
        if (triggerDecision.type() == TriggerType.FORCE) {
            log.info("{} 手动刷新触发，开始检查", title);
        }
    }

    private static void logTriggerResult(Ani ani, TriggerDecision triggerDecision, DownloadService.DownloadAniResult result) {
        String title = ani.getTitle();
        if (triggerDecision.type() == TriggerType.SCHEDULE) {
            log.info("{} RSS计划检查完成，识别到 {} 条，新增下载 {} 条", title, result.itemCount(), result.addedCount());
            return;
        }
        if (triggerDecision.type() == TriggerType.FORCE) {
            log.info("{} 手动刷新完成，识别到 {} 条，新增下载 {} 条", title, result.itemCount(), result.addedCount());
        }
    }

    public static void sync() {
        synchronized (download) {
            if (download.get()) {
                throw new RuntimeException("存在未完成任务，请等待...");
            }
            download.set(true);
        }
    }

    public static void wakeUp() {
        synchronized (SLEEP_MONITOR) {
            SLEEP_MONITOR.notifyAll();
        }
    }

    @Override
    public void accept(AtomicBoolean loop) {
        Config config = ConfigUtil.CONFIG;
        if (!config.getRss()) {
            log.debug("rss未启用");
            sleep(getLoopSleepMillis(config));
            return;
        }

        try {
            sync();
            download(loop);
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        }
        sleep(getLoopSleepMillis(config));
    }

    public static long getLoopSleepMillis(Config config) {
        long globalSleepMillis = TimeUnit.MINUTES.toMillis(Math.max(1, config.getRssSleepMinutes()));
        long now = System.currentTimeMillis();
        long nearestScheduleDelay = AniUtil.ANI_LIST.stream()
                .filter(Objects::nonNull)
                .filter(ani -> Boolean.TRUE.equals(ani.getEnable()))
                .mapToLong(ani -> nextScheduleDelayMillis(ani, now))
                .filter(delay -> delay >= 0)
                .min()
                .orElse(Long.MAX_VALUE);
        if (nearestScheduleDelay == Long.MAX_VALUE) {
            return globalSleepMillis;
        }
        return Math.max(1_000L, Math.min(globalSleepMillis, nearestScheduleDelay));
    }

    private static long nextScheduleDelayMillis(Ani ani, long now) {
        if (!Boolean.TRUE.equals(ani.getCustomRssScheduleEnable())) {
            return -1;
        }
        List<Integer> weeks = ani.getRssScheduleWeeks();
        String scheduleTime = ani.getRssScheduleTime();
        if (weeks == null || weeks.isEmpty() || StrUtil.isBlank(scheduleTime)) {
            return -1;
        }
        String[] hm = scheduleTime.split(":");
        if (hm.length != 2) {
            return -1;
        }
        int hour;
        int minute;
        try {
            hour = Integer.parseInt(hm[0]);
            minute = Integer.parseInt(hm[1]);
        } catch (Exception e) {
            return -1;
        }

        Date baseDate = new Date(now);
        for (int offset = 0; offset < 7; offset++) {
            Date candidateDate = DateUtil.offsetDay(baseDate, offset);
            int candidateWeek = DateUtil.dayOfWeek(candidateDate);
            if (!weeks.contains(candidateWeek)) {
                continue;
            }
            Date slot = DateUtil.date(candidateDate)
                    .setField(DateField.HOUR_OF_DAY, hour)
                    .setField(DateField.MINUTE, minute)
                    .setField(DateField.SECOND, 0)
                    .setField(DateField.MILLISECOND, 0);
            long delay = slot.getTime() - now;
            if (delay >= 0) {
                return delay;
            }
        }
        return -1;
    }

    private static void sleep(long sleepMillis) {
        synchronized (SLEEP_MONITOR) {
            try {
                SLEEP_MONITOR.wait(Math.max(1_000L, sleepMillis));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
