package dev.matt.wtf.client.mixin;

import dev.matt.wtf.client.WTFClient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public class BlockItemMixin {
    @Unique
    private BlockPos wtf$placePos;
    @Unique
    private ItemStack wtf$stack;

    @Inject(method = "useOn", at = @At("HEAD"))
    private void onUseOnHead(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack stack = context.getItemInHand();
        String itemPath = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (!itemPath.contains("shulker_box")) {
            wtf$placePos = null;
            wtf$stack = null;
            return;
        }
        wtf$stack = stack.copy();
        BlockPos clickedPos = context.getClickedPos();
        Direction clickedFace = context.getClickedFace();
        BlockState clickedState = context.getLevel().getBlockState(clickedPos);
        if (clickedState.canBeReplaced()) {
            wtf$placePos = clickedPos;
        } else {
            wtf$placePos = clickedPos.relative(clickedFace);
        }
    }

    @Inject(method = "useOn", at = @At("RETURN"))
    private void onUseOnReturn(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = cir.getReturnValue();
        if (!result.consumesAction() || wtf$placePos == null || wtf$stack == null) {
            wtf$placePos = null;
            wtf$stack = null;
            return;
        }
        Player player = context.getPlayer();
        if (player == null) {
            wtf$placePos = null;
            wtf$stack = null;
            return;
        }
        WTFClient.onShulkerPlaced(player, wtf$placePos, context.getHand(), wtf$stack);
        wtf$placePos = null;
        wtf$stack = null;
    }
}
