package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.RssDownloadRuleTemplate;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

@Slf4j
public class RssDownloadRuleUtil {
    public static boolean hit(Ani ani, String title) {
        if (StrUtil.isBlank(title)) {
            return false;
        }

        String ruleName = StrUtil.blankToDefault(ani.getRssDownloadRuleName(), "");
        if (StrUtil.isBlank(ruleName)) {
            return true;
        }

        Config config = ConfigUtil.CONFIG;
        List<RssDownloadRuleTemplate> templates = config.getRssDownloadRuleTemplates();
        if (Objects.isNull(templates) || templates.isEmpty()) {
            return true;
        }

        RssDownloadRuleTemplate template = templates.stream()
                .filter(it -> StrUtil.equals(ruleName, it.getName()))
                .findFirst()
                .orElse(null);
        if (Objects.isNull(template) || !Boolean.TRUE.equals(template.getEnable())) {
            return true;
        }

        boolean useRegex = Boolean.TRUE.equals(template.getUseRegex());
        String mustContain = StrUtil.blankToDefault(template.getMustContain(), "");
        String mustNotContain = StrUtil.blankToDefault(template.getMustNotContain(), "");

        try {
            if (StrUtil.isNotBlank(mustContain)) {
                boolean contain = useRegex ? ReUtil.contains(mustContain, title) : title.contains(mustContain);
                if (!contain) {
                    return false;
                }
            }

            if (StrUtil.isNotBlank(mustNotContain)) {
                boolean contain = useRegex ? ReUtil.contains(mustNotContain, title) : title.contains(mustNotContain);
                if (contain) {
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("RSS下载规则匹配失败 rule:{} title:{}", ruleName, title);
            log.error(e.getMessage(), e);
        }

        return true;
    }

    public static String resolveSubgroup(Ani ani, String title, String currentSubgroup) {
        if (StrUtil.isNotBlank(currentSubgroup) && !"未知字幕组".equals(currentSubgroup)) {
            return currentSubgroup;
        }

        String titleSubgroup = inferSubgroupFromTitle(title);
        if (StrUtil.isNotBlank(titleSubgroup)) {
            return titleSubgroup;
        }

        String ruleName = StrUtil.blankToDefault(ani.getRssDownloadRuleName(), "");
        if (StrUtil.isNotBlank(ruleName)) {
            return ruleName;
        }

        return currentSubgroup;
    }

    public static String inferSubgroupFromTitle(String title) {
        if (StrUtil.isBlank(title)) {
            return "";
        }

        String subgroup = ReUtil.get("^\\[([^\\]]+)]", title, 1);
        if (StrUtil.isNotBlank(subgroup)) {
            return subgroup.trim();
        }

        subgroup = ReUtil.get("^【([^】]+)】", title, 1);
        if (StrUtil.isNotBlank(subgroup)) {
            return subgroup.trim();
        }

        return "";
    }
}
