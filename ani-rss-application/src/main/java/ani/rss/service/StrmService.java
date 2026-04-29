package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.TorrentsInfo;
import ani.rss.enums.StringEnum;
import ani.rss.enums.NotificationTypeEnum;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
public class StrmService {
    @Resource
    private DownloadService downloadService;

    public void writeLocalStrm(Ani ani, TorrentsInfo torrentsInfo) {
        if (!enabled()) {
            return;
        }
        List<String> names = Optional.ofNullable(torrentsInfo.getFiles())
                .map(files -> files.get())
                .orElse(List.of());
        if (names.isEmpty()) {
            return;
        }

        String downloadDir = torrentsInfo.getDownloadDir();
        for (String name : names) {
            if (StrUtil.isBlank(name) || !FileUtils.isVideoFormat(name)) {
                continue;
            }
            writeLocalStrm(ani, new File(downloadDir, name));
        }
    }

    public void writeLocalStrm(Ani ani, File videoFile) {
        if (!enabled() || Objects.isNull(videoFile) || !FileUtils.isVideoFormat(videoFile.getName())) {
            return;
        }
        String url = getLocalUrl(videoFile);
        if (StrUtil.isBlank(url)) {
            return;
        }
        writeStrm(ani, videoFile, url, false);
    }

    public void switchToCloudStrm(Ani ani, File localVideoFile) {
        if (!enabled() || Objects.isNull(localVideoFile) || !FileUtils.isVideoFormat(localVideoFile.getName())) {
            return;
        }
        String url = getCloudUrl(ani, localVideoFile);
        if (StrUtil.isBlank(url)) {
            return;
        }
        writeStrm(ani, localVideoFile, url, true);
    }

    public void syncLibrary(Ani ani) {
        if (!enabled() || Objects.isNull(ani)) {
            return;
        }
        copyMetadata(ani);

        String downloadPath = downloadService.getDownloadPath(ani);
        if (StrUtil.isBlank(downloadPath)) {
            return;
        }
        File downloadDir = new File(downloadPath);
        if (!downloadDir.exists() || !downloadDir.isDirectory()) {
            return;
        }

        List<File> files = FileUtil.loopFiles(downloadDir, file ->
                file.isFile() && (FileUtils.isVideoFormat(file.getName()) || "strm".equalsIgnoreCase(FileUtil.extName(file.getName())))
        );
        for (File file : files) {
            if (FileUtils.isVideoFormat(file.getName())) {
                writeLocalStrm(ani, file);
                continue;
            }
            copyExistingStrm(ani, file);
        }
    }

    public void deleteLibrary(Ani ani) {
        if (!enabled() || Objects.isNull(ani)) {
            return;
        }
        File source = getLibrarySeasonDir(ani);
        if (Objects.isNull(source)) {
            return;
        }
        if (!source.exists()) {
            return;
        }
        File target = getPastSeasonArchiveDir(ani);
        if (Objects.isNull(target)) {
            return;
        }
        if (Objects.equals(FileUtils.getAbsolutePath(source), FileUtils.getAbsolutePath(target))) {
            return;
        }
        if (target.exists()) {
            FileUtil.del(target);
        }
        FileUtil.mkParentDirs(target);
        log.info("归档 STRM 媒体库目录 {} => {}", source, target);
        FileUtil.move(source, target, true);
        deleteEmptyParents(source.getParentFile());
    }

    public List<File> getExistingLibrarySeasonDirs(Ani ani) {
        if (!enabled() || Objects.isNull(ani)) {
            return List.of();
        }
        return List.of(getLibrarySeasonDir(ani), getPastSeasonArchiveDir(ani))
                .stream()
                .filter(Objects::nonNull)
                .map(file -> new File(FileUtils.getAbsolutePath(file)))
                .distinct()
                .filter(File::exists)
                .filter(File::isDirectory)
                .toList();
    }

