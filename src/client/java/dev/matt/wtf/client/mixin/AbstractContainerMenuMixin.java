package dev.matt.wtf.client.mixin;

import dev.matt.wtf.client.WTFClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public class AbstractContainerMenuMixin {
    @Inject(method = "clicked", at = @At("HEAD"))
    private void wtf$preClick(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (player instanceof LocalPlayer) {
            WTFClient.INSTANCE.onMenuClickPre((AbstractContainerMenu) (Object) this);
        }
    }

    @Inject(method = "clicked", at = @At("TAIL"))
    private void wtf$postClick(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (player instanceof LocalPlayer) {
            WTFClient.INSTANCE.onMenuClickPost((AbstractContainerMenu) (Object) this);
        }
    }
}
