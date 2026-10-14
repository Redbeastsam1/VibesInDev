# VibesInDev

VibesInDev is a Spigot music plugin designed to use a separate Fabric client mod for actual audio playback.

## Current server-side architecture

```text
/music play <URL>
        |
        v
MusicSourceManager
   |       |        |
YouTube SoundCloud Spotify
   |       |        |
   +-------+--------+
           |
           v
        Track(s)
           |
           v
         yt-dlp
           |
           v
         FFmpeg
           |
           v
      cache/*.ogg
           |
           v
   Fabric client (next)
```

## Supported input

- YouTube single videos
- YouTube playlists
- SoundCloud tracks
- SoundCloud sets/playlists
- Spotify tracks
- Spotify playlists that the authorized Spotify account owns or collaborates on

Spotify is used for metadata only. The plugin does not download Spotify audio. For Spotify tracks, the title/artist metadata is resolved through Spotify and then a playable source is searched through yt-dlp.

## Spotify setup

1. Create a Spotify Web API app.
2. Put its Client ID and Client Secret into `config.yml`.
3. Add this Redirect URI to the Spotify app:

```text
http://127.0.0.1:8888/callback
```

4. Start the server.
5. Run:

```text
/music spotify login
```

6. Open the URL printed by the server and authorize the app.

Spotify's current API only exposes playlist items through the playlist-items endpoint for playlists the authorized user owns or collaborates on. Other public playlists may return metadata without their contents.

## Important

Audio is sent to the Fabric client through the existing Minecraft connection; no additional HTTP/TCP/UDP audio server or public domain is required. The Fabric client is expected to keep only a bounded playback buffer in memory and not save the received audio to disk.

No resource pack, datapack, custom jukebox registry, or music discs are used.
