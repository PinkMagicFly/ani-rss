package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.LocalFileRecord;
import ani.rss.entity.TorrentsInfo;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 延时清理本地文件
 */
@Slf4j
@Component
public class FileCleanupTask implements BaseTask {
    private static final int SLEEP_MINUTES = 60;
    private static final long AVOID_RSS_COLLISION_MILLIS = TimeUnit.MINUTES.toMillis(5);

    @Resource
    private DownloadService downloadService;

    @Override
    public void accept(java.util.concurrent.atomic.AtomicBoolean loop) {
        if (!TorrentUtil.login()) {
            ThreadUtil.sleep(getLoopSleepMillis());
            return;
        }

        try {
            List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();
            boolean sync = false;
            for (Ani ani : AniUtil.ANI_LIST) {
                if (!loop.get()) {
                    return;
                }
                if (!Boolean.TRUE.equals(ani.getAutoDeleteLocalFilesEnable())) {
                    continue;
                }

                Integer keepHours = ani.getAutoDeleteLocalFilesHours();
                if (keepHours == null || keepHours <= 0) {
                    continue;
                }

                String downloadPath = downloadService.getDownloadPath(ani);
                File downloadDir = new File(downloadPath);
                List<LocalFileRecord> records = Optional.ofNullable(ani.getAutoDeleteLocalFileRecords())
                        .orElseGet(ArrayList::new);
                ani.setAutoDeleteLocalFileRecords(records);
                if (!downloadDir.exists() && records.isEmpty()) {
                    continue;
                }

                Set<String> activePaths = new HashSet<>();
                torrentsInfos.stream()
                        .filter(torrentsInfo -> Objects.equals(downloadPath, torrentsInfo.getDownloadDir()))
                        .forEach(torrentsInfo -> {
                            List<String> names = Optional.ofNullable(torrentsInfo.getFiles())
                                    .map(files -> files.get())
                                    .orElse(List.of());
                            for (String name : names) {
                                if (StrUtil.isBlank(name)) {
                                    continue;
                                }
                                activePaths.add(FileUtils.getAbsolutePath(downloadPath + "/" + name));
                            }
                        });

                long expireTime = System.currentTimeMillis() - keepHours * 60L * 60 * 1000;
                Set<String> deletedPaths = new HashSet<>();
                if (downloadDir.exists()) {
                    List<File> files = FileUtil.loopFiles(downloadDir, file -> file.isFile());
                    for (File file : files) {
                        String path = FileUtils.getAbsolutePath(file);
                        if (!isSubPath(downloadPath, path)) {
                            continue;
                        }
                        if (activePaths.contains(path)) {
                            continue;
                        }
                        long lastModified = file.lastModified();
                        if (lastModified > 0 && lastModified > expireTime) {
                            continue;
                        }
                        if (!isAutoDeletePrimaryFile(file)) {
                            continue;
                        }
                        log.info("订阅 {} 已到自动删除时间, 删除文件 {}", ani.getTitle(), file);
                        deleteEpisodeFiles(file, activePaths, deletedPaths);
                        sync = true;
                    }
                }

                for (LocalFileRecord record : new ArrayList<>(records)) {
                    String path = record.getPath();
                    if (StrUtil.isBlank(path)) {
                        records.remove(record);
                        sync = true;
                        continue;
                    }
                    path = FileUtils.getAbsolutePath(path);
                    if (!isSubPath(downloadPath, path)) {
                        // 超出当前订阅目录的记录不再继续追踪
                        records.remove(record);
                        sync = true;
                        continue;
                    }
                    Long trackedAt = record.getTrackedAt();
                    if (trackedAt == null || trackedAt > expireTime) {
                        continue;
                    }
                    if (activePaths.contains(path)) {
                        continue;
                    }
                    if (deletedPaths.contains(path)) {
                        records.remove(record);
                        sync = true;
                        continue;
                    }

                    File file = new File(path);
                    if (!file.exists()) {
                        records.remove(record);
                        sync = true;
                        continue;
                    }
                    if (!file.isFile()) {
                        records.remove(record);
                        sync = true;
                        continue;
                    }
                    String name = file.getName();
                    if (!FileUtils.isVideoFormat(name) && !FileUtils.isSubtitleFormat(name)) {
                        records.remove(record);
                        sync = true;
                        continue;
                    }
                    long lastModified = file.lastModified();
                    if (lastModified > 0 && lastModified > expireTime) {
                        continue;
                    }

                    log.info("订阅 {} 已到自动删除时间, 删除文件 {}", ani.getTitle(), file);
                    deleteEpisodeFiles(file, activePaths, deletedPaths);
                    records.remove(record);
                    sync = true;
                }
            }
            if (sync) {
                AniUtil.sync();
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        }

        ThreadUtil.sleep(getLoopSleepMillis());
    }

    private boolean isSubPath(String parent, String child) {
        String normalizedParent = FileUtils.getAbsolutePath(parent);
        String normalizedChild = FileUtils.getAbsolutePath(child);
        return normalizedChild.equals(normalizedParent) || normalizedChild.startsWith(normalizedParent + "/");
    }

    private boolean isAutoDeletePrimaryFile(File file) {
        return FileUtils.isVideoFormat(file.getName());
    }

    private void deleteEpisodeFiles(File primaryFile, Set<String> activePaths, Set<String> deletedPaths) {
        File dir = primaryFile.getParentFile();
        if (Objects.isNull(dir) || !dir.exists() || !dir.isDirectory()) {
            deleteFile(primaryFile, deletedPaths);
            return;
        }

        String mainName = FileUtil.mainName(primaryFile);
        for (File file : FileUtils.listFiles(dir)) {
            if (!file.isFile()) {
                continue;
            }
            String path = FileUtils.getAbsolutePath(file);
            if (activePaths.contains(path)) {
                continue;
            }
            if (!isSameEpisodeFile(mainName, file)) {
                continue;
            }
            deleteFile(file, deletedPaths);
        }
    }

    private boolean isSameEpisodeFile(String mainName, File file) {
        String name = file.getName();
        if (FileUtils.isVideoFormat(name) || FileUtils.isSubtitleFormat(name)) {
            return Objects.equals(FileUtil.mainName(name), mainName);
        }
        String extName = FileUtil.extName(name).toLowerCase();
        if ("nfo".equals(extName) || "bif".equals(extName)) {
            return Objects.equals(FileUtil.mainName(name), mainName);
        }
        return name.startsWith(mainName + "-thumb.");
    }

    private void deleteFile(File file, Set<String> deletedPaths) {
        String path = FileUtils.getAbsolutePath(file);
        log.info("删除文件 {}", file);
        FileUtil.del(file);
        deletedPaths.add(path);
    }

    private long getLoopSleepMillis() {
        long now = System.currentTimeMillis();
        long nextCleanupTime = DateUtil.date(now)
                .offset(DateField.HOUR_OF_DAY, 1)
                .setField(DateField.MINUTE, 0)
                .setField(DateField.SECOND, 0)
                .setField(DateField.MILLISECOND, 0)
                .getTime();

        Config config = ConfigUtil.CONFIG;
        long rssDelay = RssTask.getLoopSleepMillis(config);
        long rssWakeTime = now + rssDelay;
        if (Math.abs(nextCleanupTime - rssWakeTime) < TimeUnit.MINUTES.toMillis(1)) {
            nextCleanupTime += AVOID_RSS_COLLISION_MILLIS;
        }
        return Math.max(1_000L, nextCleanupTime - now);
    }
}
