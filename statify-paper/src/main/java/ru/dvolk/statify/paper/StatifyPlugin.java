package ru.dvolk.statify.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import ru.dvolk.statify.paper.command.StatifyCommand;
import ru.dvolk.statify.paper.config.StatifyConfig;
import ru.dvolk.statify.paper.db.Database;
import ru.dvolk.statify.paper.db.DatabaseType;
import ru.dvolk.statify.paper.format.DurationFormat;
import ru.dvolk.statify.paper.listener.PlayerSessionListener;
import ru.dvolk.statify.paper.placeholder.StatifyExpansion;
import ru.dvolk.statify.paper.tracker.PlaytimeTracker;

import java.util.logging.Level;

public final class StatifyPlugin extends JavaPlugin {

    private StatifyConfig config;
    private Database database;
    private PlaytimeTracker tracker;
    private StatifyExpansion expansion;

    @Override
    public void onEnable() {
        this.config = new StatifyConfig(this);
        try {
            this.config.loadOrMigrate();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Не удалось загрузить конфиг Statify", ex);
            disableSelf();
            return;
        }

        DatabaseType type = config.getDatabaseType();
        if (type == DatabaseType.NONE) {
            getLogger().warning("Тип БД не выбран (database.type=none). Плагин отключается.");
            getLogger().warning("Укажите database.type: mysql или mariadb в plugins/Statify/config.yml");
            disableSelf();
            return;
        }

        this.database = new Database(this, config);
        try {
            database.connect();
            database.initSchema();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Не удалось подключиться к БД", ex);
            disableSelf();
            return;
        }

        this.tracker = new PlaytimeTracker(this, database, config.getServerName(), config.getFlushIntervalMinutes());
        this.tracker.start();

        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(new PlayerSessionListener(tracker), this);

        DurationFormat durationFormat = new DurationFormat(
                config.getSuffixDay(), config.getSuffixHour(),
                config.getSuffixMinute(), config.getSuffixSecond(),
                config.getSeparator(), config.getZeroText());

        if (pm.getPlugin("PlaceholderAPI") != null) {
            this.expansion = new StatifyExpansion(this, tracker, database, config.getServerName(), durationFormat);
            this.expansion.register();
            getLogger().info("Расширение PlaceholderAPI зарегистрировано.");
        } else {
            getLogger().warning("PlaceholderAPI не найден — плейсхолдеры недоступны.");
        }

        StatifyCommand cmd = new StatifyCommand(this);
        if (getCommand("statify") != null) {
            getCommand("statify").setExecutor(cmd);
            getCommand("statify").setTabCompleter(cmd);
        }

        getLogger().info("Statify запущен. Сервер: " + config.getServerName() + ", БД: " + type);
    }

    /**
     * Перечитывает конфиг без пересоздания подключения к БД и трекера.
     * Применяются: server-name, flush-interval-minutes, вся секция format.
     * Секция database.* игнорируется — для смены подключения нужен перезапуск плагина.
     */
    public void reloadRuntime() throws java.io.IOException {
        config.reload();
        tracker.applyServerName(config.getServerName());
        tracker.applyFlushInterval(config.getFlushIntervalMinutes());
        if (expansion != null) {
            DurationFormat df = new DurationFormat(
                    config.getSuffixDay(), config.getSuffixHour(),
                    config.getSuffixMinute(), config.getSuffixSecond(),
                    config.getSeparator(), config.getZeroText());
            expansion.apply(config.getServerName(), df);
        }
    }

    @Override
    public void onDisable() {
        if (expansion != null) {
            expansion.unregister();
        }
        if (tracker != null) {
            tracker.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

    private void disableSelf() {
        Bukkit.getPluginManager().disablePlugin(this);
    }
}
