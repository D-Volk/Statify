package ru.dvolk.statify.forge.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import ru.dvolk.statify.forge.StatifyMod;

public final class StatifyCommand {

    private StatifyCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("statify")
                .requires(src -> src.hasPermission(0))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(3))
                        .executes(ctx -> {
                            try {
                                StatifyMod.get().reloadRuntime();
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("Конфиг перезагружен. Подключение к БД не затронуто."),
                                        false);
                            } catch (Exception ex) {
                                ctx.getSource().sendFailure(Component.literal("Ошибка перезагрузки: " + ex.getMessage()));
                            }
                            return 1;
                        }))
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("Statify — /statify reload"), false);
                    return 1;
                }));
    }
}
