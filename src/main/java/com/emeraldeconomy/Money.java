package com.emeraldeconomy;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Изумруды в инвентаре, богатство и уровни уважения.
 */
public final class Money {
    private Money() {
    }

    public static final String[] TIERS = {"Бродяга", "Горожанин", "Уважаемый", "Богач", "Магнат"};
    public static final int[] THRESH = {0, 16, 64, 192, 576};
    /** Бонус к «доброй славе» у жителей (скидки) за уровень. */
    public static final int[] DISCOUNT = {0, 15, 40, 80, 150};

    public static final int HIRE_COST = 30;
    public static final int MAYOR_HIT_EXTRA = 60;

    public static int count(PlayerEntity p) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.EMERALD)) n += s.getCount();
            else if (s.isOf(Items.EMERALD_BLOCK)) n += s.getCount() * 9;
        }
        return n;
    }

    /** Забрать изумруды из инвентаря. В творческом режиме бесплатно. */
    public static boolean take(PlayerEntity p, int amount) {
        if (amount <= 0) return true;
        if (p.isCreative()) return true;
        if (count(p) < amount) return false;
        PlayerInventory inv = p.getInventory();
        int left = amount;
        for (int i = 0; i < inv.size() && left > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.EMERALD)) {
                int d = Math.min(left, s.getCount());
                s.decrement(d);
                left -= d;
            }
        }
        for (int i = 0; i < inv.size() && left > 0; i++) {
            ItemStack s = inv.getStack(i);
            while (left > 0 && s.isOf(Items.EMERALD_BLOCK) && s.getCount() > 0) {
                s.decrement(1);
                left -= 9;
            }
        }
        if (left < 0) give(p, -left);
        inv.markDirty();
        return true;
    }

    public static void give(PlayerEntity p, int amount) {
        while (amount > 0) {
            int c = Math.min(64, amount);
            p.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, c));
            amount -= c;
        }
    }

    public static int wealth(ServerPlayerEntity p) {
        EcoState.PInfo i = EcoState.get(p.getServer()).player(p.getUuid());
        return count(p) + i.deposit - i.loan;
    }

    public static int tier(int wealth) {
        int t = 0;
        for (int i = 0; i < THRESH.length; i++) if (wealth >= THRESH[i]) t = i;
        return t;
    }

    public static int tierOf(ServerPlayerEntity p) {
        return tier(wealth(p));
    }

    /** «5 изумрудов», «1 изумруд», «3 изумруда». */
    public static String em(int n) {
        int a = Math.abs(n) % 100, b = a % 10;
        String w;
        if (a > 10 && a < 20) w = "изумрудов";
        else if (b == 1) w = "изумруд";
        else if (b >= 2 && b <= 4) w = "изумруда";
        else w = "изумрудов";
        return n + " " + w;
    }
}
