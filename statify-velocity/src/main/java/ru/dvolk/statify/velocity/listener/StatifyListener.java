package ru.dvolk.statify.velocity.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import ru.dvolk.statify.velocity.StatifyPlugin;
import ru.dvolk.statify.velocity.config.StatifyConfig;
import ru.dvolk.statify.velocity.db.Database;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class StatifyListener {

    private final StatifyPlugin plugin;
    private final ProxyServer proxy;
    private final Database database;
    private final StatifyConfig config;
    private final Logger logger;

    private final ConcurrentHashMap<UUID, String> lastServerCache = new ConcurrentHashMap<>();
    /** Игроки, которых мы перекинули — чтобы обработать fallback при неудаче. */
    private final Set<UUID> redirectedPlayers = ConcurrentHashMap.newKeySet();

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
        int protocol = event.getPlayer().getProtocolVersion().getProtocol();

        // 1. Version-routing: если для протокола задан сервер — он в приоритете.
        String versionTarget = config.getVersionRouting().get(protocol);
        if (versionTarget != null) {
            Optional<RegisteredServer> server = proxy.getServer(versionTarget);
            if (server.isPresent()) {
                int timeout = config.getPingTimeoutMs() > 0 ? config.getPingTimeoutMs() : 1500;
                if (pingable(server.get(), timeout)) {
                    redirectedPlayers.add(uuid);
                    event.setInitialServer(server.get());
                    return;
                }
                logger.info("version-routing: сервер '{}' (протокол {}) недоступен — fallback на try.", versionTarget, protocol);
            } else {
                logger.warn("version-routing: сервер '{}' не зарегистрирован в Velocity.", versionTarget);
            }
            return;
        }

        // 2. Sticky-connect: last_server из БД.
        String last = lastServerCache.get(uuid);
        if (last == null) return;
        if (config.getBlacklist().contains(last)) return;

        Optional<RegisteredServer> target = proxy.getServer(last);
        if (target.isEmpty()) {
            logger.debug("Сервер '{}' не зарегистрирован в Velocity — редирект пропущен.", last);
            return;
        }

        int timeout = config.getPingTimeoutMs() > 0 ? config.getPingTimeoutMs() : 1500;
        if (!pingable(target.get(), timeout)) {
            logger.info("Сервер '{}' недоступен — игрок пойдёт на try из velocity.toml.", last);
            return;
        }

        redirectedPlayers.add(uuid);
        event.setInitialServer(target.get());
    }

    @Subscribe(order = PostOrder.LATE)
    public void onKickedFromServer(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!redirectedPlayers.remove(uuid)) return;
        if (player.getCurrentServer().isPresent()) return;

        String failedServer = event.getServer().getServerInfo().getName();
        logger.info("Сервер '{}' отклонил подключение {} — ищем fallback.", failedServer, player.getUsername());

        for (String name : proxy.getConfiguration().getAttemptConnectionOrder()) {
            if (name.equals(failedServer)) continue;
            Optional<RegisteredServer> candidate = proxy.getServer(name);
            if (candidate.isPresent()) {
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(candidate.get()));
                logger.info("Fallback для {}: перенаправлен на '{}'.", player.getUsername(), name);
                return;
            }
        }

        logger.warn("Нет доступных fallback-серверов для {}.", player.getUsername());
    }

    @Subscribe(order = PostOrder.LATE)
    public void onServerConnected(ServerConnectedEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getUsername();
        String server = event.getServer().getServerInfo().getName();
        String ip = event.getPlayer().getRemoteAddress().getAddress().getHostAddress();

        redirectedPlayers.remove(uuid);
        lastServerCache.put(uuid, server);

        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                database.setLastServer(uuid, name, server, ip);
            } catch (Exception ex) {
                logger.warn("Не удалось сохранить last_server для {}: {}", name, ex.getMessage());
            }
        }).schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastServerCache.remove(uuid);
        redirectedPlayers.remove(uuid);
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
