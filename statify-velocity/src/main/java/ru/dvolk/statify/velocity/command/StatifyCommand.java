package ru.dvolk.statify.velocity.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import ru.dvolk.statify.velocity.StatifyPlugin;
import ru.dvolk.statify.velocity.db.Database;

import java.util.Optional;
import java.util.UUID;

public final class StatifyCommand {

    private static final String PERMISSION = "statify.admin";

    private StatifyCommand() {}

    public static void register(ProxyServer proxy, StatifyPlugin plugin, Database database, Logger logger) {
        CommandManager cm = proxy.getCommandManager();
        CommandMeta meta = cm.metaBuilder("statify").plugin(plugin).build();
        cm.register(meta, new BrigadierCommand(build(proxy, plugin, database, logger)));
    }

    private static LiteralArgumentBuilder<CommandSource> build(final ProxyServer proxy, final StatifyPlugin plugin,
                                                               final Database database, final Logger logger) {
        return LiteralArgumentBuilder.<CommandSource>literal("statify")
                .requires(src -> src.hasPermission(PERMISSION))
                .executes(ctx -> {
                    send(ctx.getSource(), "§eИспользование: /statify <reload|look|forget|set> …");
                    return 1;
                })
                .then(LiteralArgumentBuilder.<CommandSource>literal("reload")
                        .executes(ctx -> {
                            try {
                                plugin.reloadRuntime();
                                send(ctx.getSource(), "§aКонфиг перечитан.");
                            } catch (Exception ex) {
                                send(ctx.getSource(), "§cОшибка reload: " + ex.getMessage());
                                logger.warn("reload failed", ex);
                            }
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<CommandSource>literal("look")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "player");
                                    handleLook(plugin, proxy, database, ctx.getSource(), name);
                                    return 1;
                                })))
                .then(LiteralArgumentBuilder.<CommandSource>literal("forget")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "player");
                                    handleForget(plugin, proxy, database, ctx.getSource(), name);
                                    return 1;
                                })))
                .then(LiteralArgumentBuilder.<CommandSource>literal("set")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                                .then(RequiredArgumentBuilder.<CommandSource, String>argument("server", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "player");
                                            String server = StringArgumentType.getString(ctx, "server");
                                            handleSet(plugin, proxy, database, ctx.getSource(), name, server);
                                            return 1;
                                        }))));
    }

    private static void handleLook(StatifyPlugin plugin, ProxyServer proxy, Database database,
                                    CommandSource source, String playerName) {
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                UUID uuid = resolveUuid(proxy, database, playerName);
                if (uuid == null) {
                    send(source, "§cИгрок §f" + playerName + "§c не найден ни в онлайне, ни в БД.");
                    return;
                }
                Optional<String> last = database.getLastServer(uuid);
                if (last.isPresent()) {
                    send(source, "§7Последний сервер §f" + playerName + "§7: §a" + last.get());
                } else {
                    send(source, "§7Для §f" + playerName + " §7нет записи о последнем сервере.");
                }
            } catch (Exception ex) {
                send(source, "§cОшибка: " + ex.getMessage());
            }
        }).schedule();
    }

    private static void handleForget(StatifyPlugin plugin, ProxyServer proxy, Database database,
                                      CommandSource source, String playerName) {
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                UUID uuid = resolveUuid(proxy, database, playerName);
                if (uuid == null) {
                    send(source, "§cИгрок §f" + playerName + "§c не найден ни в онлайне, ни в БД.");
                    return;
                }
                boolean updated = database.updateLastServerOnly(uuid, null);
                if (updated) {
                    send(source, "§aЗапись last_server для §f" + playerName + "§a сброшена.");
                } else {
                    send(source, "§7Запись last_server для §f" + playerName + "§7 не найдена.");
                }
            } catch (Exception ex) {
                send(source, "§cОшибка: " + ex.getMessage());
            }
        }).schedule();
    }

    private static void handleSet(StatifyPlugin plugin, ProxyServer proxy, Database database,
                                   CommandSource source, String playerName, String serverName) {
        proxy.getScheduler().buildTask(plugin, () -> {
            if (proxy.getServer(serverName).isEmpty()) {
                send(source, "§cСервер §f" + serverName + "§c не зарегистрирован в Velocity.");
                return;
            }
            try {
                UUID uuid = resolveUuid(proxy, database, playerName);
                if (uuid == null) {
                    send(source, "§cИгрок §f" + playerName + "§c не найден ни в онлайне, ни в БД.");
                    return;
                }
                boolean updated = database.updateLastServerOnly(uuid, serverName);
                if (updated) {
                    send(source, "§aДля §f" + playerName + "§a установлен last_server: §e" + serverName);
                } else {
                    send(source, "§cДля §f" + playerName + "§c нет строки в БД — сначала он должен войти хоть раз.");
                }
            } catch (Exception ex) {
                send(source, "§cОшибка: " + ex.getMessage());
            }
        }).schedule();
    }

    /**
     * Ищет UUID сначала среди онлайн-игроков (свежее имя), потом в БД (по last-seen name).
     * Возвращает null, если не найден нигде.
     */
    private static UUID resolveUuid(ProxyServer proxy, Database database, String playerName) throws java.sql.SQLException {
        Optional<Player> op = proxy.getPlayer(playerName);
        if (op.isPresent()) return op.get().getUniqueId();
        return database.findUuidByName(playerName).orElse(null);
    }

    private static void send(CommandSource src, String msg) {
        src.sendMessage(Component.text(msg).color(NamedTextColor.WHITE));
    }
}
