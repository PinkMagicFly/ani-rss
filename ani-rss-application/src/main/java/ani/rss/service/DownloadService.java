package ani.rss.service;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.PinyinUtils;
import ani.rss.entity.*;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.StringEnum;
import ani.rss.enums.TorrentsTags;
import ani.rss.util.other.*;
import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.lang.func.Func1;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import wushuo.tmdb.api.entity.Tmdb;

import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 下载的主要逻辑
 */
@Slf4j
@Service
public class DownloadService {
    private static final Object LOCK = new Object();
    private static final UploadPauseGate UPLOAD_PAUSE_GATE = new UploadPauseGate();
    private static final Set<String> COMPLETED_NOTIFICATION_HASHES = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final ExecutorService COMPLETED_TORRENT_EXECUTOR = ExecutorBuilder.create()
            .setCorePoolSize(2)
            .setMaxPoolSize(4)
            .setWorkQueue(new LinkedBlockingQueue<>(256))
            .build();
    private static final ExecutorService UPLOAD_NOTIFICATION_EXECUTOR = ExecutorBuilder.create()
            .setCorePoolSize(1)
            .setMaxPoolSize(1)
            .setWorkQueue(new LinkedBlockingQueue<>(256))
            .build();

    public record DownloadAniResult(int itemCount, int addedCount) {
    }

    private record CompletedNotificationContext(TorrentsInfo torrentsInfo, Ani ani, String text) {
    }

    @Resource
    private ScrapeService scrapeService;

    @PostConstruct
    public void restorePausedSeedingTorrentsOnStartup() {
        UPLOAD_PAUSE_GATE.resumeIfIdle();
    }

    /**
     * 下载动漫
     *
     * @param ani
     */
    @Synchronized("LOCK")
    public DownloadAniResult downloadAni(Ani ani) {
        Config config = ConfigUtil.CONFIG;
        Boolean autoDisabled = config.getAutoDisabled();

        String title = ani.getTitle();
        Integer season = ani.getSeason();

        List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();

        long count = torrentsInfos
                .stream()
                .filter(it -> {
                    TorrentsInfo.State state = it.getState();
                    if (Objects.isNull(state)) {
                        return true;
                    }
                    // 未下载完成
                    return !List.of(
                            TorrentsInfo.State.queuedUP.name(),
                            TorrentsInfo.State.uploading.name(),
                            TorrentsInfo.State.stalledUP.name(),
                            TorrentsInfo.State.pausedUP.name(),
                            TorrentsInfo.State.stoppedUP.name()
                    ).contains(state.name());
                })
                .count();

        String savePath = getDownloadPath(ani);

        List<Item> mainItems;
        try {
            mainItems = ItemsUtil.getItems(ani);
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error("{} 主RSS检查失败 {}", title, message);
            log.error(message, e);
            mainItems = new ArrayList<>();
        }
        boolean subgroupChanged = refreshAniSubgroup(ani, mainItems);
        ItemsUtil.omit(ani, mainItems);
        log.debug("{} 主RSS共 {} 个", title, mainItems.size());

        List<Item> checkedItems = new ArrayList<>(mainItems);
        SourceDownloadResult sourceResult = downloadSource(ani, mainItems, torrentsInfos, count, savePath);
        count = sourceResult.count();

        boolean sync = sourceResult.sync();
        int addedCount = sourceResult.addedCount();
        int currentDownloadCount = sourceResult.currentDownloadCount();

        if (addedCount == 0 && config.getStandbyRss()) {
            List<StandbyRss> standbyRssList = ani.getStandbyRssList();
            if (Objects.nonNull(standbyRssList)) {
                for (StandbyRss standbyRss : standbyRssList) {
                    ThreadUtil.sleep(1000);
                    try {
                        List<Item> standbyItems = ItemsUtil.getStandbyItems(ani, standbyRss);
                        checkedItems.addAll(standbyItems);
                        log.debug("{} 备用RSS {} 共 {} 个", title, StrUtil.blankToDefault(standbyRss.getLabel(), "未知字幕组"), standbyItems.size());
                        sourceResult = downloadSource(ani, standbyItems, torrentsInfos, count, savePath);
                        count = sourceResult.count();
                        sync = sync || sourceResult.sync();
                        addedCount += sourceResult.addedCount();
                        currentDownloadCount += sourceResult.currentDownloadCount();
                        subgroupChanged = refreshAniSubgroup(ani, standbyItems) || subgroupChanged;
                        if (sourceResult.addedCount() > 0) {
                            break;
                        }
                    } catch (Exception e) {
                        String message = ExceptionUtils.getMessage(e);
                        log.error("{} 备用RSS检查失败 {}", title, message);
                        log.error(message, e);
                    }
                }
            }
        }

        if (sync) {
            int size = ItemsUtil.currentEpisodeNumber(ani, checkedItems);
            // 更新当前集数
            ani.setCurrentEpisodeNumber(size);
            // 更新下载时间
            ani.setLastDownloadTime(System.currentTimeMillis());
            AniUtil.sync();
        } else if (subgroupChanged) {
            AniUtil.sync();
        }

        DownloadAniResult result = new DownloadAniResult(checkedItems.size(), addedCount);

        if (!autoDisabled) {
            return result;
        }
        Integer totalEpisodeNumber = ani.getTotalEpisodeNumber();
        if (totalEpisodeNumber < 1) {
            return result;
        }
        if (currentDownloadCount >= totalEpisodeNumber) {
            log.info("{} 第 {} 季 共 {} 集 已全部下载完成, 自动停止订阅", title, season, totalEpisodeNumber);
            NotificationUtil.send(config, ani, StrFormatter.format("{} 订阅已完结", title), NotificationStatusEnum.COMPLETED);
            ani.setEnable(false);
            AniUtil.sync();
        }
        return result;
    }

