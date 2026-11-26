package dev.roms.allinonebungee;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.logging.Level;

public final class AllInOneBungee extends Plugin implements Listener {
    public static final String DEFAULT_CHANNEL = "allinone:sync";
    public static final String AUTH_CHANNEL = "allinone:auth";
    private PlayerDataStore dataStore;
    private String channel;
    private Set<String> allowedServers = Set.of();
    private final Set<UUID> authenticatedSessions = ConcurrentHashMap.newKeySet();
    private String lobbyServer = "lobby";

    @Override public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) { getLogger().severe("Could not create plugin data folder."); return; }
        File configFile = new File(getDataFolder(), "config.yml");
        try {
            if (!configFile.exists()) {
                Configuration defaults = new Configuration();
                defaults.set("channel", DEFAULT_CHANNEL); defaults.set("storage-file", "players.yml");
                defaults.set("allowed-servers", java.util.List.of("lobby", "survival"));
                defaults.set("lobby-server", "lobby");
                ConfigurationProvider.getProvider(YamlConfiguration.class).save(defaults, configFile);
            }
            Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
            channel = config.getString("channel", DEFAULT_CHANNEL);
            Set<String> servers = new HashSet<>();
            for (String server : config.getStringList("allowed-servers")) servers.add(server.toLowerCase(Locale.ROOT));
            allowedServers = Set.copyOf(servers);
            lobbyServer = config.getString("lobby-server", "lobby").toLowerCase(Locale.ROOT);
            dataStore = new PlayerDataStore(new File(getDataFolder(), config.getString("storage-file", "players.yml")));
            getProxy().registerChannel(channel);
            getProxy().registerChannel(AUTH_CHANNEL);
            getProxy().getPluginManager().registerListener(this, this);
            getProxy().getPluginManager().registerCommand(this, new SyncCommand());
            getLogger().info("Rank, balance, bounty and playtime synchronization is ACTIVE on " + channel + ". Lobby auth gate: " + lobbyServer + ". Approved backend servers: " + allowedServers);
        } catch (IOException ex) { getLogger().log(Level.SEVERE, "Could not initialize AllInOneBungee.", ex); }
    }
    @Override public void onDisable() {
        if (dataStore != null) try { dataStore.save(); } catch (IOException ex) { getLogger().log(Level.SEVERE, "Could not save player data.", ex); }
        if (channel != null) getProxy().unregisterChannel(channel);
        getProxy().unregisterChannel(AUTH_CHANNEL);
    }

    @EventHandler public void onPluginMessage(PluginMessageEvent event) {
        if (AUTH_CHANNEL.equals(event.getTag())) {
            handleAuthMessage(event);
            return;
        }
        if (channel == null || !channel.equals(event.getTag())) return;
        event.setCancelled(true);
        if (!(event.getSender() instanceof Server source) || !(event.getReceiver() instanceof ProxiedPlayer carrier)) return;
        String sourceName = source.getInfo().getName().toLowerCase(Locale.ROOT);
        if (!allowedServers.contains(sourceName) || event.getData() == null || event.getData().length > 4096) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String operation = in.readUTF();
            if ("REQUEST_ALL".equals(operation)) {
                if (in.available() == 0) getLogger().info("Accepted full-sync request from " + sourceName);
                return;
            }
            if ("PLAYTIME_ADD".equals(operation)) {
                UUID uuid = UUID.fromString(in.readUTF()); long seconds = in.readLong();
                if (!uuid.equals(carrier.getUniqueId()) || seconds < 1 || seconds > 3600 || in.available() != 0) return;
                dataStore.addPlaytime(uuid, seconds);
                PlayerRecord record = dataStore.get(uuid);
                sendSync(source, uuid, record);
                return;
            }
            if ("BOUNTY_ADD".equals(operation)) {
                UUID targetId = UUID.fromString(in.readUTF()); double amount = in.readDouble();
                if (!Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000D || in.available() != 0) return;
                dataStore.addBounty(targetId, amount);
                syncOnlinePlayer(targetId);
                carrier.sendMessage(new TextComponent("§aKopfgeld erhöht: §e" + String.format(Locale.US, "%.2f", amount) + " Coins§a."));
                return;
            }
            if ("BOUNTY_CLAIM".equals(operation)) {
                UUID targetId = UUID.fromString(in.readUTF()); UUID hunterId = UUID.fromString(in.readUTF());
                if (!hunterId.equals(carrier.getUniqueId()) || in.available() != 0 || targetId.equals(hunterId)) return;
                double reward = dataStore.claimBounty(targetId);
                if (reward <= 0) return;
                syncOnlinePlayer(targetId);
                sendBountyReward(source, hunterId, reward);
                getLogger().info("Bounty " + reward + " claimed for " + hunterId + " from " + targetId);
                return;
            }

            UUID uuid = UUID.fromString(in.readUTF());
            String rank = in.readUTF();
            BigDecimal balance = new BigDecimal(in.readUTF());
            if (!uuid.equals(carrier.getUniqueId()) || !rank.matches("[a-zA-Z0-9_-]{1,32}")
                    || balance.signum() < 0 || balance.scale() > 2 || balance.precision() > 18 || in.available() != 0) return;
            PlayerRecord record;
            if ("HELLO".equals(operation)) {
                record = dataStore.get(uuid);
                if (record == null) { record = new PlayerRecord(rank, balance.doubleValue()); dataStore.set(uuid, record); }
            } else if ("UPDATE".equals(operation)) {
                PlayerRecord old = dataStore.get(uuid);
                record = new PlayerRecord(rank, balance.doubleValue(), old == null ? 0 : old.playtimeSeconds(), old == null ? 0 : old.bounty());
                dataStore.set(uuid, record);
            } else return;
            sendSync(source, uuid, record);
        } catch (Exception ex) { getLogger().log(Level.WARNING, "Rejected malformed sync packet.", ex); }
    }


    private void handleAuthMessage(PluginMessageEvent event) {
        event.setCancelled(true);
        if (!(event.getSender() instanceof Server source) || !(event.getReceiver() instanceof ProxiedPlayer carrier)) return;
        if (!source.getInfo().getName().equalsIgnoreCase(lobbyServer) || event.getData() == null || event.getData().length > 256) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String operation = in.readUTF();
            UUID uuid = UUID.fromString(in.readUTF());
            if (!uuid.equals(carrier.getUniqueId()) || in.available() != 0) return;
            if ("AUTHENTICATED".equals(operation)) {
                authenticatedSessions.add(uuid);
                carrier.sendMessage(new TextComponent("§aAllInOne Auth: Anmeldung bestätigt."));
            } else if ("LOGOUT".equals(operation)) {
                authenticatedSessions.remove(uuid);
            }
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Rejected malformed auth packet.", ex);
        }
    }

    @EventHandler public void onServerConnect(ServerConnectEvent event) {
        ProxiedPlayer player = event.getPlayer();
        if (authenticatedSessions.contains(player.getUniqueId())) return;
        if (event.getTarget().getName().equalsIgnoreCase(lobbyServer)) return;
        event.setCancelled(true);
        ServerInfo lobby = getProxy().getServerInfo(lobbyServer);
        if (lobby == null) {
            player.disconnect(new TextComponent("§cDie Lobby ist nicht verfügbar. Bitte versuche es später erneut."));
            return;
        }
        if (player.getServer() == null || !player.getServer().getInfo().getName().equalsIgnoreCase(lobbyServer)) {
            player.connect(lobby);
        } else {
            player.sendMessage(new TextComponent("§cBitte melde dich zuerst in der Lobby mit /login an."));
        }
    }

    @EventHandler public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        authenticatedSessions.remove(event.getPlayer().getUniqueId());
    }

    private void syncOnlinePlayer(UUID uuid) {
        ProxiedPlayer target = getProxy().getPlayer(uuid);
        if (target != null && target.getServer() != null) sendSync(target.getServer(), uuid, dataStore.get(uuid));
    }
    @EventHandler public void onServerConnected(ServerConnectedEvent event) {
        if (dataStore == null || channel == null) return;
        PlayerRecord record = dataStore.get(event.getPlayer().getUniqueId());
        if (record != null) sendSync(event.getServer(), event.getPlayer().getUniqueId(), record);
    }
    private void sendSync(Server targetServer, UUID uuid, PlayerRecord record) {
        if (record == null) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("SYNC"); out.writeUTF(uuid.toString()); out.writeUTF(record.rank());
            out.writeUTF(BigDecimal.valueOf(record.balance()).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            out.writeLong(record.playtimeSeconds()); out.writeDouble(record.bounty()); out.flush();
            targetServer.sendData(channel, bytes.toByteArray());
        } catch (IOException ex) { getLogger().log(Level.WARNING, "Could not send synchronized player data.", ex); }
    }
    private void sendBountyReward(Server targetServer, UUID hunterId, double amount) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("BOUNTY_REWARD"); out.writeUTF(hunterId.toString()); out.writeDouble(amount); out.flush();
            targetServer.sendData(channel, bytes.toByteArray());
        } catch (IOException ex) { getLogger().log(Level.WARNING, "Could not send bounty reward.", ex); }
    }

    private final class SyncCommand extends Command {
        private SyncCommand() { super("allinoneproxy", "allinoneproxy.sync", "allinoneproy"); }
        @Override public void execute(CommandSender sender, String[] args) {
            if (!sender.hasPermission("allinoneproxy.sync")) { sender.sendMessage(new TextComponent("§cDafür hast du keine Rechte.")); return; }
            if (args.length != 4 || !(args[0].equalsIgnoreCase("sync") || args[0].equalsIgnoreCase("synch")) || !args[1].equalsIgnoreCase("all") || !args[2].equalsIgnoreCase("from")) {
                sender.sendMessage(new TextComponent("§eNutzung: /allinoneproxy sync all from <Server>")); return;
            }
            String serverName = args[3];
            if (!allowedServers.contains(serverName.toLowerCase(Locale.ROOT))) { sender.sendMessage(new TextComponent("§cDieser Server ist nicht in allowed-servers eingetragen.")); return; }
            ServerInfo target = getProxy().getServerInfo(serverName);
            if (target == null) { sender.sendMessage(new TextComponent("§cServer '" + serverName + "' wurde im Proxy nicht gefunden.")); return; }
            if (target.getPlayers().isEmpty()) { sender.sendMessage(new TextComponent("§cAuf " + target.getName() + " ist gerade niemand online.")); return; }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
                out.writeUTF("REQUEST_ALL"); out.flush();
                if (!target.sendData(channel, bytes.toByteArray(), false)) { sender.sendMessage(new TextComponent("§cAnfrage konnte nicht gesendet werden.")); return; }
                sender.sendMessage(new TextComponent("§aVollständige Synchronisierung von " + target.getName() + " angefordert."));
            } catch (IOException ex) { sender.sendMessage(new TextComponent("§cSync-Anfrage konnte nicht erstellt werden.")); }
        }
    }
    public PlayerDataStore getDataStore() { return dataStore; }
}
