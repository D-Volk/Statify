package ru.dvolk.statify.forge.config;

import org.yaml.snakeyaml.Yaml;
import ru.dvolk.statify.forge.StatifyMod;
import ru.dvolk.statify.forge.db.DatabaseType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public final class StatifyConfig {

    private static final String DEFAULT_YAML = ""
            + "# Statify — конфигурация мода.\n"
            + "# Внимание: при смене версии мода этот файл будет автоматически переименован\n"
            + "# в config.old.<старая-версия>.yml, а на его месте будет создан новый.\n"
            + "config-version: '${version}'\n"
            + "\n"
            + "# Имя сервера. Используется как раздел в БД, чтобы одна БД могла обслуживать\n"
            + "# несколько серверов и они не смешивались.\n"
            + "server-name: 'lobby'\n"
            + "\n"
            + "database:\n"
            + "  # Тип БД: mysql, mariadb или none.\n"
            + "  # При значении none мод отключится с предупреждением.\n"
            + "  type: 'none'\n"
            + "  host: 'localhost'\n"
            + "  port: 3306\n"
            + "  database: 'statify'\n"
            + "  username: 'statify'\n"
            + "  password: 'change_me'\n"
            + "  use-ssl: false\n"
            + "  pool-size: 6\n"
            + "\n"
            + "flush-interval-minutes: 15\n"
            + "\n"
            + "format:\n"
            + "  suffix-day: 'd'\n"
            + "  suffix-hour: 'h'\n"
            + "  suffix-minute: 'm'\n"
            + "  suffix-second: 's'\n"
            + "  separator: ' '\n"
            + "  zero: '0s'\n";

    private final Path configDir;
    private final Path configFile;
    private final String modVersion;

    private String configVersion;
    private String serverName;
    private DatabaseType databaseType;
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;
    private boolean useSsl;
    private int poolSize;
    private int flushIntervalMinutes;
    private String suffixDay;
    private String suffixHour;
    private String suffixMinute;
    private String suffixSecond;
    private String separator;
    private String zeroText;

    public StatifyConfig(Path configDir, String modVersion) {
        this.configDir = configDir;
        this.configFile = configDir.resolve("config.yml");
        this.modVersion = modVersion;
    }

    public void reload() throws IOException {
        if (!Files.exists(configFile)) {
            throw new IOException("config.yml не найден");
        }
        parse();
    }

    public void loadOrMigrate() throws IOException {
        Files.createDirectories(configDir);

        if (!Files.exists(configFile)) {
            writeDefault();
        } else {
            String existingVersion = readExistingVersion();
            if (!modVersion.equals(existingVersion)) {
                Path backup = configDir.resolve("config.old." + existingVersion + ".yml");
                int i = 1;
                while (Files.exists(backup)) {
                    backup = configDir.resolve("config.old." + existingVersion + "." + i + ".yml");
                    i++;
                }
                Files.move(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
                StatifyMod.LOGGER.warn("Версия конфига ({}) отличается от версии мода ({}). "
                        + "Старый конфиг сохранён как {}, создан новый.", existingVersion, modVersion, backup.getFileName());
                writeDefault();
            }
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
        String content = DEFAULT_YAML.replace("${version}", modVersion);
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
            this.serverName = str(root.get("server-name"), "server");

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
            this.poolSize = intVal(db.get("pool-size"), 6);
            this.flushIntervalMinutes = Math.max(1, intVal(root.get("flush-interval-minutes"), 15));

            Object fmtNode = root.get("format");
            Map<String, Object> fmt = fmtNode instanceof Map ? (Map<String, Object>) fmtNode : Map.of();
            this.suffixDay = str(fmt.get("suffix-day"), "d");
            this.suffixHour = str(fmt.get("suffix-hour"), "h");
            this.suffixMinute = str(fmt.get("suffix-minute"), "m");
            this.suffixSecond = str(fmt.get("suffix-second"), "s");
            this.separator = str(fmt.get("separator"), " ");
            this.zeroText = str(fmt.get("zero"), "0s");
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
    public String getServerName() { return serverName; }
    public DatabaseType getDatabaseType() { return databaseType; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getDatabase() { return database; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public boolean isUseSsl() { return useSsl; }
    public int getPoolSize() { return poolSize; }
    public int getFlushIntervalMinutes() { return flushIntervalMinutes; }
    public String getSuffixDay() { return suffixDay; }
    public String getSuffixHour() { return suffixHour; }
    public String getSuffixMinute() { return suffixMinute; }
    public String getSuffixSecond() { return suffixSecond; }
    public String getSeparator() { return separator; }
    public String getZeroText() { return zeroText; }
}
