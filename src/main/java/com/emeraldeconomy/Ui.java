package com.emeraldeconomy;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Сообщения в чат и кликабельные кнопки.
 */
public final class Ui {
    private Ui() {
    }

    public static MutableText t(String s) {
        return Text.literal(s);
    }

    public static MutableText t(String s, Formatting f) {
        return Text.literal(s).formatted(f);
    }

    public static void msg(ServerPlayerEntity p, String s) {
        p.sendMessage(t(s, Formatting.GRAY), false);
    }

    public static void ok(ServerPlayerEntity p, String s) {
        p.sendMessage(t(s, Formatting.GREEN), false);
    }

    public static void bad(ServerPlayerEntity p, String s) {
        p.sendMessage(t(s, Formatting.RED), false);
    }

    public static void bar(ServerPlayerEntity p, Text s) {
        p.sendMessage(s, true);
    }

    /** Реплика жителя: [Иван, фермер] текст. */
    public static MutableText say(String who, String line) {
        return t("[" + who + "] ", Formatting.GOLD).append(t(line, Formatting.WHITE));
    }

    public static MutableText button(String label, String command, Formatting color, String hover) {
        return Text.literal("[" + label + "]").styled(st -> st
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
    }

    public static MutableText sp() {
        return Text.literal(" ");
    }

    public static String time(long ticks) {
        if (ticks <= 0) return "сегодня";
        long days = ticks / 24000L;
        long hours = (ticks % 24000L) / 1000L;
        if (days > 0) return days + " дн. " + hours + " ч.";
        return hours + " ч.";
    }
}
