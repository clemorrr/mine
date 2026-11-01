package com.emeraldeconomy;

import com.emeraldeconomy.EcoState.Hire;
import com.emeraldeconomy.EcoState.House;
import com.emeraldeconomy.EcoState.PInfo;
import com.emeraldeconomy.EcoState.VInfo;
import com.emeraldeconomy.EcoState.Village;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.GlobalPos;

import java.util.UUID;

/**
 * Команды: /кошелёк /уважение /банк /мэр /выборы /дом /заказ (+ английские синонимы).
 */
public final class Commands {
    private Commands() {
    }

    private static LiteralArgumentBuilder<ServerCommandSource> lit(String n) {
        return CommandManager.literal(n);
    }

    private static ServerPlayerEntity p(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        return c.getSource().getPlayerOrThrow();
    }

    private static int num(CommandContext<ServerCommandSource> c) {
        return IntegerArgumentType.getInteger(c, "n");
    }

    private static EcoState st(ServerPlayerEntity p) {
        return EcoState.get(p.getServer());
    }

    public static void register(CommandDispatcher<ServerCommandSource> d, CommandRegistryAccess access, CommandManager.RegistrationEnvironment env) {
        for (String r : new String[]{"кошелёк", "кошелек", "wallet"}) d.register(lit(r).executes(c -> wallet(p(c))));
        for (String r : new String[]{"уважение", "respect"}) d.register(lit(r).executes(c -> respect(p(c))));
        for (String r : new String[]{"банк", "bank"}) d.register(bank(r));
        for (String r : new String[]{"мэр", "mayor"}) d.register(mayor(r));
        for (String r : new String[]{"выборы", "election"}) d.register(election(r));
        for (String r : new String[]{"дом", "house"}) d.register(house(r));
        for (String r : new String[]{"заказ", "contract"}) {
            d.register(lit(r).then(CommandManager.argument("цель", EntityArgumentType.player())
                    .executes(c -> {
                        ServerPlayerEntity pl = p(c);
                        ServerPlayerEntity target = EntityArgumentType.getPlayer(c, "цель");
                        if (!Hires.markTarget(pl, st(pl), target)) Ui.bad(pl, "Сначала наймите исполнителя (присядьте + ПКМ по жителю или голему).");
                        return 1;
                    })));
        }
        d.register(emv());
    }

    // ------------------------------------------------------ кошелёк

    private static int wallet(ServerPlayerEntity p) {
        PInfo pi = st(p).player(p.getUuid());
        int w = Money.wealth(p), t = Money.tier(w);
        MutableText m = Ui.t("━━ Кошелёк ━━", Formatting.GOLD)
                .append(Ui.t("\nВ кармане: " + Money.em(Money.count(p)) + " │ Вклад: " + Money.em(pi.deposit)
                        + (pi.loan > 0 ? " │ Долг банку: " + Money.em(pi.loan) : ""), Formatting.GREEN))
                .append(Ui.t("\nСостояние: " + Money.em(w) + " → «" + Money.TIERS[t] + "»", Formatting.YELLOW));
        if (t < Money.TIERS.length - 1) {
            m.append(Ui.t(" (до «" + Money.TIERS[t + 1] + "» ещё " + Money.em(Money.THRESH[t + 1] - w) + ")", Formatting.GRAY));
        }
        p.sendMessage(m, false);
        return 1;
    }

