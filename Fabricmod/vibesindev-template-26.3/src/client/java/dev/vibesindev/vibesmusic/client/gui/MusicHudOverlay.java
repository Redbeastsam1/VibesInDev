package dev.vibesindev.vibesmusic.client.gui;

import dev.vibesindev.vibesmusic.client.audio.MusicAudioPlayer;
import dev.vibesindev.vibesmusic.client.audio.MusicStreamBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.Identifier;

/**
 * A compact, always-on-screen HUD overlay that appears while music is playing.
 *
 * Layout (bottom-left, 2px padding from edge):
 *
 *   ┌──────────────────────────────────┐
 *   │  [HEAD]  Title - Artist          │
 *   │          ────────────────────    │
 *   │          [◀◀] [⏸] [▶▶]  🔊    │
 *   └──────────────────────────────────┘
 *
 * The player head is fetched from the local Minecraft session profile skin,
 * so it always shows the current logged-in player's face.
 */
public final class MusicHudOverlay {

    // Panel geometry
    private static final int PANEL_W    = 220;
    private static final int PANEL_H    = 54;
    private static final int PADDING    = 4;
    private static final int HEAD_SIZE  = 24;
    private static final int MARGIN     = 8;   // gap between head and text

    // Progress bar
    private static final int BAR_H      = 3;

    // Fade animation
    private static final int FADE_IN_TICKS  = 10;
    private static final int FADE_OUT_TICKS = 20;

    // How long (ms) the HUD stays fully visible after the last track change
    private static final long VISIBLE_MS = Long.MAX_VALUE; // always visible while playing

    // State
    private static float opacity    = 0f;
    private static boolean visible  = false;
    private static int fadeTick     = 0;
    private static boolean fadingIn = false;

    private MusicHudOverlay() {}

    // =========================================================
    // PUBLIC API
    // =========================================================

    public static void tick() {
        boolean shouldShow = MusicAudioPlayer.isPlaying() && !MusicAudioPlayer.isPaused()
                || MusicAudioPlayer.isPaused();

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

    public static void render(GuiGraphics gfx, float partialTick) {
        if (!visible || opacity <= 0f) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }

        MusicStreamBuffer.StreamSession session = MusicStreamBuffer.current();
        if (session == null) {
            return;
        }

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int panelX = PADDING;
        int panelY = screenH - PANEL_H - PADDING;

        int alpha = (int) (opacity * 200);

        // --- Background panel ---
        int bg = colorWithAlpha(0x0D0D0D, alpha);
        int border = colorWithAlpha(0x6644DDFF, alpha);

        gfx.fill(panelX,     panelY,      panelX + PANEL_W,     panelY + PANEL_H,     bg);
        // left accent bar
        gfx.fill(panelX,     panelY,      panelX + 2,           panelY + PANEL_H,     border);

        // --- Player Head ---
        int headX = panelX + 6;
        int headY = panelY + (PANEL_H - HEAD_SIZE) / 2;
        renderPlayerHead(gfx, mc, headX, headY, HEAD_SIZE, alpha);

        // --- Text area ---
        int textX = headX + HEAD_SIZE + MARGIN;
        int textY = panelY + 8;

        String title = session.title();
        String artist = session.artist();

        // Title (white, slightly larger via shadow)
        int titleColor = colorWithAlpha(0xFFFFFF, alpha);
        int artistColor = colorWithAlpha(0xAAAAAA, alpha);

        gfx.drawString(mc.font, truncate(mc, title, PANEL_W - textX + panelX - 8), textX, textY, titleColor, true);
        gfx.drawString(mc.font, truncate(mc, artist, PANEL_W - textX + panelX - 8), textX, textY + 10, artistColor, false);

        // --- Progress bar ---
        int barX = textX;
        int barY = textY + 22;
        int barW = PANEL_W - (textX - panelX) - 8;

        long totalMs = session.totalBytes() > 0
                ? estimateDurationMs(session)
                : 0;
        long posMs = MusicAudioPlayer.getPositionMs();
        float progress = (totalMs > 0) ? Math.min(1f, (float) posMs / totalMs) : 0f;

        int barBg  = colorWithAlpha(0x333333, alpha);
        int barFg  = colorWithAlpha(0x8855FF, alpha);

        gfx.fill(barX, barY, barX + barW, barY + BAR_H, barBg);
        if (progress > 0f) {
            gfx.fill(barX, barY, barX + (int)(barW * progress), barY + BAR_H, barFg);
        }

        // --- State badge ---
        if (MusicAudioPlayer.isPaused()) {
            int badgeColor = colorWithAlpha(0xFFAA00, alpha);
            gfx.drawString(mc.font, "⏸ Paused", textX, textY + 30, badgeColor, false);
        } else {
            // time display
            String timeStr = formatMs(posMs) + " / " + (totalMs > 0 ? formatMs(totalMs) : "--:--");
            int timeColor = colorWithAlpha(0x888888, alpha);
            gfx.drawString(mc.font, timeStr, textX, textY + 30, timeColor, false);
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private static void renderPlayerHead(
            GuiGraphics gfx,
            Minecraft mc,
            int x, int y, int size, int alpha
    ) {
        if (mc.player == null) return;

        // Use the local player's skin texture
        Identifier skin = mc.player.getSkin().texture();

        // Faces are in the top-left 8×8 region of the 64×64 skin atlas
        // u0=8, v0=8, u1=16, v1=16 → UV region of the head face
        int a = Math.min(255, alpha + 55);
        gfx.blit(
                RenderType::guiTextured,
                skin,
                x, y, size, size,
                8, 8, 8, 8,  // srcX, srcY, srcW, srcH
                64, 64        // texW, texH
        );

        // Overlay layer (hat): UV region 40,8 → 48,16
        gfx.blit(
                RenderType::guiTextured,
                skin,
                x, y, size, size,
                40, 8, 8, 8,
                64, 64
        );
    }

    private static long estimateDurationMs(MusicStreamBuffer.StreamSession session) {
        // We don't store duration in StreamSession directly.
        // Fall back to bytes-based estimate using MusicAudioPlayer's PCM rate if available.
        long bps = MusicAudioPlayer.getLastBytesPerSecond();
        if (bps > 0 && session.totalBytes() > 0) {
            // totalBytes is OGG bytes; rough 1:10 ratio to PCM
            return (session.totalBytes() * 10L * 1000L) / bps;
        }
        return 0;
    }

    private static String truncate(Minecraft mc, String text, int maxPx) {
        if (mc.font.width(text) <= maxPx) return text;
        while (text.length() > 1 && mc.font.width(text + "…") > maxPx) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
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
