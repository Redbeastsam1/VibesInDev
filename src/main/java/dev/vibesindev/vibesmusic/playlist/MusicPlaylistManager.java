package dev.vibesindev.vibesmusic.playlist;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.model.Track;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MusicPlaylistManager {

    private final VibesInDevPlugin plugin;
    private final File file;
    private YamlConfiguration config;

    // UUID -> (playlistName lowercase -> CustomPlaylist)
    private final Map<UUID, Map<String, CustomPlaylist>> playlists = new ConcurrentHashMap<>();

    public record CustomPlaylist(String name, long createdAt, List<Track> tracks) {}

    public MusicPlaylistManager(VibesInDevPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playlists.yml");
        load();
    }

    public synchronized void load() {
        playlists.clear();
        if (!file.exists()) {
            config = new YamlConfiguration();
            return;
        }

        config = YamlConfiguration.loadConfiguration(file);
        for (String uuidStr : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ConfigurationSection userSec = config.getConfigurationSection(uuidStr);
                if (userSec == null) continue;

                Map<String, CustomPlaylist> userMap = new ConcurrentHashMap<>();
                for (String plKey : userSec.getKeys(false)) {
                    ConfigurationSection plSec = userSec.getConfigurationSection(plKey);
                    if (plSec == null) continue;

                    String name = plSec.getString("name", plKey);
                    long createdAt = plSec.getLong("created-at", System.currentTimeMillis());
                    List<?> rawTracks = plSec.getList("tracks");
                    List<Track> tracks = new ArrayList<>();

                    if (rawTracks != null) {
                        for (Object obj : rawTracks) {
                            if (obj instanceof Map<?, ?> m) {
                                String id = String.valueOf(m.get("id"));
                                String title = String.valueOf(m.get("title"));
                                String artist = String.valueOf(m.get("artist"));
                                long duration = 0L;
                                Object durObj = m.get("duration");
                                if (durObj instanceof Number n) {
                                    duration = n.longValue();
                                }
                                String url = String.valueOf(m.get("url"));
                                tracks.add(new Track(id, title, artist, duration, url));
                            }
                        }
                    }
                    userMap.put(name.toLowerCase(Locale.ROOT), new CustomPlaylist(name, createdAt, tracks));
                }
                playlists.put(uuid, userMap);
            } catch (Exception e) {
                plugin.getLogger().warning("[VibesInDev] Error loading playlist section: " + uuidStr + " - " + e.getMessage());
            }
        }
    }

    public synchronized void save() {
        if (config == null) {
            config = new YamlConfiguration();
        }

        for (Map.Entry<UUID, Map<String, CustomPlaylist>> userEntry : playlists.entrySet()) {
            String uuidStr = userEntry.getKey().toString();
            config.set(uuidStr, null); // Clear existing user section

            for (CustomPlaylist pl : userEntry.getValue().values()) {
                String path = uuidStr + "." + pl.name().toLowerCase(Locale.ROOT);
                config.set(path + ".name", pl.name());
                config.set(path + ".created-at", pl.createdAt());

                List<Map<String, Object>> trackList = new ArrayList<>();
                for (Track t : pl.tracks()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", t.id());
                    map.put("title", t.title());
                    map.put("artist", t.artist());
                    map.put("duration", t.durationSeconds());
                    map.put("url", t.sourceUrl());
                    trackList.add(map);
                }
                config.set(path + ".tracks", trackList);
            }
        }

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("[VibesInDev] Failed to save playlists.yml: " + e.getMessage());
        }
    }

    public boolean savePlaylist(UUID player, String name, List<Track> tracks) {
        if (player == null || name == null || name.isBlank() || tracks == null || tracks.isEmpty()) {
            return false;
        }

        Map<String, CustomPlaylist> userMap = playlists.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        String key = name.toLowerCase(Locale.ROOT).trim();
        userMap.put(key, new CustomPlaylist(name.trim(), System.currentTimeMillis(), new ArrayList<>(tracks)));
        save();
        return true;
    }

    public CustomPlaylist getPlaylist(UUID player, String name) {
        if (player == null || name == null) return null;
        Map<String, CustomPlaylist> userMap = playlists.get(player);
        if (userMap == null) return null;
        return userMap.get(name.toLowerCase(Locale.ROOT).trim());
    }

    public Map<String, CustomPlaylist> getPlaylists(UUID player) {
        if (player == null) return Collections.emptyMap();
        Map<String, CustomPlaylist> userMap = playlists.get(player);
        if (userMap == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(userMap);
    }

    public List<String> getPlaylistNames(UUID player) {
        if (player == null) return Collections.emptyList();
        Map<String, CustomPlaylist> userMap = playlists.get(player);
        if (userMap == null) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (CustomPlaylist pl : userMap.values()) {
            list.add(pl.name());
        }
        Collections.sort(list);
        return list;
    }

    public boolean deletePlaylist(UUID player, String name) {
        if (player == null || name == null) return false;
        Map<String, CustomPlaylist> userMap = playlists.get(player);
        if (userMap == null) return false;

        String key = name.toLowerCase(Locale.ROOT).trim();
        CustomPlaylist removed = userMap.remove(key);
        if (removed != null) {
            if (config != null) {
                config.set(player.toString() + "." + key, null);
            }
            save();
            return true;
        }
        return false;
    }

    public String formatTotalDuration(List<Track> tracks) {
        long totalSeconds = 0;
        for (Track t : tracks) {
            totalSeconds += t.durationSeconds();
        }
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        } else {
            return String.format("%02d:%02d", m, s);
        }
    }
}
