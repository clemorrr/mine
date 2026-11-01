package com.emeraldeconomy;

import com.emeraldeconomy.EcoState.Hire;
import com.emeraldeconomy.EcoState.PInfo;
import com.emeraldeconomy.EcoState.VInfo;
import com.emeraldeconomy.EcoState.Village;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Охранники, наёмные убийцы, «иди за мной» и коллекторы банка.
 */
public final class Hires {
    private Hires() {
    }

    public static final long DAY = 24000L;

    public static Hire byEntity(EcoState s, UUID id) {
        for (Hire h : s.hires) if (h.entity != null && h.entity.equals(id)) return h;
        return null;
    }

    public static List<Hire> byOwner(EcoState s, UUID owner, int mode) {
        List<Hire> l = new ArrayList<>();
        for (Hire h : s.hires) if (owner.equals(h.owner) && (mode < 0 || h.mode == mode)) l.add(h);
        return l;
    }

    public static String modeName(int mode) {
        return switch (mode) {
            case Hire.GUARD -> "охранник";
            case Hire.HIT -> "исполнитель заказа";
            case Hire.FOLLOW -> "идёт за вами";
            default -> "коллектор";
        };
    }

    // -------------------------------------------------------------- найм

    public static void hire(ServerPlayerEntity p, EcoState s, MobEntity mob, int mode) {
        ServerWorld w = p.getServerWorld();
        Hire existing = byEntity(s, mob.getUuid());
        if (existing != null) {
            Ui.bad(p, existing.owner.equals(p.getUuid()) ? "Он уже работает на вас." : "Он уже нанят игроком " + existing.ownerName + ".");
            return;
        }
        Village v = Villages.at(s, mob.getBlockPos());
        if (mob instanceof VillagerEntity ve && v != null && ve.getUuid().equals(v.mayorVillager)) {
            Ui.bad(p, "Мэр не нанимается в охрану!");
            return;
        }
        if (mode == Hire.HIT && !byOwner(s, p.getUuid(), Hire.HIT).stream().filter(h -> h.target == null).toList().isEmpty()) {
            Ui.bad(p, "Сначала отметьте цель для уже нанятого исполнителя.");
            return;
        }
        int cost = mode == Hire.FOLLOW ? 0 : Money.HIRE_COST;
        if (!Money.take(p, cost)) {
            Ui.bad(p, "Нужно " + Money.em(cost) + ". У вас " + Money.em(Money.count(p)) + ".");
            return;
        }
        if (cost > 0) {
            if (mob instanceof VillagerEntity ve) Villages.info(s, ve).wallet += cost;
            else if (v != null) v.treasury = Math.min(Economy.TREASURY_CAP, v.treasury + cost);
        }
        Hire h = new Hire();
        h.entity = mob.getUuid();
        h.owner = p.getUuid();
        h.ownerName = p.getGameProfile().getName();
        h.mode = mode;
        h.villager = mob instanceof VillagerEntity;
        long now = w.getTime();
        h.expires = now + (mode == Hire.GUARD ? 3 * DAY : mode == Hire.HIT ? 2 * DAY : DAY);
        s.hires.add(h);
        mob.setPersistent();
        if (mob instanceof IronGolemEntity g) {
            g.stopAnger();
            g.setTarget(null);
        }
        if (h.villager && mode != Hire.FOLLOW) {
            mob.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            mob.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
        }
        String who = mob instanceof VillagerEntity ve ? Villages.title(s, ve) : "Голем";
        switch (mode) {
            case Hire.GUARD -> Ui.ok(p, who + " теперь ваш охранник на 3 дня.");
            case Hire.FOLLOW -> Ui.ok(p, who + " идёт за вами.");
            case Hire.HIT -> {
                PInfo pi = s.player(p.getUuid());
                pi.pendingHit = mob.getUuid();
                pi.pendingHitUntil = now + 1200;
                Ui.ok(p, who + " взялся за заказ. Ударьте цель в течение минуты или напишите /заказ <ник игрока>.");
            }
            default -> {
            }
        }
    }

