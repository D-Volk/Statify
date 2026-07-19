package ru.dvolk.statify.fabric.placeholder;

import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import ru.dvolk.statify.fabric.StatifyMod;
import ru.dvolk.statify.fabric.db.Database;
import ru.dvolk.statify.fabric.format.DurationFormat;
import ru.dvolk.statify.fabric.tracker.PlaytimeTracker;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StatifyPlaceholders {

    private static final long CACHE_TTL_MS = 5_000L;
    private static final long TOP_CACHE_TTL_MS = 30_000L;
    private static final int TOP_LIMIT = 20;
    private static final ConcurrentHashMap<String, CachedValue> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CachedTop> TOP_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, CachedIp> IP_CACHE = new ConcurrentHashMap<>();

    private static volatile String LOCAL_SERVER = "";
    private static volatile DurationFormat FORMAT;

    private StatifyPlaceholders() {}

    public static void apply(String localServer, DurationFormat df) {
        LOCAL_SERVER = localServer;
        FORMAT = df;
        CACHE.clear();
        TOP_CACHE.clear();
        IP_CACHE.clear();
    }

    private static String resolveIp(Database database, UUID id) {
        long now = System.currentTimeMillis();
        CachedIp c = IP_CACHE.get(id);
        if (c != null && now - c.timestamp < CACHE_TTL_MS) {
            return c.value == null ? "" : c.value;
        }
        try {
            String ip = database.getLastIp(id);
            IP_CACHE.put(id, new CachedIp(ip, now));
            return ip == null ? "" : ip;
        } catch (SQLException ex) {
            StatifyMod.LOGGER.warn("Statify: ip query failed for {}", id, ex);
            return c == null ? "" : (c.value == null ? "" : c.value);
        }
    }

    private static final class CachedIp {
        final String value;
        final long timestamp;

        CachedIp(String value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }

    public static void register(PlaytimeTracker tracker, Database database, String localServer, DurationFormat df) {
        apply(localServer, df);
        Placeholders.register(Identifier.of(StatifyMod.MOD_ID, "name"), (ctx, arg) -> {
            ServerPlayerEntity p = ctx.player();
            return p == null ? PlaceholderResult.invalid() : PlaceholderResult.value(p.getGameProfile().getName());
        });
        Placeholders.register(Identifier.of(StatifyMod.MOD_ID, "uuid"), (ctx, arg) -> {
            ServerPlayerEntity p = ctx.player();
            return p == null ? PlaceholderResult.invalid() : PlaceholderResult.value(p.getUuidAsString());
        });
        Placeholders.register(Identifier.of(StatifyMod.MOD_ID, "server"), (ctx, arg) -> PlaceholderResult.value(LOCAL_SERVER));
        Placeholders.register(Identifier.of(StatifyMod.MOD_ID, "ip"), (ctx, arg) -> {
            ServerPlayerEntity p = ctx.player();
            if (p == null) return PlaceholderResult.invalid();
            return PlaceholderResult.value(resolveIp(database, p.getUuid()));
        });

        registerMetric("total", tracker, database, (db, srv, id) -> db.getTotalSeconds(srv, id));
        registerMetric("day",   tracker, database, (db, srv, id) -> db.getSecondsForDay(srv, id, Database.today()));
        registerMetric("week",  tracker, database, (db, srv, id) -> db.getSecondsForWeek(srv, id, Database.today()));
        registerMetric("month", tracker, database, (db, srv, id) -> db.getSecondsForMonth(srv, id, Database.today()));

        registerTopField("top_name",    database, e -> e.name);
        registerTopField("top_uuid",    database, e -> e.uuid);
        registerTopField("top_time",    database, e -> FORMAT.format(e.seconds));
        registerTopField("top_seconds", database, e -> Long.toString(e.seconds));
    }

    private static void registerTopField(String id, Database database,
                                         java.util.function.Function<Database.TopEntry, String> extractor) {
        Placeholders.register(Identifier.of(StatifyMod.MOD_ID, id), (ctx, arg) -> {
            if (arg == null || arg.isBlank()) return PlaceholderResult.invalid();
            String rank;
            String server;
            int slash = arg.indexOf('/');
            if (slash < 0) {
                rank = arg.trim();
                server = LOCAL_SERVER;
            } else {
                rank = arg.substring(0, slash).trim();
                server = arg.substring(slash + 1).trim();
                if (server.isEmpty()) server = LOCAL_SERVER;
            }
            int n;
            try { n = Integer.parseInt(rank); } catch (NumberFormatException e) { return PlaceholderResult.invalid(); }
            if (n < 1 || n > TOP_LIMIT) return PlaceholderResult.value("");

            List<Database.TopEntry> top = getCachedTop(database, server);
            if (top == null || top.size() < n) return PlaceholderResult.value("");
            return PlaceholderResult.value(extractor.apply(top.get(n - 1)));
        });
    }

    private static List<Database.TopEntry> getCachedTop(Database database, String server) {
        long now = System.currentTimeMillis();
        CachedTop ct = TOP_CACHE.get(server);
        if (ct != null && now - ct.timestamp < TOP_CACHE_TTL_MS) {
            return ct.entries;
        }
        try {
            List<Database.TopEntry> fresh = database.getTopTotal(server, TOP_LIMIT);
            TOP_CACHE.put(server, new CachedTop(fresh, now));
            return fresh;
        } catch (SQLException ex) {
            StatifyMod.LOGGER.warn("Statify: top query failed for server {}", server, ex);
            return ct == null ? null : ct.entries;
        }
    }

    private static void registerMetric(String name, PlaytimeTracker tracker, Database database, DbQuery query) {
        Placeholders.register(Identifier.of(StatifyMod.MOD_ID, name), (ctx, arg) -> {
            ServerPlayerEntity p = ctx.player();
            if (p == null) return PlaceholderResult.invalid();
            UUID id = p.getUuid();
            String server = (arg == null || arg.isBlank()) ? LOCAL_SERVER : arg.trim();
            long s = cached(name.charAt(0) + ":" + server + ":" + id, () -> query.get(database, server, id));
            if (server.equalsIgnoreCase(LOCAL_SERVER)) {
                s += tracker.pendingSeconds(id);
            }
            return PlaceholderResult.value(FORMAT.format(s));
        });
    }

    private static long cached(String key, SqlLongSupplier supplier) {
        long now = System.currentTimeMillis();
        CachedValue cv = CACHE.get(key);
        if (cv != null && now - cv.timestamp < CACHE_TTL_MS) {
            return cv.value;
        }
        try {
            long v = supplier.get();
            CACHE.put(key, new CachedValue(v, now));
            return v;
        } catch (SQLException ex) {
            StatifyMod.LOGGER.warn("Statify: DB query failed for {}", key, ex);
            return cv == null ? 0L : cv.value;
        }
    }

    @FunctionalInterface
    private interface DbQuery {
        long get(Database db, String server, UUID id) throws SQLException;
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
