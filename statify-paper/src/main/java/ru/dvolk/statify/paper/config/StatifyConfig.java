package ru.dvolk.statify.paper.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.dvolk.statify.paper.db.DatabaseType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class StatifyConfig {

    private final JavaPlugin plugin;
    private final File configFile;
    private YamlConfiguration yaml;

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

    public StatifyConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
    }

    public void loadOrMigrate() throws IOException {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("Не удалось создать каталог " + dataFolder);
        }

        String pluginVersion = plugin.getDescription().getVersion();

        if (!configFile.exists()) {
            writeDefault(pluginVersion);
        } else {
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(configFile);
            String existingVersion = existing.getString("config-version", "0");
            if (!pluginVersion.equals(existingVersion)) {
                File backup = new File(dataFolder, "config.old." + existingVersion + ".yml");
                int i = 1;
                while (backup.exists()) {
                    backup = new File(dataFolder, "config.old." + existingVersion + "." + i + ".yml");
                    i++;
                }
                Files.move(configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().warning("Версия конфига (" + existingVersion + ") отличается от версии плагина ("
                        + pluginVersion + "). Старый конфиг сохранён как " + backup.getName() + ", создан новый.");
                writeDefault(pluginVersion);
            }
        }

        this.yaml = YamlConfiguration.loadConfiguration(configFile);
        readFields();
    }

    /** Перечитывает YAML без миграции. Не трогает database.* (их всё равно нельзя применить без переподключения). */
    public void reload() throws IOException {
        if (!configFile.exists()) {
            throw new IOException("config.yml не найден");
        }
        this.yaml = YamlConfiguration.loadConfiguration(configFile);
        readFields();
    }

    private void writeDefault(String pluginVersion) throws IOException {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                throw new IOException("Ресурс config.yml отсутствует в jar плагина");
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            content = content.replace("${version}", pluginVersion);
            Files.writeString(configFile.toPath(), content, StandardCharsets.UTF_8);
        }
    }

    private void readFields() {
        this.configVersion = yaml.getString("config-version", "unknown");
        this.serverName = yaml.getString("server-name", "server");
        this.databaseType = DatabaseType.parse(yaml.getString("database.type", "none"));
        this.host = yaml.getString("database.host", "localhost");
        this.port = yaml.getInt("database.port", 3306);
        this.database = yaml.getString("database.database", "statify");
        this.username = yaml.getString("database.username", "root");
        this.password = yaml.getString("database.password", "");
        this.useSsl = yaml.getBoolean("database.use-ssl", false);
        this.poolSize = yaml.getInt("database.pool-size", 6);
        this.flushIntervalMinutes = Math.max(1, yaml.getInt("flush-interval-minutes", 15));
        this.suffixDay = yaml.getString("format.suffix-day", "d");
        this.suffixHour = yaml.getString("format.suffix-hour", "h");
        this.suffixMinute = yaml.getString("format.suffix-minute", "m");
        this.suffixSecond = yaml.getString("format.suffix-second", "s");
        this.separator = yaml.getString("format.separator", " ");
        this.zeroText = yaml.getString("format.zero", "0s");
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
