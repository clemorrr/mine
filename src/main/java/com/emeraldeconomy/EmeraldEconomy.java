package com.emeraldeconomy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * «Изумрудная власть» — изумруды как официальная валюта.
 */
public final class EmeraldEconomy implements ModInitializer {
    public static final String MOD_ID = "emeraldeconomy";
    public static final Logger LOG = LoggerFactory.getLogger("EmeraldEconomy");

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(Ticker::tick);
        UseEntityCallback.EVENT.register(Interactions::onUseEntity);
        AttackEntityCallback.EVENT.register(Interactions::onAttackEntity);
        UseBlockCallback.EVENT.register(Interactions::onUseBlock);
        PlayerBlockBreakEvents.BEFORE.register(Interactions::onBreakBlock);
        ServerLivingEntityEvents.AFTER_DEATH.register(Events::onDeath);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(Events::onDamage);
        CommandRegistrationCallback.EVENT.register(Commands::register);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            Villages.clearBars();
            Behavior.clear();
            EcoState.reset();
        });
        LOG.info("Изумрудная власть загружена");
    }
}
