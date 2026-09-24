package dev.matt.wtf.client.mixin;

import dev.matt.wtf.client.WTFClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Both HashedStack.create calls in a click - the changed slots and the cursor -
// hash the stack as the server holds it, without our client-only stamp. See
// WTFClient.withoutStamp for what went wrong without this.
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @ModifyArg(
        method = "handleContainerInput",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/HashedStack;create(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/network/HashedPatchMap$HashGenerator;)Lnet/minecraft/network/HashedStack;"
        ),
        index = 0
    )
    private ItemStack wtf$hashAsTheServerHoldsIt(ItemStack stack) {
        return WTFClient.INSTANCE.withoutStamp(stack);
    }
}
