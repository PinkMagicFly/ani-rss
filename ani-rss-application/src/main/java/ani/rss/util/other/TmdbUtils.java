package ani.rss.util.other;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.CustomTmdbConfig;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.TmdbUtil;
import wushuo.tmdb.api.entity.*;
import wushuo.tmdb.api.enums.TmdbTypeEnum;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * tmdb封装
 */
@Slf4j
public class TmdbUtils {
    public final static TmdbConfig config = new CustomTmdbConfig();
    public final static TmdbUtil TMDB_UTIL = new TmdbUtil(config);

    /**
     * 获取番剧在tmdb的名称
     *
     * @param ani 订阅
     * @return
     */
    public synchronized static String getFinalName(Ani ani) {
        Boolean ova = ani.getOva();
        String name = ani.getTitle();
        name = RenameUtil.renameDel(name, false);
        if (StrUtil.isBlank(name)) {
            return "";
        }

        Optional<Tmdb> tmdb;
        try {
            if (ova) {
                tmdb = getTmdbMovie(name);
                if (tmdb.isEmpty()) {
                    tmdb = getTmdbTv(name);
                }
            } else {
                tmdb = getTmdbTv(name);
                if (tmdb.isEmpty() && isMovieFallbackCandidate(ani)) {
                    tmdb = getTmdbMovie(name);
                    if (tmdb.isPresent()) {
                        // 单话作品补查到电影条目后，后续刮削需要走电影分支
                        ani.setOva(true);
                        log.info("{} TMDB TV未匹配到，回退到电影条目 {}", ani.getTitle(), tmdb.get().getId());
                    }
                }
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
            return "";
        }

        tmdb.ifPresent(ani::setTmdb);

        if (tmdb.isEmpty()) {
            return "";
        }

        String themoviedbName = tmdb.get().getName();
        return getFinalName(themoviedbName, tmdb.get());
    }

    private static boolean isMovieFallbackCandidate(Ani ani) {
        if (Objects.isNull(ani)) {
            return false;
        }
        Integer totalEpisodeNumber = ani.getTotalEpisodeNumber();
        return Objects.equals(totalEpisodeNumber, 1);
    }

    /**
     * 获取添加tmdbid与年份后的名称
     *
     * @param title 标题
     * @param tmdb  tmdb
     * @return
     */
    public static String getFinalName(String title, Tmdb tmdb) {
        if (Objects.isNull(tmdb)) {
            return title;
        }
        Config config = ConfigUtil.CONFIG;

        boolean titleYear = config.getTitleYear();
        if (titleYear) {
            title = RenameUtil.renameDel(title, false);
            title = StrFormatter.format("{} ({})", title, DateUtil.year(tmdb.getDate()));
        }

        boolean tmdbId = config.getTmdbId();
        boolean tmdbIdPlexMode = config.getTmdbIdPlexMode();
        if (tmdbId) {
            if (tmdbIdPlexMode) {
                title = StrFormatter.format("{} {tmdb-{}}", title, tmdb.getId());
            } else {
                title = StrFormatter.format("{} [tmdbid={}]", title, tmdb.getId());
            }
        }
        return RenameUtil.getName(title);
    }

    /**
     * 获取所有标题
     *
     * @param tmdb     tmdb
     * @param tmdbType 类型
     * @return
     */
    public static List<TmdbTitle> getTitles(Tmdb tmdb, TmdbTypeEnum tmdbType) {
        return TMDB_UTIL.getTitles(tmdb, tmdbType);
    }

    /**
     * 获取罗马音
     *
     * @param tmdb     tmdb
     * @param tmdbType 类型
     */
    public static void getRomaji(Tmdb tmdb, TmdbTypeEnum tmdbType) {
        if (Objects.isNull(tmdb)) {
            return;
        }

        Config config = ConfigUtil.CONFIG;
        Boolean tmdbRomaji = config.getTmdbRomaji();
        if (!tmdbRomaji) {
            // 未开启罗马音
            return;
        }

        List<TmdbTitle> titles = getTitles(tmdb, tmdbType);

        for (TmdbTitle tmdbTitle : titles) {
            String iso31661 = tmdbTitle.getIso31661();
            String type = tmdbTitle.getType();
            String title = tmdbTitle.getTitle();
            if (!iso31661.equals("JP")) {
                continue;
            }
            if (List.of("romaji", "romanization").contains(type.toLowerCase())) {
                title = RenameUtil.getName(title);
                // 判断为罗马音
                tmdb.setName(title);
                return;
            }
        }

        String romaji = "";
        try {
            romaji = AniListUtil.getRomaji(tmdb.getName());
            romaji = RenameUtil.getName(romaji);
        } catch (Exception e) {
            log.error("通过AniList获取罗马音失败");
            log.error(e.getMessage(), e);
        }
        if (StrUtil.isNotBlank(romaji)) {
            tmdb.setName(romaji);
        }
    }

    /**
     * 根据标题获得tmdb
     *
     * @param titleName 标题名
     * @return
     */
    public static Optional<Tmdb> getTmdbMovie(String titleName) {
        Optional<Tmdb> tmdb = getTmdb(titleName, TmdbTypeEnum.MOVIE);
        tmdb.ifPresent(it -> getRomaji(it, TmdbTypeEnum.MOVIE));
        return tmdb;
    }

    /**
     * 根据标题获得tmdb
     *
     * @param titleName 标题名
     * @return
     */
    public static Optional<Tmdb> getTmdbTv(String titleName) {
        Optional<Tmdb> tmdb = getTmdb(titleName, TmdbTypeEnum.TV);
        tmdb.ifPresent(it -> getRomaji(it, TmdbTypeEnum.TV));
        return tmdb;
    }

    /**
     * 根据名称获取tmdb信息
     *
     * @param titleName 标题名
     * @param tmdbType  类型
     * @return
     */
    public static Optional<Tmdb> getTmdb(String titleName, TmdbTypeEnum tmdbType) {
        Optional<Tmdb> tmdb = TMDB_UTIL.getTmdb(titleName, tmdbType);
        if (tmdb.isPresent()) {
            return tmdb;
        }
        return searchTmdbByApi(titleName, tmdbType);
    }

    private static Optional<Tmdb> searchTmdbByApi(String titleName, TmdbTypeEnum tmdbType) {
        if (StrUtil.isBlank(titleName)) {
            return Optional.empty();
        }

        Config config = ConfigUtil.CONFIG;
        String api = StrUtil.blankToDefault(config.getTmdbApi(), "https://api.themoviedb.org");
        String apiKey = config.getTmdbApiKey();
        apiKey = StrUtil.blankToDefault(apiKey, "450e4f651e1c93e31383e20f8e731e5f");
        String language = StrUtil.blankToDefault(config.getTmdbLanguage(), "zh-CN");
        String mediaType = tmdbType == TmdbTypeEnum.MOVIE ? "movie" : "tv";
        String url = StrFormatter.format(
                "{}/3/search/{}?api_key={}&language={}&query={}",
                api,
                mediaType,
                apiKey,
                language,
                URLUtil.encodeQuery(titleName, java.nio.charset.StandardCharsets.UTF_8)
        );

        try {
            return HttpReq.get(url)
                    .timeout(5000)
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject body = GsonStatic.fromJson(res.body(), JsonObject.class);
                        JsonArray results = body.getAsJsonArray("results");
                        if (Objects.isNull(results) || results.isEmpty()) {
                            return Optional.<Tmdb>empty();
                        }
                        JsonObject first = results.get(0).getAsJsonObject();
                        String id = first.get("id").getAsString();
                        String title = first.has("title") && !first.get("title").isJsonNull() ?
                                first.get("title").getAsString() :
                                first.get("name").getAsString();
                        String dateStr = first.has("release_date") && !first.get("release_date").isJsonNull() ?
                                first.get("release_date").getAsString() :
                                first.has("first_air_date") && !first.get("first_air_date").isJsonNull() ?
                                        first.get("first_air_date").getAsString() : "";
                        DateTime date = StrUtil.isBlank(dateStr) ? DateUtil.date() : DateUtil.parseDate(dateStr);
                        Tmdb seed = new Tmdb()
                                .setId(id)
                                .setName(title)
                                .setDate(date);
                        Optional<Tmdb> detail = getTmdb(seed, tmdbType);
                        if (detail.isPresent()) {
                            return detail;
                        }
                        return Optional.of(seed);
                    });
        } catch (Exception e) {
            log.warn("TMDB官方搜索兜底失败 {} {}", tmdbType, titleName);
            log.warn(ExceptionUtils.getMessage(e));
            return Optional.empty();
        }
    }

    /**
     * 获取季信息
     *
     * @param tmdb   tmdb
     * @param season 季
     * @return
     */
    public static Optional<TmdbSeason> getTmdbSeason(Tmdb tmdb, Integer season) {
        return TMDB_UTIL.getTmdbSeason(tmdb, season);
    }

    /**
     * 获取每集的标题
     *
     * @param ani 订阅
     * @return
     */
    public static synchronized Map<Integer, String> getEpisodeTitleMap(Ani ani) {
        Map<Integer, String> episodeTitleMap = new HashMap<>();

        if (Objects.isNull(ani)) {
            return episodeTitleMap;
        }

        Tmdb tmdb = ani.getTmdb();
        Integer season = ani.getSeason();
        Boolean ova = ani.getOva();

        if (ova) {
            return episodeTitleMap;
        }

        if (Objects.isNull(tmdb)) {
            return episodeTitleMap;
        }

        String tmdbId = tmdb.getId();
        String tmdbGroupId = tmdb.getTmdbGroupId();

        String key = StrFormatter.format("TMDB_getEpisodeTitleMap:{}:{}:{}", tmdbId, tmdbGroupId, season);

        Map<Integer, String> cacheMap = CacheUtils.get(key);
        if (Objects.nonNull(cacheMap)) {
            return cacheMap;
        }

        episodeTitleMap = getEpisodeTitleMap(tmdb, season);
        if (episodeTitleMap.isEmpty()) {
            CacheUtils.put(key, episodeTitleMap, 1000 * 10);
        } else {
            CacheUtils.put(key, episodeTitleMap, TimeUnit.MINUTES.toMillis(5));
        }
        return episodeTitleMap;
    }

    /**
     * 获取每集的标题
     *
     * @param tmdb   tmdb
     * @param season 季
     * @return
     */
    public static Map<Integer, String> getEpisodeTitleMap(Tmdb tmdb, Integer season) {
        return TMDB_UTIL.getEpisodeTitleMap(tmdb, season);
    }

    /**
     * 获取剧集组
     *
     * @param tmdb tmdb
     * @return
     */
    public static List<TmdbGroup> getTmdbGroup(Tmdb tmdb) {
        return TMDB_UTIL.getTmdbGroup(tmdb);
    }

    /**
     * 获取图片
     *
     * @param tmdb     tmdb
     * @param tmdbType 类型
     * @return
     */
    public static TmdbImages getTmdbImages(Tmdb tmdb, TmdbTypeEnum tmdbType) {
        return TMDB_UTIL.getTmdbImages(tmdb, tmdbType);
    }

    public static Optional<Tmdb> getTmdb(Tmdb tmdb, TmdbTypeEnum tmdbTypeEnum) {
        return TMDB_UTIL.getTmdb(tmdb, tmdbTypeEnum);
    }
}
