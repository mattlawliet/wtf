package dev.matt.wtf.client.mixin;

import dev.matt.wtf.client.WTFClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void onTakeItemEntity(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        WTFClient.INSTANCE.onTakeItemEntity(packet.getItemId(), packet.getPlayerId());
    }
}
