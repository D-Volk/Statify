package ru.dvolk.statify.forge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dvolk.statify.forge.command.StatifyCommand;
import ru.dvolk.statify.forge.config.StatifyConfig;
import ru.dvolk.statify.forge.db.Database;
import ru.dvolk.statify.forge.db.DatabaseType;
import ru.dvolk.statify.forge.format.DurationFormat;
import ru.dvolk.statify.forge.placeholder.StatifyPlaceholders;
import ru.dvolk.statify.forge.tracker.PlaytimeTracker;

import java.nio.file.Path;

@Mod(StatifyMod.MOD_ID)
public final class StatifyMod {

    public static final String MOD_ID = "statify";
    public static final Logger LOGGER = LoggerFactory.getLogger("Statify");

    private static StatifyMod instance;

    private StatifyConfig config;
    private Database database;
    private PlaytimeTracker tracker;
    private volatile boolean active;

    public StatifyMod() {
        instance = this;
        Path configDir = FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
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

        MinecraftForge.EVENT_BUS.register(this);

        DurationFormat durationFormat = new DurationFormat(
                config.getSuffixDay(), config.getSuffixHour(),
                config.getSuffixMinute(), config.getSuffixSecond(),
                config.getSeparator(), config.getZeroText());

        // Placeholder-API для Forge пока не подключён — StatifyPlaceholders.register сейчас no-op.
        StatifyPlaceholders.register(tracker, database, config.getServerName(), durationFormat);

        LOGGER.info("Statify инициализирован. Сервер: {}, БД: {}", config.getServerName(), type);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (tracker != null) tracker.bootstrap(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (tracker != null) tracker.shutdown();
        if (database != null) database.close();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && tracker != null) {
            tracker.onServerTick();
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (tracker != null && event.getEntity() instanceof ServerPlayer sp) {
            tracker.handleJoin(sp);
        }
    }

    @SubscribeEvent
    public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (tracker != null && event.getEntity() instanceof ServerPlayer sp) {
            tracker.handleQuit(sp);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        StatifyCommand.register(event.getDispatcher());
    }

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
        return ModList.get().getModContainerById(MOD_ID)
                .map(ModContainer::getModInfo)
                .map(info -> info.getVersion().toString())
                .orElse("unknown");
    }

    public boolean isActive() {
        return active;
    }

    public static StatifyMod get() {
        return instance;
    }
}