    public boolean hasEpisodeMetadata(Ani ani, Item item) {
        if (!enabled() || Objects.isNull(ani) || Objects.isNull(item)) {
            return false;
        }
        String episodeKey = episodeKey(item.getReName());
        if (StrUtil.isBlank(episodeKey)) {
            return false;
        }
        File archiveDir = getPastSeasonArchiveDir(ani);
        String archivePath = Objects.isNull(archiveDir) ? "" : FileUtils.getAbsolutePath(archiveDir);
        return List.of(getOutputDir(ani), archivePath)
                .stream()
                .filter(StrUtil::isNotBlank)
                .map(File::new)
                .filter(file -> file.exists() && file.isDirectory())
                .flatMap(file -> FileUtil.loopFiles(file, this::isStrmEpisodeMetadata).stream())
                .map(file -> episodeKey(file.getName()))
                .anyMatch(episodeKey::equals);
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(ConfigUtil.CONFIG.getStrm());
    }

    private void writeStrm(Ani ani, File videoFile, String url, boolean allowCloudOverwrite) {
        File strmFile = getStrmFile(ani, videoFile);
        if (Objects.isNull(strmFile)) {
            return;
        }
        if (!allowCloudOverwrite && keepExistingCloudStrm(strmFile, url)) {
            copyMetadata(ani);
            return;
        }
        FileUtil.mkParentDirs(strmFile);
        FileUtil.writeUtf8String(url + System.lineSeparator(), strmFile);
        copyMetadata(ani);
        log.info("STRM 已更新 {} => {}", strmFile, url);
    }

    private void copyExistingStrm(Ani ani, File sourceStrmFile) {
        File strmFile = getStrmFile(ani, sourceStrmFile);
        if (Objects.isNull(strmFile)) {
            return;
        }
        if (Objects.equals(FileUtils.getAbsolutePath(sourceStrmFile), FileUtils.getAbsolutePath(strmFile))) {
            return;
        }
        String url = FileUtil.readUtf8String(sourceStrmFile).trim();
        if (StrUtil.isBlank(url)) {
            return;
        }
        if (keepExistingCloudStrm(strmFile, url)) {
            copyMetadata(ani);
            return;
        }
        FileUtil.mkParentDirs(strmFile);
        FileUtil.writeUtf8String(url + System.lineSeparator(), strmFile);
        copyMetadata(ani);
        log.info("STRM 已同步 {} => {}", strmFile, url);
    }

    private boolean keepExistingCloudStrm(File strmFile, String nextUrl) {
        if (Objects.isNull(strmFile) || !strmFile.exists()) {
            return false;
        }
        String currentUrl = FileUtil.readUtf8String(strmFile).trim();
        if (!isCloudUrl(currentUrl) || isCloudUrl(nextUrl)) {
            return false;
        }
        log.info("STRM 已是云端地址，跳过本地覆盖 {} => {}", strmFile, currentUrl);
        return true;
    }

    private void copyMetadata(Ani ani) {
        String sourceDir = downloadService.getDownloadPath(ani);
        String outputDir = getOutputDir(ani);
        if (StrUtil.isBlank(sourceDir) || StrUtil.isBlank(outputDir)) {
            return;
        }
        File source = new File(sourceDir);
        File output = new File(outputDir);
        copySidecars(source, output);
        copySidecars(source.getParentFile(), output.getParentFile());
    }

    private void copySidecars(File sourceDir, File outputDir) {
        if (Objects.isNull(sourceDir) || Objects.isNull(outputDir) || !sourceDir.exists() || !sourceDir.isDirectory()) {
            return;
        }
        if (Objects.equals(FileUtils.getAbsolutePath(sourceDir), FileUtils.getAbsolutePath(outputDir))) {
            return;
        }
        FileUtil.mkdir(outputDir);
        for (File file : FileUtils.listFiles(sourceDir)) {
            if (!file.isFile() || !isMetadataFile(file)) {
                continue;
            }
            File target = new File(outputDir, file.getName());
            try {
                java.nio.file.Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                log.warn("复制 STRM 元数据失败 {} => {}", file, target);
                log.debug(e.getMessage(), e);
            }
        }
    }

