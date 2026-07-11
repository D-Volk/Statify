package ru.dvolk.statify.velocity.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import ru.dvolk.statify.velocity.StatifyPlugin;
import ru.dvolk.statify.velocity.config.StatifyConfig;
import ru.dvolk.statify.velocity.db.Database;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * События вокруг подключения игрока:
 *  - LoginEvent — асинхронно тянем last_server из БД в кэш.
 *  - PlayerChooseInitialServerEvent — синхронно применяем кэшированное значение,
 *    если сервер зарегистрирован, не в чёрном списке и (опционально) пингуется.
 *    Если чего-то из этого нет — событие не трогаем, работает штатный try из velocity.toml.
 *  - ServerConnectedEvent — пишем актуальный сервер игрока в БД (одна запись на переключение).
 *  - DisconnectEvent — вычищаем кэш.
 */
public final class StatifyListener {

    private final StatifyPlugin plugin;
    private final ProxyServer proxy;
    private final Database database;
    private final StatifyConfig config;
    private final Logger logger;

    /** UUID → last_server, известный на момент логина. Живёт только пока игрок онлайн. */
    private final ConcurrentHashMap<UUID, String> lastServerCache = new ConcurrentHashMap<>();

    public StatifyListener(StatifyPlugin plugin, ProxyServer proxy, Database database, StatifyConfig config, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.database = database;
        this.config = config;
        this.logger = logger;
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        if (!config.isRedirectEnabled()) return null;
        UUID uuid = event.getPlayer().getUniqueId();
        return EventTask.async(() -> {
            try {
                Optional<String> last = database.getLastServer(uuid);
                last.ifPresent(s -> lastServerCache.put(uuid, s));
            } catch (Exception ex) {
                logger.warn("Не удалось прочитать last_server для {}: {}", uuid, ex.getMessage());
            }
        });
    }

    @Subscribe(order = PostOrder.LATE)
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (!config.isRedirectEnabled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        String last = lastServerCache.get(uuid);
        if (last == null) return;
        if (config.getBlacklist().contains(last)) return;

        Optional<RegisteredServer> target = proxy.getServer(last);
        if (target.isEmpty()) {
            logger.debug("Сервер '{}' не зарегистрирован в Velocity — редирект пропущен.", last);
            return;
        }

        if (config.getPingTimeoutMs() > 0 && !pingable(target.get(), config.getPingTimeoutMs())) {
            logger.debug("Сервер '{}' недоступен по пингу — редирект пропущен.", last);
            return;
        }

        event.setInitialServer(target.get());
    }

    @Subscribe(order = PostOrder.LATE)
    public void onServerConnected(ServerConnectedEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getUsername();
        String server = event.getServer().getServerInfo().getName();

        // держим кэш в актуальном состоянии — если игрока перекинут на другой сервер и он выйдет,
        // возвращаться должно на последний, а не на тот, что был при входе.
        lastServerCache.put(uuid, server);

        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                database.setLastServer(uuid, name, server);
            } catch (Exception ex) {
                logger.warn("Не удалось сохранить last_server для {}: {}", name, ex.getMessage());
            }
        }).schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        lastServerCache.remove(event.getPlayer().getUniqueId());
    }

    private boolean pingable(RegisteredServer server, int timeoutMs) {
        try {
            server.ping().get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