    private static int respect(ServerPlayerEntity p) {
        EcoState s = st(p);
        int t = Money.tierOf(p);
        String[] perks = {
                "Жители вас не замечают.",
                "Небольшие скидки у торговцев.",
                "Хорошие скидки. Жители сами подходят с просьбами.",
                "Большие скидки. Жители кланяются и слушаются ваших приказов.",
                "Огромные скидки. Вас боготворят, а если вы с оружием — разбегаются в страхе."
        };
        MutableText m = Ui.t("━━ Уважение: «" + Money.TIERS[t] + "» ━━", Formatting.GOLD)
                .append(Ui.t("\n" + perks[t], Formatting.GRAY));
        Village v = Villages.at(s, p.getBlockPos());
        if (v != null) {
            UUID id = p.getUuid();
            m.append(Ui.t("\nДеревня «" + v.name + "»: репутация " + v.rep(id) + ", страх " + v.fear(id), Formatting.YELLOW));
            if (v.isWanted(id, p.getServerWorld().getTime())) m.append(Ui.t(" │ ВЫ В РОЗЫСКЕ", Formatting.RED));
            if (id.equals(v.mayorPlayer)) m.append(Ui.t("\nВы — мэр этой деревни. Управление: /мэр", Formatting.GOLD));
        }
        for (Village vv : s.villages) {
            if (p.getUuid().equals(vv.mayorPlayer) && vv != v) m.append(Ui.t("\nВы мэр деревни «" + vv.name + "».", Formatting.GOLD));
        }
        p.sendMessage(m, false);
        return 1;
    }

    // --------------------------------------------------------- банк

    private static LiteralArgumentBuilder<ServerCommandSource> bank(String root) {
        LiteralArgumentBuilder<ServerCommandSource> b = lit(root).executes(c -> bankInfo(p(c)));
        for (String n : new String[]{"положить", "deposit"})
            b.then(lit(n).then(CommandManager.argument("n", IntegerArgumentType.integer(1)).executes(c -> deposit(p(c), num(c)))));
        for (String n : new String[]{"снять", "withdraw"})
            b.then(lit(n).then(CommandManager.argument("n", IntegerArgumentType.integer(1)).executes(c -> withdraw(p(c), num(c)))));
        for (String n : new String[]{"кредит", "loan"})
            b.then(lit(n).then(CommandManager.argument("n", IntegerArgumentType.integer(1)).executes(c -> loan(p(c), num(c)))));
        for (String n : new String[]{"вернуть", "repay"})
            b.then(lit(n).executes(c -> repay(p(c), Integer.MAX_VALUE))
                    .then(CommandManager.argument("n", IntegerArgumentType.integer(1)).executes(c -> repay(p(c), num(c)))));
        return b;
    }

    private static Village needVillage(ServerPlayerEntity p) {
        Village v = Villages.at(st(p), p.getBlockPos());
        if (v == null) Ui.bad(p, "Вы не в деревне.");
        return v;
    }

    private static int loanLimit(ServerPlayerEntity p) {
        return 20 + 30 * Money.tierOf(p);
    }

    private static int bankInfo(ServerPlayerEntity p) {
        PInfo pi = st(p).player(p.getUuid());
        long day = p.getServerWorld().getTimeOfDay() / 24000L;
        MutableText m = Ui.t("━━ Банк ━━", Formatting.GOLD)
                .append(Ui.t("\nВаш вклад: " + Money.em(pi.deposit) + " (от 50 — +2% в день, при смерти не теряется)", Formatting.GREEN))
                .append(Ui.t(pi.loan > 0 ? "\nДолг: " + Money.em(pi.loan) + ", вернуть до: " + Math.max(0, pi.loanDue - day) + " дн." :
                        "\nКредит: до " + Money.em(loanLimit(p)) + " под 20% на 5 дней", Formatting.YELLOW))
                .append(Ui.t("\n/банк положить <n> │ /банк снять <n> │ /банк кредит <n> │ /банк вернуть", Formatting.GRAY));
        p.sendMessage(m, false);
        return 1;
    }

    private static int deposit(ServerPlayerEntity p, int n) {
        if (needVillage(p) == null) return 0;
        if (!Money.take(p, n)) {
            Ui.bad(p, "У вас нет " + Money.em(n) + ".");
            return 0;
        }
        PInfo pi = st(p).player(p.getUuid());
        pi.deposit += n;
        Ui.ok(p, "Положено " + Money.em(n) + ". Вклад: " + Money.em(pi.deposit) + ".");
        return 1;
    }

