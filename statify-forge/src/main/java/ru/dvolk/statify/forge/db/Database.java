package ru.dvolk.statify.forge.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ru.dvolk.statify.forge.StatifyMod;
import ru.dvolk.statify.forge.config.StatifyConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.UUID;

public final class Database {

    private final StatifyConfig config;
    private HikariDataSource dataSource;

    public Database(StatifyConfig config) {
        this.config = config;
    }

    public void connect() throws SQLException {
        HikariConfig hc = new HikariConfig();
        String jdbc;
        if (config.getDatabaseType() == DatabaseType.MARIADB) {
            jdbc = "jdbc:mariadb://" + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase();
            hc.setDriverClassName("org.mariadb.jdbc.Driver");
        } else {
            jdbc = "jdbc:mysql://" + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase();
            hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        }
        jdbc += "?useSSL=" + config.isUseSsl()
                + "&useUnicode=true&characterEncoding=utf8"
                + "&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        hc.setJdbcUrl(jdbc);
        hc.setUsername(config.getUsername());
        hc.setPassword(config.getPassword());
        hc.setMaximumPoolSize(Math.max(1, config.getPoolSize()));
        hc.setPoolName("Statify-Hikari");
        hc.setConnectionTimeout(10_000L);
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");

        this.dataSource = new HikariDataSource(hc);
        try (Connection c = dataSource.getConnection()) {
            StatifyMod.LOGGER.info("Соединение с БД установлено: {}", c.getMetaData().getDatabaseProductName());
        }
    }

    public void initSchema() throws SQLException {
        String players = "CREATE TABLE IF NOT EXISTS statify_players ("
                + " uuid CHAR(36) NOT NULL,"
                + " name VARCHAR(32) NOT NULL,"
                + " last_seen BIGINT NOT NULL,"
                + " last_ip VARCHAR(45) NULL,"
                + " PRIMARY KEY (uuid),"
                + " INDEX idx_name (name)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        String daily = "CREATE TABLE IF NOT EXISTS statify_daily ("
                + " server VARCHAR(64) NOT NULL,"
                + " uuid CHAR(36) NOT NULL,"
                + " day DATE NOT NULL,"
                + " seconds BIGINT NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (server, uuid, day),"
                + " INDEX idx_day (day),"
                + " INDEX idx_uuid (uuid)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(players);
            st.executeUpdate(daily);
        }

        addColumnIfMissing("last_ip", "VARCHAR(45) NULL");
    }

    private void addColumnIfMissing(String column, String definition) throws SQLException {
        String sql = "ALTER TABLE statify_players ADD COLUMN " + column + " " + definition;
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(sql);
            StatifyMod.LOGGER.info("В таблицу statify_players добавлена колонка {}.", column);
        } catch (SQLException ex) {
            if (ex.getErrorCode() != 1060) throw ex;
        }
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    public void upsertPlayer(UUID uuid, String name, String ip) throws SQLException {
        String sql = "INSERT INTO statify_players(uuid, name, last_seen, last_ip) VALUES(?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE name=VALUES(name), last_seen=VALUES(last_seen), last_ip=VALUES(last_ip)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, Instant.now().getEpochSecond());
            ps.setString(4, ip);
            ps.executeUpdate();
        }
    }

    public void addPlaytime(String server, UUID uuid, LocalDate day, long seconds) throws SQLException {
        if (seconds <= 0) return;
        String sql = "INSERT INTO statify_daily(server, uuid, day, seconds) VALUES(?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE seconds = seconds + VALUES(seconds)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, server);
            ps.setString(2, uuid.toString());
            ps.setDate(3, java.sql.Date.valueOf(day));
            ps.setLong(4, seconds);
            ps.executeUpdate();
        }
    }

    public long getTotalSeconds(String server, UUID uuid) throws SQLException {
        String sql = "SELECT COALESCE(SUM(seconds),0) FROM statify_daily WHERE server=? AND uuid=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, server);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public long getSecondsBetween(String server, UUID uuid, LocalDate from, LocalDate toInclusive) throws SQLException {
        String sql = "SELECT COALESCE(SUM(seconds),0) FROM statify_daily "
                + "WHERE server=? AND uuid=? AND day BETWEEN ? AND ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, server);
            ps.setString(2, uuid.toString());
            ps.setDate(3, java.sql.Date.valueOf(from));
            ps.setDate(4, java.sql.Date.valueOf(toInclusive));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public long getSecondsForDay(String server, UUID uuid, LocalDate day) throws SQLException {
        return getSecondsBetween(server, uuid, day, day);
    }

    public long getSecondsForWeek(String server, UUID uuid, LocalDate reference) throws SQLException {
        WeekFields wf = WeekFields.of(Locale.getDefault());
        LocalDate weekStart = reference.with(wf.dayOfWeek(), 1);
        LocalDate weekEnd = weekStart.plusDays(6);
        return getSecondsBetween(server, uuid, weekStart, weekEnd);
    }

    public long getSecondsForMonth(String server, UUID uuid, LocalDate reference) throws SQLException {
        LocalDate monthStart = reference.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
        return getSecondsBetween(server, uuid, monthStart, monthEnd);
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    public java.util.List<TopEntry> getTopTotal(String server, int limit) throws SQLException {
        String sql = "SELECT d.uuid, p.name, SUM(d.seconds) AS total "
                + "FROM statify_daily d LEFT JOIN statify_players p ON p.uuid = d.uuid "
                + "WHERE d.server = ? GROUP BY d.uuid, p.name ORDER BY total DESC LIMIT ?";
        java.util.List<TopEntry> result = new java.util.ArrayList<>(limit);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, server);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uuid = rs.getString(1);
                    String name = rs.getString(2);
                    long seconds = rs.getLong(3);
                    result.add(new TopEntry(uuid == null ? "" : uuid, name == null ? "?" : name, seconds));
                }
            }
        }
        return result;
    }

    public static final class TopEntry {
        public final String uuid;
        public final String name;
        public final long seconds;

        public TopEntry(String uuid, String name, long seconds) {
            this.uuid = uuid;
            this.name = name;
            this.seconds = seconds;
        }
    }
}
