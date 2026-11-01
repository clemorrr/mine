package com.emeraldeconomy;

import com.emeraldeconomy.EcoState.Hire;
import com.emeraldeconomy.EcoState.House;
import com.emeraldeconomy.EcoState.PInfo;
import com.emeraldeconomy.EcoState.VInfo;
import com.emeraldeconomy.EcoState.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Взаимодействия: меню жителей и големов, сдача заданий, ограбление банка.
 */
public final class Interactions {
    private Interactions() {
    }

    private static final Map<UUID, Long> LAST_MENU = new HashMap<>();

    // ------------------------------------------------------- ПКМ по мобу

    public static ActionResult onUseEntity(PlayerEntity player, World world, Hand hand, Entity entity, EntityHitResult hit) {
        if (world.isClient() || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
        if (!player.isSneaking()) return ActionResult.PASS;
        if (!(entity instanceof VillagerEntity) && !(entity instanceof IronGolemEntity)) return ActionResult.PASS;
        if (entity instanceof IronGolemEntity && player.getMainHandStack().isOf(Items.IRON_INGOT)) return ActionResult.PASS;
        if (entity instanceof VillagerEntity baby && baby.isBaby()) return ActionResult.PASS;

        long now = world.getTime();
        Long last = LAST_MENU.get(player.getUuid());
        if (last != null && now - last < 5) return ActionResult.SUCCESS; // пакет приходит дважды
        LAST_MENU.put(player.getUuid(), now);

        EcoState s = EcoState.get(sp.getServer());
        if (entity instanceof VillagerEntity ve) {
            VInfo i = Villages.info(s, ve);
            if (tryQuest(sp, s, ve, i)) return ActionResult.SUCCESS;
            villagerMenu(sp, s, ve, i);
        } else {
            golemMenu(sp, s, (IronGolemEntity) entity);
        }
        return ActionResult.SUCCESS;
    }

    private static boolean tryQuest(ServerPlayerEntity p, EcoState s, VillagerEntity ve, VInfo i) {
        if (!p.getUuid().equals(i.questPlayer)) return false;
        if (p.getServerWorld().getTime() > i.questExpire) {
            i.questPlayer = null;
            return false;
        }
        ItemStack held = p.getMainHandStack();
        if (held.isEmpty() || !Registries.ITEM.getId(held.getItem()).toString().equals(i.questItem)) return false;
        if (held.getCount() < i.questCount) {
            Ui.bad(p, "Нужно " + i.questCount + " шт., у вас в руке " + held.getCount() + ".");
            return true;
        }
        held.decrement(i.questCount);
        int pay = Math.min(i.wallet, i.questReward);
        i.wallet -= pay;
        Money.give(p, pay);
        i.questPlayer = null;
        Village v = Villages.at(s, ve.getBlockPos());
        if (v != null) v.addRep(p.getUuid(), 5);
        p.sendMessage(Ui.say(Villages.title(s, ve), pay > 0 ? "Спасибо! Вот ваши " + Money.em(pay) + "." :
                "Спасибо огромное! Денег у меня нет, но я всем расскажу о вашей доброте."), false);
        ve.playSound(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_YES, 1f, 1f);
        return true;
    }

    private static boolean canCommand(EcoState s, Village v, ServerPlayerEntity p) {
        if (v == null) return Money.tierOf(p) >= 3;
        return Money.tierOf(p) >= 3 || v.fear(p.getUuid()) >= 20 || p.getUuid().equals(v.mayorPlayer);
    }

    private static boolean ownedByPlayer(EcoState s, BlockPos bed) {
        for (PInfo pi : s.players.values()) for (House h : pi.houses) if (bed.equals(h.bed)) return true;
        return false;
    }

    public static void villagerMenu(ServerPlayerEntity p, EcoState s, VillagerEntity ve, VInfo i) {
        Village v = Villages.at(s, ve.getBlockPos());
        String u = ve.getUuid().toString();
        boolean mayor = v != null && ve.getUuid().equals(v.mayorVillager);
        Hire h = Hires.byEntity(s, ve.getUuid());
        Optional<GlobalPos> home = Villages.home(ve);

        MutableText m = Ui.t("━━ " + i.name + " · " + Names.prof(ve) + (v != null ? " · «" + v.name + "»" : "") + " ━━", Formatting.GOLD);
        String homeTxt = i.landlord != null ? "снимает у игрока" : (home.isPresent() ? "свой" : "нет дома");
        m.append(Ui.t("\nКошелёк: " + Money.em(i.wallet) + " │ Дом: " + homeTxt
                + (v != null ? " │ Ваша репутация: " + v.rep(p.getUuid()) : ""), Formatting.GRAY));
        if (i.hungry > 0) m.append(Ui.t("\nГолодает уже " + i.hungry + " дн.", Formatting.RED));
        if (mayor) m.append(Ui.t("\nЭто мэр деревни (" + (v.kind ? "добрый" : "высокомерный") + "). Казна: " + v.treasury, Formatting.YELLOW));
        if (h != null) m.append(Ui.t("\nНанят: " + Hires.modeName(h.mode) + " (" + h.ownerName + ")", Formatting.AQUA));
        if (p.getUuid().equals(i.questPlayer)) {
            m.append(Ui.t("\nЗадание: принести " + i.questCount + " × ", Formatting.AQUA))
                    .append(Registries.ITEM.get(new net.minecraft.util.Identifier(i.questItem)).getName().copy().formatted(Formatting.AQUA))
                    .append(Ui.t(" → " + Money.em(i.questReward), Formatting.AQUA));
        }
        m.append(Ui.t("\n"));
        if (!mayor && h == null) {
            m.append(Ui.button("Нанять охранником · 30", "/emv hire " + u + " guard", Formatting.GREEN, "Будет защищать вас 3 дня"))
                    .append(Ui.sp())
                    .append(Ui.button("Заказать убийство · 30", "/emv hire " + u + " hit", Formatting.DARK_RED, "Убьёт того, кого вы укажете"))
                    .append(Ui.sp());
        }
        m.append(Ui.button("Дать 1", "/emv tip " + u, Formatting.GREEN, "Подарить 1 изумруд (+репутация)"));
        if (i.begPlayer != null && i.begPlayer.equals(p.getUuid()) && p.getServerWorld().getTime() < i.begExpire) {
            m.append(Ui.sp()).append(Ui.button("Дать " + i.begAmount + " (просит)", "/emv give " + u + " " + i.begAmount, Formatting.GREEN, "Он просил помочь"));
        }
        if (!mayor && i.landlord == null && home.isPresent() && !ownedByPlayer(s, home.get().getPos()) && v != null
                && !home.get().getPos().equals(v.mayorBed)) {
            m.append(Ui.sp()).append(Ui.button("Купить дом · " + i.housePrice, "/emv buyhouse " + u, Formatting.YELLOW, "Житель продаст вам свой дом и съедет"));
        }
        if (canCommand(s, v, p) && !mayor) {
            m.append(Ui.t("\n"));
            if (h == null) m.append(Ui.button("Иди за мной", "/emv follow " + u, Formatting.AQUA, "Бесплатно: вас слушаются"));
            else if (h.mode == Hire.FOLLOW && p.getUuid().equals(h.owner)) m.append(Ui.button("Отстань", "/emv stay " + u, Formatting.AQUA, "Отпустить"));
        }
        if (h != null && p.getUuid().equals(h.owner) && h.mode != Hire.FOLLOW) {
            m.append(Ui.sp()).append(Ui.button("Уволить", "/emv fire " + u, Formatting.GRAY, "Расторгнуть контракт (без возврата денег)"));
        }
        p.sendMessage(m, false);
    }

    public static void golemMenu(ServerPlayerEntity p, EcoState s, IronGolemEntity g) {
        Village v = Villages.at(s, g.getBlockPos());
        String u = g.getUuid().toString();
        Hire h = Hires.byEntity(s, g.getUuid());
        MutableText m = Ui.t("━━ Железный голем" + (v != null ? " · «" + v.name + "»" : "") + " ━━", Formatting.GOLD);
        if (v != null && v.guards.contains(g.getUuid())) {
            m.append(Ui.t("\nЭто охрана банка. Её не нанять.", Formatting.GRAY));
            p.sendMessage(m, false);
            return;
        }
        if (h != null) {
            m.append(Ui.t("\nНанят: " + Hires.modeName(h.mode) + " (" + h.ownerName + ")", Formatting.AQUA));
            if (p.getUuid().equals(h.owner) && h.mode != Hire.COLLECTOR) {
                m.append(Ui.t("\n")).append(Ui.button("Уволить", "/emv fire " + u, Formatting.GRAY, "Расторгнуть контракт"));
            }
        } else {
            m.append(Ui.t("\nОплата идёт в казну деревни.\n", Formatting.GRAY))
                    .append(Ui.button("Нанять охранником · 30", "/emv hire " + u + " guard", Formatting.GREEN, "Будет защищать вас 3 дня"))
                    .append(Ui.sp())
                    .append(Ui.button("Заказать убийство · 30", "/emv hire " + u + " hit", Formatting.DARK_RED, "Убьёт того, кого вы укажете"));
        }
        p.sendMessage(m, false);
    }

    // ------------------------------------------- действия кнопок (/emv)

    public static MobEntity resolve(ServerPlayerEntity p, String uuid) {
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Entity e = p.getServerWorld().getEntity(id);
        if (!(e instanceof MobEntity mob) || !mob.isAlive() || mob.squaredDistanceTo(p) > 10 * 10) {
            Ui.bad(p, "Подойдите ближе.");
            return null;
        }
        return mob;
    }

    public static void give(ServerPlayerEntity p, EcoState s, VillagerEntity ve, int amount, boolean asked) {
        if (!Money.take(p, amount)) {
            Ui.bad(p, "У вас нет " + Money.em(amount) + ".");
            return;
        }
        VInfo i = Villages.info(s, ve);
        i.wallet += amount;
        i.hungry = 0;
        if (asked && p.getUuid().equals(i.begPlayer)) i.begPlayer = null;
        Village v = Villages.at(s, ve.getBlockPos());
        if (v != null) v.addRep(p.getUuid(), Math.min(15, (asked ? 3 : 2) * amount));
        p.sendMessage(Ui.say(Villages.title(s, ve), Names.thanks(ve.getRandom())), false);
        ve.playSound(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_CELEBRATE, 1f, 1f);
    }

    public static void refuse(ServerPlayerEntity p, EcoState s, VillagerEntity ve) {
        VInfo i = Villages.info(s, ve);
        if (!p.getUuid().equals(i.begPlayer)) return;
        i.begPlayer = null;
        Village v = Villages.at(s, ve.getBlockPos());
        if (v != null) v.addRep(p.getUuid(), -1);
        ve.setHeadRollingTimeLeft(40);
        p.sendMessage(Ui.say(Villages.title(s, ve), Names.refused(ve.getRandom())), false);
    }

    public static void buyHouse(ServerPlayerEntity p, EcoState s, VillagerEntity ve) {
        VInfo i = Villages.info(s, ve);
        Village v = Villages.at(s, ve.getBlockPos());
        Optional<GlobalPos> home = Villages.home(ve);
        if (v == null || home.isEmpty() || i.landlord != null || ve.getUuid().equals(v.mayorVillager)
                || ownedByPlayer(s, home.get().getPos()) || home.get().getPos().equals(v.mayorBed)) {
            Ui.bad(p, "Этот житель не может продать дом.");
            return;
        }
        if (!Money.take(p, i.housePrice)) {
            Ui.bad(p, "Дом стоит " + Money.em(i.housePrice) + ".");
            return;
        }
        i.wallet += i.housePrice;
        BlockPos bed = home.get().getPos().toImmutable();
        ve.getBrain().forget(MemoryModuleType.HOME);
        if (ve.isSleeping()) ve.wakeUp();
        House h = new House();
        h.bed = bed;
        h.villageId = v.id;
        s.player(p.getUuid()).houses.add(h);
        p.sendMessage(Ui.say(Villages.title(s, ve), "По рукам! Дом ваш, а я поищу себе другое жильё..."), false);
        Ui.ok(p, "Теперь вам принадлежит дом с кроватью на " + bed.getX() + " " + bed.getY() + " " + bed.getZ()
                + ". Его можно сдать бездомному жителю: встаньте рядом и напишите /дом сдать");
    }

    public static void follow(ServerPlayerEntity p, EcoState s, VillagerEntity ve) {
        Village v = Villages.at(s, ve.getBlockPos());
        if (!canCommand(s, v, p)) {
            p.sendMessage(Ui.say(Villages.title(s, ve), "С чего бы мне вас слушаться?"), false);
            ve.setHeadRollingTimeLeft(40);
            return;
        }
        Hires.hire(p, s, ve, Hire.FOLLOW);
    }

    public static void stay(ServerPlayerEntity p, EcoState s, VillagerEntity ve) {
        Hire h = Hires.byEntity(s, ve.getUuid());
        if (h != null && h.mode == Hire.FOLLOW && p.getUuid().equals(h.owner)) {
            Hires.end(s, h, p.getServerWorld(), null);
            p.sendMessage(Ui.say(Villages.title(s, ve), "Как скажете."), false);
        }
    }

    public static void fire(ServerPlayerEntity p, EcoState s, MobEntity mob) {
        Hire h = Hires.byEntity(s, mob.getUuid());
        if (h == null || !p.getUuid().equals(h.owner) || h.mode == Hire.COLLECTOR) return;
        Hires.end(s, h, p.getServerWorld(), "Контракт расторгнут.");
        PInfo pi = s.player(p.getUuid());
        if (mob.getUuid().equals(pi.pendingHit)) pi.pendingHit = null;
    }

    // --------------------------------------------- ЛКМ: отметка цели

    public static ActionResult onAttackEntity(PlayerEntity player, World world, Hand hand, Entity entity, EntityHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity sp) || !(entity instanceof LivingEntity le)) return ActionResult.PASS;
        EcoState s = EcoState.get(sp.getServer());
        PInfo pi = s.player(sp.getUuid());
        if (pi.pendingHit != null && world.getTime() <= pi.pendingHitUntil) {
            Hires.markTarget(sp, s, le);
            return ActionResult.FAIL;
        }
        Hires.ownerAttacked(s, sp, le);
        return ActionResult.PASS;
    }

    // ------------------------------------------------ сейф банка

    public static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (world.isClient() || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
        BlockPos pos = hit.getBlockPos();
        EcoState s = EcoState.get(sp.getServer());
        for (Village v : s.villages) {
            if (!pos.equals(v.vault)) continue;
            if (sp.getUuid().equals(v.mayorPlayer)) {
                Ui.msg(sp, "Это сейф вашей казны (" + Money.em(v.treasury) + "). Снять деньги: /мэр снять <сколько>");
                return ActionResult.PASS;
            }
            if (v.treasury <= 0) {
                Ui.msg(sp, "Сейф пуст.");
                return ActionResult.PASS;
            }
            rob(sp, s, v, (ServerWorld) world);
            return ActionResult.PASS;
        }
        return ActionResult.PASS;
    }

    private static void rob(ServerPlayerEntity p, EcoState s, Village v, ServerWorld w) {
        BlockEntity be = w.getBlockEntity(v.vault);
        if (!(be instanceof ChestBlockEntity chest)) return;
        int left = v.treasury;
        for (int slot = 0; slot < chest.size() && left > 0; slot++) {
            if (!chest.getStack(slot).isEmpty()) continue;
            int c = Math.min(64, left);
            chest.setStack(slot, new ItemStack(Items.EMERALD, c));
            left -= c;
        }
        int stolen = v.treasury - left;
        v.treasury = left;
        chest.markDirty();
        long now = w.getTime();
        v.wanted.put(p.getUuid(), now + 2 * Hires.DAY);
        v.addRep(p.getUuid(), -50);
        v.addFear(p.getUuid(), 10);
        v.robbedDay = w.getTimeOfDay() / 24000L;
        for (VillagerEntity e : Villages.villagers(w, v)) {
            e.getGossip().startGossip(p.getUuid(), net.minecraft.village.VillageGossipType.MAJOR_NEGATIVE, 25);
        }
        Villages.broadcast(w, v, Ui.t("⚠ БАНК ДЕРЕВНИ «" + v.name + "» ОГРАБЛЕН! Грабитель: " + p.getGameProfile().getName()
                + ". Украдено " + Money.em(stolen) + ".", Formatting.DARK_RED));
        Ui.bad(p, "Вы вскрыли сейф! Теперь вы в розыске 2 дня — големы деревни будут охотиться на вас.");
    }

    public static boolean onBreakBlock(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity be) {
        if (world.isClient() || player.getServer() == null) return true;
        EcoState s = EcoState.get(player.getServer());
        for (Village v : s.villages) {
            if (pos.equals(v.vault)) {
                if (player instanceof ServerPlayerEntity sp) Ui.bad(sp, "Сейф не взломать киркой. Его можно только вскрыть (ПКМ).");
                return false;
            }
        }
        return true;
    }
}
