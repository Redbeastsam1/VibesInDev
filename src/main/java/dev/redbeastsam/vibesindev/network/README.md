# Network layer

VibesInDev sends audio through the existing Minecraft server/client connection.
No additional HTTP/TCP/UDP audio server or public domain is required.

## Protocol

Channel:

```text
vibesindev:music
```

Messages:

- `HELLO` — Fabric client announces protocol version.
- `HELLO_ACK` — server confirms the protocol.
- `START` — stream metadata and track information.
- `DATA` — one bounded OGG byte chunk with a sequence number.
- `END` — stream finished.
- `STOP` — current stream cancelled.

The server uses 32 KiB chunks by default. The Spigot 26.3 API allows plugin messages up to 1 MiB, so the chunk size leaves substantial room for packet metadata.

The client is expected to keep only a bounded audio buffer in memory and must not save the received OGG to disk.

No resource pack, datapack, custom jukebox registry, or music discs are used.
