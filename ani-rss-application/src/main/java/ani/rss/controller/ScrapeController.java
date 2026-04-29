package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.Ani;
import ani.rss.entity.web.Result;
import ani.rss.service.ScrapeService;
import ani.rss.util.other.AniUtil;
import cn.hutool.core.thread.ThreadUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScrapeController extends BaseController {

    @Resource
    private ScrapeService scrapeService;

    @Auth
    @Operation(summary = "刮削")
    @PostMapping("/scrape")
    public Result<Void> scrape(@RequestParam("force") Boolean force, @RequestBody Ani ani) {
        ThreadUtil.execute(() ->
                scrapeService.scrape(ani, force)
        );

        String title = ani.getTitle();

        return Result.success("已开始刮削 {}", title);
    }

    @Auth
    @Operation(summary = "STRM目录刮削")
    @PostMapping("/scrapeStrm")
    public Result<Void> scrapeStrm(@RequestParam("force") Boolean force, @RequestBody Ani ani) {
        ThreadUtil.execute(() ->
                scrapeService.scrapeStrm(ani, force)
        );

        String title = ani.getTitle();

        return Result.success("已开始 STRM 目录刮削 {}", title);
    }

    @Auth
    @Operation(summary = "全量STRM目录刮削")
    @PostMapping("/scrapeStrmAll")
    public Result<Void> scrapeStrmAll(@RequestParam("force") Boolean force) {
        ThreadUtil.execute(() -> AniUtil.ANI_LIST.forEach(ani ->
                scrapeService.scrapeStrm(ani, force)
        ));
        return Result.success("已开始全量 STRM 目录刮削");
    }
}
