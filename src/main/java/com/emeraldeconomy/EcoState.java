package com.emeraldeconomy;

import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Всё сохраняемое состояние мода (хранится в мире, в data/emeraldeconomy.dat).
 */
public class EcoState extends PersistentState {
    public final Map<UUID, VInfo> villagers = new HashMap<>();
    public final List<Village> villages = new ArrayList<>();
    public final Map<UUID, PInfo> players = new HashMap<>();
    public final List<Hire> hires = new ArrayList<>();
    public long lastDay = -1;
    public int nextVillageId = 1;

    private static EcoState cached;
    private static MinecraftServer cachedServer;

    public static EcoState get(MinecraftServer server) {
        if (cached != null && cachedServer == server) return cached;
        cached = server.getOverworld().getPersistentStateManager()
                .getOrCreate(EcoState::fromNbt, EcoState::new, EmeraldEconomy.MOD_ID);
        cachedServer = server;
        return cached;
    }

    public static void reset() {
        cached = null;
        cachedServer = null;
    }

    @Override
    public boolean isDirty() {
        return true;
    }

    public PInfo player(UUID id) {
        return players.computeIfAbsent(id, k -> new PInfo());
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putLong("lastDay", lastDay);
        nbt.putInt("nextVillageId", nextVillageId);
        NbtCompound vs = new NbtCompound();
        for (Map.Entry<UUID, VInfo> e : villagers.entrySet()) vs.put(e.getKey().toString(), e.getValue().toNbt());
        nbt.put("villagers", vs);
        NbtList vl = new NbtList();
        for (Village v : villages) vl.add(v.toNbt());
        nbt.put("villages", vl);
        NbtCompound ps = new NbtCompound();
        for (Map.Entry<UUID, PInfo> e : players.entrySet()) ps.put(e.getKey().toString(), e.getValue().toNbt());
        nbt.put("players", ps);
        NbtList hl = new NbtList();
        for (Hire h : hires) hl.add(h.toNbt());
        nbt.put("hires", hl);
        return nbt;
    }

