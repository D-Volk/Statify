package ru.dvolk.statify.forge.placeholder;

import ru.dvolk.statify.forge.db.Database;
import ru.dvolk.statify.forge.format.DurationFormat;
import ru.dvolk.statify.forge.tracker.PlaytimeTracker;

/**
 * Заглушка для Forge — placeholder-api от pb4 не поддерживает Forge 1.20.1,
 * а форджового аналога с совместимым API нет. Класс оставлен, чтобы StatifyMod
 * не менялся: если появится подходящий placeholder-мод, реализацию можно вернуть.
 */
public final class StatifyPlaceholders {

    private StatifyPlaceholders() {}

    public static void register(PlaytimeTracker tracker, Database database, String localServer, DurationFormat df) {
        // no-op
    }

    public static void apply(String localServer, DurationFormat df) {
        // no-op
    }
}