    private boolean isMetadataFile(File file) {
        String extName = FileUtil.extName(file.getName()).toLowerCase();
        return List.of("nfo", "jpg", "jpeg", "png", "webp").contains(extName);
    }

    private boolean isStrmEpisodeMetadata(File file) {
        if (!file.isFile()) {
            return false;
        }
        String extName = FileUtil.extName(file.getName()).toLowerCase();
        if (!List.of("strm", "nfo", "jpg", "jpeg", "png", "webp").contains(extName)) {
            return false;
        }
        return StrUtil.isNotBlank(episodeKey(file.getName()));
    }

    private String episodeKey(String name) {
        String mainName = FileUtil.mainName(StrUtil.blankToDefault(name, "")).trim().toUpperCase();
        if (!ReUtil.contains(StringEnum.SEASON_REG, mainName)) {
            return "";
        }
        String season = ReUtil.get(StringEnum.SEASON_REG, mainName, 1);
        String episode = ReUtil.get(StringEnum.SEASON_REG, mainName, 2);
        if (StrUtil.isBlank(season) || StrUtil.isBlank(episode)) {
            return "";
        }
        try {
            return "S" + String.format("%02d", Integer.parseInt(season)) + "E" + formatEpisode(episode);
        } catch (Exception e) {
            return "";
        }
    }

    private String formatEpisode(String episode) {
        try {
            double value = Double.parseDouble(episode);
            if (value == (int) value) {
                return String.format("%02d", (int) value);
            }
            return String.valueOf(value).replace(".", "_");
        } catch (Exception e) {
            return episode.replace(".", "_");
        }
    }

    private File getLibrarySeasonDir(Ani ani) {
        String outputDir = getOutputDir(ani);
        if (StrUtil.isBlank(outputDir)) {
            return null;
        }

        File output = new File(outputDir);
        String libraryRoot = getLibraryRoot();
        if (StrUtil.isBlank(libraryRoot)) {
            return output;
        }

        String rootPath = FileUtils.getAbsolutePath(libraryRoot);
        String outputPath = FileUtils.getAbsolutePath(output);
        if (!isSubPath(rootPath, outputPath) || Objects.equals(rootPath, outputPath)) {
            return output;
        }
        return output;
    }

    private File getPastSeasonArchiveDir(Ani ani) {
        String libraryRoot = getLibraryRoot();
        if (StrUtil.isBlank(libraryRoot)) {
            log.warn("STRM 输出路径模版为空，跳过往季归档 {}", ani.getTitle());
            return null;
        }
        String relativePath = getDownloadRelativePath(ani);
        if (StrUtil.isBlank(relativePath)) {
            return null;
        }
        return new File(new File(libraryRoot, "往季"), relativePath);
    }

    private String getDownloadRelativePath(Ani ani) {
        String downloadPath = downloadService.getDownloadPath(ani);
        if (StrUtil.isBlank(downloadPath)) {
            return "";
        }
        String downloadRoot = StrUtil.blankToDefault(ConfigUtil.CONFIG.getStrmLocalPathPrefix(), inferLocalPathPrefix());
        if (StrUtil.isBlank(downloadRoot)) {
            return new File(downloadPath).getName();
        }

        String rootPath = FileUtils.getAbsolutePath(downloadRoot);
        String path = FileUtils.getAbsolutePath(downloadPath);
        if (!isSubPath(rootPath, path) || Objects.equals(rootPath, path)) {
            return new File(path).getName();
        }
        return FileUtils.normalize(StrUtil.removePrefix(path.substring(rootPath.length()), "/"));
    }

    private void deleteEmptyParents(File dir) {
        File root = new File(StrUtil.removeSuffix(getLibraryRoot(), "/"));
        while (Objects.nonNull(dir) && dir.exists() && dir.isDirectory()) {
            if (!isSubPath(root.getPath(), dir.getPath())) {
                return;
            }
            if (Objects.equals(FileUtils.getAbsolutePath(root), FileUtils.getAbsolutePath(dir))) {
                return;
            }
            String[] children = dir.list();
            if (children == null || children.length > 0) {
                return;
            }
            File parent = dir.getParentFile();
            FileUtil.del(dir);
            dir = parent;
        }
    }

