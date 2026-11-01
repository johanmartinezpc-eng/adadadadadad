package com.wardenmic.client;

import com.wardenmic.AlertPayload;
import com.wardenmic.NoisePayload;
import com.wardenmic.WardenMic;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public class WardenMicClient implements ClientModInitializer {

    private static final int HISTORY = 80;
    private static final float[] history = new float[HISTORY];
    private static int tickCounter = 0;

    // Estado de la barra de alerta (lo actualiza el servidor)
    private static volatile float alert = 0f;
    private static volatile boolean chasing = false;

    // Colores ARGB
    private static final int PANEL_BG   = 0xAA000000; // negro transparente
    private static final int WAVE_COLOR = 0xFF55CCFF; // azul celeste
    private static final int LINE_COLOR = 0x5555CCFF; // línea central tenue
    private static final int BAR_WARN   = 0xFFFFAA00; // barra normal (ámbar)
    private static final int BAR_CHASE  = 0xFFFF3333; // barra en persecución (rojo)
    private static final int MARKER     = 0xFFFFFFFF; // raya del 8/10

    private static final MicCapture MIC = new MicCapture();

    @Override
    public void onInitializeClient() {
        MIC.start();

        ClientPlayNetworking.registerGlobalReceiver(AlertPayload.TYPE, (payload, context) -> {
            alert = payload.alert();
            chasing = payload.chasing();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            float level = MIC.getLevel();

            System.arraycopy(history, 1, history, 0, HISTORY - 1);
            history[HISTORY - 1] = level;

            tickCounter++;
            if (client.player != null && tickCounter % 2 == 0
                    && level >= WardenMic.THRESHOLD
                    && ClientPlayNetworking.canSend(NoisePayload.TYPE)) {
                ClientPlayNetworking.send(new NoisePayload(level));
            }
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("wardenmic", "mic_wave"),
                (GuiGraphicsExtractor g, DeltaTracker delta) -> {
            var mc = net.minecraft.client.Minecraft.getInstance();

            // ---------- Panel de ondas (abajo a la izquierda) ----------
            int w = HISTORY * 2;
            int h = 40;
            int x = 8;
            int y = mc.getWindow().getGuiScaledHeight() - h - 30;

            g.fill(x - 4, y - 4, x + w + 4, y + h + 4, PANEL_BG);
            int cy = y + h / 2;
            g.fill(x, cy, x + w, cy + 1, LINE_COLOR);
            for (int i = 0; i < HISTORY; i++) {
                int half = Math.max(1, Math.round(history[i] * (h / 2f)));
                int bx = x + i * 2;
                g.fill(bx, cy - half, bx + 1, cy + half, WAVE_COLOR);
            }

            // ---------- Barra de alerta (arriba al centro) ----------
            float a = Math.max(0f, Math.min(1f, alert));
            if (a > 0.005f || chasing) {
                int bw = 182;
                int bh = 7;
                int barX = (mc.getWindow().getGuiScaledWidth() - bw) / 2;
                int barY = 14;

                g.fill(barX - 2, barY - 2, barX + bw + 2, barY + bh + 2, PANEL_BG);
                int fillW = Math.round(bw * a);
                if (fillW > 0) {
                    g.fill(barX, barY, barX + fillW, barY + bh, chasing ? BAR_CHASE : BAR_WARN);
                }
                // Raya indicadora en el 8/10
                int mx = barX + Math.round(bw * WardenMic.CHASE_START);
                g.fill(mx - 1, barY - 4, mx + 1, barY + bh + 4, MARKER);
            }
        });
    }
}
