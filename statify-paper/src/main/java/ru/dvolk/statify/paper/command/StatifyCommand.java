package ru.dvolk.statify.paper.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import ru.dvolk.statify.paper.StatifyPlugin;

import java.util.List;

public final class StatifyCommand implements CommandExecutor, TabCompleter {

    private final StatifyPlugin plugin;

    public StatifyCommand(StatifyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eStatify §fv" + plugin.getDescription().getVersion());
            sender.sendMessage("§7/" + label + " reload §f— перезагрузить конфиг (кроме подключения к БД)");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("statify.reload")) {
                sender.sendMessage("§cНет прав.");
                return true;
            }
            try {
                plugin.reloadRuntime();
                sender.sendMessage("§aКонфиг перезагружен. Подключение к БД не затронуто.");
            } catch (Exception ex) {
                sender.sendMessage("§cОшибка перезагрузки: " + ex.getMessage());
                plugin.getLogger().warning("Ошибка reload: " + ex.getMessage());
            }
            return true;
        }

        sender.sendMessage("§cНеизвестная подкоманда. Используй: /" + label + " reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload");
        }
        return List.of();
    }
}
