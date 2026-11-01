package com.emeraldeconomy.mixin;

import com.emeraldeconomy.Economy;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.village.TradeOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * После каждой сделки изумруды переходят из рук в руки по-настоящему.
 */
@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin {
    @Inject(method = "afterUsing", at = @At("HEAD"))
    private void emeraldeconomy$afterUsing(TradeOffer offer, CallbackInfo ci) {
        Economy.onTrade((VillagerEntity) (Object) this, offer);
    }
}
