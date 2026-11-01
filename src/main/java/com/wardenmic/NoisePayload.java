package com.wardenmic;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Nivel de ruido del micrófono (0.0 a 1.0) enviado del cliente al servidor. */
public record NoisePayload(float level) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<NoisePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("wardenmic", "noise"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NoisePayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.FLOAT, NoisePayload::level, NoisePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