    private static int withdraw(ServerPlayerEntity p, int n) {
        if (needVillage(p) == null) return 0;
        PInfo pi = st(p).player(p.getUuid());
        if (pi.deposit < n) {
            Ui.bad(p, "На вкладе только " + Money.em(pi.deposit) + ".");
            return 0;
        }
        pi.deposit -= n;
        Money.give(p, n);
        Ui.ok(p, "Снято " + Money.em(n) + ". Осталось на вкладе: " + Money.em(pi.deposit) + ".");
        return 1;
    }

    private static int loan(ServerPlayerEntity p, int n) {
        if (needVillage(p) == null) return 0;
        PInfo pi = st(p).player(p.getUuid());
        if (pi.loan > 0) {
            Ui.bad(p, "Сначала верните старый кредит.");
            return 0;
        }
        if (n > loanLimit(p)) {
            Ui.bad(p, "Вам дадут не больше " + Money.em(loanLimit(p)) + ". Разбогатейте — лимит вырастет.");
            return 0;
        }
        pi.loan = (int) Math.ceil(n * 1.2);
        pi.loanDue = p.getServerWorld().getTimeOfDay() / 24000L + 5;
        Money.give(p, n);
        Ui.ok(p, "Вы взяли " + Money.em(n) + ". Вернуть " + Money.em(pi.loan) + " за 5 дней, иначе пришлём коллектора.");
        return 1;
    }

    private static int repay(ServerPlayerEntity p, int n) {
        PInfo pi = st(p).player(p.getUuid());
        if (pi.loan <= 0) {
            Ui.msg(p, "У вас нет долгов.");
            return 0;
        }
        int want = Math.min(n, pi.loan);
        int cash = Math.min(want, Money.count(p));
        if (p.isCreative()) cash = want;
        Money.take(p, cash);
        int fromDep = Math.min(want - cash, pi.deposit);
        pi.deposit -= fromDep;
        int paid = cash + fromDep;
        pi.loan -= paid;
        if (paid == 0) {
            Ui.bad(p, "Нечем платить.");
            return 0;
        }
        if (pi.loan <= 0) {
            pi.loan = 0;
            Hires.removeCollector(p.getServerWorld(), st(p), p);
            Ui.ok(p, "Кредит полностью погашен (" + Money.em(paid) + ").");
        } else {
            Ui.ok(p, "Внесено " + Money.em(paid) + ". Осталось: " + Money.em(pi.loan) + ".");
        }
        return 1;
    }

    // ---------------------------------------------------------- мэр

    private static LiteralArgumentBuilder<ServerCommandSource> mayor(String root) {
        LiteralArgumentBuilder<ServerCommandSource> b = lit(root).executes(c -> mayorInfo(p(c)));
        for (String n : new String[]{"налог", "tax"})
            b.then(lit(n).then(CommandManager.argument("n", IntegerArgumentType.integer(0, 50)).executes(c -> setTax(p(c), num(c)))));
        for (String n : new String[]{"зарплата", "salary"})
            b.then(lit(n).then(CommandManager.argument("n", IntegerArgumentType.integer(0, 5)).executes(c -> setSalary(p(c), num(c)))));
        for (String n : new String[]{"снять", "withdraw"})
            b.then(lit(n).then(CommandManager.argument("n", IntegerArgumentType.integer(1)).executes(c -> treasuryOut(p(c), num(c)))));
        for (String n : new String[]{"положить", "deposit"})
            b.then(lit(n).then(CommandManager.argument("n", IntegerArgumentType.integer(1)).executes(c -> treasuryIn(p(c), num(c)))));
        return b;
    }

