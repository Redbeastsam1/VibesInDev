# VibesInDev

[![Java](https://img.shields.io/badge/Java-21%20%7C%2025-orange.svg)](https://adoptium.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.4%2B-brightgreen.svg)](https://www.minecraft.net/)
[![Paper](https://img.shields.io/badge/Platform-Paper%20%2F%20Purpur-blue.svg)](https://papermc.io/)
[![Fabric](https://img.shields.io/badge/Client-Fabric-lightgrey.svg)](https://fabricmc.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**VibesInDev** is a full-featured music streaming and recommendation suite for Minecraft. It pairs a high-performance **Paper/Purpur server plugin** with a lightweight **Fabric client companion mod** to stream real-time audio over standard Minecraft custom packet channels—with **zero voice chat mods, zero external bots, and zero web clients** required for listeners.

Powered by **Recommendation Engine v2**, VibesInDev keeps the music playing with intelligent autoplay, personalized Spotify taste profile ingestion, group-listening blending, and gameplay-context environmental awareness.

---

## Architecture Overview

```text
               +-------------------------------------------+
               |  Player In-Game: /music play <Query/URL>  |
               +-------------------------------------------+
                                     |
                                     v
                       +---------------------------+
                       |    MusicSourceManager     |
                       | (YouTube/SoundCloud/Spoti)|
                       +---------------------------+
                                     |
                                     v
                       +---------------------------+
                       |    Audio Pipeline Engine  |
                       |  1. yt-dlp (Audio Stream) |
                       |  2. FFmpeg (PCM / Vorbis) |
                       |  3. Disk LRU Cache        |
                       +---------------------------+
                                     |
                                     v
                       +---------------------------+
                       |   MusicStreamer (Server)  |
                       | Sends chunks over custom  |
                       | Minecraft payload channel |
                       +---------------------------+
                                     |
                 (Minecraft Network Connection: vibesindev:music)
                                     |
                                     v
                       +---------------------------+
                       |   Fabric Mod (Client)     |
                       | PositionTrackingStream    |
                       | In-Game Spatial / Record  |
                       +---------------------------+
```

---

## Features

- 🎵 **Seamless Streaming**: Plays YouTube videos, playlists, SoundCloud tracks, and Spotify albums/tracks/playlists.
- 🎧 **Native Packet Audio**: Audio streams directly into player clients via standard Minecraft networking packets; volume can be adjusted via `/music volume <0-100>` or Minecraft's Jukebox volume slider.
- 👥 **Party System**: Listen together with friends using synchronized playback, shared queues, and voting controls.
- 🧠 **Recommendation Engine v2**:
  - **Dynamic Mode Mixture**: Blends Familiar (30%), Similar (50%), and Explore (20%) tracks when queues run empty.
  - **Least-Misery Group Blending**: Synthesizes taste profiles for parties so everyone enjoys the session.
  - **MMR Diversity**: Penalizes repetition against recent plays using Maximal Marginal Relevance.
  - **Gameplay Context**: Biases energy dynamically based on dimension, combat state, underground depth, and boss fights.
- 🔒 **Zero-Port-Forwarding Spotify OAuth**: Flexible authentication supporting local loopback, Cloudflare Tunnels, and in-game Auth Books.

---

## Requirements & Prerequisites

### Server-Side Requirements
* **Server Software**: Paper, Purpur, or Spigot for **Minecraft 1.21.4+** (Java 21 or Java 25 recommended).
* **System Binaries**: Both `yt-dlp` and `ffmpeg` must be installed on the machine hosting the Minecraft server:
  * **Windows (via winget)**:
    ```powershell
    winget install yt-dlp.yt-dlp
    winget install Gyan.FFmpeg
    ```
  * **Ubuntu / Debian**:
    ```bash
    sudo apt update && sudo apt install -y ffmpeg
    sudo curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp
    sudo chmod a+rx /usr/local/bin/yt-dlp
    ```
  * **Arch Linux**:
    ```bash
    sudo pacman -S yt-dlp ffmpeg
    ```
  * **macOS (via Homebrew)**:
    ```bash
    brew install yt-dlp ffmpeg
    ```

### Client-Side Requirements (For Players)
* **Minecraft**: Java Edition 1.21.4+.
* **Fabric Loader**: [Fabric Loader](https://fabricmc.net/use/installer/) installed on the player's launcher.
* **Fabric API**: Latest [Fabric API](https://modrinth.com/mod/fabric-api) jar in `.minecraft/mods/`.
* **Companion Mod**: `vibesindev-1.0.0.jar` placed in `.minecraft/mods/`.

---

## Step-by-Step Setup Guide

### 1. Server Installation
1. Download the latest `VibesInDev-1.0.0.jar` from the [Releases](https://github.com/your-username/VibesInDev/releases) page.
2. Place `VibesInDev-1.0.0.jar` into your Minecraft server's `plugins/` directory.
3. Start the server once to generate the default configuration file, then shut down or prepare to edit `plugins/VibesInDev/config.yml`.
4. Verify `tools.yt-dlp` and `tools.ffmpeg` in `config.yml`:
   * If both binaries are in your system's `PATH`, the defaults (`"yt-dlp"` and `"ffmpeg"`) work out of the box.
   * If installed to a custom directory, set absolute paths (e.g., `yt-dlp: "C:/tools/yt-dlp.exe"`).

---

### 2. Spotify Developer Setup (Metadata & Personal Playlists)
Spotify integration enables public track/album resolution, personal playlist import, and Recommendation Engine v2 taste ingestion.

1. Log into the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) with your Spotify account.
2. Click **Create app**:
   * **App name**: `VibesInDev`
   * **App description**: `Minecraft Music Streaming`
   * **Redirect URIs**: Add `http://127.0.0.1:8888/callback` *(see the Remote Server section below if hosting remotely)*.
   * **APIs used**: Check **Web API**.
   * Agree to terms and save.
3. Go to **Settings** in your new app and copy your **Client ID** and **Client Secret**.
4. Open `plugins/VibesInDev/config.yml` on your server and enter your credentials:
   ```yaml
   spotify:
     enabled: true
     client-id: "YOUR_SPOTIFY_CLIENT_ID"
     client-secret: "YOUR_SPOTIFY_CLIENT_SECRET"
     redirect-uri: "http://127.0.0.1:8888/callback"
   ```
5. Run `/music reload` or restart your server.

---

### 3. How Players Connect Spotify

#### Mode A: Local / Same-Machine Server (1-Click Automatic)
If running a local test server on your own computer:
1. In-game, type `/music spotify login`.
2. Click **`[CLICK HERE TO LOG IN]`** $\rightarrow$ click **Agree** on Spotify's website.
3. The server's internal callback receiver captures the response and connects your account automatically!

#### Mode B: Cloudflare Tunnel (1-Click Automatic for Remote Friends Without Port Forwarding)
To allow friends connecting over the internet to log in automatically without router port forwarding:
1. Download [cloudflared](https://github.com/cloudflare/cloudflared/releases) on the host machine.
2. Run the tunnel:
   ```powershell
   .\cloudflared.exe tunnel --url http://127.0.0.1:8888
   ```
   Cloudflare will output a public HTTPS link, e.g.: `https://random-words.trycloudflare.com`
3. In [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) $\rightarrow$ Settings $\rightarrow$ **Redirect URIs**, add:
   ```text
   https://random-words.trycloudflare.com/callback
   ```
4. In `plugins/VibesInDev/config.yml`:
   ```yaml
   spotify:
     redirect-uri: "https://random-words.trycloudflare.com/callback"
     webserver:
       enabled: true
       bind-address: "127.0.0.1"
   ```
5. Run `/music reload`. Any friend who clicks login now connects **100% automatically**!

#### Mode C: Zero-Port-Forwarding Manual Flow (Chat or Auth Book)
If you do not run a tunnel or reverse proxy:
1. In-game, run `/music spotify login` and click the login link.
2. After clicking **Agree**, your browser will show *"This site can't be reached"*. **This is expected.**
3. Copy the URL from your browser's address bar.
4. In-game:
   * **Direct Command**: Type `/music spotify code <paste_url_or_code>`.
   * **Or Auth Book**: Click **`[CLICK FOR AUTH BOOK]`**, paste with `Ctrl + V`, and click **Done**.

---

### 4. Client Companion Mod Setup (For Players)
1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft **1.21.4+**.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in `.minecraft/mods/`.
3. Download `vibesindev-1.0.0.jar` from [Releases](https://github.com/your-username/VibesInDev/releases) and place it in `.minecraft/mods/`.
4. Launch Minecraft and join the server. Audio will stream natively through your client!

---

## Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/music play <URL/Query>` | `vibesindev.play` | Play or queue a song/playlist from YouTube, SoundCloud, or Spotify |
| `/music pause` / `/music resume` | `vibesindev.control` | Pause or resume current playback |
| `/music skip` | `vibesindev.control` | Skip the current track |
| `/music stop` | `vibesindev.control` | Stop music and clear the personal queue |
| `/music queue` | `vibesindev.play` | View current track and upcoming queue |
| `/music volume <0-100>` | `vibesindev.play` | Adjust personal streaming volume |
| `/music autoplay [on/off]` | `vibesindev.play` | Toggle personal continuous autoplay |
| `/music why` | `vibesindev.play` | Inspect why the current autoplay track was recommended |
| `/music like` / `/music dislike` | `vibesindev.play` | Provide explicit preference feedback for taste profiling |
| `/music party create [name]` | `vibesindev.party` | Create a synchronized group listening party |
| `/music party join <player/id>` | `vibesindev.party` | Join another player's listening party |
| `/music party leave` | `vibesindev.party` | Leave the active party |
| `/music spotify login` | `vibesindev.spotify` | Link your Spotify account for taste profile syncing |
| `/music spotify code <url/code>` | `vibesindev.spotify` | Complete Spotify OAuth code exchange |
| `/music spotify sync` | `vibesindev.spotify` | Manually synchronize top tracks, artists, and saved music |
| `/music spotify status` | `vibesindev.spotify` | View active Spotify authorization status |
| `/music spotify logout` | `vibesindev.spotify` | Unlink Spotify account and purge session |
| `/music rec stats [days]` | `vibesindev.admin` | View recommendation engine diagnostic statistics |
| `/music reload` | `vibesindev.admin` | Reload `config.yml` |

---

## Troubleshooting & FAQ

#### Q: yt-dlp fails with "Sign in to confirm you're not a bot" or age-restriction errors.
YouTube frequently blocks requests originating from cloud hosting / datacenter IP addresses.
* **Fix**: Export cookies from your browser using a cookies extension or set:
  ```yaml
  tools:
    cookies-from-browser: "chrome"  # or firefox, edge, brave
  ```
  or export a Netscape-format cookie file and point `tools.cookies-file: "C:/path/to/cookies.txt"`.

#### Q: Players can't hear any music.
1. Ensure the player has installed `vibesindev-1.0.0.jar` and `fabric-api` in their local client `.minecraft/mods/` folder.
2. Ensure their in-game volume for **Jukebox / Note Blocks** and **Master Volume** is turned up.
3. Test running `/music volume 100`.

---

## Legal, Copyright & Terms of Service Disclaimer

### 1. Open Source Software
VibesInDev is open-source software provided under the [MIT License](LICENSE). The repository contains **zero copyrighted music files, audio recordings, or proprietary media**. All software logic consists of networking protocols, recommendation mathematics, and format converters.

### 2. Third-Party Service Terms of Service
- **Spotify**: Spotify Web API is used solely for metadata resolution, playlist indexing, and taste analysis. VibesInDev **does not download, rip, or store audio streams from Spotify**. In accordance with the Spotify Developer Policy, this software is intended for personal, non-commercial use.
- **YouTube & SoundCloud**: Audio playback utilizes public tools (`yt-dlp` and `ffmpeg`) to decode public media streams. Accessing streams without displaying advertisements may be contrary to the YouTube Terms of Service. Server operators assume full responsibility for their server's usage patterns.

### 3. Copyright & Performance Rights
- **Personal & Private Server Use**: Playing music in a private Minecraft server among friends is generally considered private, non-commercial listening.
- **Commercial / Public Servers**: Operating a large, publicly accessible, or monetized Minecraft server that broadcasts copyrighted music publicly may require public performance licenses (e.g., ASCAP, BMI, SESAC, PRS) from relevant copyright holders.
- Neither the authors nor contributors of VibesInDev endorse, encourage, or facilitate copyright infringement. All trademarks, artist names, album art, and audio streams remain the exclusive intellectual property of their respective owners.

---

## License

This project is licensed under the [MIT License](LICENSE).
