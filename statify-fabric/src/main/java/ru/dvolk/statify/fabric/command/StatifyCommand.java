package ru.dvolk.statify.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import ru.dvolk.statify.fabric.StatifyMod;

public final class StatifyCommand {

    private StatifyCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("statify")
                .requires(src -> src.hasPermissionLevel(0))
                .then(CommandManager.literal("reload")
                        .requires(Permissions.require("statify.command.reload", 3))
                        .executes(ctx -> {
                            try {
                                StatifyMod.get().reloadRuntime();
                                ctx.getSource().sendFeedback(
                                        () -> Text.literal("Конфиг перезагружен. Подключение к БД не затронуто."),
                                        false);
                            } catch (Exception ex) {
                                ctx.getSource().sendError(Text.literal("Ошибка перезагрузки: " + ex.getMessage()));
                            }
                            return 1;
                        }))
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal("Statify — /statify reload"), false);
                    return 1;
                }));
    }
}
