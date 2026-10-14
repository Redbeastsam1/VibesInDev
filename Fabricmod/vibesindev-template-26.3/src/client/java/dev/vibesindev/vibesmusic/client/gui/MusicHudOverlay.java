package dev.vibesindev.vibesmusic.client.gui;

import dev.vibesindev.vibesmusic.client.audio.MusicAudioPlayer;
import dev.vibesindev.vibesmusic.client.audio.MusicStreamBuffer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * A compact, always-on-screen HUD overlay that appears while music is playing.
 */
public final class MusicHudOverlay implements HudElement {

    // Panel geometry
    private static final int PANEL_W    = 220;
    private static final int PANEL_H    = 54;
    private static final int PADDING    = 4;
    private static final int HEAD_SIZE  = 24;
    private static final int MARGIN     = 8;

    // Progress bar
    private static final int BAR_H      = 3;

    // Fade animation
    private static final int FADE_IN_TICKS  = 10;
    private static final int FADE_OUT_TICKS = 20;

    // State
    private static float opacity    = 0f;
    private static boolean visible  = false;
    private static int fadeTick     = 0;
    private static boolean fadingIn = false;

    private static volatile String reconnectNotice = null;
    private static volatile long reconnectNoticeExpiry = 0L;

    public MusicHudOverlay() {}

    public static void showReconnectNotice(String title, String artist, long posMs) {
        reconnectNotice = "Resumed: " + title;
        reconnectNoticeExpiry = System.currentTimeMillis() + 6000L;
    }

    public static void tick() {
        boolean shouldShow = MusicAudioPlayer.isPlaying() || MusicAudioPlayer.isPaused();

        if (shouldShow && !visible) {
            visible  = true;
            fadingIn = true;
            fadeTick = 0;
        } else if (!shouldShow && visible) {
            fadingIn = false;
            fadeTick = 0;
        }

        if (visible) {
            if (fadingIn) {
                fadeTick++;
                opacity = Math.min(1f, (float) fadeTick / FADE_IN_TICKS);
            } else {
                fadeTick++;
                opacity = Math.max(0f, 1f - (float) fadeTick / FADE_OUT_TICKS);
                if (opacity <= 0f) {
                    visible = false;
                    fadeTick = 0;
                }
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, DeltaTracker deltaTracker) {
        if (!visible || opacity <= 0f) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden()) {
            return;
        }

        MusicStreamBuffer.StreamSession session = MusicStreamBuffer.current();
        if (session == null) {
            return;
        }

        int screenH = mc.getWindow().getGuiScaledHeight();
        int panelX = PADDING;
        int panelY = screenH - PANEL_H - PADDING;

        int alpha = (int) (opacity * 200);

        // --- Background panel ---
        int bg = colorWithAlpha(0x0D0D0D, alpha);
        int border = colorWithAlpha(0x6644DDFF, alpha);

        gfx.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, bg);
        gfx.fill(panelX, panelY, panelX + 2, panelY + PANEL_H, border);

        // --- Player Head ---
        int headX = panelX + 6;
        int headY = panelY + (PANEL_H - HEAD_SIZE) / 2;
        renderPlayerHead(gfx, mc, headX, headY, HEAD_SIZE, alpha);

        // --- Text area ---
        int textX = headX + HEAD_SIZE + MARGIN;
        int textY = panelY + 8;

        String title = session.title();
        String artist = session.artist();

        int titleColor = colorWithAlpha(0xFFFFFF, alpha);
        int artistColor = colorWithAlpha(0xAAAAAA, alpha);

        gfx.text(mc.font, truncate(mc, title, PANEL_W - textX + panelX - 8), textX, textY, titleColor, true);
        gfx.text(mc.font, truncate(mc, artist, PANEL_W - textX + panelX - 8), textX, textY + 10, artistColor, false);

        // --- Progress bar ---
        int barX = textX;
        int barY = textY + 22;
        int barW = PANEL_W - (textX - panelX) - 8;

        long totalMs = session.totalBytes() > 0 ? estimateDurationMs(session) : 0;
        long posMs = MusicAudioPlayer.getPositionMs();
        float progress = (totalMs > 0) ? Math.min(1f, (float) posMs / totalMs) : 0f;

        int barBg = colorWithAlpha(0x333333, alpha);
        int barFg = colorWithAlpha(0x8855FF, alpha);

        gfx.fill(barX, barY, barX + barW, barY + BAR_H, barBg);
        if (progress > 0f) {
            gfx.fill(barX, barY, barX + (int)(barW * progress), barY + BAR_H, barFg);
        }

        // --- State badge ---
        if (System.currentTimeMillis() < reconnectNoticeExpiry && reconnectNotice != null) {
            int reconnectColor = colorWithAlpha(0x00FFCC, alpha);
            gfx.text(mc.font, truncate(mc, reconnectNotice, PANEL_W - textX + panelX - 8), textX, textY + 30, reconnectColor, false);
        } else if (MusicAudioPlayer.isPaused()) {
            int badgeColor = colorWithAlpha(0xFFAA00, alpha);
            gfx.text(mc.font, "Paused", textX, textY + 30, badgeColor, false);
        } else {
            String timeStr = formatMs(posMs) + " / " + (totalMs > 0 ? formatMs(totalMs) : "--:--");
            int timeColor = colorWithAlpha(0x888888, alpha);
            gfx.text(mc.font, timeStr, textX, textY + 30, timeColor, false);
        }
    }

    private static void renderPlayerHead(
            GuiGraphicsExtractor gfx,
            Minecraft mc,
            int x, int y, int size, int alpha
    ) {
        if (mc.player == null) return;
        try {
            Identifier skin = (mc.player.getSkin() != null && mc.player.getSkin().body() != null)
                    ? mc.player.getSkin().body().id()
                    : null;

            if (skin != null) {
                // Base head layer: UV region (8, 8) to (16, 16) on 64x64 skin
                gfx.blit(skin, x, y, size, size, 8.0f / 64.0f, 8.0f / 64.0f, 16.0f / 64.0f, 16.0f / 64.0f);
                // Hat / overlay layer: UV region (40, 8) to (48, 16)
                gfx.blit(skin, x, y, size, size, 40.0f / 64.0f, 8.0f / 64.0f, 48.0f / 64.0f, 16.0f / 64.0f);
            }
        } catch (Exception ignored) {
        }
    }

    private static long estimateDurationMs(MusicStreamBuffer.StreamSession session) {
        long bps = MusicAudioPlayer.getLastBytesPerSecond();
        if (bps > 0 && session.totalBytes() > 0) {
            return (session.totalBytes() * 10L * 1000L) / bps;
        }
        return 0;
    }

    private static String truncate(Minecraft mc, String text, int maxPx) {
        if (mc.font.width(text) <= maxPx) return text;
        while (text.length() > 1 && mc.font.width(text + "...") > maxPx) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }

    private static String formatMs(long ms) {
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return String.format("%02d:%02d", m, s);
    }

    private static int colorWithAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
}
