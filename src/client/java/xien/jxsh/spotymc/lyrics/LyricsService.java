package xien.jxsh.spotymc.lyrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches synced (LRC) lyrics from LRCLIB (https://lrclib.net), a free,
 * no-auth lyrics database -- Spotify's own API does not expose lyrics.
 * Results are cached per track so re-plays don't re-fetch.
 */
public class LyricsService {
    private static final String SEARCH_URL = "https://lrclib.net/api/get";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern LINE_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})]\\s*(.*)");

    private final ConcurrentHashMap<String, List<LyricLine>> cache = new ConcurrentHashMap<>();

    public List<LyricLine> fetch(String trackTitle, String artistName, int durationMs)
            throws Exception {
        String key = (artistName + "|" + trackTitle).toLowerCase();
        List<LyricLine> cached = cache.get(key);
        if (cached != null) return cached;

        String url = SEARCH_URL
                + "?track_name=" + enc(trackTitle)
                + "&artist_name=" + enc(artistName)
                + "&duration=" + (durationMs / 1000);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "spotymc-minecraft (https://github.com/xienjxsh)")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

        List<LyricLine> lines;
        if (resp.statusCode() == 200) {
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (json.has("syncedLyrics") && !json.get("syncedLyrics").isJsonNull()) {
                lines = parseLrc(json.get("syncedLyrics").getAsString());
            } else {
                lines = List.of(); // instrumental or no synced lyrics available
            }
        } else {
            lines = List.of();
        }

        cache.put(key, lines);
        return lines;
    }

    private List<LyricLine> parseLrc(String lrc) {
        List<LyricLine> lines = new ArrayList<>();
        for (String rawLine : lrc.split("\\r?\\n")) {
            Matcher m = LINE_PATTERN.matcher(rawLine);
            if (!m.matches()) continue;
            int min = Integer.parseInt(m.group(1));
            int sec = Integer.parseInt(m.group(2));
            String fracStr = m.group(3);
            int frac = Integer.parseInt(fracStr);
            int fracMs = fracStr.length() == 2 ? frac * 10 : frac;
            int timeMs = min * 60_000 + sec * 1000 + fracMs;
            String text = m.group(4).trim();
            lines.add(new LyricLine(timeMs, text));
        }
        lines.sort((a, b) -> Integer.compare(a.timeMs(), b.timeMs()));
        return lines;
    }

    /** Returns the line that should be showing at the given playback position, or null if before the first line. */
    public static LyricLine currentLine(List<LyricLine> lines, int positionMs) {
        LyricLine current = null;
        for (LyricLine line : lines) {
            if (line.timeMs() <= positionMs) current = line;
            else break;
        }
        return current;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
