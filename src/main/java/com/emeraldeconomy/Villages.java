package com.emeraldeconomy;

import com.emeraldeconomy.EcoState.House;
import com.emeraldeconomy.EcoState.PInfo;
import com.emeraldeconomy.EcoState.VInfo;
import com.emeraldeconomy.EcoState.Village;
import net.minecraft.block.BedBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Деревни: поиск, мэры, выборы, банк, полоса сверху экрана.
 */
public final class Villages {
    private Villages() {
    }

    public static final int RADIUS = 72;
    private static final Map<Integer, ServerBossBar> BARS = new HashMap<>();

    // ------------------------------------------------------------ поиск

    public static Village at(EcoState s, BlockPos pos) {
        Village best = null;
        double bd = (double) RADIUS * RADIUS;
        for (Village v : s.villages) {
            double dx = v.center.getX() - pos.getX(), dz = v.center.getZ() - pos.getZ();
            double d = dx * dx + dz * dz;
            if (d <= bd) {
                bd = d;
                best = v;
            }
        }
        return best;
    }

    public static Village byId(EcoState s, int id) {
        for (Village v : s.villages) if (v.id == id) return v;
        return null;
    }

    public static Box area(Village v) {
        return new Box(v.center).expand(RADIUS, 48, RADIUS);
    }

    public static List<VillagerEntity> villagers(ServerWorld w, Village v) {
        return w.getEntitiesByClass(VillagerEntity.class, area(v), Entity::isAlive);
    }

    public static List<IronGolemEntity> golems(ServerWorld w, Village v) {
        return w.getEntitiesByClass(IronGolemEntity.class, area(v), Entity::isAlive);
    }

