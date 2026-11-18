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
        if (file.exists()) {
            data = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
        } else {
            data = new Configuration();
        }
        if (data.getSection("players") == null) data.set("players", new Configuration());
        save();
    }

    public synchronized PlayerRecord get(UUID uuid) {
        Configuration players = data.getSection("players");
        Configuration player = players == null ? null : players.getSection(uuid.toString());
        if (player == null) return null;
        String rank = player.getString("rank", "member");
        // Migrate legacy proxy records that used "default"; ServerToolkit's default rank is "member".
        if (rank == null || rank.isBlank() || rank.equalsIgnoreCase("default")) {
            rank = "member";
            player.set("rank", rank);
            try { save(); }
            catch (IOException ex) { throw new IllegalStateException("Could not migrate player rank " + uuid, ex); }
        }
        return new PlayerRecord(rank, player.getDouble("balance", 0.0D));
    }

    public synchronized void set(UUID uuid, PlayerRecord record) {
        Configuration players = data.getSection("players");
        if (players == null) {
            players = new Configuration();
            data.set("players", players);
        }
        Configuration player = new Configuration();
        player.set("rank", record.rank());
        player.set("balance", record.balance());
        players.set(uuid.toString(), player);
        try { save(); }
        catch (IOException ex) { throw new IllegalStateException("Could not persist player " + uuid, ex); }
    }

    public synchronized void save() throws IOException {
        ConfigurationProvider.getProvider(YamlConfiguration.class).save(data, file);
    }

    public File getFile() { return file; }
}