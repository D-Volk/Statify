package ru.dvolk.statify.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import ru.dvolk.statify.velocity.command.StatifyCommand;
import ru.dvolk.statify.velocity.config.StatifyConfig;
import ru.dvolk.statify.velocity.db.Database;
import ru.dvolk.statify.velocity.db.DatabaseType;
import ru.dvolk.statify.velocity.listener.StatifyListener;

import java.nio.file.Path;

@Plugin(
        id = "statify",
        name = "Statify",
        version = "1.0.0",
        description = "Sticky-connect для Velocity: возвращает игрока на последний сервер.",
        authors = {"dvolk"}
)
public final class StatifyPlugin {

    public static final String PLUGIN_VERSION = "1.0.0";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private StatifyConfig config;
    private Database database;
    private volatile boolean active;

    @Inject
    public StatifyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        this.config = new StatifyConfig(dataDirectory, PLUGIN_VERSION, logger);
        try {
            config.loadOrMigrate();
        } catch (Exception ex) {
            logger.error("Не удалось загрузить конфиг Statify", ex);
            return;
        }

        DatabaseType type = config.getDatabaseType();
        if (type == DatabaseType.NONE) {
            logger.warn("Тип БД не выбран (database.type=none). Плагин Statify неактивен.");
            logger.warn("Укажите database.type: mysql или mariadb в {}", dataDirectory.resolve("config.yml"));
            return;
        }

        this.database = new Database(config, logger);
        try {
            database.connect();
            database.initSchema();
        } catch (Exception ex) {
            logger.error("Не удалось подключиться к БД", ex);
            return;
        }

        this.active = true;

        StatifyListener listener = new StatifyListener(this, proxy, database, config, logger);
        proxy.getEventManager().register(this, listener);

        StatifyCommand.register(proxy, this, database, logger);

        logger.info("Statify (Velocity) запущен. Редирект: {}, БД: {}", config.isRedirectEnabled(), type);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (database != null) {
            database.close();
        }
    }

    public void reloadRuntime() throws java.io.IOException {
        if (!active) throw new java.io.IOException("Плагин не активен (БД не подключена).");
        config.reload();
    }

    public StatifyConfig getConfig() { return config; }
    public boolean isActive() { return active; }
}
