package dev.matt.wtf.client.mixin;

import dev.matt.wtf.client.WTFClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public class AbstractContainerMenuMixin {
    @Inject(method = "clicked", at = @At("HEAD"))
    private void wtf$preClick(int slotId, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (player instanceof LocalPlayer) {
            WTFClient.INSTANCE.onMenuClickPre((AbstractContainerMenu) (Object) this);
        }
    }

    // slotId and button are forwarded because a SWAP (F, or a number key) names
    // both slots it exchanges - the clicked one and the hotbar index, with 40
    // meaning the offhand. Inferring that from before/after snapshots is guessing
    // about the one gesture that moves two stacks in a single click.
    @Inject(method = "clicked", at = @At("TAIL"))
    private void wtf$postClick(int slotId, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (player instanceof LocalPlayer) {
            WTFClient.INSTANCE.onMenuClickPost(
                (AbstractContainerMenu) (Object) this, slotId, button, input == ContainerInput.SWAP);
        }
    }
}
