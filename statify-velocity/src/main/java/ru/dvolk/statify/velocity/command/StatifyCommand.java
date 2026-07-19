package ru.dvolk.statify.velocity.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import ru.dvolk.statify.velocity.StatifyPlugin;
import ru.dvolk.statify.velocity.db.Database;

import java.util.Optional;

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
                    send(ctx.getSource(), "§eИспользование: /statify <reload|look|forget> …");
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
                                })));
    }

    private static void handleLook(StatifyPlugin plugin, ProxyServer proxy, Database database,
                                    CommandSource source, String playerName) {
        proxy.getScheduler().buildTask(plugin, () -> {
            Optional<com.velocitypowered.api.proxy.Player> op = proxy.getPlayer(playerName);
            if (op.isEmpty()) {
                send(source, "§cИгрок " + playerName + " не в сети — по имени спросить у БД нельзя (в таблице ключ — UUID).");
                return;
            }
            try {
                Optional<String> last = database.getLastServer(op.get().getUniqueId());
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
            Optional<com.velocitypowered.api.proxy.Player> op = proxy.getPlayer(playerName);
            if (op.isEmpty()) {
                send(source, "§cИгрок " + playerName + " не в сети — используйте команду когда он подключён.");
                return;
            }
            try {
                // Пишем пустую строку как last_server. Логика редиректа игнорирует null/empty.
                database.setLastServer(op.get().getUniqueId(), op.get().getUsername(), "", null);
                send(source, "§aЗаписи last_server для " + playerName + " сброшены.");
            } catch (Exception ex) {
                send(source, "§cОшибка: " + ex.getMessage());
            }
        }).schedule();
    }

    private static void send(CommandSource src, String msg) {
        src.sendMessage(Component.text(msg).color(NamedTextColor.WHITE));
    }
}
