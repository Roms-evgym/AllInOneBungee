package dev.roms.allinonebungee;

import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

public final class PlayerDataStore {
    private final File file;
    private Configuration data;
    public PlayerDataStore(File file) throws IOException {
        this.file = file;
        data = file.exists() ? ConfigurationProvider.getProvider(YamlConfiguration.class).load(file) : new Configuration();
        if (data.getSection("players") == null) data.set("players", new Configuration());
        save();
    }
    public synchronized PlayerRecord get(UUID uuid) {
        Configuration players = data.getSection("players");
        Configuration player = players == null ? null : players.getSection(uuid.toString());
        if (player == null) return null;
        String rank = player.getString("rank", "member");
        if (rank == null || rank.isBlank() || rank.equalsIgnoreCase("default")) {
            rank = "member"; player.set("rank", rank);
            try { save(); } catch (IOException ex) { throw new IllegalStateException("Could not migrate player rank " + uuid, ex); }
        }
        return new PlayerRecord(rank, player.getDouble("balance", 0.0D),
                Math.max(0L, player.getLong("playtime-seconds", 0L)),
                Math.max(0.0D, player.getDouble("bounty", 0.0D)));
    }
    public synchronized void set(UUID uuid, PlayerRecord record) {
        Configuration players = data.getSection("players");
        if (players == null) { players = new Configuration(); data.set("players", players); }
        Configuration player = new Configuration();
        player.set("rank", record.rank());
        player.set("balance", record.balance());
        player.set("playtime-seconds", record.playtimeSeconds());
        player.set("bounty", record.bounty());
        players.set(uuid.toString(), player);
        try { save(); } catch (IOException ex) { throw new IllegalStateException("Could not persist player " + uuid, ex); }
    }
    public synchronized long addPlaytime(UUID uuid, long seconds) {
        PlayerRecord old = getOrDefault(uuid);
        long total = Math.addExact(old.playtimeSeconds(), Math.max(0L, seconds));
        set(uuid, new PlayerRecord(old.rank(), old.balance(), total, old.bounty()));
        return total;
    }
    public synchronized double addBounty(UUID uuid, double amount) {
        PlayerRecord old = getOrDefault(uuid);
        double total = old.bounty() + amount;
        if (!Double.isFinite(total) || total < 0) throw new IllegalArgumentException("Invalid bounty total");
        set(uuid, new PlayerRecord(old.rank(), old.balance(), old.playtimeSeconds(), total));
        return total;
    }
    public synchronized double claimBounty(UUID uuid) {
        PlayerRecord old = getOrDefault(uuid);
        set(uuid, new PlayerRecord(old.rank(), old.balance(), old.playtimeSeconds(), 0.0D));
        return old.bounty();
    }
    private PlayerRecord getOrDefault(UUID uuid) {
        PlayerRecord record = get(uuid);
        return record == null ? new PlayerRecord("member", 0.0D) : record;
    }
    public synchronized void save() throws IOException {
        ConfigurationProvider.getProvider(YamlConfiguration.class).save(data, file);
    }
    public File getFile() { return file; }
}
