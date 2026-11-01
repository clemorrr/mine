package com.emeraldeconomy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/**
 * Главный таймер мода.
 */
public final class Ticker {
    private Ticker() {
    }

    public static void tick(MinecraftServer server) {
        ServerWorld w = server.getOverworld();
        if (w == null) return;
        EcoState s = EcoState.get(server);
        long t = w.getTime();
        long day = w.getTimeOfDay() / 24000L;
        try {
            if (t % 10 == 0) Behavior.lookInfo(w, s);
            if (t % 20 == 0) {
                if (s.lastDay < 0 || day < s.lastDay) s.lastDay = day;
                if (day > s.lastDay) {
                    long n = Math.min(3, day - s.lastDay);
                    for (long k = n; k >= 1; k--) Economy.daily(w, s, day - k + 1);
                    s.lastDay = day;
                }
                Hires.tick(w, s, t);
                Behavior.second(w, s, t);
                Villages.updateBars(w, s, day);
                Villages.elections(w, s, day);
            }
            if (t % 100 == 0) {
                Villages.detect(w, s, day);
                Villages.maintain(w, s, day);
                Behavior.gossip(w, s);
            }
        } catch (RuntimeException e) {
            if (t % 200 == 0) EmeraldEconomy.LOG.error("Ошибка в тике мода", e);
        }
    }
}