    private record SourceDownloadResult(long count, int currentDownloadCount, int addedCount, boolean sync) {
    }

    private SourceDownloadResult downloadSource(Ani ani,
                                                List<Item> items,
                                                List<TorrentsInfo> torrentsInfos,
                                                long count,
                                                String savePath) {
        Config config = ConfigUtil.CONFIG;
        Integer downloadCount = config.getDownloadCount();
        Integer delayedDownload = config.getDelayedDownload();
        Boolean downloadNew = ani.getDownloadNew();
        List<Double> notDownload = ani.getNotDownload();

        ItemsUtil.procrastinating(ani, items);

        boolean sync = false;
        int addedCount = 0;
        int currentDownloadCount = 0;

        for (Item item : items) {
            log.debug(JSONUtil.formatJsonStr(GsonStatic.toJson(item)));
            String reName = item.getReName();
            File torrent = TorrentUtil.getTorrent(ani, item);
            Boolean master = item.getMaster();
            String hash = FileUtil.mainName(torrent)
                    .trim().toLowerCase();

            Double episode = item.getEpisode();
            boolean is5 = ItemsUtil.is5(episode);

            if (torrent.exists()) {
                if (master && !is5) {
                    currentDownloadCount++;
                }
                continue;
            }

            if (notDownload.contains(episode)) {
                if (master && !is5) {
                    currentDownloadCount++;
                }
                log.debug("已被禁止下载: {}", reName);
                continue;
            }

            if (downloadNew) {
                Item newItem = items.get(items.size() - 1);

                Date pubDate = item.getPubDate();
                Date newPubDate = newItem.getPubDate();
                if (Objects.nonNull(pubDate) && Objects.nonNull(newPubDate)) {
                    String pubDateFormat = DateUtil.format(pubDate, "yyyy-MM-dd");
                    String newPubDateFormat = DateUtil.format(newPubDate, "yyyy-MM-dd");
                    if (!pubDateFormat.equals(newPubDateFormat)) {
                        if (master && !is5) {
                            currentDownloadCount++;
                        }
                        continue;
                    }
                } else if (item != newItem) {
                    if (master && !is5) {
                        currentDownloadCount++;
                    }
                    continue;
                }
            }

            Date pubDate = item.getPubDate();
            if (Objects.nonNull(pubDate) && delayedDownload > 0) {
                Date now = DateUtil.offset(new Date(), DateField.MINUTE, -delayedDownload);
                if (now.getTime() < pubDate.getTime()) {
                    log.info("延迟下载 {}", reName);
                    continue;
                }
            }

            if (torrentsInfos
                    .stream()
                    .anyMatch(torrentsInfo -> torrentsInfo.getHash().equals(hash))) {
                if (master && !is5) {
                    currentDownloadCount++;
                }
                continue;
            }

            if (itemDownloaded(ani, item, true)) {
                if (master && !is5) {
                    currentDownloadCount++;
                }
                continue;
            }

            if (downloadCount > 0 && count >= downloadCount) {
                log.debug("达到同时下载数量限制 {}", downloadCount);
                continue;
            }

            File saveTorrent = TorrentUtil.saveTorrent(ani, item);

            if (!saveTorrent.exists()) {
                continue;
            }

            String itemSubgroup = StrUtil.blankToDefault(item.getSubgroup(), "");
            if (StrUtil.isNotBlank(itemSubgroup) && !"未知字幕组".equals(itemSubgroup)) {
                ani.setSubgroup(itemSubgroup);
            }

            if (!AniUtil.ANI_LIST.contains(ani)) {
                return new SourceDownloadResult(count, currentDownloadCount, addedCount, sync);
            }

            sync = true;

            if (download(ani, item, savePath, saveTorrent)) {
                addedCount++;

                if (master && !is5) {
                    currentDownloadCount++;
                }
                count++;
            }
        }

        return new SourceDownloadResult(count, currentDownloadCount, addedCount, sync);
    }

