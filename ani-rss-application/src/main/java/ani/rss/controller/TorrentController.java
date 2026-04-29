package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.TorrentsInfo;
import ani.rss.entity.web.Result;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
public class TorrentController extends BaseController {
    @Resource
    private DownloadService downloadService;

    @Auth
    @Operation(summary = "删除缓存种子")
    @PostMapping("/deleteTorrent")
    public Result<Void> deleteTorrent(@RequestParam("id") String id, @RequestParam("hash") String hash) {
        Optional<Ani> first = AniUtil.ANI_LIST.stream()
                .filter(ani -> id.equals(ani.getId()))
                .findFirst();
        if (first.isEmpty()) {
            return Result.error("此订阅不存在");
        }

        List<String> hashList = StrUtil.split(hash, ",", true, true);
        Ani ani = first.get();
        File torrentDir = TorrentUtil.getTorrentDir(ani);
        File[] files = FileUtils.listFiles(torrentDir);
        for (File file : files) {
            String name = FileUtil.mainName(file);
            if (hashList.contains(name)) {
                log.info("删除种子 {}", file);
                FileUtil.del(file);
            }
        }
        return Result.success("删除完成");
    }

    @Auth
    @Operation(summary = "重新执行已完成任务的上传通知")
    @PostMapping("/retryUpload")
    public Result<Void> retryUpload(@RequestParam("hash") String hash) {
        List<String> hashList = StrUtil.split(hash, ",", true, true);
        List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();

        int count = 0;
        for (TorrentsInfo torrentsInfo : torrentsInfos) {
            if (!hashList.contains(torrentsInfo.getHash())) {
                continue;
            }
            downloadService.retryNotification(torrentsInfo);
            count++;
        }

        if (count < 1) {
            return Result.error("未找到对应任务");
        }
        return Result.success("已加入重试队列");
    }
}
