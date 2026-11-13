package dev.roms.allinonebungee;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class AllInOneBungee extends Plugin implements Listener {
    public static final String DEFAULT_CHANNEL = "allinone:sync";
    private PlayerDataStore dataStore;
    private String channel;
    private Set<String> allowedServers = Set.of();
    private boolean bridgeEnabled;

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
                defaults.set("bridge-enabled", true);
                ConfigurationProvider.getProvider(YamlConfiguration.class).save(defaults, configFile);
            }
            Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
            channel = config.getString("channel", DEFAULT_CHANNEL);
            Set<String> servers = new HashSet<>();
            for (String server : config.getStringList("allowed-servers")) {
                servers.add(server.toLowerCase(Locale.ROOT));
            }
            allowedServers = Set.copyOf(servers);
            bridgeEnabled = config.getBoolean("bridge-enabled", true);
            dataStore = new PlayerDataStore(new File(getDataFolder(),
                    config.getString("storage-file", "players.yml")));

            getProxy().registerChannel(channel);
            getProxy().getPluginManager().registerListener(this, this);
            getLogger().info("Loaded local player data. Approved backend servers: " + allowedServers);
            if (bridgeEnabled) getLogger().info("Rank and balance synchronization is active.");
            else getLogger().warning("Sync is disabled. Set bridge-enabled: true in config.yml.");
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
        event.setCancelled(true);
        if (!bridgeEnabled || !(event.getSender() instanceof Server source)
                || !(event.getReceiver() instanceof ProxiedPlayer player)) return;
        if (!allowedServers.contains(source.getInfo().getName().toLowerCase(Locale.ROOT))) {
            getLogger().warning("Rejected sync message from unapproved server: " + source.getInfo().getName());
            return;
        }
        if (event.getData() == null || event.getData().length > 4096) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String operation = in.readUTF();
            UUID uuid = UUID.fromString(in.readUTF());
            String rank = in.readUTF();
            BigDecimal balance = new BigDecimal(in.readUTF());
            if (!uuid.equals(player.getUniqueId()) || !rank.matches("[a-zA-Z0-9_-]{1,32}")
                    || balance.signum() < 0 || balance.scale() > 2 || balance.precision() > 18
                    || in.available() != 0) {
                getLogger().warning("Rejected invalid sync payload from " + source.getInfo().getName());
                return;
            }

            PlayerRecord record;
            if ("HELLO".equals(operation)) {
                record = dataStore.get(uuid);
                if (record == null) {
                    record = new PlayerRecord(rank, balance.doubleValue());
                    dataStore.set(uuid, record);
                }
            } else if ("UPDATE".equals(operation)) {
                record = new PlayerRecord(rank, balance.doubleValue());
                dataStore.set(uuid, record);
            } else {
                return;
            }
            sendSync(player, uuid, record);
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Rejected malformed sync packet.", ex);
        }
    }

    private void sendSync(ProxiedPlayer player, UUID uuid, PlayerRecord record) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("SYNC");
            out.writeUTF(uuid.toString());
            out.writeUTF(record.rank());
            out.writeUTF(BigDecimal.valueOf(record.balance()).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            out.flush();
            player.sendData(channel, bytes.toByteArray());
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Could not send synchronized player data.", ex);
        }
    }

    public PlayerDataStore getDataStore() { return dataStore; }
}
