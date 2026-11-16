# AllInOneBungee 1.1.4

BungeeCord proxy plugin for synchronizing ServerToolkit ranks and balances across the network.

## Requirements

- Java 25
- BungeeCord proxy
- ServerToolkit 1.14.2 on every backend that should sync

## How it works

The proxy stores the authoritative player records in its local `plugins/AllInOneBungee/players.yml`. No database is used. Paper backends communicate through the `allinone:sync` plugin-message channel.

- On join, ServerToolkit sends a `HELLO` packet. The proxy replies to the backend connection with the saved record, or initializes it from the first backend if no record exists.
- When a rank is assigned or removed through ServerToolkit, the backend sends an `UPDATE` packet.
- The proxy validates the player's UUID and the sending backend server, stores the record, and replies directly to the sending backend. On every backend switch, the proxy also sends the stored rank and balance to the newly connected Paper server.
- The Paper bridge applies the rank using ServerToolkit's normal rank refresh path, including prefix, display name and permissions.

## Configuration

In `plugins/AllInOneBungee/config.yml`, the `allowed-servers` names must exactly match the backend names in BungeeCord's `config.yml` (case-insensitive). The default list is `lobby` and `survival`.

## Installation

1. Build with `mvn clean package` using Java 25.
2. Install `allinonebungee-1.1.3.jar` in the proxy's `plugins/` folder.
3. Install ServerToolkit 1.14.1 on both the Lobby and Survival Paper backends.
4. Restart the proxy and both Paper servers.
5. Check the proxy log for `Rank and balance synchronization is ACTIVE`.

The proxy plugin and the Paper bridge must both be installed. Updating only one side is not sufficient.

## Force a full sync

Run `/allinoneproxy sync all from survival` in the proxy console or as a player with `allinoneproxy.sync`. The named backend publishes the rank and balance of every player currently online there to the proxy store.
