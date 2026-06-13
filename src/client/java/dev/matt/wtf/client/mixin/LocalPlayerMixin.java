package dev.matt.wtf.client.mixin;

import dev.matt.wtf.client.WTFClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
    @Inject(method = "drop(Z)Z", at = @At("HEAD"))
    private void wtf$preDrop(boolean dropAll, CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        WTFClient.INSTANCE.onLocalDrop(self.getInventory().getSelectedItem());
    }
}