    private String getLibraryRoot() {
        String template = ConfigUtil.CONFIG.getStrmOutputPathTemplate();
        if (StrUtil.isBlank(template)) {
            return "";
        }
        int index = template.indexOf("${");
        if (index > -1) {
            template = template.substring(0, index);
        }
        return StrUtil.removeSuffix(template, "/");
    }

    private File getStrmFile(Ani ani, File videoFile) {
        if (StrUtil.isBlank(ConfigUtil.CONFIG.getStrmOutputPathTemplate())) {
            return new File(videoFile.getParentFile(), FileUtil.mainName(videoFile.getName()) + ".strm");
        }

        String outputDir = getOutputDir(ani);
        if (StrUtil.isBlank(outputDir)) {
            return null;
        }

        String downloadPath = downloadService.getDownloadPath(ani);
        String videoPath = FileUtils.getAbsolutePath(videoFile);
        String relativeParent = "";
        String normalizedDownloadPath = FileUtils.getAbsolutePath(downloadPath);
        if (isSubPath(normalizedDownloadPath, videoPath)) {
            String relative = videoPath.substring(normalizedDownloadPath.length());
            relative = StrUtil.removePrefix(relative, "/");
            String parent = new File(relative).getParent();
            if (StrUtil.isNotBlank(parent) && !".".equals(parent)) {
                relativeParent = FileUtils.normalize(parent);
            }
        }

        File dir = StrUtil.isBlank(relativeParent) ? new File(outputDir) : new File(outputDir, relativeParent);
        return new File(dir, FileUtil.mainName(videoFile.getName()) + ".strm");
    }

    private String getOutputDir(Ani ani) {
        Config config = ConfigUtil.CONFIG;
        String template = config.getStrmOutputPathTemplate();
        if (StrUtil.isBlank(template)) {
            return downloadService.getDownloadPath(ani);
        }
        Ani strmAni = ObjectUtil.clone(ani);
        strmAni.setCustomDownloadPath(true);
        strmAni.setDownloadPath(template);
        return downloadService.getDownloadPath(strmAni);
    }

    private String getLocalUrl(File videoFile) {
        Config config = ConfigUtil.CONFIG;
        String baseUrl = getBaseUrl(config);
        if (StrUtil.isBlank(baseUrl)) {
            log.warn("STRM 播放基础地址为空，跳过生成");
            return "";
        }

        String localPathPrefix = StrUtil.blankToDefault(config.getStrmLocalPathPrefix(), inferLocalPathPrefix());
        if (StrUtil.isBlank(localPathPrefix)) {
            log.warn("STRM 本地文件系统前缀为空，跳过生成 {}", videoFile);
            return "";
        }
        String localPrefix = FileUtils.getAbsolutePath(localPathPrefix);
        String localWebDavPrefix = StrUtil.blankToDefault(config.getStrmLocalWebDavPathPrefix(), "/local");
        if (StrUtil.isBlank(localWebDavPrefix)) {
            log.warn("STRM 本地 WebDAV 路径前缀为空，跳过生成 {}", videoFile);
            return "";
        }

        String videoPath = FileUtils.getAbsolutePath(videoFile);
        if (!isSubPath(localPrefix, videoPath)) {
            log.warn("STRM 本地文件不在本地前缀内: {} prefix={}", videoFile, localPrefix);
            return "";
        }

        String relative = StrUtil.removePrefix(videoPath.substring(localPrefix.length()), "/");
        return joinUrl(baseUrl, localWebDavPrefix, relative);
    }

    private String inferLocalPathPrefix() {
        String template = ConfigUtil.CONFIG.getDownloadPathTemplate();
        if (StrUtil.isBlank(template)) {
            return "";
        }
        int index = template.indexOf("${");
        if (index > -1) {
            template = template.substring(0, index);
        }
        template = StrUtil.removeSuffix(template, "/");
        return FileUtil.getParent(template, 1);
    }

