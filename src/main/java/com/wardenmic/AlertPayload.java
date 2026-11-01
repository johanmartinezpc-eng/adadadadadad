package com.wardenmic;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Estado de la barra de alerta (0.0 a 1.0) enviado del servidor al cliente. */
public record AlertPayload(float alert, boolean chasing) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AlertPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("wardenmic", "alert"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AlertPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.FLOAT, AlertPayload::alert,
                    ByteBufCodecs.BOOL, AlertPayload::chasing,
                    AlertPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
