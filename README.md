# VibesInDev

VibesInDev is a Spigot music plugin designed to use a separate Fabric client mod for actual audio playback over the existing Minecraft packet connection, paired with an advanced multi-objective recommendation engine (Recommendation Engine v2).

## Repository Structure

This repository is structured as a unified monorepo containing both the server plugin and client companion mod:

- **Root (`/`)**: Spigot/Paper server plugin source code, built with Maven (`./mvnw clean package`).
- **[`client/`](client/)**: Fabric client companion mod, built with Gradle (`./gradlew build`). Formatted for direct upload to Modrinth.
- **[`tools/`](tools/)**: Offline recommendation ranker training utilities (`fit_ranker.py`).

## Current Server-Side Architecture

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
   Fabric client packet stream
```

## Supported Input

- YouTube single videos & playlists
- SoundCloud tracks & sets
- Spotify tracks, albums, and playlists

Spotify is used for metadata, taste profile ingestion, and candidate discovery. The plugin does not download Spotify audio streams; for Spotify tracks, metadata is resolved and playable audio is retrieved via yt-dlp.

---

## Spotify OAuth Setup & Deployment Modes

### 1. Same-Host Browser Callback (Default)
When the Minecraft server and your web browser run on the same physical host (e.g. local development or personal server):
1. In the Spotify Developer Dashboard, register Redirect URI: `http://127.0.0.1:8888/callback`
2. Set `spotify.webserver.bind-address: "127.0.0.1"` in `config.yml`.
3. In-game, run `/music spotify login` and complete authorization in your browser.

### 2. Remote Dedicated Server Deployment
When your Minecraft server is hosted remotely:
- **Zero-Port-Forwarding Manual Code Flow (Recommended for remote servers without reverse proxy):**
  1. In `config.yml`, set `spotify.webserver.enabled: false`.
  2. Run `/music spotify login`.
  3. Click the authorization URL. After consenting, copy the code parameter from the browser address bar (`?code=...`).
  4. Run `/music spotify code <your_code>` in game.
- **Public Reverse Proxy with TLS (Advanced):**
  If using an automatic callback on a remote server, terminate TLS at a secure reverse proxy (e.g., Caddy or Nginx) forwarding to `spotify.webserver.port`. Register the exact public HTTPS URI (e.g., `https://music.yourserver.com/callback`) in the Spotify Developer Dashboard and set `spotify.redirect-uri`.
  > [!CAUTION]
  > Never expose the internal callback server directly to the public internet by binding `0.0.0.0` without TLS termination, firewall rules, and an HTML-sanitized callback handler.

### Token-at-Rest Security Policy
Per-player Spotify OAuth refresh tokens are persisted in `spotify_tokens.json` / SQLite.
- File-system access permissions are the security boundary: tokens are protected by operating system file permissions, not encryption.
- Server operators must restrict file permissions to the server process owner (`chmod 600` on Linux, restricted ACLs on Windows).

---

## Recommendation Engine v2 & Continuous Autoplay

When a player's or party's queue runs empty, continuous autoplay keeps music playing seamlessly.

### Features
- **Dynamic Mode Mixture**: Stochastically routes candidates across **Familiar** (30%), **Similar** (50%), and **Explore** (20%) modes.
- **Maximal Marginal Relevance (MMR)**: Penalizes tracks musically redundant with the listener's last 5 plays ($\lambda = 0.70$).
- **Multi-Objective Scoring**: Evaluates genre cosine affinity, artist affinity, audio continuity, familiarity, quality, and novelty.
- **Least Misery Party Blending**: Synthesizes group taste profiles ($M = 0.5 \cdot \text{mean} + 0.5 \cdot \min$) with optional leader rotation.
- **Adaptive Exploration**: Dynamically tunes exploration ratio between $5\%$ and $40\%$ based on player skip/completion feedback.
- **Minecraft Gameplay Context**: Samples dimension, underground depth, combat state, solar cycle, and boss encounters to subtly bias target energy ($w \le 0.10$).
- **Zero-Latency Preloading**: Prepares the next audio track when $15\%$ of the active song remains.

### Telemetry & Privacy Policy
VibesInDev logs candidate-level feature vectors and listening outcomes strictly to local SQLite tables (`rec_impressions` and `rec_candidates`):
- **Purpose**: Powers explainability (`/music why`), admin diagnostics (`/music rec stats`), and offline ranker fitting (`tools/fit_ranker.py`).
- **Privacy**: All telemetry remains strictly local to the server database. No listening habits, usernames, or telemetry vectors are transmitted to external services.
- **Retention**: Records older than 30 days are automatically pruned at startup and during daily maintenance.

---

## Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/music play <query/URL>` | `vibesindev.play` | Play a track or playlist |
| `/music autoplay [on\|off]` | `vibesindev.play` | Toggle personal continuous autoplay |
| `/music why` | `vibesindev.play` | Explain why the current autoplay track was recommended |
| `/music like` / `/music dislike` | `vibesindev.play` | Provide explicit feedback for current track |
| `/music party autoplay [on\|off]` | `vibesindev.party.manage` | Toggle party-wide autoplay |
| `/music spotify login` | `vibesindev.spotify` | Initiate Spotify OAuth authorization |
| `/music spotify code <code>` | `vibesindev.spotify` | Complete authorization via manual code flow |
| `/music spotify sync` | `vibesindev.spotify` | Synchronize top artists, top tracks, and saved library |
| `/music spotify status` | `vibesindev.spotify` | View active Spotify authorization status |
| `/music spotify logout` | `vibesindev.spotify` | Unlink Spotify account and revoke session |
| `/music rec stats [days]` | `vibesindev.admin` | View recommendation engine diagnostic statistics |
| `/music reload` | `vibesindev.admin` | Reload `config.yml` |

---

## License

This project is open-source software licensed under the MIT License. See [LICENSE](LICENSE) for details.
