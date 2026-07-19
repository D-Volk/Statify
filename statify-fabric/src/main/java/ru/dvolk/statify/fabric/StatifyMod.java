package ru.dvolk.statify.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dvolk.statify.fabric.command.StatifyCommand;
import ru.dvolk.statify.fabric.config.StatifyConfig;
import ru.dvolk.statify.fabric.db.Database;
import ru.dvolk.statify.fabric.db.DatabaseType;
import ru.dvolk.statify.fabric.format.DurationFormat;
import ru.dvolk.statify.fabric.placeholder.StatifyPlaceholders;
import ru.dvolk.statify.fabric.tracker.PlaytimeTracker;

import java.nio.file.Path;

public final class StatifyMod implements ModInitializer {

    public static final String MOD_ID = "statify";
    public static final Logger LOGGER = LoggerFactory.getLogger("Statify");

    private static StatifyMod instance;

    private StatifyConfig config;
    private Database database;
    private PlaytimeTracker tracker;
    private volatile boolean active;

    @Override
    public void onInitialize() {
        instance = this;
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        this.config = new StatifyConfig(configDir, currentModVersion());

        try {
            config.loadOrMigrate();
        } catch (Exception ex) {
            LOGGER.error("Не удалось загрузить конфиг Statify", ex);
            return;
        }

        DatabaseType type = config.getDatabaseType();
        if (type == DatabaseType.NONE) {
            LOGGER.warn("Тип БД не выбран (database.type=none). Модуль Statify отключён.");
            LOGGER.warn("Укажите database.type: mysql или mariadb в " + configDir.resolve("config.yml"));
            return;
        }

        this.database = new Database(config);
        try {
            database.connect();
            database.initSchema();
        } catch (Exception ex) {
            LOGGER.error("Не удалось подключиться к БД", ex);
            return;
        }

        this.tracker = new PlaytimeTracker(database, config.getServerName(), config.getFlushIntervalMinutes());
        this.active = true;

        ServerLifecycleEvents.SERVER_STARTED.register(server -> tracker.start(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            tracker.shutdown();
            database.close();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                tracker.handleJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                tracker.handleQuit(handler.getPlayer()));

        DurationFormat durationFormat = new DurationFormat(
                config.getSuffixDay(), config.getSuffixHour(),
                config.getSuffixMinute(), config.getSuffixSecond(),
                config.getSeparator(), config.getZeroText());

        if (FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            StatifyPlaceholders.register(tracker, database, config.getServerName(), durationFormat);
            LOGGER.info("Плейсхолдеры Statify зарегистрированы.");
        } else {
            LOGGER.warn("placeholder-api не найден — плейсхолдеры недоступны.");
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                StatifyCommand.register(dispatcher));

        LOGGER.info("Statify инициализирован. Сервер: {}, БД: {}", config.getServerName(), type);
    }

    /**
     * Перечитывает конфиг без пересоздания подключения к БД и трекера.
     * Применяются: server-name, flush-interval-minutes, вся секция format.
     * Секция database.* игнорируется — для смены подключения нужен перезапуск сервера.
     */
    public void reloadRuntime() throws java.io.IOException {
        if (!active) throw new java.io.IOException("Модуль не активен (БД не подключена).");
        config.reload();
        tracker.applyServerName(config.getServerName());
        tracker.applyFlushInterval(config.getFlushIntervalMinutes());
        DurationFormat df = new DurationFormat(
                config.getSuffixDay(), config.getSuffixHour(),
                config.getSuffixMinute(), config.getSuffixSecond(),
                config.getSeparator(), config.getZeroText());
        StatifyPlaceholders.apply(config.getServerName(), df);
    }

    private String currentModVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public boolean isActive() {
        return active;
    }

    public static StatifyMod get() {
        return instance;
    }
}
