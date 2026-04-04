package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 本地文件追踪记录
 */
@Data
@Accessors(chain = true)
@Schema(description = "本地文件追踪记录")
public class LocalFileRecord implements Serializable {
    @Schema(description = "文件绝对路径")
    private String path;

    @Schema(description = "记录时间戳")
    private Long trackedAt;
}
