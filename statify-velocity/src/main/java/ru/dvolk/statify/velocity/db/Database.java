package ru.dvolk.statify.velocity.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import ru.dvolk.statify.velocity.config.StatifyConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class Database {

    private final StatifyConfig config;
    private final Logger logger;
    private HikariDataSource dataSource;

    public Database(StatifyConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
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
        hc.setPoolName("Statify-Velocity-Hikari");
        hc.setConnectionTimeout(10_000L);
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");

        this.dataSource = new HikariDataSource(hc);
        try (Connection c = dataSource.getConnection()) {
            logger.info("Соединение с БД установлено: {}", c.getMetaData().getDatabaseProductName());
        }
    }

    public void initSchema() throws SQLException {
        String players = "CREATE TABLE IF NOT EXISTS statify_players ("
                + " uuid CHAR(36) NOT NULL,"
                + " name VARCHAR(32) NOT NULL,"
                + " last_seen BIGINT NOT NULL,"
                + " last_server VARCHAR(64) NULL,"
                + " PRIMARY KEY (uuid),"
                + " INDEX idx_name (name)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(players);
        }

        addLastServerColumnIfMissing();
    }

    /**
     * Таблица statify_players создаётся paper/fabric-плагинами без колонки last_server.
     * Добавляем колонку постфактум, если её ещё нет. Работает и на MySQL, и на MariaDB.
     */
    private void addLastServerColumnIfMissing() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            boolean exists = false;
            try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, "statify_players", "last_server")) {
                exists = rs.next();
            }
            if (exists) return;
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE statify_players ADD COLUMN last_server VARCHAR(64) NULL");
                logger.info("В таблицу statify_players добавлена колонка last_server.");
            }
        }
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Записывает последний сервер игрока и обновляет last_seen/name.
     * Одним запросом, чтобы не терять запись если строки ещё нет.
     */
    public void setLastServer(UUID uuid, String name, String server) throws SQLException {
        String sql = "INSERT INTO statify_players(uuid, name, last_seen, last_server) VALUES(?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE name=VALUES(name), last_seen=VALUES(last_seen), last_server=VALUES(last_server)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, Instant.now().getEpochSecond());
            ps.setString(4, server);
            ps.executeUpdate();
        }
    }

    public Optional<String> getLastServer(UUID uuid) throws SQLException {
        String sql = "SELECT last_server FROM statify_players WHERE uuid=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String s = rs.getString(1);
                return (s == null || s.isEmpty()) ? Optional.empty() : Optional.of(s);
            }
        }
    }
}