    private static int mayorInfo(ServerPlayerEntity p) {
        Village v = needVillage(p);
        if (v == null) return 0;
        ServerWorld w = p.getServerWorld();
        int count = 0, hungry = 0, employed = 0;
        EcoState s = st(p);
        for (VillagerEntity e : Villages.villagers(w, v)) {
            if (e.isBaby()) continue;
            count++;
            if (Villages.info(s, e).hungry > 0) hungry++;
            if (Names.employed(e)) employed++;
        }
        long left = v.nextElection * 24000L - w.getTimeOfDay();
        MutableText m = Ui.t("━━ Деревня «" + v.name + "» ━━", Formatting.GOLD)
                .append(Ui.t("\nМэр: " + (v.hasMayor() ? v.mayorName : "нет") + (v.mayorPlayer != null ? " (игрок)" : (v.kind ? " (добрый)" : " (высокомерный)")), Formatting.YELLOW))
                .append(Ui.t("\nКазна: " + Money.em(v.treasury) + " │ Налог: " + v.tax + "% │ Зарплата: " + v.salary + "/день", Formatting.GREEN))
                .append(Ui.t("\nЖителей: " + count + " (работают " + employed + ", голодают " + hungry + ")", Formatting.GRAY))
                .append(Ui.t("\nДо выборов: " + Ui.time(left) + " │ Кандидатов-игроков: " + v.candidates.size(), Formatting.GRAY));
        if (p.getUuid().equals(v.mayorPlayer)) {
            m.append(Ui.t("\n/мэр налог <0-50> │ /мэр зарплата <0-5> │ /мэр снять <n> │ /мэр положить <n>", Formatting.AQUA))
                    .append(Ui.t("\nВысокие налоги и воровство из казны злят жителей — на выборах это припомнят.", Formatting.DARK_GRAY));
        } else {
            m.append(Ui.t("\nСтать мэром: победить на выборах (/выборы участвовать) или убить мэра.", Formatting.DARK_GRAY));
        }
        p.sendMessage(m, false);
        return 1;
    }

    private static Village needMayor(ServerPlayerEntity p) {
        Village v = needVillage(p);
        if (v != null && !p.getUuid().equals(v.mayorPlayer)) {
            Ui.bad(p, "Вы не мэр этой деревни.");
            return null;
        }
        return v;
    }

    private static int setTax(ServerPlayerEntity p, int n) {
        Village v = needMayor(p);
        if (v == null) return 0;
        v.tax = n;
        Villages.broadcast(p.getServerWorld(), v, Ui.t("Мэр " + v.mayorName + " установил налог " + n + "%.", Formatting.YELLOW));
        return 1;
    }

    private static int setSalary(ServerPlayerEntity p, int n) {
        Village v = needMayor(p);
        if (v == null) return 0;
        v.salary = n;
        Ui.ok(p, "Зарплата работникам: " + Money.em(n) + " в день.");
        return 1;
    }

    private static int treasuryOut(ServerPlayerEntity p, int n) {
        Village v = needMayor(p);
        if (v == null) return 0;
        if (v.treasury < n) {
            Ui.bad(p, "В казне только " + Money.em(v.treasury) + ".");
            return 0;
        }
        v.treasury -= n;
        Money.give(p, n);
        v.addRep(p.getUuid(), -Math.max(1, n / 4));
        Ui.ok(p, "Вы взяли из казны " + Money.em(n) + ". Жители недовольны.");
        return 1;
    }

    private static int treasuryIn(ServerPlayerEntity p, int n) {
        Village v = needMayor(p);
        if (v == null) return 0;
        if (!Money.take(p, n)) {
            Ui.bad(p, "У вас нет " + Money.em(n) + ".");
            return 0;
        }
        int room = Economy.TREASURY_CAP - v.treasury;
        int put = Math.max(0, Math.min(n, room));
        v.treasury += put;
        if (put < n) Money.give(p, n - put);
        v.addRep(p.getUuid(), put / 4);
        Ui.ok(p, "В казну внесено " + Money.em(put) + ". Казна: " + v.treasury + (put < n ? " (максимум 200)" : ""));
        return 1;
    }

