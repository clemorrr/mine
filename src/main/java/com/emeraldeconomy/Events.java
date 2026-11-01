package com.emeraldeconomy;

import com.emeraldeconomy.EcoState.Hire;
import com.emeraldeconomy.EcoState.House;
import com.emeraldeconomy.EcoState.PInfo;
import com.emeraldeconomy.EcoState.VInfo;
import com.emeraldeconomy.EcoState.Village;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Смерти и урон: убийство жителей и мэра, выполнение заказов, охрана.
 */
public final class Events {
    private Events() {
    }

    public static boolean onDamage(LivingEntity entity, DamageSource source, float amount) {
        if (entity.getWorld().isClient() || !(entity instanceof ServerPlayerEntity owner)) return true;
        Entity a = source.getAttacker();
        if (a instanceof LivingEntity attacker && attacker != owner) {
            Hires.ownerHurt(EcoState.get(owner.getServer()), owner, attacker);
        }
        return true;
    }

    public static void onDeath(LivingEntity entity, DamageSource source) {
        if (!(entity.getWorld() instanceof ServerWorld w)) return;
        MinecraftServer server = w.getServer();
        EcoState s = EcoState.get(server);

        // кто виноват: сам игрок или нанятый им исполнитель
        Entity attacker = source.getAttacker();
        UUID killer = null;
        String killerName = "";
        if (attacker instanceof ServerPlayerEntity p) {
            killer = p.getUuid();
            killerName = p.getGameProfile().getName();
        } else if (attacker != null) {
            Hire h = Hires.byEntity(s, attacker.getUuid());
            if (h != null && h.mode != Hire.COLLECTOR) {
                killer = h.owner;
                killerName = h.ownerName;
            }
        }

        // выполненные заказы
        for (Hire h : new ArrayList<>(s.hires)) {
            if (h.mode == Hire.HIT && entity.getUuid().equals(h.target)) {
                Hires.end(s, h, w, "Заказ выполнен: " + h.targetName + " мёртв.");
            }
            if (entity.getUuid().equals(h.entity)) {
                String what = h.mode == Hire.GUARD ? "Ваш охранник погиб." : h.mode == Hire.HIT ? "Ваш исполнитель погиб." : null;
                s.hires.remove(h);
                ServerPlayerEntity o = server.getPlayerManager().getPlayer(h.owner);
                if (o != null && what != null) Ui.bad(o, what);
            }
            if (h.mode == Hire.COLLECTOR && entity.getUuid().equals(h.target) && attacker != null && attacker.getUuid().equals(h.entity)) {
                // коллектор «выбил» долг — исчезает до следующего дня
                Hires.end(s, h, w, null);
            }
        }

        if (entity instanceof VillagerEntity ve) villagerDied(w, s, ve, killer, killerName);
        if (entity instanceof IronGolemEntity g) {
            for (Village v : s.villages) v.guards.remove(g.getUuid());
        }
        if (entity instanceof ServerPlayerEntity dead && killer != null && !killer.equals(dead.getUuid())) {
            for (Village v : s.villages) {
                if (dead.getUuid().equals(v.mayorPlayer)) {
                    Villages.setPlayerMayor(w, s, v, killer, killerName);
                    Villages.broadcast(w, v, Ui.t(killerName + " убил мэра " + dead.getGameProfile().getName()
                            + " и захватил власть в «" + v.name + "»!", Formatting.DARK_RED));
                }
            }
        }
    }

    private static void villagerDied(ServerWorld w, EcoState s, VillagerEntity ve, UUID killer, String killerName) {
        VInfo i = s.villagers.remove(ve.getUuid());
        if (i != null && i.landlord != null) {
            for (House h : s.player(i.landlord).houses) if (ve.getUuid().equals(h.tenant)) h.tenant = null;
        }
        for (PInfo pi : s.players.values()) for (House h : pi.houses) if (ve.getUuid().equals(h.tenant)) h.tenant = null;
        Village v = Villages.at(s, ve.getBlockPos());
        if (v == null) return;
        boolean wasMayor = ve.getUuid().equals(v.mayorVillager);

        if (killer == null) {
            if (wasMayor) {
                v.mayorVillager = null;
                Villages.broadcast(w, v, Ui.t("Мэр «" + v.name + "» " + v.mayorName + " погиб. Деревня выберет нового.", Formatting.YELLOW));
            }
            return;
        }

        // кошелёк убитого выпадает
        if (i != null && i.wallet > 0) ve.dropStack(new ItemStack(Items.EMERALD, Math.min(64, i.wallet)));

        v.addFear(killer, wasMayor ? 50 : 25);
        v.addRep(killer, -30);
        if (wasMayor) {
            Villages.setPlayerMayor(w, s, v, killer, killerName);
            Villages.broadcast(w, v, Ui.t(killerName + " убил мэра " + (i != null ? i.name : "") + " и захватил власть в «"
                    + v.name + "»! Жители в страхе.", Formatting.DARK_RED));
            return;
        }
        boolean witnessed = !w.getEntitiesByClass(VillagerEntity.class, ve.getBoundingBox().expand(16), e -> e.isAlive() && e != ve).isEmpty()
                || !w.getEntitiesByClass(IronGolemEntity.class, ve.getBoundingBox().expand(16), Entity::isAlive).isEmpty();
        ServerPlayerEntity kp = w.getServer().getPlayerManager().getPlayer(killer);
        if (witnessed) {
            v.wanted.put(killer, w.getTime() + Hires.DAY);
            if (kp != null) Ui.bad(kp, "Убийство видели! Вы в розыске в «" + v.name + "» на 1 день. Жители теперь вас боятся.");
        } else if (kp != null) {
            Ui.msg(kp, "Никто не видел... но слухи поползут. Жители «" + v.name + "» начинают вас бояться.");
        }
    }
}