    private String getCloudUrl(Ani ani, File localVideoFile) {
        Config config = ConfigUtil.CONFIG;
        String baseUrl = getBaseUrl(config);
        if (StrUtil.isBlank(baseUrl)) {
            return "";
        }

        String cloudDir = config.getStrmCloudWebDavPathPrefix();
        if (StrUtil.isBlank(cloudDir)) {
            cloudDir = getOpenListUploadTarget(ani);
        }
        if (StrUtil.isBlank(cloudDir)) {
            log.warn("STRM 云端 WebDAV 路径前缀为空，跳过切换 {}", localVideoFile);
            return "";
        }

        return joinUrl(baseUrl, cloudDir, localVideoFile.getName());
    }

    private String getBaseUrl(Config config) {
        return StrUtil.blankToDefault(config.getStrmBaseUrl(), config.getStrmWebDavBaseUrl());
    }

    private boolean isCloudUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return false;
        }
        String baseUrl = StrUtil.removeSuffix(getBaseUrl(ConfigUtil.CONFIG), "/");
        if (StrUtil.isBlank(baseUrl) || !url.startsWith(baseUrl)) {
            return false;
        }
        String localPrefix = StrUtil.blankToDefault(ConfigUtil.CONFIG.getStrmLocalWebDavPathPrefix(), "/local");
        String encodedLocalPrefix = encodePath(localPrefix);
        return !url.startsWith(baseUrl + encodedLocalPrefix + "/");
    }

    private String getOpenListUploadTarget(Ani ani) {
        Config config = ConfigUtil.CONFIG;
        List<NotificationConfig> notificationConfigs = Optional.ofNullable(config.getNotificationConfigList())
                .orElse(List.of());
        NotificationConfig uploadConfig = notificationConfigs.stream()
                .filter(notificationConfig -> Boolean.TRUE.equals(notificationConfig.getEnable()))
                .filter(notificationConfig -> notificationConfig.getNotificationType() == NotificationTypeEnum.OPEN_LIST_UPLOAD)
                .findFirst()
                .orElse(null);
        if (Objects.isNull(uploadConfig)) {
            return "";
        }

        Ani uploadAni = ObjectUtil.clone(ani);
        if (Boolean.TRUE.equals(uploadAni.getOva())) {
            uploadAni.setDownloadPath(uploadConfig.getOpenListUploadOvaPath());
        } else {
            uploadAni.setDownloadPath(uploadConfig.getOpenListUploadPath());
        }
        if (Boolean.TRUE.equals(uploadAni.getCustomUploadEnable())) {
            uploadAni.setDownloadPath(uploadAni.getCustomUploadPathTarget());
        }
        uploadAni.setCustomDownloadPath(true);
        String target = downloadService.getDownloadPath(uploadAni);
        return target.replaceFirst("^[A-z]:", "");
    }

    private String joinUrl(String baseUrl, String path, String filename) {
        baseUrl = StrUtil.removeSuffix(baseUrl.trim(), "/");
        path = path.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        path = StrUtil.removeSuffix(path, "/");
        String encodedPath = encodePath(path);
        String encodedName = encodePath(filename);
        return baseUrl + encodedPath + "/" + encodedName;
    }

    private String encodePath(String path) {
        boolean startsWithSlash = path.startsWith("/");
        path = StrUtil.removePrefix(path, "/");
        path = StrUtil.removeSuffix(path, "/");
        String encoded = StrUtil.split(path, "/", true, true)
                .stream()
                .map(URLUtil::encode)
                .reduce("", (left, right) -> StrUtil.isBlank(left) ? right : left + "/" + right);
        if (StrUtil.isBlank(encoded)) {
            return startsWithSlash ? "/" : "";
        }
        return startsWithSlash ? "/" + encoded : encoded;
    }

    private boolean isSubPath(String parent, String child) {
        String normalizedParent = FileUtils.getAbsolutePath(parent);
        String normalizedChild = FileUtils.getAbsolutePath(child);
        return normalizedChild.equals(normalizedParent) || normalizedChild.startsWith(normalizedParent + "/");
    }
}
