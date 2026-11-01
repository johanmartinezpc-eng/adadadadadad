package com.wardenmic;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.warden.WardenAi;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WardenMic implements ModInitializer {

    // ---- Micrófono -> radio de audición ----
    /** Por debajo de este nivel (0-1) se considera ruido de fondo. */
    public static final float THRESHOLD = 0.35f;
    public static final double MIN_RADIUS = 2.0;
    public static final double MAX_RADIUS = 30.0;

    // ---- Barra de alerta ----
    /** Al llegar a esta marca (8/10) el Warden empieza a perseguirte. */
    public static final float CHASE_START = 0.8f;
    /** La persecución termina cuando la barra baja hasta este valor. */
    public static final float CHASE_STOP = 0.25f;
    /** Cuánto sube la barra por cada paquete (10/s): base + ruido * extra. */
    public static final float GAIN_BASE = 0.01f;
    public static final float GAIN_LOUD = 0.04f;
    /** Cuánto baja la barra por tick (20/s) cuando estás en silencio. */
    public static final float IDLE_DECAY = 0.004f;
    /** Igual, pero durante la persecución (más lento: hay que huir). */
    public static final float CHASE_DECAY = 0.0025f;
    /** Alcance (bloques) para persecución. */
    public static final double CHASE_RANGE = 64.0;

    private static class State {
        float alert = 0f;
        boolean chasing = false;
        int ticksSinceHeard = 1000;
        float lastSentAlert = -1f;
        boolean lastSentChasing = false;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static int serverTicks = 0;

    /** Lo consultan los mixins: ¿este jugador está siendo perseguido? */
    public static boolean isChasing(UUID id) {
        State s = STATES.get(id);
        return s != null && s.chasing;
    }

    public static double radiusFor(float level) {
        if (level < THRESHOLD) return 0;
        double t = (level - THRESHOLD) / (1.0 - THRESHOLD);
        return MIN_RADIUS + t * (MAX_RADIUS - MIN_RADIUS);
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(NoisePayload.TYPE, NoisePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AlertPayload.TYPE, AlertPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(NoisePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            float level = Math.max(0f, Math.min(1f, payload.level()));
            double radius = radiusFor(level);
            if (radius <= 0) return;

            ServerLevel world = (ServerLevel) player.level();
            List<Warden> wardens = world.getEntitiesOfClass(
                    Warden.class, player.getBoundingBox().inflate(radius));

            boolean heard = false;
            for (Warden warden : wardens) {
                if (warden.distanceTo(player) > radius) continue;
                heard = true;
                State s = STATES.get(player.getUUID());
                if (s == null || !s.chasing) {
                    // El Warden se orienta hacia donde hiciste ruido
                    WardenAi.setDisturbanceLocation(warden, BlockPos.containing(player.position()));
                }
            }

            if (heard) {
                State s = STATES.computeIfAbsent(player.getUUID(), k -> new State());
                float t = (level - THRESHOLD) / (1f - THRESHOLD);
                s.alert = Math.min(1f, s.alert + GAIN_BASE + t * GAIN_LOUD);
                s.ticksSinceHeard = 0;
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(this::serverTick);
    }

    private void serverTick(MinecraftServer server) {
        serverTicks++;
        Set<UUID> online = new HashSet<>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            State s = STATES.computeIfAbsent(player.getUUID(), k -> new State());

            if (!player.isAlive()) {
                if (s.chasing) endChase(player, s);
                s.alert = 0f;
            }

            s.ticksSinceHeard++;

            // La barra baja si llevas más de 1 segundo sin que te oigan
            if (s.ticksSinceHeard > 20) {
                s.alert = Math.max(0f, s.alert - (s.chasing ? CHASE_DECAY : IDLE_DECAY));
            }

            if (!s.chasing && s.alert >= CHASE_START) {
                s.chasing = true;
            }
            if (s.chasing && s.alert <= CHASE_STOP) {
                endChase(player, s);
            }

            // Durante la persecución, los Warden cercanos te tienen como objetivo
            if (s.chasing && serverTicks % 10 == 0) {
                ServerLevel world = (ServerLevel) player.level();
                for (Warden warden : world.getEntitiesOfClass(
                        Warden.class, player.getBoundingBox().inflate(CHASE_RANGE))) {
                    warden.increaseAngerAt(player, 40, false);
                }
            }

            // Sincroniza la barra con el cliente
            if (Math.abs(s.alert - s.lastSentAlert) > 0.001f || s.chasing != s.lastSentChasing) {
                ServerPlayNetworking.send(player, new AlertPayload(s.alert, s.chasing));
                s.lastSentAlert = s.alert;
                s.lastSentChasing = s.chasing;
            }
        }

        STATES.keySet().retainAll(online);
    }

    private void endChase(ServerPlayer player, State s) {
        s.chasing = false;
        ServerLevel world = (ServerLevel) player.level();
        for (Warden warden : world.getEntitiesOfClass(
                Warden.class, player.getBoundingBox().inflate(CHASE_RANGE + 32))) {
            warden.getAngerManagement().clearAnger(player);
        }
    }
}
