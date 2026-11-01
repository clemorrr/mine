package com.emeraldeconomy;

import com.emeraldeconomy.EcoState.Hire;
import com.emeraldeconomy.EcoState.PInfo;
import com.emeraldeconomy.EcoState.VInfo;
import com.emeraldeconomy.EcoState.Village;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.village.VillageGossipType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Поведение жителей рядом с игроком: страх, розыск, просьбы, скидки богатым.
 */
public final class Behavior {
    private Behavior() {
    }

    private record Approach(UUID villager, long until) {
    }

    private static final Map<UUID, Approach> APPROACHES = new HashMap<>();

    public static void clear() {
        APPROACHES.clear();
    }

    private static boolean armed(ServerPlayerEntity p) {
        Item i = p.getMainHandStack().getItem();
        return i instanceof SwordItem || i instanceof AxeItem;
    }

    public static boolean scary(Village v, ServerPlayerEntity p, int tier) {
        return v.fear(p.getUuid()) >= 20 || (tier >= 4 && armed(p));
    }

    // ------------------------------------------------- раз в секунду

    public static void second(ServerWorld w, EcoState s, long now) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator()) continue;
            Village v = Villages.at(s, p.getBlockPos());
            if (v == null) {
                APPROACHES.remove(p.getUuid());
                continue;
            }
            UUID id = p.getUuid();
            int tier = Money.tierOf(p);

            // розыск: големы деревни охотятся на игрока
            if (v.isWanted(id, now)) {
                for (IronGolemEntity g : w.getEntitiesByClass(IronGolemEntity.class, p.getBoundingBox().expand(24), Entity::isAlive)) {
                    Hire h = Hires.byEntity(s, g.getUuid());
                    if (h != null && id.equals(h.owner)) continue;
                    g.setAngryAt(id);
                    g.setAngerTime(400);
                    g.setTarget(p);
                }
                if (now % 100 == 0) {
                    Ui.bar(p, Ui.t("Вы в розыске в деревне «" + v.name + "»! Осталось " + Ui.time(v.wanted.get(id) - now), Formatting.RED));
                }
            }

            boolean scary = scary(v, p, tier);
            if (scary) {
                for (VillagerEntity e : w.getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(7, 3, 7), Entity::isAlive)) {
                    if (Hires.byEntity(s, e.getUuid()) != null) continue;
                    flee(e, p.getPos());
                }
            }
            approach(w, s, v, p, tier, scary, now);
        }
    }

    public static void flee(VillagerEntity e, Vec3d from) {
        Vec3d dir = e.getPos().subtract(from).multiply(1, 0, 1);
        if (dir.lengthSquared() < 0.01) dir = new Vec3d(1, 0, 0);
        Vec3d to = e.getPos().add(dir.normalize().multiply(8));
        e.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(to, 0.75f, 0));
    }

    // ------------------------------------- жители сами подходят к богатым

    private static long delay(int tier, Random r) {
        return switch (tier) {
            case 4 -> 1800 + r.nextInt(1200);
            case 3 -> 3000 + r.nextInt(1800);
            default -> 4800 + r.nextInt(2400);
        };
    }

    private static void approach(ServerWorld w, EcoState s, Village v, ServerPlayerEntity p, int tier, boolean scary, long now) {
        UUID id = p.getUuid();
        Approach ap = APPROACHES.get(id);
        if (ap != null) {
            Entity e = w.getEntity(ap.villager());
            if (!(e instanceof VillagerEntity ve) || !ve.isAlive() || now > ap.until() || scary) {
                APPROACHES.remove(id);
            } else if (ve.squaredDistanceTo(p) < 9) {
                APPROACHES.remove(id);
                speak(w, s, v, ve, p, tier, now);
            } else {
                Hires.walk(ve, p, 0.6f, 2);
            }
            return;
        }
        if (tier < 2 || scary) return;
        PInfo pi = s.player(id);
        if (pi.nextApproach == 0 || pi.nextApproach > now + 20000) {
            pi.nextApproach = now + delay(tier, w.getRandom());
            return;
        }
        if (now < pi.nextApproach) return;
        pi.nextApproach = now + delay(tier, w.getRandom());
        List<VillagerEntity> near = w.getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(20, 6, 20),
                e -> e.isAlive() && !e.isBaby() && !e.isSleeping() && !e.hasCustomer()
                        && Hires.byEntity(s, e.getUuid()) == null && !e.getUuid().equals(v.mayorVillager));
        if (near.isEmpty()) return;
        VillagerEntity ve = near.get(w.getRandom().nextInt(near.size()));
        APPROACHES.put(id, new Approach(ve.getUuid(), now + 600));
    }

    private static void speak(ServerWorld w, EcoState s, Village v, VillagerEntity ve, ServerPlayerEntity p, int tier, long now) {
        Random r = w.getRandom();
        VInfo i = Villages.info(s, ve);
        String who = Villages.title(s, ve);
        String bow = tier >= 4 ? "*низко кланяется* " : (tier >= 3 ? "*кланяется* " : "");
        String sir = tier >= 4 ? "Ваше сиятельство" : "Господин";
        boolean beg = i.wallet <= 1 || i.hungry > 0 || r.nextInt(100) < 40;
        if (beg) {
            int amount = 1 + r.nextInt(tier >= 4 ? 5 : 3);
            i.begPlayer = p.getUuid();
            i.begAmount = amount;
            i.begExpire = now + 1200;
            String u = ve.getUuid().toString();
            MutableText m = Ui.say(who, bow + sir + "! " + Names.beg(r) + " Дадите " + Money.em(amount) + "?")
                    .append(Ui.sp())
                    .append(Ui.button("Дать " + amount, "/emv give " + u + " " + amount, Formatting.GREEN, "Отдать " + Money.em(amount)))
                    .append(Ui.sp())
                    .append(Ui.button("Отказать", "/emv refuse " + u, Formatting.RED, "Прогнать"));
            p.sendMessage(m, false);
        } else {
            Names.Quest q = Names.quest(ve, r);
            int reward = Math.max(1, Math.min(i.wallet, 1 + r.nextInt(3)));
            i.questPlayer = p.getUuid();
            i.questItem = Registries.ITEM.getId(q.item()).toString();
            i.questCount = q.count();
            i.questReward = reward;
            i.questExpire = now + 2 * Hires.DAY;
            MutableText m = Ui.say(who, bow + sir + ", не поможете? Принесите мне " + q.count() + " × ")
                    .append(new ItemStack(q.item()).getName().copy().formatted(Formatting.AQUA))
                    .append(Ui.t(" — заплачу " + Money.em(reward) + ".", Formatting.WHITE))
                    .append(Ui.t("\n(Принесите предметы, присядьте и нажмите ПКМ по жителю.)", Formatting.DARK_GRAY));
            p.sendMessage(m, false);
        }
        ve.getBrain().remember(MemoryModuleType.LOOK_TARGET, new net.minecraft.entity.ai.brain.EntityLookTarget(p, true));
    }

    // ---------------------------------------- скидки: богатых уважают

    public static void gossip(ServerWorld w, EcoState s) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            Village v = Villages.at(s, p.getBlockPos());
            if (v == null) continue;
            UUID id = p.getUuid();
            int want = Money.DISCOUNT[Money.tierOf(p)] + Math.max(0, v.rep(id)) / 2 + Math.min(60, v.fear(id)) / 2;
            if (id.equals(v.mayorPlayer)) want += 30;
            want = Math.min(200, want);
            for (VillagerEntity e : w.getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(24, 8, 24), Entity::isAlive)) {
                int cur = e.getGossip().getReputationFor(id, t -> t == VillageGossipType.MINOR_POSITIVE);
                if (cur == want) continue;
                e.getGossip().remove(id, VillageGossipType.MINOR_POSITIVE);
                if (want > 0) e.getGossip().startGossip(id, VillageGossipType.MINOR_POSITIVE, want);
            }
        }
    }

    // ------------------------------- подсказка при взгляде на жителя/голема

    public static void lookInfo(ServerWorld w, EcoState s) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator()) continue;
            Vec3d eye = p.getCameraPosVec(1f);
            Vec3d look = p.getRotationVec(1f).multiply(6);
            Box box = p.getBoundingBox().stretch(look).expand(1);
            EntityHitResult hit = ProjectileUtil.raycast(p, eye, eye.add(look), box,
                    e -> e instanceof VillagerEntity || e instanceof IronGolemEntity, 36);
            if (hit == null) continue;
            Entity e = hit.getEntity();
            Hire h = Hires.byEntity(s, e.getUuid());
            String hired = h == null ? "" : " │ " + Hires.modeName(h.mode) + ": " + h.ownerName;
            if (e instanceof VillagerEntity ve) {
                if (ve.isBaby()) {
                    Ui.bar(p, Ui.t("Ребёнок", Formatting.GRAY));
                    continue;
                }
                VInfo i = Villages.info(s, ve);
                Village v = Villages.at(s, ve.getBlockPos());
                boolean mayor = v != null && ve.getUuid().equals(v.mayorVillager);
                String home = i.landlord != null ? "снимает" : (Villages.home(ve).isPresent() ? "свой" : "нет");
                MutableText t = Ui.t(i.name + " (" + Names.prof(ve) + ")", mayor ? Formatting.GOLD : Formatting.YELLOW)
                        .append(Ui.t(" │ кошелёк: " + Money.em(i.wallet) + " │ дом: " + home, Formatting.GREEN))
                        .append(Ui.t(mayor ? " │ МЭР" : "", Formatting.GOLD))
                        .append(Ui.t(i.hungry > 0 ? " │ голодает" : "", Formatting.RED))
                        .append(Ui.t(hired, Formatting.AQUA));
                Ui.bar(p, t);
            } else {
                Village v = Villages.at(s, e.getBlockPos());
                String what = h != null ? "Голем" : (v != null && v.guards.contains(e.getUuid()) ? "Охрана банка" :
                        (v != null ? "Голем деревни «" + v.name + "»" : "Голем"));
                Ui.bar(p, Ui.t(what + hired + (h == null ? " │ присядьте + ПКМ — нанять" : ""), Formatting.AQUA));
            }
        }
    }
}
