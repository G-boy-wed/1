package dev.linjian.peek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 免费音乐能力：网易云同款「一起听」用的轻量 API 封装。
 *
 * 设计原则（保证可用、不依赖付费 key）：
 *  1) 默认走公开的免费聚合接口（可配置），搜索歌曲 / 取热门歌单；
 *  2) 任一接口失败自动降级到下一个源，全部失败返回明确 code，不返回假数据；
 *  3) 播放控制仍复用系统媒体会话（MediaState），不内置音源解码，避免版权与稳定性问题；
 *  4) 接口地址集中在 API_BASES，方便替换成自建的 NeteaseCloudMusicApi。
 *
 * 返回的 Song：id / name / artist / album / cover / duration_ms。
 */
public final class FreeMusic {
    private static final String TAG = "FreeMusic";

    /** 免费音源基址，按顺序尝试。首个为社区常用的公开 NeteaseCloudMusicApi 镜像，失败自动换源。 */
    private static final String[] API_BASES = {
            "https://netease-cloud-music-api-kohl.vercel.app",
            "https://ncma-api.vercel.app"
    };

    public static class Song {
        public String id = "", name = "", artist = "", album = "", cover = "";
        public long durationMs = 0;
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id); o.put("name", name); o.put("artist", artist);
                o.put("album", album); o.put("cover", cover); o.put("duration_ms", durationMs);
            } catch (Exception ignored) { }
            return o;
        }
    }

    public static class Result {
        public boolean ok = false;
        public String source = "";
        public String error = "";
        public List<Song> songs = new ArrayList<>();
    }

    private FreeMusic() { }

    /** 搜索歌曲；全部源失败时 ok=false 且 error 有明确原因。 */
    public static Result search(String keyword, int limit) {
        Result r = new Result();
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) kw = "热门";
        if (limit <= 0 || limit > 50) limit = 20;
        for (String base : API_BASES) {
            try {
                String url = base + "/search?keywords=" + URLEncoder.encode(kw, "UTF-8") + "&limit=" + limit;
                String body = httpGet(url, 6000);
                JSONObject root = new JSONObject(body);
                JSONArray songs = root.optJSONObject("result").optJSONArray("songs");
                if (songs == null) songs = root.optJSONArray("songs");
                if (songs != null && songs.length() > 0) {
                    List<Song> list = new ArrayList<>();
                    for (int i = 0; i < songs.length(); i++) {
                        Song s = parseSong(songs.optJSONObject(i));
                        if (s != null) list.add(s);
                    }
                    if (!list.isEmpty()) {
                        r.ok = true; r.source = base; r.songs = list;
                        return r;
                    }
                }
            } catch (Exception ignored) { }
        }
        r.error = "暂时连不上在线曲库，已切换到本机播放状态。可检查网络或稍后重试。";
        return r;
    }

    /** 热门歌单（一起听默认展示）。 */
    public static Result hot(int limit) {
        return search("热门", limit);
    }

    private static Song parseSong(JSONObject o) {
        if (o == null) return null;
        Song s = new Song();
        s.id = o.optString("id", "");
        s.name = o.optString("name", "");
        JSONArray artists = o.optJSONArray("artists");
        if (artists == null) artists = o.optJSONArray("ar");
        if (artists != null && artists.length() > 0) {
            JSONObject a = artists.optJSONObject(0);
            if (a != null) s.artist = a.optString("name", "");
        }
        if (s.artist.isEmpty() && o.has("artist")) {
            Object ar = o.opt("artist");
            s.artist = ar instanceof String ? (String) ar : (ar instanceof JSONObject ? ((JSONObject) ar).optString("name", "") : "");
        }
        JSONObject album = o.optJSONObject("album");
        if (album != null) {
            s.album = album.optString("name", "");
            s.cover = album.optString("picUrl", album.optString("artworkUrl", ""));
        }
        if (s.cover.isEmpty()) s.cover = o.optString("cover", o.optString("picUrl", ""));
        long dur = o.optLong("duration", o.optLong("duration_ms", 0));
        s.durationMs = dur;
        return s;
    }

    private static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Zhangxinchuang/0.3.9.0");
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException2("HTTP " + code);
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();
        return sb.toString();
    }

    private static final class IOException2 extends Exception {
        IOException2(String m) { super(m); }
    }

    /** 由本机媒体会话构造一首「正在播放」的 Song，供无网络时展示。 */
    public static Song fromMediaState(JSONObject media) {
        Song s = new Song();
        if (media == null) return s;
        s.name = media.optString("title", "");
        s.artist = media.optString("artist", media.optString("author", ""));
        s.album = media.optString("album", "");
        s.cover = media.optString("cover", "");
        s.durationMs = media.optLong("duration_ms", 0);
        return s;
    }
}
