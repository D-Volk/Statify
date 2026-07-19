package ru.dvolk.statify.forge.tracker;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ru.dvolk.statify.forge.StatifyMod;
import ru.dvolk.statify.forge.db.Database;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlaytimeTracker {

    private final Database database;
    private volatile String server;
    private volatile long flushIntervalTicks;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Statify-DB");
        t.setDaemon(true);
        return t;
    });

    private final Map<UUID, Session> sessions = new HashMap<>();
    private long tickCounter;

    public PlaytimeTracker(Database database, String server, int flushIntervalMinutes) {
        this.database = database;
        this.server = server;
        this.flushIntervalTicks = 20L * 60L * Math.max(1, flushIntervalMinutes);
    }

    public void bootstrap(MinecraftServer mcServer) {
        long now = Instant.now().getEpochSecond();
        LocalDate today = Database.today();
        synchronized (this) {
            for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
                sessions.put(p.getUUID(), new Session(p.getGameProfile().getName(), now, today));
            }
        }
    }

    public void onServerTick() {
        tickCounter++;
        if (tickCounter % flushIntervalTicks != 0) return;
        long now = Instant.now().getEpochSecond();
        Map<UUID, Session> snapshot;
        synchronized (this) {
            snapshot = new HashMap<>(sessions);
        }
        io.execute(() -> {
            for (Map.Entry<UUID, Session> e : snapshot.entrySet()) {
                flushSession(e.getKey(), e.getValue(), now);
            }
        });
    }

    public void applyServerName(String newName) {
        if (newName == null || newName.equals(server)) return;
        long now = Instant.now().getEpochSecond();
        Map<UUID, Session> snapshot;
        synchronized (this) {
            snapshot = new HashMap<>(sessions);
        }
        io.execute(() -> {
            for (Map.Entry<UUID, Session> e : snapshot.entrySet()) {
                flushSession(e.getKey(), e.getValue(), now);
            }
            this.server = newName;
        });
    }

    public void applyFlushInterval(int minutes) {
        this.flushIntervalTicks = 20L * 60L * Math.max(1, minutes);
    }

    public void shutdown() {
        long now = Instant.now().getEpochSecond();
        Map<UUID, Session> snapshot;
        synchronized (this) {
            snapshot = new HashMap<>(sessions);
            sessions.clear();
        }
        for (Map.Entry<UUID, Session> e : snapshot.entrySet()) {
            flushSession(e.getKey(), e.getValue(), now);
        }
        io.shutdown();
    }

    public void handleJoin(ServerPlayer player) {
        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        String ip = null;
        if (player.connection != null && player.connection.connection != null
                && player.connection.connection.getRemoteAddress() instanceof java.net.InetSocketAddress inet) {
            ip = inet.getAddress().getHostAddress();
        }
        final String playerIp = ip;
        long now = Instant.now().getEpochSecond();
        synchronized (this) {
            sessions.put(id, new Session(name, now, Database.today()));
        }
        io.execute(() -> {
            try {
                database.upsertPlayer(id, name, playerIp);
            } catch (SQLException ex) {
                StatifyMod.LOGGER.warn("upsertPlayer failed", ex);
            }
        });
    }

    public void handleQuit(ServerPlayer player) {
        UUID id = player.getUUID();
        Session s;
        synchronized (this) {
            s = sessions.remove(id);
        }
        if (s == null) return;
        long now = Instant.now().getEpochSecond();
        io.execute(() -> flushSession(id, s, now));
    }

    private void flushSession(UUID id, Session s, long now) {
        long cursor;
        synchronized (s) {
            if (now <= s.lastTick) {
                s.lastTick = now;
                return;
            }
            cursor = s.lastTick;
            s.lastTick = now;
            s.day = Database.today();
        }
        ZoneId zone = ZoneId.systemDefault();
        try {
            while (cursor < now) {
                LocalDate day = LocalDate.ofInstant(Instant.ofEpochSecond(cursor), zone);
                long nextMidnight = day.plusDays(1).atStartOfDay(zone).toEpochSecond();
                long chunkEnd = Math.min(nextMidnight, now);
                database.addPlaytime(server, id, day, chunkEnd - cursor);
                cursor = chunkEnd;
            }
        } catch (SQLException ex) {
            StatifyMod.LOGGER.warn("addPlaytime failed for {}", s.name, ex);
        }
    }

    public String serverName() {
        return server;
    }

    public Database database() {
        return database;
    }

    public synchronized long pendingSeconds(UUID id) {
        Session s = sessions.get(id);
        if (s == null) return 0L;
        return Math.max(0L, Instant.now().getEpochSecond() - s.lastTick);
    }

    private static final class Session {
        final String name;
        long lastTick;
        LocalDate day;

        Session(String name, long lastTick, LocalDate day) {
            this.name = name;
            this.lastTick = lastTick;
            this.day = day;
        }
    }
}