    public static EcoState fromNbt(NbtCompound nbt) {
        EcoState s = new EcoState();
        s.lastDay = nbt.getLong("lastDay");
        s.nextVillageId = Math.max(1, nbt.getInt("nextVillageId"));
        NbtCompound vs = nbt.getCompound("villagers");
        for (String k : vs.getKeys()) {
            try {
                s.villagers.put(UUID.fromString(k), VInfo.fromNbt(vs.getCompound(k)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        NbtList vl = nbt.getList("villages", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < vl.size(); i++) s.villages.add(Village.fromNbt(vl.getCompound(i)));
        NbtCompound ps = nbt.getCompound("players");
        for (String k : ps.getKeys()) {
            try {
                s.players.put(UUID.fromString(k), PInfo.fromNbt(ps.getCompound(k)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        NbtList hl = nbt.getList("hires", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < hl.size(); i++) s.hires.add(Hire.fromNbt(hl.getCompound(i)));
        return s;
    }

    // ---------------------------------------------------------------- utils

    static void putUuid(NbtCompound n, String key, UUID id) {
        if (id != null) n.putUuid(key, id);
    }

    static UUID getUuid(NbtCompound n, String key) {
        return n.containsUuid(key) ? n.getUuid(key) : null;
    }

    static void putPos(NbtCompound n, String key, BlockPos p) {
        if (p != null) n.putLong(key, p.asLong());
    }

    static BlockPos getPos(NbtCompound n, String key) {
        return n.contains(key) ? BlockPos.fromLong(n.getLong(key)) : null;
    }

    static NbtCompound intMap(Map<UUID, Integer> m) {
        NbtCompound c = new NbtCompound();
        for (Map.Entry<UUID, Integer> e : m.entrySet()) c.putInt(e.getKey().toString(), e.getValue());
        return c;
    }

    static void readIntMap(NbtCompound c, Map<UUID, Integer> m) {
        for (String k : c.getKeys()) {
            try {
                m.put(UUID.fromString(k), c.getInt(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // ============================================================ VInfo

    /** Данные одного жителя. */
    public static class VInfo {
        public String name = "Житель";
        public int wallet;
        public int hungry;
        public int housePrice = 20;
        public UUID landlord;          // игрок, у которого житель снимает дом

        public UUID begPlayer;
        public int begAmount;
        public long begExpire;

        public UUID questPlayer;
        public String questItem = "";
        public int questCount;
        public int questReward;
        public long questExpire;

        NbtCompound toNbt() {
            NbtCompound n = new NbtCompound();
            n.putString("name", name);
            n.putInt("wallet", wallet);
            n.putInt("hungry", hungry);
            n.putInt("housePrice", housePrice);
            putUuid(n, "landlord", landlord);
            putUuid(n, "begPlayer", begPlayer);
            n.putInt("begAmount", begAmount);
            n.putLong("begExpire", begExpire);
            putUuid(n, "questPlayer", questPlayer);
            n.putString("questItem", questItem);
            n.putInt("questCount", questCount);
            n.putInt("questReward", questReward);
            n.putLong("questExpire", questExpire);
            return n;
        }

        static VInfo fromNbt(NbtCompound n) {
            VInfo i = new VInfo();
            i.name = n.getString("name");
            i.wallet = n.getInt("wallet");
            i.hungry = n.getInt("hungry");
            i.housePrice = n.getInt("housePrice");
            i.landlord = getUuid(n, "landlord");
            i.begPlayer = getUuid(n, "begPlayer");
            i.begAmount = n.getInt("begAmount");
            i.begExpire = n.getLong("begExpire");
            i.questPlayer = getUuid(n, "questPlayer");
            i.questItem = n.getString("questItem");
            i.questCount = n.getInt("questCount");
            i.questReward = n.getInt("questReward");
            i.questExpire = n.getLong("questExpire");
            return i;
        }
    }

    // ========================================================== Village

    /** Деревня: мэр, казна, банк, выборы, отношение к игрокам. */
    public static class Village {
        public int id;
        public String name = "Деревня";
        public BlockPos center = BlockPos.ORIGIN;

        public UUID mayorVillager;
        public UUID mayorPlayer;
        public String mayorName = "";
        public boolean kind = true;

        public int treasury;
        public int tax = 10;
        public int salary = 1;
        public long nextElection;

        public BlockPos bankEntrance;
        public BlockPos vault;
        public BlockPos house;
        public BlockPos mayorBed;
        public int houseTier = -1;
        public boolean needHome;
        public int bankAttempts;
        public int houseAttempts;
        public long robbedDay = -100;

        public final List<UUID> guards = new ArrayList<>();
        public final Map<UUID, Integer> rep = new HashMap<>();
        public final Map<UUID, Integer> fear = new HashMap<>();
        public final Map<UUID, Integer> campaign = new HashMap<>();
        public final Map<UUID, Long> wanted = new HashMap<>();
        public final Set<UUID> candidates = new HashSet<>();

        public transient ServerBossBar bar;

        public int rep(UUID p) {
            return rep.getOrDefault(p, 0);
        }

        public void addRep(UUID p, int d) {
            rep.put(p, Math.max(-100, Math.min(100, rep(p) + d)));
        }

        public int fear(UUID p) {
            return fear.getOrDefault(p, 0);
        }

        public void addFear(UUID p, int d) {
            fear.put(p, Math.max(0, Math.min(100, fear(p) + d)));
        }

        public boolean isWanted(UUID p, long now) {
            Long t = wanted.get(p);
            return t != null && t > now;
        }

        public boolean hasMayor() {
            return mayorVillager != null || mayorPlayer != null;
        }

        NbtCompound toNbt() {
            NbtCompound n = new NbtCompound();
            n.putInt("id", id);
            n.putString("name", name);
            putPos(n, "center", center);
            putUuid(n, "mayorVillager", mayorVillager);
            putUuid(n, "mayorPlayer", mayorPlayer);
            n.putString("mayorName", mayorName);
            n.putBoolean("kind", kind);
            n.putInt("treasury", treasury);
            n.putInt("tax", tax);
            n.putInt("salary", salary);
            n.putLong("nextElection", nextElection);
            putPos(n, "bankEntrance", bankEntrance);
            putPos(n, "vault", vault);
            putPos(n, "house", house);
            putPos(n, "mayorBed", mayorBed);
            n.putInt("houseTier", houseTier);
            n.putBoolean("needHome", needHome);
            n.putInt("bankAttempts", bankAttempts);
            n.putInt("houseAttempts", houseAttempts);
            n.putLong("robbedDay", robbedDay);
            NbtList g = new NbtList();
            for (UUID u : guards) g.add(NbtString.of(u.toString()));
            n.put("guards", g);
            n.put("rep", intMap(rep));
            n.put("fear", intMap(fear));
            n.put("campaign", intMap(campaign));
            NbtCompound w = new NbtCompound();
            for (Map.Entry<UUID, Long> e : wanted.entrySet()) w.putLong(e.getKey().toString(), e.getValue());
            n.put("wanted", w);
            NbtList c = new NbtList();
            for (UUID u : candidates) c.add(NbtString.of(u.toString()));
            n.put("candidates", c);
            return n;
        }

        static Village fromNbt(NbtCompound n) {
            Village v = new Village();
            v.id = n.getInt("id");
            v.name = n.getString("name");
            BlockPos c = getPos(n, "center");
            v.center = c == null ? BlockPos.ORIGIN : c;
            v.mayorVillager = getUuid(n, "mayorVillager");
            v.mayorPlayer = getUuid(n, "mayorPlayer");
            v.mayorName = n.getString("mayorName");
            v.kind = n.getBoolean("kind");
            v.treasury = n.getInt("treasury");
            v.tax = n.getInt("tax");
            v.salary = n.getInt("salary");
            v.nextElection = n.getLong("nextElection");
            v.bankEntrance = getPos(n, "bankEntrance");
            v.vault = getPos(n, "vault");
            v.house = getPos(n, "house");
            v.mayorBed = getPos(n, "mayorBed");
            v.houseTier = n.getInt("houseTier");
            v.needHome = n.getBoolean("needHome");
            v.bankAttempts = n.getInt("bankAttempts");
            v.houseAttempts = n.getInt("houseAttempts");
            v.robbedDay = n.contains("robbedDay") ? n.getLong("robbedDay") : -100;
            NbtList g = n.getList("guards", NbtElement.STRING_TYPE);
            for (int i = 0; i < g.size(); i++) {
                try {
                    v.guards.add(UUID.fromString(g.getString(i)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            readIntMap(n.getCompound("rep"), v.rep);
            readIntMap(n.getCompound("fear"), v.fear);
            readIntMap(n.getCompound("campaign"), v.campaign);
            NbtCompound w = n.getCompound("wanted");
            for (String k : w.getKeys()) {
                try {
                    v.wanted.put(UUID.fromString(k), w.getLong(k));
                } catch (IllegalArgumentException ignored) {
                }
            }
            NbtList cl = n.getList("candidates", NbtElement.STRING_TYPE);
            for (int i = 0; i < cl.size(); i++) {
                try {
                    v.candidates.add(UUID.fromString(cl.getString(i)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return v;
        }
    }

    // ============================================================ PInfo

    /** Данные игрока: вклад, кредит, дома. */
    public static class PInfo {
        public int deposit;
        public int loan;
        public long loanDue;
        public final List<House> houses = new ArrayList<>();
        public long nextApproach;
        public UUID pendingHit;       // исполнитель, ждущий отметки цели
        public long pendingHitUntil;
        public UUID collector;

        NbtCompound toNbt() {
            NbtCompound n = new NbtCompound();
            n.putInt("deposit", deposit);
            n.putInt("loan", loan);
            n.putLong("loanDue", loanDue);
            NbtList hl = new NbtList();
            for (House h : houses) hl.add(h.toNbt());
            n.put("houses", hl);
            putUuid(n, "collector", collector);
            return n;
        }

        static PInfo fromNbt(NbtCompound n) {
            PInfo p = new PInfo();
            p.deposit = n.getInt("deposit");
            p.loan = n.getInt("loan");
            p.loanDue = n.getLong("loanDue");
            NbtList hl = n.getList("houses", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < hl.size(); i++) p.houses.add(House.fromNbt(hl.getCompound(i)));
            p.collector = getUuid(n, "collector");
            return p;
        }
    }

    /** Дом, купленный игроком у жителя. */
    public static class House {
        public BlockPos bed;
        public int villageId;
        public UUID tenant;

        NbtCompound toNbt() {
            NbtCompound n = new NbtCompound();
            putPos(n, "bed", bed);
            n.putInt("village", villageId);
            putUuid(n, "tenant", tenant);
            return n;
        }

        static House fromNbt(NbtCompound n) {
            House h = new House();
            h.bed = getPos(n, "bed");
            h.villageId = n.getInt("village");
            h.tenant = getUuid(n, "tenant");
            return h;
        }
    }

    // ============================================================= Hire

    /** Нанятый житель или голем. */
    public static class Hire {
        public static final int GUARD = 0, HIT = 1, FOLLOW = 2, COLLECTOR = 3;

        public UUID entity;
        public UUID owner;
        public String ownerName = "";
        public int mode;
        public UUID target;
        public String targetName = "";
        public long expires;
        public boolean villager;
        public transient UUID threat;

        NbtCompound toNbt() {
            NbtCompound n = new NbtCompound();
            putUuid(n, "entity", entity);
            putUuid(n, "owner", owner);
            n.putString("ownerName", ownerName);
            n.putInt("mode", mode);
            putUuid(n, "target", target);
            n.putString("targetName", targetName);
            n.putLong("expires", expires);
            n.putBoolean("villager", villager);
            return n;
        }

        static Hire fromNbt(NbtCompound n) {
            Hire h = new Hire();
            h.entity = getUuid(n, "entity");
            h.owner = getUuid(n, "owner");
            h.ownerName = n.getString("ownerName");
            h.mode = n.getInt("mode");
            h.target = getUuid(n, "target");
            h.targetName = n.getString("targetName");
            h.expires = n.getLong("expires");
            h.villager = n.getBoolean("villager");
            return h;
        }
    }
}
