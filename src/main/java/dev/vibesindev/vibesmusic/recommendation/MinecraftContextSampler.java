package dev.vibesindev.vibesmusic.recommendation;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Samples in-game contextual Minecraft signals at track recommendation boundaries.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Non-intrusive: never samples per-tick, never interrupts playing tracks.</li>
 *   <li>Only biases the next autoplay pick when {@code recommendations.context.enabled: true}.</li>
 *   <li>Weight is strictly bounded (w_context <= 0.10) so taste profile remains primary.</li>
 * </ul>
 */
public final class MinecraftContextSampler implements Listener {

    private final VibesInDevPlugin plugin;
    private final Map<UUID, Long> lastCombatTimes = new ConcurrentHashMap<>();

    public record ContextSnapshot(
            String dimension,
            boolean inCombat,
            boolean isUnderground,
            boolean isNight,
            boolean nearBoss,
            double targetEnergy,
            String moodHint
    ) {
        public static final ContextSnapshot NEUTRAL = new ContextSnapshot(
                "OVERWORLD", false, false, false, false, 0.50, "neutral"
        );
    }

    public MinecraftContextSampler(VibesInDevPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        long now = System.currentTimeMillis();
        if (event.getDamager() instanceof Player damager) {
            lastCombatTimes.put(damager.getUniqueId(), now);
        }
        if (event.getEntity() instanceof Player victim) {
            lastCombatTimes.put(victim.getUniqueId(), now);
        }
    }

    public void recordCombat(UUID playerUuid) {
        if (playerUuid != null) {
            lastCombatTimes.put(playerUuid, System.currentTimeMillis());
        }
    }

    public boolean isInCombat(UUID playerUuid, long windowMs) {
        if (playerUuid == null) return false;
        Long last = lastCombatTimes.get(playerUuid);
        return last != null && (System.currentTimeMillis() - last) <= windowMs;
    }

    public ContextSnapshot sample(UUID playerUuid) {
        if (playerUuid == null || Bukkit.getServer() == null) {
            return ContextSnapshot.NEUTRAL;
        }

        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            return ContextSnapshot.NEUTRAL;
        }

        World world = player.getWorld();
        String dimension = (world != null && world.getEnvironment() != null)
                ? world.getEnvironment().name() : "NORMAL";

        boolean inCombat = isInCombat(playerUuid, 15_000L);

        double y = player.getLocation() != null ? player.getLocation().getY() : 64.0;
        int skyLight = 15;
        try {
            if (player.getLocation() != null && player.getLocation().getBlock() != null) {
                skyLight = player.getLocation().getBlock().getLightFromSky();
            }
        } catch (Throwable ignored) {}

        boolean isUnderground = y < 40 && skyLight < 4;

        boolean isNight = false;
        if (world != null && "NORMAL".equalsIgnoreCase(dimension)) {
            long time = world.getTime() % 24000L;
            isNight = (time >= 13000L && time <= 23000L);
        }

        boolean nearBoss = false;
        try {
            if (player.getLocation() != null && world != null) {
                for (Entity entity : player.getNearbyEntities(64.0, 32.0, 64.0)) {
                    if (entity != null && entity.getType() != null) {
                        String typeName = entity.getType().name();
                        if ("WITHER".equalsIgnoreCase(typeName) || "ENDER_DRAGON".equalsIgnoreCase(typeName) || "WARDEN".equalsIgnoreCase(typeName)) {
                            nearBoss = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Target energy mapping in [0.0, 1.0]
        double targetEnergy;
        String moodHint;

        if (inCombat || nearBoss) {
            targetEnergy = nearBoss ? 0.90 : 0.85;
            moodHint = "combat";
        } else if ("NETHER".equalsIgnoreCase(dimension)) {
            targetEnergy = 0.35;
            moodHint = "nether_atmospheric";
        } else if ("THE_END".equalsIgnoreCase(dimension)) {
            targetEnergy = 0.30;
            moodHint = "end_atmospheric";
        } else if (isUnderground) {
            targetEnergy = 0.30;
            moodHint = "caves_atmospheric";
        } else if (isNight) {
            targetEnergy = 0.45;
            moodHint = "night_ambient";
        } else {
            targetEnergy = 0.55;
            moodHint = "day_chill";
        }

        return new ContextSnapshot(dimension, inCombat, isUnderground, isNight, nearBoss, targetEnergy, moodHint);
    }
}
