package dev.roms.allinonebungee;

import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

public final class AllInOneBungee extends Plugin implements Listener {
    public static final String DEFAULT_CHANNEL = "allinone:sync";
    private PlayerDataStore dataStore;
    private String channel;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create plugin data folder.");
            return;
        }

        File configFile = new File(getDataFolder(), "config.yml");
        try {
            if (!configFile.exists()) {
                Configuration defaults = new Configuration();
                defaults.set("channel", DEFAULT_CHANNEL);
                defaults.set("storage-file", "players.yml");
                defaults.set("allowed-servers", java.util.List.of("lobby", "survival"));
                defaults.set("bridge-enabled", false);
                ConfigurationProvider.getProvider(YamlConfiguration.class).save(defaults, configFile);
            }
            Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
            channel = config.getString("channel", DEFAULT_CHANNEL);
            Set<String> allowedServers = new HashSet<>(config.getStringList("allowed-servers"));
            dataStore = new PlayerDataStore(new File(getDataFolder(),
                    config.getString("storage-file", "players.yml")));

            getProxy().registerChannel(channel);
            getProxy().getPluginManager().registerListener(this, this);
            getLogger().info("Loaded local player data. Approved backend servers: " + allowedServers);
            getLogger().warning("Sync protocol is not activated until the matching ServerToolkit bridge is installed.");
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not initialize AllInOneBungee.", ex);
        }
    }

    @Override
    public void onDisable() {
        if (dataStore != null) {
            try { dataStore.save(); }
            catch (IOException ex) { getLogger().log(Level.SEVERE, "Could not save player data.", ex); }
        }
        if (channel != null) getProxy().unregisterChannel(channel);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (channel == null || !channel.equals(event.getTag())) return;
        // Intentionally reject messages until the matching backend bridge and its
        // versioned protocol are implemented. This prevents untrusted payloads changing data.
        event.setCancelled(true);
    }

    public PlayerDataStore getDataStore() { return dataStore; }
}