    /** Отметить цель заказа. Возвращает true, если цель принята. */
    public static boolean markTarget(ServerPlayerEntity p, EcoState s, LivingEntity target) {
        PInfo pi = s.player(p.getUuid());
        ServerWorld w = p.getServerWorld();
        if (pi.pendingHit == null || w.getTime() > pi.pendingHitUntil) return false;
        Hire h = byEntity(s, pi.pendingHit);
        if (h == null || h.mode != Hire.HIT) {
            pi.pendingHit = null;
            return false;
        }
        if (target == p || target.getUuid().equals(h.entity)) {
            Ui.bad(p, "Эту цель выбрать нельзя.");
            return true;
        }
        Village v = Villages.at(s, target.getBlockPos());
        boolean mayor = v != null && target.getUuid().equals(v.mayorVillager);
        if (target instanceof ServerPlayerEntity tp) {
            for (Village vv : s.villages) if (tp.getUuid().equals(vv.mayorPlayer)) mayor = true;
        }
        if (mayor) {
            if (!Money.take(p, Money.MAYOR_HIT_EXTRA)) {
                Ui.bad(p, "Убийство мэра стоит ещё " + Money.em(Money.MAYOR_HIT_EXTRA) + " сверху. Не хватает денег.");
                return true;
            }
            Ui.msg(p, "За мэра взято ещё " + Money.em(Money.MAYOR_HIT_EXTRA) + ".");
        }
        h.target = target.getUuid();
        h.targetName = target.getName().getString();
        pi.pendingHit = null;
        Ui.ok(p, "Цель отмечена: " + h.targetName + ". Исполнитель выдвинулся.");
        return true;
    }

    public static void end(EcoState s, Hire h, ServerWorld w, String why) {
        s.hires.remove(h);
        Entity e = w.getEntity(h.entity);
        if (e instanceof IronGolemEntity g) {
            g.setTarget(null);
            g.stopAnger();
            if (h.mode == Hire.COLLECTOR) g.discard();
        }
        if (e instanceof VillagerEntity ve) {
            ve.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            ve.getBrain().forget(MemoryModuleType.WALK_TARGET);
        }
        ServerPlayerEntity owner = w.getServer().getPlayerManager().getPlayer(h.owner);
        if (owner != null && why != null) Ui.msg(owner, why);
    }

    // -------------------------------------------------------------- тик

    public static void tick(ServerWorld w, EcoState s, long now) {
        MinecraftServer server = w.getServer();
        Iterator<Hire> it = new ArrayList<>(s.hires).iterator();
        while (it.hasNext()) {
            Hire h = it.next();
            Entity e = w.getEntity(h.entity);
            if (e == null) {
                if (now > h.expires + DAY) s.hires.remove(h);
                continue;
            }
            if (!e.isAlive() || !(e instanceof MobEntity mob)) {
                s.hires.remove(h);
                continue;
            }
            if (now > h.expires) {
                String why = switch (h.mode) {
                    case Hire.GUARD -> "Контракт охранника закончился.";
                    case Hire.HIT -> "Исполнитель не справился с заказом за 2 дня. Деньги не возвращаются.";
                    case Hire.FOLLOW -> null;
                    default -> null;
                };
                end(s, h, w, why);
                continue;
            }
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(h.owner);
            switch (h.mode) {
                case Hire.GUARD, Hire.FOLLOW -> guard(w, s, h, mob, owner);
                case Hire.HIT -> hunt(w, h, mob, find(w, server, h.target));
                case Hire.COLLECTOR -> {
                    if (owner == null || s.player(h.owner).loan <= 0) {
                        end(s, h, w, null);
                        mob.discard();
                    } else hunt(w, h, mob, owner);
                }
                default -> {
                }
            }
        }
    }

    private static LivingEntity find(ServerWorld w, MinecraftServer server, UUID id) {
        if (id == null) return null;
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
        if (p != null) return p.getServerWorld() == w && !p.isSpectator() ? p : null;
        Entity e = w.getEntity(id);
        return e instanceof LivingEntity le && le.isAlive() ? le : null;
    }

    private static void guard(ServerWorld w, EcoState s, Hire h, MobEntity mob, ServerPlayerEntity owner) {
        if (owner == null || owner.getServerWorld() != w) return;
        double d = mob.squaredDistanceTo(owner);
        if (d > 48 * 48) {
            mob.requestTeleport(owner.getX(), owner.getY(), owner.getZ());
            return;
        }
        LivingEntity threat = null;
        if (h.mode == Hire.GUARD) {
            if (h.threat != null) {
                Entity t = w.getEntity(h.threat);
                if (t instanceof LivingEntity le && le.isAlive() && le.squaredDistanceTo(owner) < 24 * 24) threat = le;
                else h.threat = null;
            }
            if (threat == null) {
                double best = 12 * 12;
                for (HostileEntity m : w.getEntitiesByClass(HostileEntity.class, owner.getBoundingBox().expand(12), Entity::isAlive)) {
                    double dd = m.squaredDistanceTo(owner);
                    if (dd < best) {
                        best = dd;
                        threat = m;
                    }
                }
            }
        }
        if (mob instanceof IronGolemEntity g) {
            if (g.getTarget() == owner) {
                g.setTarget(null);
                g.stopAnger();
            }
            if (threat != null && g.getTarget() == null) g.setTarget(threat);
            if (g.getTarget() == null && d > 36) g.getNavigation().startMovingTo(owner, 1.0);
        } else if (mob instanceof VillagerEntity ve) {
            if (threat != null) strike(w, ve, threat);
            else if (d > 9) walk(ve, owner, 0.65f, 3);
        }
    }

