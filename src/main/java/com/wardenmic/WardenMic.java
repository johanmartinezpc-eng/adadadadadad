package com.wardenmic;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
    /** Valor máximo de la barra: 1.0 = primera barra llena, 2.0 = barra oscura llena. */
    public static final float MAX_ALERT = 2.0f;
    /** Al llegar a esta marca (8/10) el Warden empieza a perseguirte. */
    public static final float CHASE_START = 0.8f;

    /** Subida por paquete (10/s) con ruido normal: base + intensidad * extra. */
    public static final float GAIN_BASE = 0.02f;
    public static final float GAIN_LOUD = 0.08f;

    /** Nivel (0-1) del micrófono que cuenta como GRITO. Bájalo si tu micrófono es poco sensible. */
    public static final float SHOUT_LEVEL = 0.75f;
    /** Un grito llena la primera barra de golpe. Si ya está llena, cada grito suma esto a la barra oscura. */
    public static final float SHOUT_OVERFILL = 0.25f;
    /** Ticks entre gritos que cuentan como gritos distintos (20 = 1 s). */
    public static final int SHOUT_COOLDOWN = 20;

    /** Segundos que tarda en vaciarse UNA barra completa si no lo molestas. */
    public static final float EMPTY_SECONDS = 30f;
    public static final float DECAY_PER_TICK = 1f / (EMPTY_SECONDS * 20f);
    /** Ticks sin hacer ruido antes de que la barra empiece a bajar. */
    public static final int DECAY_DELAY = 20;

    /** Velocidad extra máxima del Warden con la barra oscura llena (1.0 = +100%). */
    public static final double SPEED_BOOST_MAX = 1.0;

    /** Alcance (bloques) para persecución. */
    public static final double CHASE_RANGE = 64.0;

    /**
     * En el juego normal el Warden se entierra si pasan ~60 s sin perturbaciones.
     * Como este mod le quita vibraciones, olfato, etc., se enterraría siempre.
     * Con true, se renueva ese temporizador y el Warden se queda donde está.
     */
    public static final boolean WARDENS_NEVER_DIG = true;
    public static final double KEEP_AWAKE_RANGE = 128.0;

    private static final Identifier SPEED_ID = Identifier.fromNamespaceAndPath("wardenmic", "chase_speed");

    private static class State {
        float alert = 0f;
        boolean chasing = false;
        int ticksSinceHeard = 1000;
        int shoutCooldown = 0;
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

            State s = STATES.computeIfAbsent(player.getUUID(), k -> new State());

            boolean heard = false;
            for (Warden warden : wardens) {
                if (warden.distanceTo(player) > radius) continue;
                heard = true;
                if (!s.chasing) {
                    // El Warden se orienta hacia donde hiciste ruido
                    WardenAi.setDisturbanceLocation(warden, BlockPos.containing(player.position()));
                }
            }

            if (heard) {
                float t = (level - THRESHOLD) / (1f - THRESHOLD);
                float gain = GAIN_BASE + t * GAIN_LOUD;

                if (level >= SHOUT_LEVEL) {
                    if (s.shoutCooldown <= 0) {
                        s.shoutCooldown = SHOUT_COOLDOWN;
                        if (s.alert < 1f) {
                            s.alert = 1f;   // un grito llena la primera barra completa
                            gain = 0f;
                        } else {
                            gain = SHOUT_OVERFILL;
                        }
                    } else {
                        gain = 0f;          // mismo grito, no cuenta doble
                    }
                }

                s.alert = Math.min(MAX_ALERT, s.alert + gain);
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
            if (s.shoutCooldown > 0) s.shoutCooldown--;

            // Si no lo molestas, la barra se vacía sola
            if (s.ticksSinceHeard > DECAY_DELAY) {
                s.alert = Math.max(0f, s.alert - DECAY_PER_TICK);
            }

            if (!s.chasing && s.alert >= CHASE_START) {
                s.chasing = true;
            }
            // El Warden se calma cuando la barra se vacía por completo
            if (s.chasing && s.alert <= 0.001f) {
                endChase(player, s);
            }

            // Durante la persecución: enojo constante y velocidad según la barra oscura
            if (s.chasing && serverTicks % 10 == 0) {
                double overfill = Math.max(0f, Math.min(1f, s.alert - 1f));
                ServerLevel world = (ServerLevel) player.level();
                for (Warden warden : world.getEntitiesOfClass(
                        Warden.class, player.getBoundingBox().inflate(CHASE_RANGE))) {
                    warden.increaseAngerAt(player, 40, false);
                    setSpeedBoost(warden, overfill * SPEED_BOOST_MAX);
                }
            }

            // Evita que el Warden se entierre y desaparezca
            if (WARDENS_NEVER_DIG && serverTicks % 20 == 0) {
                ServerLevel world = (ServerLevel) player.level();
                for (Warden warden : world.getEntitiesOfClass(
                        Warden.class, player.getBoundingBox().inflate(KEEP_AWAKE_RANGE))) {
                    WardenAi.setDigCooldown(warden);
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

    private static void setSpeedBoost(Warden warden, double amount) {
        AttributeInstance inst = warden.getAttribute(Attributes.MOVEMENT_SPEED);
        if (inst == null) return;
        if (amount <= 0) {
            inst.removeModifier(SPEED_ID);
        } else {
            inst.addOrUpdateTransientModifier(new AttributeModifier(
                    SPEED_ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    private void endChase(ServerPlayer player, State s) {
        s.chasing = false;
        ServerLevel world = (ServerLevel) player.level();
        for (Warden warden : world.getEntitiesOfClass(
                Warden.class, player.getBoundingBox().inflate(CHASE_RANGE + 32))) {
            warden.getAngerManagement().clearAnger(player);
            setSpeedBoost(warden, 0);
        }
    }
}