    /**
     * 删除备用rss
     *
     * @param ani
     * @param item
     */
    public void deleteStandbyRss(Ani ani, Item item) {
        Config config = ConfigUtil.CONFIG;
        Boolean standbyRss = config.getStandbyRss();
        Boolean coexist = config.getCoexist();
        Boolean delete = config.getDelete();
        String reName = item.getReName();

        if (!delete) {
            return;
        }

        if (!standbyRss) {
            return;
        }

        if (coexist) {
            // 开启多字幕组共存将不会进行洗版
            return;
        }

        if (!ReUtil.contains(StringEnum.SEASON_REG, reName)) {
            return;
        }
        reName = ReUtil.get(StringEnum.SEASON_REG, reName, 0);

        String downloadPath = getDownloadPath(ani);

        List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();

        String finalReName = reName;
        TorrentsInfo standbyRSS = torrentsInfos
                .stream()
                .filter(torrentsInfo -> {
                    String downloadDir = torrentsInfo.getDownloadDir();
                    if (!downloadDir.equals(downloadPath)) {
                        return false;
                    }
                    if (!ReUtil.contains(StringEnum.SEASON_REG, torrentsInfo.getName())) {
                        return false;
                    }
                    String s = ReUtil.get(StringEnum.SEASON_REG, torrentsInfo.getName(), 0);
                    return s.equals(finalReName);
                })
                .findFirst()
                .orElse(null);
        if (Objects.nonNull(standbyRSS)) {
            TorrentUtil.delete(standbyRSS, true, true);
        }

        File[] files = FileUtils.listFiles(downloadPath);
        for (File file : files) {
            String fileMainName = FileUtil.mainName(file);
            if (StrUtil.isBlank(fileMainName)) {
                continue;
            }
            if (!ReUtil.contains(StringEnum.SEASON_REG, fileMainName)) {
                continue;
            }
            fileMainName = ReUtil.get(StringEnum.SEASON_REG, fileMainName, 0);
            if (!fileMainName.equals(reName)) {
                continue;
            }
            boolean isDel = false;
            // 文件在删除前先判断其格式
            if (file.isFile()) {
                String extName = FileUtil.extName(file);
                // 没有后缀 跳过
                if (StrUtil.isBlank(extName)) {
                    continue;
                }
                if (FileUtils.isVideoFormat(extName)) {
                    isDel = true;
                }
                if (List.of("nfo", "bif").contains(extName)) {
                    isDel = true;
                }
                if (file.getName().endsWith("-thumb.jpg")) {
                    isDel = true;
                }
            }
            if (file.isDirectory()) {
                isDel = true;
            }
            if (isDel) {
                log.info("已开启备用RSS, 自动删除 {}", FileUtils.getAbsolutePath(file));
                try {
                    FileUtil.del(file);
                    log.info("删除成功 {}", FileUtils.getAbsolutePath(file));
                } catch (Exception e) {
                    log.error("删除失败 {}", FileUtils.getAbsolutePath(file));
                    log.error(e.getMessage(), e);
                }
            }
        }
    }

    private boolean refreshAniSubgroup(Ani ani, List<Item> items) {
        String currentSubgroup = StrUtil.blankToDefault(ani.getSubgroup(), "");
        if (StrUtil.isNotBlank(currentSubgroup) && !"未知字幕组".equals(currentSubgroup)) {
            return false;
        }

        String subgroup = items.stream()
                .filter(item -> !Objects.equals(Boolean.FALSE, item.getMaster()))
                .map(Item::getSubgroup)
                .filter(StrUtil::isNotBlank)
                .filter(it -> !"未知字幕组".equals(it))
                .findFirst()
                .orElseGet(() -> items.stream()
                        .map(Item::getSubgroup)
                        .filter(StrUtil::isNotBlank)
                        .filter(it -> !"未知字幕组".equals(it))
                        .findFirst()
                        .orElse(""));

        if (StrUtil.isBlank(subgroup)) {
            return false;
        }

        if (Objects.equals(ani.getSubgroup(), subgroup)) {
            return false;
        }

        ani.setSubgroup(subgroup);
        return true;
    }