    private static void hunt(ServerWorld w, Hire h, MobEntity mob, LivingEntity target) {
        if (target == null) return;
        if (mob instanceof IronGolemEntity g) {
            if (target instanceof ServerPlayerEntity tp) {
                g.setAngryAt(tp.getUuid());
                g.setAngerTime(600);
            }
            g.setTarget(target);
            if (g.squaredDistanceTo(target) > 20 * 20) g.getNavigation().startMovingTo(target, 1.0);
        } else if (mob instanceof VillagerEntity ve) {
            strike(w, ve, target);
        }
    }

    /** Житель с мечом подходит и бьёт. */
    private static void strike(ServerWorld w, VillagerEntity ve, LivingEntity target) {
        if (ve.squaredDistanceTo(target) < 2.6 * 2.6) {
            ve.swingHand(Hand.MAIN_HAND);
            target.damage(w.getDamageSources().mobAttack(ve), 5f);
        } else {
            walk(ve, target, 0.75f, 1);
        }
    }

    public static void walk(VillagerEntity ve, Entity to, float speed, int range) {
        ve.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(to, speed, range));
        ve.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(to, true));
    }

    // -------------------------------------------------------- события

    public static void ownerHurt(EcoState s, ServerPlayerEntity owner, LivingEntity attacker) {
        ServerWorld w = owner.getServerWorld();
        for (Hire h : byOwner(s, owner.getUuid(), Hire.GUARD)) {
            if (attacker.getUuid().equals(h.entity)) continue;
            if (byEntity(s, attacker.getUuid()) != null && owner.getUuid().equals(byEntity(s, attacker.getUuid()).owner)) continue;
            h.threat = attacker.getUuid();
            Entity e = w.getEntity(h.entity);
            if (e instanceof IronGolemEntity g) g.setTarget(attacker);
        }
    }

    public static void ownerAttacked(EcoState s, ServerPlayerEntity owner, LivingEntity victim) {
        if (victim instanceof VillagerEntity) return;
        Hire vh = byEntity(s, victim.getUuid());
        if (vh != null && owner.getUuid().equals(vh.owner)) return;
        ServerWorld w = owner.getServerWorld();
        for (Hire h : byOwner(s, owner.getUuid(), Hire.GUARD)) {
            h.threat = victim.getUuid();
            Entity e = w.getEntity(h.entity);
            if (e instanceof IronGolemEntity g) g.setTarget(victim);
        }
    }

    /** Коллектор банка за просроченный кредит. */
    public static void sendCollector(ServerWorld w, EcoState s, ServerPlayerEntity p) {
        PInfo pi = s.player(p.getUuid());
        if (pi.collector != null && w.getEntity(pi.collector) instanceof IronGolemEntity old && old.isAlive()) return;
        IronGolemEntity g = EntityType.IRON_GOLEM.create(w);
        if (g == null) return;
        double a = w.getRandom().nextDouble() * Math.PI * 2;
        int x = (int) Math.floor(p.getX() + Math.cos(a) * 6), z = (int) Math.floor(p.getZ() + Math.sin(a) * 6);
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(y - p.getY()) > 8) {
            x = p.getBlockX();
            y = p.getBlockY();
            z = p.getBlockZ();
        }
        g.refreshPositionAndAngles(x + 0.5, y, z + 0.5, 0f, 0f);
        g.setCustomName(Ui.t("Коллектор банка", Formatting.DARK_RED));
        g.setCustomNameVisible(true);
        g.setPersistent();
        w.spawnEntity(g);
        Hire h = new Hire();
        h.entity = g.getUuid();
        h.owner = p.getUuid();
        h.ownerName = p.getGameProfile().getName();
        h.mode = Hire.COLLECTOR;
        h.target = p.getUuid();
        h.targetName = h.ownerName;
        h.expires = w.getTime() + 30 * DAY;
        s.hires.add(h);
        pi.collector = g.getUuid();
        Ui.bad(p, "Вы не вернули кредит! За вами выехал коллектор. Погасите долг: /банк вернуть");
    }

    public static void removeCollector(ServerWorld w, EcoState s, ServerPlayerEntity p) {
        PInfo pi = s.player(p.getUuid());
        if (pi.collector == null) return;
        Hire h = byEntity(s, pi.collector);
        if (h != null) s.hires.remove(h);
        Entity e = w.getEntity(pi.collector);
        if (e != null) e.discard();
        pi.collector = null;
    }
}
