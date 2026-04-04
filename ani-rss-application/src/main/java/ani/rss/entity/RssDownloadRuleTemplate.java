package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
@Schema(description = "RSS下载规则模板")
public class RssDownloadRuleTemplate implements Serializable {
    @Schema(description = "规则名称")
    private String name;

    @Schema(description = "启用")
    private Boolean enable;

    @Schema(description = "是否使用正则")
    private Boolean useRegex;

    @Schema(description = "必须包含")
    private String mustContain;

    @Schema(description = "禁止包含")
    private String mustNotContain;
}