    /**
     * 下载
     *
     * @param ani
     * @param item
     * @param savePath
     * @param torrentFile
     */
    public synchronized boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        ani = ObjectUtil.clone(ani);

        String name = item.getReName();
        Boolean ova = ani.getOva();
        Boolean master = item.getMaster();
        String subgroup = item.getSubgroup();
        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");
        ani.setSubgroup(subgroup);

        log.info("添加下载 {}", name);

        if (!torrentFile.exists()) {
            log.error("种子下载出现问题 {} {}", name, FileUtils.getAbsolutePath(torrentFile));
            return false;
        }
        ThreadUtil.sleep(1000);
        savePath = FileUtils.getAbsolutePath(savePath);

        String text = StrFormatter.format("{} 已更新", name);
        if (!master) {
            text = StrFormatter.format("(备用RSS) {}", text);
        }
        NotificationUtil.send(ConfigUtil.CONFIG, ani, text, NotificationStatusEnum.DOWNLOAD_START);

        Config config = ConfigUtil.CONFIG;

        Integer downloadRetry = config.getDownloadRetry();
        for (int i = 1; i <= downloadRetry; i++) {
            try {
                if (TorrentUtil.DOWNLOAD.download(ani, item, savePath, torrentFile, ova)) {
                    return true;
                }
            } catch (Exception e) {
                String message = ExceptionUtils.getMessage(e);
                log.error(message, e);
            }
            log.error("{} 下载失败将进行重试, 当前重试次数为{}次", name, i);
        }

        // 删除下载失败的种子, 下次轮询仍会重试
        FileUtil.del(torrentFile);

