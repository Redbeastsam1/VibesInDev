package dev.vibesindev.vibesmusic.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

@SuppressWarnings("null")
public record MusicPayload(byte[] data) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath("vibesindev", "music");

    public static final Type<MusicPayload> TYPE =
            new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MusicPayload> CODEC =
            new StreamCodec<>() {

                @Override
                public MusicPayload decode(RegistryFriendlyByteBuf buf) {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);

                    return new MusicPayload(data);
                }

                @Override
                public void encode(
                        RegistryFriendlyByteBuf buf,
                        MusicPayload payload
                ) {
                    buf.writeBytes(payload.data());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}