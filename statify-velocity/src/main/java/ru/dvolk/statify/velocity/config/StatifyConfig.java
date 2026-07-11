package ru.dvolk.statify.velocity.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import ru.dvolk.statify.velocity.db.DatabaseType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public final class StatifyConfig {

    private static final String DEFAULT_YAML = ""
            + "# Statify для Velocity — «липкий» коннект: возвращает игрока на тот сервер,\n"
            + "# на котором он был перед выходом. Использует общую с paper/fabric таблицу\n"
            + "# statify_players (добавляется колонка last_server).\n"
            + "config-version: '${version}'\n"
            + "\n"
            + "database:\n"
            + "  # Тип БД: mysql, mariadb или none.\n"
            + "  # При значении none плагин не подключится к БД и не будет менять поведение.\n"
            + "  type: 'none'\n"
            + "  host: 'localhost'\n"
            + "  port: 3306\n"
            + "  database: 'statify'\n"
            + "  username: 'statify'\n"
            + "  password: 'change_me'\n"
            + "  use-ssl: false\n"
            + "  # Размер пула Hikari.\n"
            + "  pool-size: 4\n"
            + "\n"
            + "redirect:\n"
            + "  # Если true — при входе игрока перекидываем его на last_server из БД.\n"
            + "  # Если сервер неизвестен Velocity или недоступен — падаем к try из velocity.toml.\n"
            + "  enabled: true\n"
            + "  # Проверять доступность сервера пингом перед редиректом (мс на попытку).\n"
            + "  # 0 = не проверять, доверять try самой Velocity в fallback.\n"
            + "  ping-timeout-ms: 1500\n"
            + "  # Серверы, на которые НЕЛЬЗЯ редиректить (напр. лобби-only). Пусто — редирект везде.\n"
            + "  blacklist: []\n";

    private final Path configDir;
    private final Path configFile;
    private final String pluginVersion;
    private final Logger logger;

    private String configVersion;
    private DatabaseType databaseType;
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;
    private boolean useSsl;
    private int poolSize;
    private boolean redirectEnabled;
    private int pingTimeoutMs;
    private java.util.List<String> blacklist;

    public StatifyConfig(Path configDir, String pluginVersion, Logger logger) {
        this.configDir = configDir;
        this.configFile = configDir.resolve("config.yml");
        this.pluginVersion = pluginVersion;
        this.logger = logger;
    }

    public void loadOrMigrate() throws IOException {
        Files.createDirectories(configDir);

        if (!Files.exists(configFile)) {
            writeDefault();
        } else {
            String existingVersion = readExistingVersion();
            if (!pluginVersion.equals(existingVersion)) {
                Path backup = configDir.resolve("config.old." + existingVersion + ".yml");
                int i = 1;
                while (Files.exists(backup)) {
                    backup = configDir.resolve("config.old." + existingVersion + "." + i + ".yml");
                    i++;
                }
                Files.move(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
                logger.warn("Версия конфига ({}) отличается от версии плагина ({}). "
                        + "Старый конфиг сохранён как {}, создан новый.", existingVersion, pluginVersion, backup.getFileName());
                writeDefault();
            }
        }

        parse();
    }

    public void reload() throws IOException {
        if (!Files.exists(configFile)) {
            throw new IOException("config.yml не найден");
        }
        parse();
    }

    private String readExistingVersion() throws IOException {
        try (InputStream in = Files.newInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Object obj = yaml.load(in);
            if (obj instanceof Map<?, ?> map) {
                Object v = map.get("config-version");
                return v == null ? "0" : String.valueOf(v);
            }
            return "0";
        }
    }

    private void writeDefault() throws IOException {
        String content = DEFAULT_YAML.replace("${version}", pluginVersion);
        Files.writeString(configFile, content, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private void parse() throws IOException {
        try (InputStream in = Files.newInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Object obj = yaml.load(in);
            if (!(obj instanceof Map<?, ?> root)) {
                throw new IOException("Некорректный формат config.yml");
            }
            this.configVersion = str(root.get("config-version"), "unknown");

            Object dbNode = root.get("database");
            Map<String, Object> db = dbNode instanceof Map ? (Map<String, Object>) dbNode : Map.of();
            this.databaseType = DatabaseType.parse(str(db.get("type"), "none"));
            this.host = str(db.get("host"), "localhost");
            this.port = intVal(db.get("port"), 3306);
            this.database = str(db.get("database"), "statify");
            this.username = str(db.get("username"), "root");
            this.password = str(db.get("password"), "");
            Object ssl = db.get("use-ssl");
            this.useSsl = ssl instanceof Boolean ? (Boolean) ssl : false;
            this.poolSize = intVal(db.get("pool-size"), 4);

            Object rNode = root.get("redirect");
            Map<String, Object> r = rNode instanceof Map ? (Map<String, Object>) rNode : Map.of();
            Object enabled = r.get("enabled");
            this.redirectEnabled = enabled instanceof Boolean ? (Boolean) enabled : true;
            this.pingTimeoutMs = Math.max(0, intVal(r.get("ping-timeout-ms"), 1500));
            Object bl = r.get("blacklist");
            if (bl instanceof java.util.List<?> list) {
                java.util.List<String> out = new java.util.ArrayList<>(list.size());
                for (Object o : list) out.add(String.valueOf(o));
                this.blacklist = java.util.List.copyOf(out);
            } else {
                this.blacklist = java.util.List.of();
            }
        }
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static int intVal(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return def;
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (NumberFormatException e) { return def; }
    }

    public String getConfigVersion() { return configVersion; }
    public DatabaseType getDatabaseType() { return databaseType; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getDatabase() { return database; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public boolean isUseSsl() { return useSsl; }
    public int getPoolSize() { return poolSize; }
    public boolean isRedirectEnabled() { return redirectEnabled; }
    public int getPingTimeoutMs() { return pingTimeoutMs; }
    public java.util.List<String> getBlacklist() { return blacklist; }
}