    public static boolean loaded(ServerWorld w, Village v) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.getBlockPos().getSquaredDistance(v.center) < 128 * 128) return true;
        }
        return false;
    }

    public static VInfo info(EcoState s, VillagerEntity e) {
        VInfo i = s.villagers.get(e.getUuid());
        if (i == null) {
            Random r = e.getRandom();
            i = new VInfo();
            i.name = Names.person(r);
            // у жителей обычно мало изумрудов, 10 — большая редкость
            if (r.nextInt(100) < 3) i.wallet = 10;
            else i.wallet = Math.min(9, (int) Math.floor(-Math.log(1.0 - r.nextDouble()) * 2.5));
            i.housePrice = 15 + r.nextInt(16);
            s.villagers.put(e.getUuid(), i);
        }
        return i;
    }

    public static String title(EcoState s, VillagerEntity e) {
        VInfo i = info(s, e);
        return i.name + ", " + Names.prof(e);
    }

    public static Optional<GlobalPos> home(VillagerEntity e) {
        return e.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME);
    }

    public static void broadcast(ServerWorld w, Village v, Text text) {
        for (ServerPlayerEntity p : w.getServer().getPlayerManager().getPlayerList()) {
            boolean near = p.getServerWorld() == w && p.getBlockPos().getSquaredDistance(v.center) < 128 * 128;
            if (near || p.getUuid().equals(v.mayorPlayer)) p.sendMessage(text, false);
        }
    }

    // --------------------------------------------------------- создание

    public static void detect(ServerWorld w, EcoState s, long day) {
        PointOfInterestStorage poi = w.getPointOfInterestStorage();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator()) continue;
            BlockPos pp = p.getBlockPos();
            if (at(s, pp) != null) continue;
            Optional<BlockPos> bell = poi.getNearestPosition(e -> e.matchesKey(PointOfInterestTypes.MEETING),
                    pp, 64, PointOfInterestStorage.OccupationStatus.ANY);
            if (bell.isEmpty()) continue;
            boolean taken = false;
            for (Village v : s.villages) {
                if (v.center.getSquaredDistance(bell.get()) < 110 * 110) {
                    taken = true;
                    break;
                }
            }
            if (taken) continue;
            create(w, s, bell.get().toImmutable(), day);
        }
    }

    private static void create(ServerWorld w, EcoState s, BlockPos bell, long day) {
        Random r = w.getRandom();
        Village v = new Village();
        v.id = s.nextVillageId++;
        v.name = Names.village(r);
        v.center = bell;
        v.treasury = r.nextInt(100) < 4 ? 150 + r.nextInt(51) : 30 + r.nextInt(51);
        v.nextElection = day + 7;
        s.villages.add(v);
        chooseVillagerMayor(w, s, v);
        broadcast(w, v, Ui.t("Вы нашли деревню «" + v.name + "». ", Formatting.GREEN)
                .append(Ui.t(v.hasMayor() ? "Мэр: " + v.mayorName + (v.kind ? " (добрый)" : " (высокомерный)") : "Мэра пока нет", Formatting.YELLOW)));
        EmeraldEconomy.LOG.info("Новая деревня {} #{} у {}", v.name, v.id, bell);
    }

    // ---------------------------------------------------------------- мэр

    public static void chooseVillagerMayor(ServerWorld w, EcoState s, Village v) {
        List<VillagerEntity> list = new ArrayList<>(villagers(w, v));
        list.removeIf(e -> e.isBaby() || Hires.byEntity(s, e.getUuid()) != null);
        if (list.isEmpty()) return;
        VillagerEntity best = list.get(w.getRandom().nextInt(list.size()));
        for (VillagerEntity e : list) if (info(s, e).wallet > info(s, best).wallet) best = e;
        setVillagerMayor(w, s, v, best);
    }

    public static void setVillagerMayor(ServerWorld w, EcoState s, Village v, VillagerEntity e) {
        deposeVillager(w, v);
        v.mayorPlayer = null;
        v.mayorVillager = e.getUuid();
        VInfo i = info(s, e);
        v.mayorName = i.name;
        v.kind = w.getRandom().nextBoolean();
        v.tax = v.kind ? 10 : 25;
        v.salary = 1;
        i.wallet += 10 + w.getRandom().nextInt(21);
        i.landlord = null;
        e.setCustomName(Ui.t("Мэр " + i.name, Formatting.GOLD));
        e.setCustomNameVisible(true);
        v.needHome = true;
    }

    public static void setPlayerMayor(ServerWorld w, EcoState s, Village v, UUID player, String name) {
        deposeVillager(w, v);
        v.mayorVillager = null;
        v.mayorPlayer = player;
        v.mayorName = name;
        v.candidates.remove(player);
        v.wanted.remove(player);
        v.needHome = true;
    }

    private static void deposeVillager(ServerWorld w, Village v) {
        if (v.mayorVillager == null) return;
        Entity e = w.getEntity(v.mayorVillager);
        if (e instanceof VillagerEntity ve) {
            ve.setCustomName(null);
            ve.setCustomNameVisible(false);
            if (v.mayorBed != null) {
                Optional<GlobalPos> h = home(ve);
                if (h.isPresent() && h.get().getPos().equals(v.mayorBed)) ve.getBrain().forget(MemoryModuleType.HOME);
            }
        }
        v.mayorVillager = null;
    }

    public static boolean isMayor(Village v, UUID id) {
        return id != null && (id.equals(v.mayorPlayer) || id.equals(v.mayorVillager));
    }

    // --------------------------------------------------------- поддержка

    public static void maintain(ServerWorld w, EcoState s, long day) {
        for (Village v : s.villages) {
            if (!loaded(w, v)) continue;
            if (!v.hasMayor()) chooseVillagerMayor(w, s, v);
            if (v.mayorVillager != null) {
                Entity e = w.getEntity(v.mayorVillager);
                if (e instanceof VillagerEntity ve && ve.getCustomName() == null) {
                    ve.setCustomName(Ui.t("Мэр " + v.mayorName, Formatting.GOLD));
                    ve.setCustomNameVisible(true);
                }
            }
            if (v.vault == null && v.bankAttempts < 6) {
                v.bankAttempts++;
                Builder.buildBank(w, v);
            }
            if (v.house == null && v.houseAttempts < 6) {
                v.houseAttempts++;
                Builder.buildMayorHouse(w, v, null, Economy.houseTier(v));
            }
            if (v.vault != null) Builder.leashGuards(w, v);
            homes(w, s, v);
        }
    }

    /** Мэр живёт в доме мэра, купленные игроками дома заняты только их арендаторами. */
    private static void homes(ServerWorld w, EcoState s, Village v) {
        PointOfInterestStorage poi = w.getPointOfInterestStorage();
        List<VillagerEntity> all = villagers(w, v);
        // дом мэра
        if (v.mayorBed != null && w.getBlockState(v.mayorBed).getBlock() instanceof BedBlock) {
            for (VillagerEntity e : all) {
                if (e.getUuid().equals(v.mayorVillager)) continue;
                Optional<GlobalPos> h = home(e);
                if (h.isPresent() && h.get().getPos().equals(v.mayorBed)) e.getBrain().forget(MemoryModuleType.HOME);
            }
            lock(poi, v.mayorBed);
            if (v.mayorVillager != null) {
                Entity me = w.getEntity(v.mayorVillager);
                if (me instanceof VillagerEntity mv) {
                    Optional<GlobalPos> h = home(mv);
                    if (h.isEmpty() || !h.get().getPos().equals(v.mayorBed)) {
                        if (h.isPresent() && h.get().getDimension() == w.getRegistryKey()) poi.releaseTicket(h.get().getPos());
                        mv.getBrain().remember(MemoryModuleType.HOME, GlobalPos.create(w.getRegistryKey(), v.mayorBed));
                    }
                    v.needHome = false;
                }
            } else {
                v.needHome = false;
            }
        }
        // дома игроков
        for (Map.Entry<UUID, PInfo> en : s.players.entrySet()) {
            for (House house : new ArrayList<>(en.getValue().houses)) {
                if (house.villageId != v.id || house.bed == null) continue;
                if (!w.isChunkLoaded(house.bed.getX() >> 4, house.bed.getZ() >> 4)) continue;
                if (!(w.getBlockState(house.bed).getBlock() instanceof BedBlock)) {
                    en.getValue().houses.remove(house);
                    ServerPlayerEntity owner = w.getServer().getPlayerManager().getPlayer(en.getKey());
                    if (owner != null) Ui.bad(owner, "Кровать в вашем доме в деревне «" + v.name + "» сломана — дом больше не ваш.");
                    continue;
                }
                for (VillagerEntity e : all) {
                    if (e.getUuid().equals(house.tenant)) continue;
                    Optional<GlobalPos> h = home(e);
                    if (h.isPresent() && h.get().getPos().equals(house.bed)) e.getBrain().forget(MemoryModuleType.HOME);
                }
                lock(poi, house.bed);
            }
        }
    }

    /** Занять «место» кровати, чтобы её не забрал случайный житель. */
    public static void lock(PointOfInterestStorage poi, BlockPos bed) {
        if (poi.getFreeTickets(bed) > 0) {
            poi.getPosition(e -> e.matchesKey(PointOfInterestTypes.HOME), (e, p) -> p.equals(bed), bed, 1);
        }
    }

    // ----------------------------------------------------------- выборы

    public static void elections(ServerWorld w, EcoState s, long day) {
        for (Village v : s.villages) {
            if (day >= v.nextElection && loaded(w, v)) hold(w, s, v, day);
        }
    }

    private static final class Cand {
        final UUID player;
        final VillagerEntity villager;
        final String name;
        final boolean incumbent;
        int votes;

        Cand(UUID player, VillagerEntity villager, String name, boolean incumbent) {
            this.player = player;
            this.villager = villager;
            this.name = name;
            this.incumbent = incumbent;
        }
    }

    public static void hold(ServerWorld w, EcoState s, Village v, long day) {
        Random r = w.getRandom();
        MinecraftServer server = w.getServer();
        List<VillagerEntity> voters = new ArrayList<>(villagers(w, v));
        voters.removeIf(VillagerEntity::isBaby);
        List<Cand> cands = new ArrayList<>();

        if (v.mayorPlayer != null) cands.add(new Cand(v.mayorPlayer, null, v.mayorName, true));
        else if (v.mayorVillager != null && w.getEntity(v.mayorVillager) instanceof VillagerEntity mv)
            cands.add(new Cand(null, mv, v.mayorName, true));
        for (UUID c : v.candidates) {
            if (c.equals(v.mayorPlayer)) continue;
            ServerPlayerEntity cp = server.getPlayerManager().getPlayer(c);
            cands.add(new Cand(c, null, cp != null ? cp.getGameProfile().getName() : "Игрок", false));
        }
        // против мэра-игрока народ выдвигает своего
        if (v.mayorPlayer != null || cands.isEmpty()) {
            List<VillagerEntity> pool = new ArrayList<>(voters);
            pool.removeIf(e -> Hires.byEntity(s, e.getUuid()) != null);
            if (!pool.isEmpty()) {
                VillagerEntity ch = pool.get(r.nextInt(pool.size()));
                cands.add(new Cand(null, ch, info(s, ch).name, false));
            }
        }

        v.nextElection = day + 7;
        if (cands.isEmpty()) return;
        if (cands.size() == 1 || voters.isEmpty()) {
            broadcast(w, v, Ui.t("Выборы в «" + v.name + "»: мэр " + cands.get(0).name + " остаётся у власти — соперников нет.", Formatting.YELLOW));
            v.candidates.clear();
            v.campaign.clear();
            return;
        }

        for (VillagerEntity voter : voters) {
            Cand best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Cand c : cands) {
                int score = r.nextInt(21) - 10;
                if (c.player == null) {
                    if (c.incumbent) score += 30 + (v.kind ? 15 : 0) - v.tax / 2 + (v.treasury >= 50 ? 5 : 0);
                    else score += 22 + (v.tax > 20 ? 15 : 0);
                } else {
                    score += v.rep(c.player) + 4 * v.campaign.getOrDefault(c.player, 0) + 2 * v.fear(c.player);
                    ServerPlayerEntity cp = server.getPlayerManager().getPlayer(c.player);
                    int wealth = cp != null ? Money.wealth(cp) : s.player(c.player).deposit;
                    score += 6 * Money.tier(wealth);
                    if (c.incumbent) score += 10 - v.tax / 2;
                }
                if (score > bestScore || (score == bestScore && c.incumbent)) {
                    bestScore = score;
                    best = c;
                }
            }
            if (best != null) best.votes++;
        }

        Cand win = cands.get(0);
        for (Cand c : cands) if (c.votes > win.votes || (c.votes == win.votes && c.incumbent)) win = c;

        MutableText res = Ui.t("━━ Итоги выборов в «" + v.name + "» ━━", Formatting.GOLD);
        for (Cand c : cands) {
            res.append(Ui.t("\n " + c.name + (c.player != null ? " (игрок)" : " (житель)") + ": " + c.votes + " гол.",
                    c == win ? Formatting.GREEN : Formatting.GRAY));
        }
        boolean changed = !win.incumbent;
        res.append(Ui.t("\n" + (changed ? "Новый мэр: " : "Мэр переизбран: ") + win.name, Formatting.YELLOW));
        broadcast(w, v, res);

        if (changed) {
            if (win.player != null) setPlayerMayor(w, s, v, win.player, win.name);
            else setVillagerMayor(w, s, v, win.villager);
        }
        v.candidates.clear();
        v.campaign.clear();
    }

    // ------------------------------------------------- полоса сверху экрана

    public static void updateBars(ServerWorld w, EcoState s, long day) {
        MinecraftServer server = w.getServer();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Village in = p.getServerWorld() == w ? at(s, p.getBlockPos()) : null;
            for (Map.Entry<Integer, ServerBossBar> e : BARS.entrySet()) {
                if (in == null || e.getKey() != in.id) e.getValue().removePlayer(p);
            }
            if (in != null) bar(in).addPlayer(p);
        }
        for (ServerBossBar b : BARS.values()) {
            for (ServerPlayerEntity bp : new ArrayList<>(b.getPlayers())) if (bp.isDisconnected()) b.removePlayer(bp);
        }
        long tod = w.getTimeOfDay();
        for (Village v : s.villages) {
            ServerBossBar b = BARS.get(v.id);
            if (b == null) continue;
            long left = v.nextElection * 24000L - tod;
            String mayor;
            if (v.mayorPlayer != null) mayor = v.mayorName + " (игрок)";
            else if (v.mayorVillager != null) mayor = v.mayorName + (v.kind ? " (добрый)" : " (высокомерный)");
            else mayor = "нет";
            b.setName(Ui.t(v.name + " │ Мэр: " + mayor + " │ Казна: " + v.treasury + " │ Налог: " + v.tax
                    + "% │ До выборов: " + Ui.time(left)));
            b.setPercent(Math.max(0f, Math.min(1f, 1f - left / (7f * 24000f))));
            b.setColor(v.mayorPlayer != null ? BossBar.Color.YELLOW : (v.kind ? BossBar.Color.GREEN : BossBar.Color.RED));
        }
    }

    private static ServerBossBar bar(Village v) {
        return BARS.computeIfAbsent(v.id, k -> new ServerBossBar(Ui.t(v.name), BossBar.Color.GREEN, BossBar.Style.NOTCHED_10));
    }

    public static void clearBars() {
        for (ServerBossBar b : BARS.values()) b.clearPlayers();
        BARS.clear();
    }
}
