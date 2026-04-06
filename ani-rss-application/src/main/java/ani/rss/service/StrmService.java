package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationTypeEnum;
import ani.rss.entity.web.Header;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import com.google.gson.JsonObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class StrmService {

    @Resource
    private DownloadService downloadService;

    public int generate(Ani ani, Boolean deleteLocalVideo) {
        Optional<NotificationConfig> optional = getOpenListUploadConfig();
        if (optional.isEmpty()) {
            log.info("未找到可用的 OpenList 上传配置，跳过生成 STRM {}", ani.getTitle());
            return 0;
        }
        return generate(ani, optional.get(), deleteLocalVideo);
    }

    public int generate(Ani ani, NotificationConfig notificationConfig, Boolean deleteLocalVideo) {
        ani = ObjectUtil.clone(ani);

        String localPath = downloadService.getDownloadPath(ani);
        File localDir = new File(localPath);
        FileUtil.mkdir(localDir);

        String cloudDir = getCloudDir(ani, notificationConfig);
        Set<String> filenameSet = new LinkedHashSet<>(listRemoteVideoNames(notificationConfig, cloudDir));
        for (File file : FileUtils.listFileList(localPath)) {
            if (!file.isFile()) {
                continue;
            }
            if (!FileUtils.isVideoFormat(file.getName())) {
                continue;
            }
            filenameSet.add(file.getName());
        }

        int count = 0;
        for (String filename : filenameSet) {
            File localFile = new File(localDir, filename);
            if (generateStrm(notificationConfig, cloudDir, localDir, filename)) {
                if (Boolean.TRUE.equals(deleteLocalVideo) && localFile.exists() && localFile.isFile() && FileUtils.isVideoFormat(filename)) {
                    log.info("删除本地视频 {}", FileUtils.getAbsolutePath(localFile));
                    FileUtil.del(localFile);
                }
                count++;
            }
        }
        return count;
    }

    public boolean generateForFile(NotificationConfig notificationConfig, String cloudDir, File localFile, Boolean deleteLocalVideo) {
        if (Objects.isNull(localFile)) {
            return false;
        }
        String filename = localFile.getName();
        if (!FileUtils.isVideoFormat(filename)) {
            return false;
        }
        if (generateStrm(notificationConfig, cloudDir, localFile.getParentFile(), filename)) {
            if (Boolean.TRUE.equals(deleteLocalVideo) && localFile.exists() && localFile.isFile()) {
                log.info("删除本地视频 {}", FileUtils.getAbsolutePath(localFile));
                FileUtil.del(localFile);
            }
            return true;
        }
        return false;
    }

    public String getCloudDir(Ani ani, NotificationConfig notificationConfig) {
        ani = ObjectUtil.clone(ani);

        String targetPath = notificationConfig.getOpenListUploadPath();
        if (Boolean.TRUE.equals(ani.getOva())) {
            targetPath = notificationConfig.getOpenListUploadOvaPath();
        }

        if (Boolean.TRUE.equals(ani.getCustomUploadEnable()) && StrUtil.isNotBlank(ani.getCustomUploadPathTarget())) {
            targetPath = ani.getCustomUploadPathTarget();
        }

        ani.setCustomDownloadPath(true)
                .setDownloadPath(targetPath);

        String cloudDir = downloadService.getDownloadPath(ani);
        return cloudDir.replaceFirst("^[A-z]:", "");
    }

    private Optional<String> getStrmUrl(NotificationConfig notificationConfig, String cloudDir, String filename) {
        String cloudFilePath = FileUtils.getAbsolutePath(cloudDir + "/" + filename);
        String host = StrUtil.removeSuffix(StrUtil.blankToDefault(notificationConfig.getOpenListUploadHost(), ""), "/");
        String apiKey = notificationConfig.getOpenListUploadApiKey();
        if (StrUtil.hasBlank(host, apiKey)) {
            return Optional.empty();
        }

        JsonObject jsonObject = HttpReq.post(host + "/api/fs/get")
                .header(Header.AUTHORIZATION, apiKey)
                .body(GsonStatic.toJson(java.util.Map.of(
                        "path", cloudFilePath,
                        "password", ""
                )))
                .thenFunction(res -> GsonStatic.fromJson(res.body(), JsonObject.class));

        if (Objects.isNull(jsonObject)) {
            return Optional.empty();
        }

        int code = jsonObject.get("code").getAsInt();
        if (code != 200) {
            return Optional.empty();
        }

        JsonObject data = jsonObject.getAsJsonObject("data");
        if (Objects.isNull(data)) {
            return Optional.empty();
        }

        String sign = "";
        if (data.has("sign") && !data.get("sign").isJsonNull()) {
            sign = data.get("sign").getAsString();
        }

        String encodedPath = URLUtil.encode(cloudFilePath).replace("+", "%20");
        String url = StrFormatter.format("{}/d/{}", host, encodedPath);
        if (StrUtil.isNotBlank(sign)) {
            url = url + "?sign=" + URLUtil.encode(sign).replace("+", "%20");
        }
        return Optional.of(url);
    }

    private boolean generateStrm(NotificationConfig notificationConfig, String cloudDir, File localDir, String filename) {
        Optional<String> urlOptional = getStrmUrl(notificationConfig, cloudDir, filename);
        if (urlOptional.isEmpty()) {
            log.warn("生成 STRM 失败，找不到云端文件 {}", filename);
            return false;
        }

        String url = urlOptional.get();
        File strmFile = new File(localDir, FileUtil.mainName(filename) + ".strm");
        String content = url + System.lineSeparator();

        String current = "";
        if (strmFile.exists()) {
            current = FileUtil.readUtf8String(strmFile);
        }

        if (!content.equals(current)) {
            FileUtil.writeString(content, strmFile, StandardCharsets.UTF_8);
            log.info("生成 STRM {}", FileUtils.getAbsolutePath(strmFile));
        }
        return true;
    }

    private List<String> listRemoteVideoNames(NotificationConfig notificationConfig, String cloudDir) {
        String host = StrUtil.removeSuffix(StrUtil.blankToDefault(notificationConfig.getOpenListUploadHost(), ""), "/");
        String apiKey = notificationConfig.getOpenListUploadApiKey();
        if (StrUtil.hasBlank(host, apiKey)) {
            return List.of();
        }

        JsonObject jsonObject = HttpReq.post(host + "/api/fs/list")
                .header(Header.AUTHORIZATION, apiKey)
                .body(GsonStatic.toJson(java.util.Map.of(
                        "path", cloudDir,
                        "password", "",
                        "page", 1,
                        "per_page", 1000,
                        "refresh", false
                )))
                .thenFunction(res -> GsonStatic.fromJson(res.body(), JsonObject.class));

        if (Objects.isNull(jsonObject) || !jsonObject.has("code") || jsonObject.get("code").getAsInt() != 200) {
            return List.of();
        }

        JsonObject data = jsonObject.getAsJsonObject("data");
        if (Objects.isNull(data) || !data.has("content") || data.get("content").isJsonNull()) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        for (var element : data.getAsJsonArray("content")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (item.get("is_dir").getAsBoolean()) {
                continue;
            }
            String name = item.get("name").getAsString();
            if (FileUtils.isVideoFormat(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private Optional<NotificationConfig> getOpenListUploadConfig() {
        List<NotificationConfig> notificationConfigList = ConfigUtil.CONFIG.getNotificationConfigList();
        return notificationConfigList.stream()
                .filter(Objects::nonNull)
                .filter(it -> Boolean.TRUE.equals(it.getEnable()))
                .filter(it -> NotificationTypeEnum.OPEN_LIST_UPLOAD == it.getNotificationType())
                .sorted(Comparator.comparingLong(NotificationConfig::getSort))
                .findFirst();
    }
}