    // ------------------------------------------------------- выборы

    private static LiteralArgumentBuilder<ServerCommandSource> election(String root) {
        LiteralArgumentBuilder<ServerCommandSource> b = lit(root).executes(c -> electionInfo(p(c)));
        for (String n : new String[]{"участвовать", "run"}) b.then(lit(n).executes(c -> run(p(c))));
        for (String n : new String[]{"подкуп", "bribe"})
            b.then(lit(n).then(CommandManager.argument("n", IntegerArgumentType.integer(1)).executes(c -> bribe(p(c), num(c)))));
        return b;
    }

    private static int electionInfo(ServerPlayerEntity p) {
        Village v = needVillage(p);
        if (v == null) return 0;
        ServerWorld w = p.getServerWorld();
        long left = v.nextElection * 24000L - w.getTimeOfDay();
        MutableText m = Ui.t("━━ Выборы в «" + v.name + "» ━━", Formatting.GOLD)
                .append(Ui.t("\nДо выборов: " + Ui.time(left) + ". Действующий мэр: " + (v.hasMayor() ? v.mayorName : "нет"), Formatting.YELLOW));
        for (UUID c : v.candidates) {
            ServerPlayerEntity cp = w.getServer().getPlayerManager().getPlayer(c);
            m.append(Ui.t("\n • " + (cp != null ? cp.getGameProfile().getName() : "игрок") + " — вложено в кампанию " + Money.em(v.campaign.getOrDefault(c, 0)), Formatting.GRAY));
        }
        m.append(Ui.t("\nГолоса жителей зависят от вашего богатства, репутации, подкупа и страха.", Formatting.DARK_GRAY))
                .append(Ui.t("\n/выборы участвовать │ /выборы подкуп <n>", Formatting.AQUA));
        p.sendMessage(m, false);
        return 1;
    }

    private static int run(ServerPlayerEntity p) {
        Village v = needVillage(p);
        if (v == null) return 0;
        if (p.getUuid().equals(v.mayorPlayer)) {
            Ui.msg(p, "Вы и так мэр — на выборах будете защищать своё кресло.");
            return 0;
        }
        v.candidates.add(p.getUuid());
        Villages.broadcast(p.getServerWorld(), v, Ui.t(p.getGameProfile().getName() + " выдвинулся в мэры деревни «" + v.name + "»!", Formatting.YELLOW));
        return 1;
    }

    private static int bribe(ServerPlayerEntity p, int n) {
        Village v = needVillage(p);
        if (v == null) return 0;
        if (!v.candidates.contains(p.getUuid()) && !p.getUuid().equals(v.mayorPlayer)) {
            Ui.bad(p, "Сначала выдвиньтесь: /выборы участвовать");
            return 0;
        }
        java.util.List<VillagerEntity> list = new java.util.ArrayList<>(Villages.villagers(p.getServerWorld(), v));
        list.removeIf(VillagerEntity::isBaby);
        if (list.isEmpty()) {
            Ui.bad(p, "Некого подкупать.");
            return 0;
        }
        if (!Money.take(p, n)) {
            Ui.bad(p, "У вас нет " + Money.em(n) + ".");
            return 0;
        }
        EcoState s = st(p);
        for (int k = 0; k < n; k++) Villages.info(s, list.get(k % list.size())).wallet++;
        v.campaign.merge(p.getUuid(), n, Integer::sum);
        Ui.ok(p, "Вы раздали жителям " + Money.em(n) + ". Всего вложено в кампанию: " + Money.em(v.campaign.get(p.getUuid())) + ".");
        return 1;
    }

    // ---------------------------------------------------------- дома

    private static LiteralArgumentBuilder<ServerCommandSource> house(String root) {
        LiteralArgumentBuilder<ServerCommandSource> b = lit(root).executes(c -> houses(p(c)));
        for (String n : new String[]{"сдать", "rent"}) b.then(lit(n).executes(c -> rent(p(c))));
        for (String n : new String[]{"выселить", "evict"}) b.then(lit(n).executes(c -> evict(p(c))));
        return b;
    }

