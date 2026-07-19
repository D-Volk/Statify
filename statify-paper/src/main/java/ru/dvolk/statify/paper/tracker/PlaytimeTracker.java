package ru.dvolk.statify.paper.tracker;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.dvolk.statify.paper.db.Database;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Держит открытые сессии игроков в памяти и пишет в БД: (1) при выходе игрока,
 * (2) периодически как страховка от падения сервера (интервал из конфига),
 * (3) при остановке плагина. Пересечение полуночи разбивается на дневные
 * куски, каждый пишется своей строкой в statify_daily.
 */
public final class PlaytimeTracker {

    private final JavaPlugin plugin;
    private final Database database;
    private volatile String server;
    private volatile long flushIntervalTicks;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask task;

    public PlaytimeTracker(JavaPlugin plugin, Database database, String server, int flushIntervalMinutes) {
        this.plugin = plugin;
        this.database = database;
        this.server = server;
        this.flushIntervalTicks = 20L * 60L * Math.max(1, flushIntervalMinutes);
    }

    /** Меняет имя сервера. Перед сменой полностью сбрасывает накопленное время под старым именем. */
    public void applyServerName(String newName) {
        if (newName == null || newName.equals(server)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            flushAll();
            this.server = newName;
        });
    }

    public void applyFlushInterval(int minutes) {
        long newTicks = 20L * 60L * Math.max(1, minutes);
        if (newTicks == flushIntervalTicks) return;
        this.flushIntervalTicks = newTicks;
        if (task != null) {
            task.cancel();
            this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll, newTicks, newTicks);
        }
    }

    public void start() {
        long now = Instant.now().getEpochSecond();
        LocalDate today = Database.today();
        for (Player p : Bukkit.getOnlinePlayers()) {
            sessions.put(p.getUniqueId(), new Session(p.getName(), now, today));
        }
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll,
                flushIntervalTicks, flushIntervalTicks);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        flushAll();
    }

    public void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;
        long now = Instant.now().getEpochSecond();
        synchronized (this) {
            sessions.put(id, new Session(name, now, Database.today()));
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                database.upsertPlayer(id, name, ip);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "upsertPlayer failed", ex);
            }
        });
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        Session s;
        synchronized (this) {
            s = sessions.remove(id);
        }
        if (s == null) return;
        long now = Instant.now().getEpochSecond();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> flushSession(id, s, now));
    }

    private synchronized void flushAll() {
        long now = Instant.now().getEpochSecond();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            flushSession(e.getKey(), e.getValue(), now);
        }
    }

    private void flushSession(UUID id, Session s, long now) {
        if (now <= s.lastTick) {
            s.lastTick = now;
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        long cursor = s.lastTick;
        try {
            while (cursor < now) {
                LocalDate day = LocalDate.ofInstant(Instant.ofEpochSecond(cursor), zone);
                long nextMidnight = day.plusDays(1).atStartOfDay(zone).toEpochSecond();
                long chunkEnd = Math.min(nextMidnight, now);
                database.addPlaytime(server, id, day, chunkEnd - cursor);
                cursor = chunkEnd;
            }
            s.day = Database.today();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "addPlaytime failed for " + s.name, ex);
        }
        s.lastTick = now;
    }

    public String serverName() {
        return server;
    }

    public Database database() {
        return database;
    }

    /**
     * Возвращает текущий (незафиксированный ещё в БД) фрагмент секунд.
     * Полезно для плейсхолдеров, чтобы значение росло в реальном времени.
     */
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
