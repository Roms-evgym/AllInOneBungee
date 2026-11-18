package dev.roms.allinonebungee;

public record PlayerRecord(String rank, double balance) {
    public PlayerRecord {
        if (rank == null || rank.isBlank()) rank = "member";
        if (!Double.isFinite(balance) || balance < 0) {
            throw new IllegalArgumentException("Balance must be a finite non-negative number.");
        }
    }
}