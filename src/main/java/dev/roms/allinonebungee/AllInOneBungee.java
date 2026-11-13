package dev.roms.allinonebungee;

import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.config.Configuration;
import net.md_5.bungee.api.connection.PluginMessage;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class AllInOneBungee extends Plugin implements Listener {
    public static final String DEFAULT_CHANNEL = "allinone:sync";
    private PlayerDataStore dataStore;
    private String channel;
    private Set<String> allowedServers;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create plugin data folder.");
            return;
        }
        saveDefaultConfig();
        Configuration config = getConfig();
        channel = config.getString("channel", DEFAULT_CHANNEL);
        allowedServers = new HashSet<>(config.getStringList("allowed-servers"));
        try {
            dataStore = new PlayerDataStore(new File(getDataFolder(),
                    config.getString("storage-file", "players.yml")));
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not load players.yml; plugin disabled.", ex);
            return;
        }
        getProxy().registerChannel(channel);
        getProxy().getPluginManager().registerListener(this, this);
        getLogger().info("AllInOneBungee enabled. Local player data: " + dataStore.getFile().getName());
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
        if (!channel.equals(event.getTag()) || !(event.getSender() instanceof net.md_5.bungee.api.connection.Server)) return;
        String serverName = ((net.md_5.bungee.api.connection.Server) event.getSender()).getInfo().getName();
        if (!allowedServers.contains(serverName)) {
            getLogger().warning("Ignored sync message from unapproved server: " + serverName);
            event.setCancelled(true);
            return;
        }
        // The versioned binary protocol and authenticated backend identity are implemented
        // together with the ServerToolkit bridge; do not accept arbitrary payloads here.
        event.setCancelled(true);
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        // Data is loaded from local YAML at proxy startup. The backend bridge requests the
        // authoritative snapshot after the player joins a backend server.
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        // Snapshot delivery is added with the matching ServerToolkit bridge protocol.
    }

    public PlayerDataStore getDataStore() { return dataStore; }
}