package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.Ani;
import ani.rss.entity.web.Result;
import ani.rss.service.StrmService;
import ani.rss.util.other.AniUtil;
import cn.hutool.core.thread.ThreadUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StrmController extends BaseController {

    @Resource
    private StrmService strmService;

    @Auth
    @Operation(summary = "生成 STRM")
    @PostMapping("/strm")
    public Result<Void> strm(@RequestParam("deleteLocalVideo") Boolean deleteLocalVideo, @RequestBody Ani ani) {
        ThreadUtil.execute(() -> strmService.generate(ani, deleteLocalVideo));
        return Result.success("已开始生成 STRM {}", ani.getTitle());
    }

    @Auth
    @Operation(summary = "生成全部 STRM")
    @PostMapping("/strmAll")
    public Result<Void> strmAll(@RequestParam("deleteLocalVideo") Boolean deleteLocalVideo) {
        ThreadUtil.execute(() -> AniUtil.ANI_LIST.forEach(ani -> strmService.generate(ani, deleteLocalVideo)));
        return Result.success("已开始生成全部 STRM");
    }
}
