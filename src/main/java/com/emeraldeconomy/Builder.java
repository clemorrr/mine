package com.emeraldeconomy;

import com.emeraldeconomy.EcoState.Village;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.WallMountLocation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.Iterator;
import java.util.UUID;

/**
 * Постройки: подземный банк и дом мэра (три уровня богатства).
 */
public final class Builder {
    private Builder() {
    }

    private static final int FLAGS = Block.NOTIFY_LISTENERS;

    private static void set(ServerWorld w, int x, int y, int z, BlockState s) {
        w.setBlockState(new BlockPos(x, y, z), s, FLAGS);
    }

    private static void set(ServerWorld w, int x, int y, int z, Block b) {
        set(w, x, y, z, b.getDefaultState());
    }

    private static void fill(ServerWorld w, int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                    w.setBlockState(new BlockPos(x, y, z), s, FLAGS);
    }

    private static void fill(ServerWorld w, int x1, int y1, int z1, int x2, int y2, int z2, Block b) {
        fill(w, x1, y1, z1, x2, y2, z2, b.getDefaultState());
    }

    // -------------------------------------------------------- поиск места

    private static boolean natural(BlockState s) {
        return s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.TERRACOTTA) || s.isOf(Blocks.SAND) || s.isOf(Blocks.RED_SAND)
                || s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.SNOW_BLOCK) || s.isOf(Blocks.SNOW) || s.isOf(Blocks.STONE)
                || s.isOf(Blocks.SANDSTONE) || s.isOf(Blocks.ANDESITE) || s.isOf(Blocks.DIORITE) || s.isOf(Blocks.GRANITE)
                || s.isOf(Blocks.ICE) || s.isOf(Blocks.PACKED_ICE);
    }

    private static int ground(ServerWorld w, int x, int z) {
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos below = new BlockPos(x, y - 1, z);
        if (!w.getFluidState(below).isEmpty()) return Integer.MIN_VALUE;
        if (!natural(w.getBlockState(below))) return Integer.MIN_VALUE;
        return y;
    }

    private static BlockPos findSpot(ServerWorld w, BlockPos c, int minR, int maxR, int half, int maxDiff, BlockPos avoid, int avoidDist) {
        for (int r = minR; r <= maxR; r += 2) {
            int steps = Math.max(8, r);
            for (int i = 0; i < steps; i++) {
                double a = i * 2 * Math.PI / steps + r * 0.37;
                int x = c.getX() + (int) Math.round(Math.cos(a) * r);
                int z = c.getZ() + (int) Math.round(Math.sin(a) * r);
                if (avoid != null) {
                    double dx = x - avoid.getX(), dz = z - avoid.getZ();
                    if (dx * dx + dz * dz < (double) avoidDist * avoidDist) continue;
                }
                int cy = ground(w, x, z);
                if (cy == Integer.MIN_VALUE) continue;
                boolean ok = true;
                for (int dx = -half; dx <= half && ok; dx++) {
                    for (int dz = -half; dz <= half; dz++) {
                        int y = ground(w, x + dx, z + dz);
                        if (y == Integer.MIN_VALUE || Math.abs(y - cy) > maxDiff) {
                            ok = false;
                            break;
                        }
                    }
                }
                if (ok) return new BlockPos(x, cy, z);
            }
        }
        return null;
    }

    private static void foundation(ServerWorld w, int x, int topY, int z, Block b) {
        for (int y = topY; y > topY - 10; y--) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState st = w.getBlockState(p);
            if (st.isAir() || st.isReplaceable() || !w.getFluidState(p).isEmpty()) w.setBlockState(p, b.getDefaultState(), FLAGS);
            else break;
        }
    }

    private static void sign(ServerWorld w, int x, int y, int z, Direction facing, String l1, String l2, String l3) {
        BlockPos p = new BlockPos(x, y, z);
        w.setBlockState(p, Blocks.OAK_WALL_SIGN.getDefaultState().with(Properties.HORIZONTAL_FACING, facing), FLAGS);
        BlockEntity be = w.getBlockEntity(p);
        if (be instanceof SignBlockEntity sbe) {
            SignText text = new SignText()
                    .withMessage(0, Text.literal(l1).formatted(Formatting.DARK_GREEN, Formatting.BOLD))
                    .withMessage(1, Text.literal(l2))
                    .withMessage(2, Text.literal(l3));
            sbe.setText(text, true);
            sbe.setWaxed(true);
        }
    }

    private static void door(ServerWorld w, int x, int y, int z, Block door, Direction facing) {
        BlockState base = door.getDefaultState().with(Properties.HORIZONTAL_FACING, facing).with(Properties.DOOR_HINGE, DoorHinge.LEFT);
        set(w, x, y, z, base.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        set(w, x, y + 1, z, base.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
    }

    // ----------------------------------------------------------------- банк

    public static boolean buildBank(ServerWorld w, Village v) {
        BlockPos e = findSpot(w, v.center, 5, 26, 2, 1, null, 0);
        if (e == null) return false;
        int x = e.getX(), s = e.getY(), z = e.getZ();
        int vy = s - 14;
        if (vy - 2 < w.getBottomY() + 2) return false;

        // будка банка на поверхности
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) foundation(w, x + dx, s - 2, z + dz, Blocks.COBBLESTONE);
        fill(w, x - 2, s - 1, z - 2, x + 2, s - 1, z + 2, Blocks.STONE_BRICKS);
        fill(w, x - 2, s, z - 2, x + 2, s + 3, z + 2, Blocks.STONE_BRICKS);
        fill(w, x - 1, s, z - 1, x + 1, s + 3, z + 1, Blocks.AIR);
        fill(w, x - 2, s + 4, z - 2, x + 2, s + 4, z + 2, Blocks.CHISELED_STONE_BRICKS);
        fill(w, x - 2, s + 5, z - 2, x + 2, s + 8, z + 2, Blocks.AIR);
        set(w, x, s + 5, z, Blocks.LANTERN);
        set(w, x - 2, s + 1, z, Blocks.GLASS);
        set(w, x + 2, s + 1, z, Blocks.GLASS);
        set(w, x - 1, s, z + 1, Blocks.LANTERN);
        door(w, x, s, z + 2, Blocks.IRON_DOOR, Direction.NORTH);
        BlockState button = Blocks.STONE_BUTTON.getDefaultState().with(Properties.WALL_MOUNT_LOCATION, WallMountLocation.WALL);
        set(w, x + 1, s + 1, z + 3, button.with(Properties.HORIZONTAL_FACING, Direction.SOUTH));
        set(w, x + 1, s + 1, z + 1, button.with(Properties.HORIZONTAL_FACING, Direction.NORTH));
        sign(w, x, s + 2, z + 3, Direction.SOUTH, "БАНК", v.name, "Хранилище внизу");

        // хранилище: камень + обсидиан, комната 9x9
        fill(w, x - 6, vy - 1, z - 1, x + 6, vy + 7, z + 11, Blocks.STONE_BRICKS);
        fill(w, x - 5, vy, z, x + 5, vy + 6, z + 10, Blocks.OBSIDIAN);
        fill(w, x - 4, vy + 1, z + 1, x + 4, vy + 5, z + 9, Blocks.AIR);
        fill(w, x - 4, vy, z + 1, x + 4, vy, z + 9, Blocks.POLISHED_ANDESITE);

        // шахта с лестницей (внутри северной стены)
        BlockState ladder = Blocks.LADDER.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.SOUTH);
        for (int y = vy + 1; y <= s + 1; y++) {
            set(w, x, y, z - 1, Blocks.STONE_BRICKS);
            if (y > vy + 6 && y < s - 1) {
                set(w, x - 1, y, z, Blocks.STONE_BRICKS);
                set(w, x + 1, y, z, Blocks.STONE_BRICKS);
                set(w, x, y, z + 1, Blocks.STONE_BRICKS);
            }
            set(w, x, y, z, ladder);
        }

        // убранство и сейф
        int[][] corners = {{x - 4, z + 1}, {x + 4, z + 1}, {x - 4, z + 9}, {x + 4, z + 9}};
        for (int[] c : corners) {
            set(w, c[0], vy + 1, c[1], Blocks.GOLD_BLOCK);
            set(w, c[0], vy + 2, c[1], Blocks.LANTERN);
        }
        set(w, x, vy + 1, z + 6, Blocks.CHEST.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.NORTH));
        set(w, x - 1, vy + 1, z + 6, Blocks.IRON_BLOCK);
        set(w, x + 1, vy + 1, z + 6, Blocks.IRON_BLOCK);
        set(w, x, vy + 1, z + 7, Blocks.IRON_BLOCK);
        set(w, x, vy + 2, z + 7, Blocks.LANTERN);
        set(w, x - 3, vy + 1, z + 3, Blocks.IRON_BARS);
        set(w, x + 3, vy + 1, z + 3, Blocks.IRON_BARS);

        v.bankEntrance = e.toImmutable();
        v.vault = new BlockPos(x, vy + 1, z + 6);
        v.guards.clear();
        ensureGuards(w, v);
        EmeraldEconomy.LOG.info("Банк деревни {} построен у {}", v.name, e);
        return true;
    }

    public static void ensureGuards(ServerWorld w, Village v) {
        if (v.vault == null) return;
        Iterator<UUID> it = v.guards.iterator();
        while (it.hasNext()) {
            Entity g = w.getEntity(it.next());
            if (g == null || !g.isAlive()) it.remove();
        }
        int[] offs = {-2, 2, 0};
        while (v.guards.size() < 2) {
            IronGolemEntity g = EntityType.IRON_GOLEM.create(w);
            if (g == null) return;
            int ox = offs[v.guards.size() % offs.length];
            g.refreshPositionAndAngles(v.vault.getX() + ox + 0.5, v.vault.getY(), v.vault.getZ() - 2 + 0.5, 0f, 0f);
            g.setCustomName(Text.literal("Охрана банка").formatted(Formatting.DARK_AQUA));
            g.setPersistent();
            w.spawnEntity(g);
            v.guards.add(g.getUuid());
        }
    }

    public static void leashGuards(ServerWorld w, Village v) {
        for (UUID id : v.guards) {
            Entity e = w.getEntity(id);
            if (e instanceof IronGolemEntity g && g.isAlive() && g.getTarget() == null
                    && g.squaredDistanceTo(v.vault.getX() + 0.5, v.vault.getY(), v.vault.getZ() + 0.5) > 25) {
                g.getNavigation().startMovingTo(v.vault.getX() + 0.5, v.vault.getY(), v.vault.getZ() - 1.5, 0.8);
            }
        }
    }

    // ------------------------------------------------------------ дом мэра

    private record Palette(Block floor, Block wall, Block base, Block corner, Block roof, Block trim, Block door,
                           Block bed, Block light, int height) {
    }

    private static Palette palette(int tier) {
        return switch (tier) {
            case 2 -> new Palette(Blocks.POLISHED_ANDESITE, Blocks.QUARTZ_BLOCK, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_PILLAR,
                    Blocks.QUARTZ_BLOCK, Blocks.GOLD_BLOCK, Blocks.DARK_OAK_DOOR, Blocks.RED_BED, Blocks.LANTERN, 5);
            case 1 -> new Palette(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.STONE_BRICKS, Blocks.STRIPPED_SPRUCE_LOG,
                    Blocks.DEEPSLATE_TILES, Blocks.STONE_BRICKS, Blocks.SPRUCE_DOOR, Blocks.BLUE_BED, Blocks.LANTERN, 4);
            default -> new Palette(Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.OAK_LOG,
                    Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.WHITE_BED, Blocks.TORCH, 4);
        };
    }

    /**
     * Строит (или перестраивает) дом мэра. origin == null — найти место рядом с деревней.
     */
    public static boolean buildMayorHouse(ServerWorld w, Village v, BlockPos origin, int tier) {
        BlockPos o = origin;
        if (o == null) {
            o = findSpot(w, v.center, 12, 48, 5, 2, v.bankEntrance, 12);
            if (o == null) return false;
        }
        Palette pal = palette(tier);
        int x = o.getX(), s = o.getY(), z = o.getZ(), h = pal.height();

        fill(w, x - 5, s, z - 5, x + 5, s + 10, z + 5, Blocks.AIR);
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) foundation(w, x + dx, s - 2, z + dz, Blocks.COBBLESTONE);
        fill(w, x - 5, s - 1, z - 5, x + 5, s - 1, z + 5, tier == 2 ? Blocks.SMOOTH_STONE : Blocks.COBBLESTONE);
        fill(w, x - 4, s - 1, z - 4, x + 4, s - 1, z + 4, pal.floor());

        // стены
        for (int y = s; y < s + h; y++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    boolean edgeX = Math.abs(dx) == 4, edgeZ = Math.abs(dz) == 4;
                    if (!edgeX && !edgeZ) continue;
                    Block b = (edgeX && edgeZ) ? pal.corner() : (y == s ? pal.base() : pal.wall());
                    set(w, x + dx, y, z + dz, b);
                }
            }
        }
        // крыша
        fill(w, x - 4, s + h, z - 4, x + 4, s + h, z + 4, pal.roof());
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) == 4 || Math.abs(dz) == 4) set(w, x + dx, s + h + 1, z + dz, pal.trim());
            }
        }
        if (tier == 2) {
            set(w, x - 4, s + h + 2, z - 4, Blocks.GOLD_BLOCK);
            set(w, x + 4, s + h + 2, z - 4, Blocks.GOLD_BLOCK);
            set(w, x - 4, s + h + 2, z + 4, Blocks.GOLD_BLOCK);
            set(w, x + 4, s + h + 2, z + 4, Blocks.GOLD_BLOCK);
        }
        // окна
        int winTop = s + (h >= 5 ? 2 : 1);
        for (int y = s + 1; y <= winTop; y++) {
            for (int d : new int[]{-2, 2}) {
                set(w, x + d, y, z - 4, Blocks.GLASS);
                set(w, x + d, y, z + 4, Blocks.GLASS);
                set(w, x - 4, y, z + d, Blocks.GLASS);
                set(w, x + 4, y, z + d, Blocks.GLASS);
            }
        }
        // дверь
        door(w, x, s, z + 4, pal.door(), Direction.NORTH);
        // кровать мэра
        BlockState bed = pal.bed().getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.NORTH);
        set(w, x - 2, s, z - 2, bed.with(Properties.BED_PART, BedPart.FOOT));
        set(w, x - 2, s, z - 3, bed.with(Properties.BED_PART, BedPart.HEAD));
        // свет
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) set(w, x + c[0], s, z + c[1], pal.light());
        // мебель
        switch (tier) {
            case 2 -> {
                for (int dx = 1; dx <= 2; dx++) {
                    set(w, x + dx, s, z - 3, Blocks.BOOKSHELF);
                    set(w, x + dx, s + 1, z - 3, Blocks.BOOKSHELF);
                }
                set(w, x, s, z - 3, Blocks.QUARTZ_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.NORTH));
                set(w, x - 1, s, z - 3, Blocks.GOLD_BLOCK);
                set(w, x + 3, s, z - 1, Blocks.ENCHANTING_TABLE);
                set(w, x - 3, s, z, Blocks.EMERALD_BLOCK);
                set(w, x - 3, s + 1, z, Blocks.LANTERN);
            }
            case 1 -> {
                for (int dx = 1; dx <= 3; dx++) set(w, x + dx, s, z - 3, Blocks.BOOKSHELF);
                set(w, x + 3, s, z, Blocks.CRAFTING_TABLE);
                set(w, x - 3, s, z, Blocks.BARREL);
            }
            default -> {
                set(w, x + 2, s, z - 3, Blocks.CRAFTING_TABLE);
                set(w, x + 3, s, z - 3, Blocks.BARREL);
            }
        }
        // ковёр
        if (tier > 0) {
            Block carpet = tier == 2 ? Blocks.RED_CARPET : Blocks.BLUE_CARPET;
            int r = tier == 2 ? 3 : 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = new BlockPos(x + dx, s, z + dz);
                    if (w.getBlockState(p).isAir()) w.setBlockState(p, carpet.getDefaultState(), FLAGS);
                }
            }
        }
        String[] kind = {"Скромный", "Добротный", "Роскошный"};
        sign(w, x + 1, s + 1, z + 5, Direction.SOUTH, "ДОМ МЭРА", v.name, kind[Math.max(0, Math.min(2, tier))]);

        v.house = o.toImmutable();
        v.houseTier = tier;
        v.mayorBed = new BlockPos(x - 2, s, z - 3);
        v.needHome = true;
        return true;
    }
}