    private static int houses(ServerPlayerEntity p) {
        EcoState s = st(p);
        PInfo pi = s.player(p.getUuid());
        MutableText m = Ui.t("━━ Ваши дома ━━", Formatting.GOLD);
        if (pi.houses.isEmpty()) m.append(Ui.t("\nУ вас нет домов. Купите дом у жителя: присядьте + ПКМ по нему.", Formatting.GRAY));
        for (House h : pi.houses) {
            Village v = Villages.byId(s, h.villageId);
            String tenant = "пустует";
            if (h.tenant != null) {
                VInfo ti = s.villagers.get(h.tenant);
                tenant = "жилец: " + (ti != null ? ti.name : "?") + " (1 изумруд/день)";
            }
            m.append(Ui.t("\n • " + (v != null ? v.name : "?") + ": " + h.bed.getX() + " " + h.bed.getY() + " " + h.bed.getZ() + " — " + tenant, Formatting.GRAY));
        }
        m.append(Ui.t("\n/дом сдать — сдать ближайший дом │ /дом выселить — выгнать жильца", Formatting.AQUA));
        p.sendMessage(m, false);
        return 1;
    }

    private static House nearest(ServerPlayerEntity p, PInfo pi) {
        House best = null;
        double bd = 16 * 16;
        for (House h : pi.houses) {
            double d = h.bed.getSquaredDistance(p.getBlockPos());
            if (d < bd) {
                bd = d;
                best = h;
            }
        }
        return best;
    }

