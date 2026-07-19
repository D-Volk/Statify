package ru.dvolk.statify.paper.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import ru.dvolk.statify.paper.db.Database;
import ru.dvolk.statify.paper.format.DurationFormat;
import ru.dvolk.statify.paper.tracker.PlaytimeTracker;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class StatifyExpansion extends PlaceholderExpansion {

    private static final long CACHE_TTL_MS = 5_000L;
    private static final long TOP_CACHE_TTL_MS = 30_000L;
    private static final int TOP_LIMIT = 20;

    private final JavaPlugin plugin;
    private final PlaytimeTracker tracker;
    private final Database database;
    private volatile String localServer;
    private volatile DurationFormat durationFormat;

    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedTop> topCache = new ConcurrentHashMap<>();

    public StatifyExpansion(JavaPlugin plugin, PlaytimeTracker tracker, Database database,
                            String localServer, DurationFormat durationFormat) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.database = database;
        this.localServer = localServer;
        this.durationFormat = durationFormat;
    }

    public void apply(String localServer, DurationFormat durationFormat) {
        this.localServer = localServer;
        this.durationFormat = durationFormat;
        cache.clear();
        topCache.clear();
    }

    @Override public String getIdentifier() { return "statify"; }
    @Override public String getAuthor() { return "dvolk"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || params == null) return "";
        String key = params.toLowerCase();
        UUID id = player.getUniqueId();

        // Литеральные, не связанные со временем
        switch (key) {
            case "name": return player.getName() == null ? "" : player.getName();
            case "uuid": return id.toString();
            case "server": return localServer;
        }

        if (key.startsWith("top_")) {
            return resolveTop(key.substring("top_".length()));
        }

        // Разбор: <metric>[_<server>] | <metric>_seconds[_<server>]
        // Пример: total | day_seconds | total_lobby | day_seconds_lobby
        return resolveTime(id, key);
    }

    private String resolveTop(String tail) {
        // Форматы:
        //   <N>_<field>              — текущий сервер
        //   <N>_<field>_<server>     — указанный сервер
        // field ∈ {name, uuid, time, seconds}
        int underscore = tail.indexOf('_');
        if (underscore < 0) return null;
        int n;
        try {
            n = Integer.parseInt(tail.substring(0, underscore));
        } catch (NumberFormatException e) { return null; }
        if (n < 1 || n > TOP_LIMIT) return "";

        String rest = tail.substring(underscore + 1);
        String field;
        String server;
        int u2 = rest.indexOf('_');
        if (u2 < 0) {
            field = rest;
            server = localServer;
        } else {
            field = rest.substring(0, u2);
            server = rest.substring(u2 + 1);
        }
        if (!isTopField(field)) return null;

        List<Database.TopEntry> top = getCachedTop(server);
        if (top == null || top.size() < n) return "";
        Database.TopEntry entry = top.get(n - 1);
        switch (field) {
            case "name": return entry.name;
            case "uuid": return entry.uuid;
            case "time": return format(entry.seconds);
            case "seconds": return Long.toString(entry.seconds);
            default: return null;
        }
    }

    private boolean isTopField(String f) {
        return "name".equals(f) || "uuid".equals(f) || "time".equals(f) || "seconds".equals(f);
    }

    private List<Database.TopEntry> getCachedTop(String server) {
        long now = System.currentTimeMillis();
        CachedTop ct = topCache.get(server);
        if (ct != null && now - ct.timestamp < TOP_CACHE_TTL_MS) {
            return ct.entries;
        }
        try {
            List<Database.TopEntry> fresh = database.getTopTotal(server, TOP_LIMIT);
            topCache.put(server, new CachedTop(fresh, now));
            return fresh;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Statify: top query failed for server " + server, ex);
            return ct == null ? null : ct.entries;
        }
    }

    private String resolveTime(UUID id, String key) {
        // Разбор: metric[_seconds][_<server>]
        // Сначала отделяем metric (total|day|week|month), затем интерпретируем остаток.
        // Такой порядок устраняет неоднозначность с именами серверов, содержащими "_seconds".
        String metric;
        String rest;
        int underscore = key.indexOf('_');
        if (underscore < 0) {
            metric = key;
            rest = "";
        } else {
            metric = key.substring(0, underscore);
            rest = key.substring(underscore + 1);
        }
        if (!isMetric(metric)) return null;

        boolean rawSeconds = false;
        String server;
        if (rest.isEmpty()) {
            server = localServer;
        } else if (rest.equals("seconds")) {
            rawSeconds = true;
            server = localServer;
        } else if (rest.startsWith("seconds_")) {
            rawSeconds = true;
            server = rest.substring("seconds_".length());
        } else {
            server = rest;
        }

        boolean local = server.equalsIgnoreCase(localServer);
        long seconds = query(id, metric, server);
        // Живой счётчик прибавляется только для локального сервера.
        if (local) seconds += tracker.pendingSeconds(id);

        return rawSeconds ? Long.toString(seconds) : format(seconds);
    }

    private boolean isMetric(String m) {
        return "total".equals(m) || "day".equals(m) || "week".equals(m) || "month".equals(m);
    }

    private long query(UUID id, String metric, String server) {
        String cacheKey = metric.charAt(0) + ":" + server + ":" + id;
        return getCachedSeconds(cacheKey, () -> {
            switch (metric) {
                case "total": return database.getTotalSeconds(server, id);
                case "day":   return database.getSecondsForDay(server, id, Database.today());
                case "week":  return database.getSecondsForWeek(server, id, Database.today());
                case "month": return database.getSecondsForMonth(server, id, Database.today());
                default: return 0L;
            }
        });
    }

    private long getCachedSeconds(String cacheKey, SqlLongSupplier supplier) {
        long now = System.currentTimeMillis();
        CachedValue cv = cache.get(cacheKey);
        if (cv != null && now - cv.timestamp < CACHE_TTL_MS) {
            return cv.value;
        }
        try {
            long value = supplier.get();
            cache.put(cacheKey, new CachedValue(value, now));
            return value;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Statify: DB query failed for " + cacheKey, ex);
            return cv == null ? 0L : cv.value;
        }
    }

    private String format(long seconds) {
        return durationFormat.format(seconds);
    }

    @FunctionalInterface
    private interface SqlLongSupplier {
        long get() throws SQLException;
    }

    private static final class CachedValue {
        final long value;
        final long timestamp;

        CachedValue(long value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }

    private static final class CachedTop {
        final List<Database.TopEntry> entries;
        final long timestamp;

        CachedTop(List<Database.TopEntry> entries, long timestamp) {
            this.entries = entries;
            this.timestamp = timestamp;
        }
    }
}
