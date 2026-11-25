package dev.roms.allinonebungee;

public record PlayerRecord(String rank, double balance, long playtimeSeconds, double bounty) {
    public PlayerRecord {
        if (rank == null || rank.isBlank()) rank = "member";
        if (!Double.isFinite(balance) || balance < 0) throw new IllegalArgumentException("Balance must be finite and non-negative.");
        if (playtimeSeconds < 0) throw new IllegalArgumentException("Playtime must be non-negative.");
        if (!Double.isFinite(bounty) || bounty < 0) throw new IllegalArgumentException("Bounty must be finite and non-negative.");
    }
    public PlayerRecord(String rank, double balance) { this(rank, balance, 0L, 0.0D); }
}