    private static int rent(ServerPlayerEntity p) {
        EcoState s = st(p);
        PInfo pi = s.player(p.getUuid());
        House h = nearest(p, pi);
        if (h == null) {
            Ui.bad(p, "Рядом нет вашего дома (подойдите к его кровати ближе чем на 16 блоков).");
            return 0;
        }
        if (h.tenant != null) {
            Ui.bad(p, "Дом уже сдан.");
            return 0;
        }
        Village v = Villages.byId(s, h.villageId);
        if (v == null) return 0;
        ServerWorld w = p.getServerWorld();
        VillagerEntity best = null;
        double bd = Double.MAX_VALUE;
        for (VillagerEntity e : Villages.villagers(w, v)) {
            if (e.isBaby() || e.getUuid().equals(v.mayorVillager) || Hires.byEntity(s, e.getUuid()) != null) continue;
            if (Villages.home(e).isPresent()) continue;
            double d = e.getBlockPos().getSquaredDistance(h.bed);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        if (best == null) {
            Ui.bad(p, "В деревне нет бездомных жителей. Купите дом у другого жителя — и он станет бездомным :)");
            return 0;
        }
        best.getBrain().remember(MemoryModuleType.HOME, GlobalPos.create(w.getRegistryKey(), h.bed));
        Villages.info(s, best).landlord = p.getUuid();
        h.tenant = best.getUuid();
        p.sendMessage(Ui.say(Villages.title(s, best), "Спасибо, хозяин! Буду платить исправно — 1 изумруд в день."), false);
        return 1;
    }

    private static int evict(ServerPlayerEntity p) {
        EcoState s = st(p);
        PInfo pi = s.player(p.getUuid());
        House h = nearest(p, pi);
        if (h == null || h.tenant == null) {
            Ui.bad(p, "Рядом нет вашего сданного дома.");
            return 0;
        }
        Entity e = p.getServerWorld().getEntity(h.tenant);
        if (e instanceof VillagerEntity ve) {
            ve.getBrain().forget(MemoryModuleType.HOME);
            if (ve.isSleeping()) ve.wakeUp();
            Villages.info(s, ve).landlord = null;
            p.sendMessage(Ui.say(Villages.title(s, ve), "Куда же я пойду?.."), false);
        }
        VInfo ti = s.villagers.get(h.tenant);
        if (ti != null) ti.landlord = null;
        h.tenant = null;
        Ui.ok(p, "Жилец выселен.");
        return 1;
    }

    // ----------------------------------------------- кнопки в чате (/emv)

    private static LiteralArgumentBuilder<ServerCommandSource> emv() {
        return lit("emv")
                .then(lit("hire").then(CommandManager.argument("id", StringArgumentType.word())
                        .then(CommandManager.argument("mode", StringArgumentType.word()).executes(c -> {
                            ServerPlayerEntity pl = p(c);
                            MobEntity mob = Interactions.resolve(pl, StringArgumentType.getString(c, "id"));
                            if (mob == null) return 0;
                            String mode = StringArgumentType.getString(c, "mode");
                            Hires.hire(pl, st(pl), mob, mode.equals("hit") ? Hire.HIT : Hire.GUARD);
                            return 1;
                        }))))
                .then(lit("give").then(CommandManager.argument("id", StringArgumentType.word())
                        .then(CommandManager.argument("n", IntegerArgumentType.integer(1, 64)).executes(c -> {
                            ServerPlayerEntity pl = p(c);
                            if (Interactions.resolve(pl, StringArgumentType.getString(c, "id")) instanceof VillagerEntity ve) {
                                VInfo i = Villages.info(st(pl), ve);
                                int amount = num(c);
                                boolean asked = pl.getUuid().equals(i.begPlayer) && pl.getServerWorld().getTime() < i.begExpire;
                                if (asked) amount = i.begAmount;
                                Interactions.give(pl, st(pl), ve, amount, asked);
                            }
                            return 1;
                        }))))
                .then(lit("tip").then(CommandManager.argument("id", StringArgumentType.word()).executes(c -> {
                    ServerPlayerEntity pl = p(c);
                    if (Interactions.resolve(pl, StringArgumentType.getString(c, "id")) instanceof VillagerEntity ve)
                        Interactions.give(pl, st(pl), ve, 1, false);
                    return 1;
                })))
                .then(lit("refuse").then(CommandManager.argument("id", StringArgumentType.word()).executes(c -> {
                    ServerPlayerEntity pl = p(c);
                    if (Interactions.resolve(pl, StringArgumentType.getString(c, "id")) instanceof VillagerEntity ve)
                        Interactions.refuse(pl, st(pl), ve);
                    return 1;
                })))
                .then(lit("buyhouse").then(CommandManager.argument("id", StringArgumentType.word()).executes(c -> {
                    ServerPlayerEntity pl = p(c);
                    if (Interactions.resolve(pl, StringArgumentType.getString(c, "id")) instanceof VillagerEntity ve)
                        Interactions.buyHouse(pl, st(pl), ve);
                    return 1;
                })))
                .then(lit("follow").then(CommandManager.argument("id", StringArgumentType.word()).executes(c -> {
                    ServerPlayerEntity pl = p(c);
                    if (Interactions.resolve(pl, StringArgumentType.getString(c, "id")) instanceof VillagerEntity ve)
                        Interactions.follow(pl, st(pl), ve);
                    return 1;
                })))
                .then(lit("stay").then(CommandManager.argument("id", StringArgumentType.word()).executes(c -> {
                    ServerPlayerEntity pl = p(c);
                    if (Interactions.resolve(pl, StringArgumentType.getString(c, "id")) instanceof VillagerEntity ve)
                        Interactions.stay(pl, st(pl), ve);
                    return 1;
                })))
                .then(lit("fire").then(CommandManager.argument("id", StringArgumentType.word()).executes(c -> {
                    ServerPlayerEntity pl = p(c);
                    MobEntity mob = Interactions.resolve(pl, StringArgumentType.getString(c, "id"));
                    if (mob != null) Interactions.fire(pl, st(pl), mob);
                    return 1;
                })));
    }

}
