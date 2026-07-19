package ru.dvolk.statify.forge.db;

public enum DatabaseType {
    NONE,
    MYSQL,
    MARIADB;

    public static DatabaseType parse(String raw) {
        if (raw == null) return NONE;
        String v = raw.trim().toLowerCase();
        if (v.isEmpty() || v.equals("none") || v.equals("null")) return NONE;
        if (v.equals("mysql")) return MYSQL;
        if (v.equals("mariadb")) return MARIADB;
        return NONE;
    }
}
