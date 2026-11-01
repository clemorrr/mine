package com.emeraldeconomy;

import com.emeraldeconomy.EcoState.House;
import com.emeraldeconomy.EcoState.PInfo;
import com.emeraldeconomy.EcoState.VInfo;
import com.emeraldeconomy.EcoState.Village;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.village.TradeOffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ежедневная экономика: зарплаты, налоги, еда, вклады, кредиты, аренда.
 */
public final class Economy {
    private Economy() {
    }

    public static final int TREASURY_CAP = 200;
    public static final int WALLET_CAP = 10;

    /** Уровень дома мэра по казне (с запасом, чтобы дом не перестраивался каждый день). */
    public static int houseTier(Village v) {
        int t = v.treasury;
        int cur = v.houseTier;
        if (cur < 0) return t >= 130 ? 2 : (t >= 60 ? 1 : 0);
        int up = cur;
        if (cur < 2 && t >= (cur == 0 ? 60 : 130)) up = cur + 1;
        if (cur > 0 && t < (cur == 2 ? 110 : 40)) up = cur - 1;
        return up;
    }

    public static void daily(ServerWorld w, EcoState s, long day) {
        MinecraftServer server = w.getServer();
        for (Village v : s.villages) {
            if (!Villages.loaded(w, v)) continue;
            List<VillagerEntity> list = Villages.villagers(w, v);
            List<VInfo> food = new ArrayList<>();
            int taxSum = 0, salarySum = 0, eaten = 0, hungry = 0;
            VInfo mayorInfo = null;
            for (VillagerEntity e : list) {
                if (e.isBaby()) continue;
                VInfo i = Villages.info(s, e);
                boolean mayor = e.getUuid().equals(v.mayorVillager);
                if (mayor) {
                    mayorInfo = i;
                    continue;
                }
                if (Names.foodMaker(e)) food.add(i);
                if (Names.employed(e)) {
                    int pay = Math.min(v.salary, v.treasury);
                    v.treasury -= pay;
                    i.wallet += pay;
                    salarySum += pay;
                }
                if (i.wallet > 0 && v.tax > 0) {
                    int tx = Math.min(i.wallet, Math.max(1, Math.round(i.wallet * v.tax / 100f)));
                    i.wallet -= tx;
                    v.treasury += tx;
                    taxSum += tx;
                }
                if (i.wallet > 0) {
                    i.wallet--;
                    i.hungry = 0;
                    eaten++;
                } else {
                    i.hungry++;
                    hungry++;
                }
                if (i.wallet > WALLET_CAP) {
                    v.treasury += i.wallet - WALLET_CAP;
                    i.wallet = WALLET_CAP;
                }
            }
            // деньги за еду получают фермеры, рыбаки и мясники
            for (int k = 0; k < eaten; k++) {
                if (food.isEmpty()) {
                    v.treasury++;
                    continue;
                }
                VInfo f = food.get(w.getRandom().nextInt(food.size()));
                if (f.wallet < WALLET_CAP) f.wallet++;
                else v.treasury++;
            }
            // высокомерный мэр запускает руку в казну
            if (mayorInfo != null && !v.kind && v.treasury > 10) {
                int take = v.treasury / 10;
                v.treasury -= take;
                mayorInfo.wallet += take;
            }
            if (v.treasury > TREASURY_CAP) {
                int extra = v.treasury - TREASURY_CAP;
                v.treasury = TREASURY_CAP;
                if (v.mayorPlayer != null) s.player(v.mayorPlayer).deposit += extra;
                else if (mayorInfo != null) mayorInfo.wallet += extra;
            }
            v.fear.replaceAll((k, val) -> Math.max(0, val - 5));
            v.fear.values().removeIf(x -> x <= 0);
            v.wanted.values().removeIf(t -> t < w.getTime());

            int tier = houseTier(v);
            if (v.house != null && tier != v.houseTier) {
                String how = tier > v.houseTier ? "стал богаче" : "обеднел";
                Builder.buildMayorHouse(w, v, v.house, tier);
                Villages.broadcast(w, v, Ui.t("Дом мэра в «" + v.name + "» " + how + ".", Formatting.YELLOW));
            }
            if (v.vault != null && day - v.robbedDay > 2) Builder.ensureGuards(w, v);

            if (v.mayorPlayer != null) {
                ServerPlayerEntity m = server.getPlayerManager().getPlayer(v.mayorPlayer);
                if (m != null) {
                    Ui.msg(m, "Отчёт мэра «" + v.name + "»: налоги +" + taxSum + ", зарплаты −" + salarySum
                            + ", голодают: " + hungry + ", казна: " + v.treasury + ".");
                }
            }
        }

        // вклады, кредиты, аренда
        for (Map.Entry<UUID, PInfo> en : s.players.entrySet()) {
            PInfo p = en.getValue();
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(en.getKey());
            if (p.deposit >= 50) p.deposit += p.deposit * 2 / 100;
            if (p.loan > 0 && day > p.loanDue) {
                int pay = Math.min(p.deposit, p.loan);
                p.deposit -= pay;
                p.loan -= pay;
                if (pl != null && pay > 0) Ui.bad(pl, "Банк списал с вашего вклада " + Money.em(pay) + " в счёт просроченного кредита.");
                if (p.loan > 0 && pl != null && pl.getServerWorld() == w) Hires.sendCollector(w, s, pl);
            }
            for (House h : p.houses) {
                if (h.tenant == null) continue;
                Entity t = w.getEntity(h.tenant);
                if (!(t instanceof VillagerEntity tv) || !tv.isAlive()) continue;
                VInfo ti = Villages.info(s, tv);
                if (ti.wallet > 0) {
                    ti.wallet--;
                    p.deposit++;
                } else if (pl != null) {
                    Ui.msg(pl, "Ваш жилец " + ti.name + " не смог заплатить за аренду — у него нет денег.");
                }
            }
        }
    }

    /** Торговля с жителем: изумруды игрока идут в кошелёк жителя, и наоборот. */
    public static void onTrade(VillagerEntity e, TradeOffer offer) {
        if (e.getWorld().isClient() || e.getServer() == null) return;
        EcoState s = EcoState.get(e.getServer());
        VInfo i = Villages.info(s, e);
        int paid = emeralds(offer.getAdjustedFirstBuyItem()) + emeralds(offer.getSecondBuyItem());
        int got = emeralds(offer.getSellItem());
        i.wallet += paid;
        if (got > 0) {
            int fromWallet = Math.min(i.wallet, got);
            i.wallet -= fromWallet;
            int rest = got - fromWallet;
            if (rest > 0) {
                Village v = Villages.at(s, e.getBlockPos());
                if (v != null) v.treasury = Math.max(0, v.treasury - rest);
            }
        }
    }

    private static int emeralds(ItemStack st) {
        if (st == null || st.isEmpty()) return 0;
        if (st.isOf(Items.EMERALD)) return st.getCount();
        if (st.isOf(Items.EMERALD_BLOCK)) return st.getCount() * 9;
        return 0;
    }
}