        log.error("{} 添加失败，疑似为坏种", name);
        NotificationUtil.send(ConfigUtil.CONFIG, ani,
                StrFormatter.format("{} 添加失败，疑似为坏种", name),
                NotificationStatusEnum.ERROR);
        return false;
    }

    /**
     * 下载完成通知
     *
     * @param torrentsInfo
     */
    public synchronized void dispatchNotification(TorrentsInfo torrentsInfo) {
        dispatchNotificationInternal(torrentsInfo, false);
    }

    public synchronized void retryNotification(TorrentsInfo torrentsInfo) {
        dispatchNotificationInternal(torrentsInfo, true);
    }

    private void dispatchNotificationInternal(TorrentsInfo torrentsInfo, boolean force) {
        TorrentsInfo.State state = torrentsInfo.getState();

        if (Objects.isNull(state)) {
            return;
        }
        if (!List.of(
                TorrentsInfo.State.queuedUP.name(),
                TorrentsInfo.State.uploading.name(),
                TorrentsInfo.State.stalledUP.name(),
                TorrentsInfo.State.pausedUP.name(),
                TorrentsInfo.State.stoppedUP.name()
        ).contains(state.name())) {
            return;
        }

        List<String> tags = torrentsInfo.getTags();
        if (!force && tags.contains(TorrentsTags.DOWNLOAD_COMPLETE.getValue())) {
            return;
        }

        if (!tags.contains(TorrentsTags.DOWNLOAD_COMPLETE.getValue())) {
            Boolean b = TorrentUtil.addTags(torrentsInfo, TorrentsTags.DOWNLOAD_COMPLETE.getValue());
            if (!b) {
                return;
            }
        }

        String hash = torrentsInfo.getHash();
        if (StrUtil.isBlank(hash) || !COMPLETED_NOTIFICATION_HASHES.add(hash)) {
            return;
        }

        COMPLETED_TORRENT_EXECUTOR.execute(() -> handleCompletedTorrent(torrentsInfo));
    }

    private void handleCompletedTorrent(TorrentsInfo torrentsInfo) {
        try {
            Optional<CompletedNotificationContext> contextOpt = prepareCompletedNotification(torrentsInfo);
            if (contextOpt.isPresent()) {
                queueUploadNotification(contextOpt.get());
                return;
            }
            recoverCompletedNotification(torrentsInfo);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            recoverCompletedNotification(torrentsInfo);
        }
    }

    private Optional<CompletedNotificationContext> prepareCompletedNotification(TorrentsInfo torrentsInfo) {
        String name = torrentsInfo.getName();
        List<String> tags = torrentsInfo.getTags();
        Optional<Ani> aniOpt = findAniByDownloadPath(torrentsInfo);

        if (aniOpt.isEmpty()) {
            log.debug("未能获取番剧对象: {}", torrentsInfo.getName());
            return Optional.empty();
        }

        Ani ani = ObjectUtil.clone(aniOpt.get());
        Ani aniRef = findAniRefById(ani.getId()).orElse(null);
        if (Objects.nonNull(aniRef)) {
            trackLocalFiles(aniRef, torrentsInfo);
        }

        // 根据标签反向判断出字幕组
        String subgroup = ani.getSubgroup();
        Set<String> collect = Optional.ofNullable(ani.getStandbyRssList())
                .orElse(List.of())
                .stream()
                .map(StandbyRss::getLabel)
                .collect(Collectors.toSet());

        subgroup = tags
                .stream()
                .filter(collect::contains)
                .findFirst()
                .orElse(subgroup);
        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");
        ani.setSubgroup(subgroup);

        Config config = ConfigUtil.CONFIG;
        Boolean scrape = config.getScrape();
        if (scrape) {
            try {
                // 刮削
                scrapeService.scrape(ani, false);
            } catch (Exception e) {
                log.error("刮削失败: {}", ani.getTitle());
                log.error(e.getMessage(), e);
            }
        }

        try {
            SpringUtil.getBean(StrmService.class).writeLocalStrm(ani, torrentsInfo);
        } catch (Exception e) {
            log.error("STRM 生成失败: {}", ani.getTitle());
            log.error(e.getMessage(), e);
        }

        String text = StrFormatter.format("{} 下载完成", name);
        if (tags.contains(TorrentsTags.BACK_RSS.getValue())) {
            text = StrFormatter.format("(备用RSS) {}", text);
        }
        return Optional.of(new CompletedNotificationContext(torrentsInfo, ani, text));
    }

    private void queueUploadNotification(CompletedNotificationContext context) {
        UPLOAD_PAUSE_GATE.enqueue();
        UPLOAD_NOTIFICATION_EXECUTOR.execute(() -> notification(context));
    }

    private void notification(CompletedNotificationContext context) {
        TorrentsInfo torrentsInfo = context.torrentsInfo();
        Ani ani = context.ani();
        String text = context.text();
        boolean success;

        try {
            success = NotificationUtil.sendAndWait(ConfigUtil.CONFIG, ani, text, NotificationStatusEnum.DOWNLOAD_END);
        } finally {
            endUploadWindow();
        }

        if (!success) {
            recoverCompletedNotification(torrentsInfo);
            return;
        }

        String title = ani.getTitle();

        try {
            AniUtil.completed(ani);
        } catch (Exception e) {
            log.error("番剧完结迁移失败 {}", title);
            log.error(e.getMessage(), e);
        }

        if (Boolean.TRUE.equals(ConfigUtil.CONFIG.getDeleteStandbyRSSOnly())) {
            releaseCompletedNotificationHash(torrentsInfo);
            return;
        }
        try {
            TorrentUtil.delete(torrentsInfo);
        } finally {
            releaseCompletedNotificationHash(torrentsInfo);
        }
    }

    private void releaseCompletedNotificationHash(TorrentsInfo torrentsInfo) {
        String hash = torrentsInfo.getHash();
        if (StrUtil.isBlank(hash)) {
            return;
        }
        COMPLETED_NOTIFICATION_HASHES.remove(hash);
    }

    private void recoverCompletedNotification(TorrentsInfo torrentsInfo) {
        try {
            TorrentUtil.removeTags(torrentsInfo, TorrentsTags.DOWNLOAD_COMPLETE.getValue());
        } finally {
            releaseCompletedNotificationHash(torrentsInfo);
        }
    }

    private void endUploadWindow() {
        UPLOAD_PAUSE_GATE.complete();
    }

    private static final class UploadPauseGate {
        private final AtomicInteger pendingUploadCount = new AtomicInteger(0);

        public void enqueue() {
            pendingUploadCount.incrementAndGet();
            pauseUploadsIfNeeded();
        }

        public void complete() {
            int remaining = pendingUploadCount.updateAndGet(count -> Math.max(0, count - 1));
            if (remaining > 0) {
                return;
            }

            resumePausedSeedingTorrents();
        }

        public void pauseUploadsIfNeeded() {
            if (pendingUploadCount.get() < 1) {
                return;
            }
            pauseCompletedSeedingTorrents();
        }

        public void resumeIfIdle() {
            if (pendingUploadCount.get() > 0) {
                return;
            }
            resumePausedSeedingTorrents();
        }

        private void pauseCompletedSeedingTorrents() {
            if (!TorrentUtil.login()) {
                return;
            }

            List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();
            for (TorrentsInfo torrentsInfo : torrentsInfos) {
                if (!isCompletedSeedingState(torrentsInfo)) {
                    continue;
                }

                if (TorrentUtil.pause(torrentsInfo)) {
                    log.info("上传期间暂停 qBittorrent 做种 {}", torrentsInfo.getName());
                }
            }
        }

        private void resumePausedSeedingTorrents() {
            if (!TorrentUtil.login()) {
                return;
            }

            List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();
            for (TorrentsInfo torrentsInfo : torrentsInfos) {
                TorrentsInfo.State state = torrentsInfo.getState();
                if (Objects.isNull(state) || !List.of(
                        TorrentsInfo.State.pausedUP.name(),
                        TorrentsInfo.State.stoppedUP.name()
                ).contains(state.name())) {
                    continue;
                }

                if (TorrentUtil.resume(torrentsInfo)) {
                    log.info("上传完成，恢复 qBittorrent 做种 {}", torrentsInfo.getName());
                }
            }
        }

        private boolean isCompletedSeedingState(TorrentsInfo torrentsInfo) {
            TorrentsInfo.State state = torrentsInfo.getState();
            return Objects.nonNull(state) && List.of(
                    TorrentsInfo.State.queuedUP.name(),
                    TorrentsInfo.State.uploading.name(),
                    TorrentsInfo.State.stalledUP.name()
            ).contains(state.name());
        }
    }

    /**
     * 获取下载位置
     *
     * @param ani
     * @return
     */
    public String getDownloadPath(Ani ani) {
        return getDownloadPath(ani, ConfigUtil.CONFIG);
    }

    /**
     * 获取下载位置
     *
     * @param ani
     * @return
     */
    public String getDownloadPath(Ani ani, Config config) {
        Boolean customDownloadPath = ani.getCustomDownloadPath();
        String aniDownloadPath = ani.getDownloadPath();
        Boolean ova = ani.getOva();

        String downloadPathTemplate = config.getDownloadPathTemplate();
        String ovaDownloadPathTemplate = config.getOvaDownloadPathTemplate();
        if (ova && StrUtil.isNotBlank(ovaDownloadPathTemplate)) {
            // 剧场版位置
            downloadPathTemplate = ovaDownloadPathTemplate;
        }

        if (customDownloadPath && StrUtil.isNotBlank(aniDownloadPath)) {
            // 自定义下载位置
            downloadPathTemplate = StrUtil.split(aniDownloadPath, "\n", true, true)
                    .stream()
                    .map(FileUtils::getAbsolutePath)
                    .findFirst()
                    .orElse(downloadPathTemplate);
        }

        String title = ani.getTitle().trim();

        String pinyin = PinyinUtils.getPinyin(title);
        String letter = pinyin.substring(0, 1).toUpperCase();
        if (ReUtil.isMatch("^\\d$", letter)) {
            letter = "0";
        } else if (!ReUtil.isMatch("^[a-zA-Z]$", letter)) {
            letter = "#";
        }

        downloadPathTemplate = downloadPathTemplate.replace("${letter}", letter);

        Date releaseDate = ani.getReleaseDate();

        int year = DateUtil.year(releaseDate);
        int month = DateUtil.month(releaseDate) + 1;
        String monthFormat = String.format("%02d", month);

        // 季度
        if (
                downloadPathTemplate.contains("${quarter}") ||
                        downloadPathTemplate.contains("${quarterFormat}") ||
                        downloadPathTemplate.contains("${quarterName}")
        ) {
            int quarter;
            String quarterName;
            /*
            https://github.com/wushuo894/ani-rss/pull/451
            优化季度判断规则，避免将月底先行播放的番归类到上个季度
            */
            if (List.of(12, 1, 2).contains(month)) {
                if (month == 12) {
                    // 当使用季度信息, 并且月份等于12时, 年份自动 +1。避免年份与月份不一致
                    year++;
                }
                quarter = 1;
                quarterName = "冬";
            } else if (List.of(3, 4, 5).contains(month)) {
                quarter = 4;
                quarterName = "春";
            } else if (List.of(6, 7, 8).contains(month)) {
                quarter = 7;
                quarterName = "夏";
            } else {
                quarter = 10;
                quarterName = "秋";
            }
            String quarterFormat = String.format("%02d", quarter);
            downloadPathTemplate = downloadPathTemplate.replace("${quarter}", String.valueOf(quarter));
            downloadPathTemplate = downloadPathTemplate.replace("${quarterFormat}", quarterFormat);
            downloadPathTemplate = downloadPathTemplate.replace("${quarterName}", quarterName);
        }

        downloadPathTemplate = downloadPathTemplate.replace("${year}", String.valueOf(year));
        downloadPathTemplate = downloadPathTemplate.replace("${month}", String.valueOf(month));
        downloadPathTemplate = downloadPathTemplate.replace("${monthFormat}", monthFormat);

        int season = ani.getSeason();
        String seasonFormat = String.format("%02d", season);

        downloadPathTemplate = downloadPathTemplate.replace("${season}", String.valueOf(season));
        downloadPathTemplate = downloadPathTemplate.replace("${seasonFormat}", seasonFormat);
        downloadPathTemplate = replaceWeek(downloadPathTemplate, ani);

        String bgmId = BgmUtil.getSubjectId(ani);
        downloadPathTemplate = downloadPathTemplate.replace("${bgmId}", bgmId);

        List<Func1<Ani, Object>> list = List.of(
                Ani::getTitle,
                Ani::getThemoviedbName,
                Ani::getSubgroup
        );

        downloadPathTemplate = RenameUtil.replaceField(downloadPathTemplate, ani, list);

        String tmdbId = Opt.ofNullable(ani.getTmdb())
                .map(Tmdb::getId)
                .filter(StrUtil::isNotBlank)
                .orElse("");

        downloadPathTemplate = downloadPathTemplate.replace("${tmdbid}", tmdbId);

        if (downloadPathTemplate.contains("${jpTitle}")) {
            String jpTitle = RenameUtil.getJpTitle(ani);
            downloadPathTemplate = downloadPathTemplate.replace("${jpTitle}", jpTitle);
        }

        return FileUtils.getAbsolutePath(downloadPathTemplate);
    }

    private String replaceWeek(String template, Ani ani) {
        Integer week = ani.getWeek();
        String weekValue = Objects.isNull(week) ? "" : String.valueOf(week);
        String weekName = getWeekName(week);

        template = template.replace("${week}", weekValue);
        template = template.replace("${weekName}", weekName);
        template = template.replace("${weekCn}", weekName);
        return template;
    }

    private String getWeekName(Integer week) {
        if (Objects.isNull(week)) {
            return "未知";
        }
        return switch (week) {
            case 1 -> "星期日";
            case 2 -> "星期一";
            case 3 -> "星期二";
            case 4 -> "星期三";
            case 5 -> "星期四";
            case 6 -> "星期五";
            case 7 -> "星期六";
            default -> "未知";
        };
    }


    /**
     * 判断是否已经下载过
     *
     * @param ani
     * @param item
     * @param downloadList
     * @return
     */
    public Boolean itemDownloaded(Ani ani, Item item, Boolean downloadList) {
        Config config = ConfigUtil.CONFIG;
        Integer season = ani.getSeason();
        Boolean ova = ani.getOva();
        String reName = item.getReName();
        Double episode = item.getEpisode();

        String downloadPath = getDownloadPath(ani);

        if (SpringUtil.getBean(StrmService.class).hasEpisodeMetadata(ani, item)) {
            return true;
        }

        if (downloadList) {
            List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();
            for (TorrentsInfo torrentsInfo : torrentsInfos) {
                String downloadDir = torrentsInfo.getDownloadDir();
                if (!Objects.equals(downloadDir, downloadPath)) {
                    continue;
                }
                String name = torrentsInfo.getName();
                boolean sameEpisode = sameEpisodeName(ova, season, episode, reName, name);
                if (!sameEpisode) {
                    continue;
                }
                TorrentUtil.saveTorrent(ani, item);
                return true;
            }
        }

        Boolean rename = config.getRename();
        if (!rename) {
            return false;
        }

        String downloadPathTemplate = config.getDownloadPathTemplate();

        if (StrUtil.isBlank(downloadPathTemplate)) {
            return false;
        }

        Boolean fileExist = config.getFileExist();
        if (!fileExist) {
            return false;
        }

        List<File> files = FileUtils.listFileList(downloadPath);

        if (files.stream()
                .filter(file -> {
                    if (file.isFile()) {
                        String extName = FileUtil.extName(file);
                        if (StrUtil.isBlank(extName)) {
                            return false;
                        }
                        return FileUtils.isVideoFormat(extName);
                    }
                    return true;
                })
                .anyMatch(file -> {
                    if (ova) {
                        return true;
                    }

                    String mainName = FileUtil.mainName(file);
                    if (StrUtil.isBlank(mainName)) {
                        return false;
                    }
                    mainName = mainName.trim().toUpperCase();
                    if (!ReUtil.contains(StringEnum.SEASON_REG, mainName)) {
                        return false;
                    }

                    String seasonStr = ReUtil.get(StringEnum.SEASON_REG, mainName, 1);

                    String episodeStr = ReUtil.get(StringEnum.SEASON_REG, mainName, 2);

                    if (StrUtil.isBlank(seasonStr) || StrUtil.isBlank(episodeStr)) {
                        return false;
                    }
                    return season == Integer.parseInt(seasonStr) && episode == Double.parseDouble(episodeStr);
                })) {
            // 保存 torrent 下次只校验 torrent 是否存在 ， 可以将config设置到固态硬盘，防止一直唤醒机械硬盘
            TorrentUtil.saveTorrent(ani, item);
            return true;
        }
        return false;
    }

    private boolean sameEpisodeName(Boolean ova, Integer season, Double episode, String reName, String name) {
        if (StrUtil.isBlank(name)) {
            return false;
        }
        if (name.equalsIgnoreCase(reName)) {
            return true;
        }
        if (Boolean.TRUE.equals(ova)) {
            return false;
        }
        String mainName = FileUtil.mainName(name).trim().toUpperCase();
        if (!ReUtil.contains(StringEnum.SEASON_REG, mainName)) {
            return false;
        }
        String seasonStr = ReUtil.get(StringEnum.SEASON_REG, mainName, 1);
        String episodeStr = ReUtil.get(StringEnum.SEASON_REG, mainName, 2);
        if (StrUtil.isBlank(seasonStr) || StrUtil.isBlank(episodeStr)) {
            return false;
        }
        try {
            return Objects.equals(season, Integer.parseInt(seasonStr)) &&
                    Double.compare(episode, Double.parseDouble(episodeStr)) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据任务反查订阅
     *
     * @param torrentsInfo
     * @return
     */
    public synchronized Optional<Ani> findAniByDownloadPath(TorrentsInfo torrentsInfo) {
        String downloadDir = torrentsInfo.getDownloadDir();
        return AniUtil.ANI_LIST
                .stream()
                .filter(ani -> {
                    String path = getDownloadPath(ani);
                    return path.equals(downloadDir);
                })
                .map(ObjectUtil::clone)
                .findFirst();
    }

    public synchronized Optional<Ani> findAniRefById(String id) {
        return AniUtil.ANI_LIST.stream()
                .filter(ani -> Objects.equals(ani.getId(), id))
                .findFirst();
    }

    public synchronized void trackLocalFiles(Ani ani, TorrentsInfo torrentsInfo) {
        List<String> names = Optional.ofNullable(torrentsInfo.getFiles())
                .map(files -> files.get())
                .orElse(List.of());
        if (names.isEmpty()) {
            return;
        }

        String downloadDir = torrentsInfo.getDownloadDir();
        long now = System.currentTimeMillis();
        List<LocalFileRecord> records = Optional.ofNullable(ani.getAutoDeleteLocalFileRecords())
                .orElseGet(ArrayList::new);
        ani.setAutoDeleteLocalFileRecords(records);

        boolean changed = false;

        for (String name : names) {
            if (StrUtil.isBlank(name)) {
                continue;
            }
            String extName = FileUtil.extName(name);
            if (!FileUtils.isVideoFormat(extName) && !FileUtils.isSubtitleFormat(extName)) {
                continue;
            }

            String path = FileUtils.getAbsolutePath(downloadDir + "/" + name);
            LocalFileRecord record = records.stream()
                    .filter(it -> Objects.equals(it.getPath(), path))
                    .findFirst()
                    .orElse(null);
            if (Objects.isNull(record)) {
                records.add(new LocalFileRecord()
                        .setPath(path)
                        .setTrackedAt(now));
                changed = true;
                continue;
            }
            if (!Objects.equals(record.getTrackedAt(), now)) {
                record.setTrackedAt(now);
                changed = true;
            }
        }

        if (changed) {
            AniUtil.sync();
        }
    }

}
