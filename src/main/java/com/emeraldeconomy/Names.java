package com.emeraldeconomy;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.random.Random;
import net.minecraft.village.VillagerProfession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Имена, профессии, задания и реплики.
 */
public final class Names {
    private Names() {
    }

    private static final String[] PEOPLE = {
            "Иван", "Пётр", "Фёдор", "Степан", "Гаврила", "Еремей", "Тихон", "Прохор", "Кузьма", "Савелий",
            "Матвей", "Никифор", "Остап", "Емельян", "Игнат", "Захар", "Демьян", "Харитон", "Лука", "Богдан",
            "Марфа", "Агафья", "Прасковья", "Варвара", "Дарья", "Аксинья", "Василиса", "Ульяна", "Фёкла", "Глафира",
            "Евдокия", "Пелагея", "Устинья", "Лукерья", "Арина", "Настасья", "Авдотья", "Матрёна", "Ефросинья", "Марья"
    };

    private static final String[] VILLAGES = {
            "Изумрудовка", "Берёзовка", "Липовка", "Сосновка", "Ольховка", "Дубравка", "Озёрное", "Луговое",
            "Ручейки", "Камышино", "Зелёный Дол", "Медовка", "Калиновка", "Рябиновка", "Подгорное", "Тихий Яр",
            "Светлый Бор", "Малиновка", "Овражки", "Кленовка", "Глухово", "Звонарёво", "Пшеничное", "Хуторок"
    };

    private static final Map<String, String> PROF = new HashMap<>();

    static {
        PROF.put("none", "безработный");
        PROF.put("armorer", "бронник");
        PROF.put("butcher", "мясник");
        PROF.put("cartographer", "картограф");
        PROF.put("cleric", "священник");
        PROF.put("farmer", "фермер");
        PROF.put("fisherman", "рыбак");
        PROF.put("fletcher", "лучник");
        PROF.put("leatherworker", "кожевник");
        PROF.put("librarian", "библиотекарь");
        PROF.put("mason", "каменщик");
        PROF.put("nitwit", "бездельник");
        PROF.put("shepherd", "пастух");
        PROF.put("toolsmith", "инструментальщик");
        PROF.put("weaponsmith", "оружейник");
    }

    public static String person(Random r) {
        return PEOPLE[r.nextInt(PEOPLE.length)];
    }

    public static String village(Random r) {
        return VILLAGES[r.nextInt(VILLAGES.length)];
    }

    public static String profId(VillagerEntity v) {
        return Registries.VILLAGER_PROFESSION.getId(v.getVillagerData().getProfession()).getPath();
    }

    public static String prof(VillagerEntity v) {
        return PROF.getOrDefault(profId(v), "житель");
    }

    public static boolean employed(VillagerEntity v) {
        VillagerProfession p = v.getVillagerData().getProfession();
        return p != VillagerProfession.NONE && p != VillagerProfession.NITWIT;
    }

    public static boolean foodMaker(VillagerEntity v) {
        String id = profId(v);
        return id.equals("farmer") || id.equals("fisherman") || id.equals("butcher");
    }

    /** Задание жителя: предмет и количество. */
    public record Quest(Item item, int count) {
    }

    public static Quest quest(VillagerEntity v, Random r) {
        List<Quest> q = switch (profId(v)) {
            case "farmer" -> List.of(new Quest(Items.WHEAT, 16), new Quest(Items.CARROT, 12), new Quest(Items.POTATO, 12));
            case "fisherman" -> List.of(new Quest(Items.COD, 6), new Quest(Items.SALMON, 4));
            case "shepherd" -> List.of(new Quest(Items.WHITE_WOOL, 8), new Quest(Items.SHEARS, 1));
            case "fletcher" -> List.of(new Quest(Items.STICK, 32), new Quest(Items.FEATHER, 8), new Quest(Items.FLINT, 10));
            case "librarian" -> List.of(new Quest(Items.PAPER, 16), new Quest(Items.BOOK, 3));
            case "cartographer" -> List.of(new Quest(Items.PAPER, 12), new Quest(Items.COMPASS, 1));
            case "cleric" -> List.of(new Quest(Items.ROTTEN_FLESH, 16), new Quest(Items.GOLD_INGOT, 2));
            case "armorer", "weaponsmith", "toolsmith" -> List.of(new Quest(Items.IRON_INGOT, 4), new Quest(Items.COAL, 16));
            case "butcher" -> List.of(new Quest(Items.PORKCHOP, 8), new Quest(Items.BEEF, 6), new Quest(Items.CHICKEN, 6));
            case "leatherworker" -> List.of(new Quest(Items.LEATHER, 6), new Quest(Items.RABBIT_HIDE, 4));
            case "mason" -> List.of(new Quest(Items.CLAY_BALL, 16), new Quest(Items.STONE, 32));
            default -> List.of(new Quest(Items.BREAD, 6), new Quest(Items.APPLE, 4), new Quest(Items.COOKED_COD, 4));
        };
        return q.get(r.nextInt(q.size()));
    }

    private static final String[] BEG = {
            "Смилуйтесь, господин, семья голодает...",
            "Детям нечего есть, помогите хоть немного...",
            "Мэр совсем задрал налоги, нечем кормить семью...",
            "Неурожай в этом году... Не откажите бедному человеку.",
            "Жена болеет, на лекарства не хватает. Помогите!",
            "Вы же самый уважаемый человек в округе! Подайте на хлеб..."
    };

    private static final String[] THANKS = {
            "Храни вас небо, господин! Я этого не забуду.",
            "Спасибо! Сегодня семья поест досыта.",
            "Вы святой человек! Всем расскажу о вашей щедрости."
    };

    private static final String[] REFUSED = {
            "Эх... Ну что ж, простите за беспокойство.",
            "Богатые всегда так... Ладно.",
            "Понимаю... Пойду дальше просить."
    };

    public static String beg(Random r) {
        return BEG[r.nextInt(BEG.length)];
    }

    public static String thanks(Random r) {
        return THANKS[r.nextInt(THANKS.length)];
    }

    public static String refused(Random r) {
        return REFUSED[r.nextInt(REFUSED.length)];
    }
}
