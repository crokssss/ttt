package com.example.npctts;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> VOICE;
    public static final ForgeConfigSpec.IntValue VOLUME;
    public static final ForgeConfigSpec.IntValue RATE;

    public static final ForgeConfigSpec.ConfigValue<String> SCREEN_KEYWORD;
    public static final ForgeConfigSpec.ConfigValue<String> TARGET_FIELD;
    public static final ForgeConfigSpec.IntValue MIN_LENGTH;
    public static final ForgeConfigSpec.IntValue MAX_LENGTH;
    public static final ForgeConfigSpec.BooleanValue STOP_ON_CLOSE;
    public static final ForgeConfigSpec.BooleanValue DEBUG;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("voice");
        ENABLED = b.comment("Главный выключатель озвучки.")
                .define("enabled", true);
        VOICE = b.comment(
                        "Имя системного голоса. Пусто = голос по умолчанию.",
                        "Windows: например 'Microsoft Irina Desktop'. macOS: 'Milena', 'Yuri'.",
                        "Linux (espeak): код языка, например 'ru'.")
                .define("voice", "");
        VOLUME = b.comment("Громкость 0-100 (работает на Windows).")
                .defineInRange("volume", 100, 0, 100);
        RATE = b.comment("Скорость речи. Windows: -10..10. macOS: слов в минуту, 0 = по умолчанию (~180).")
                .defineInRange("rate", 0, -10, 400);
        b.pop();

        b.push("screen");
        SCREEN_KEYWORD = b.comment(
                        "Озвучиваются только экраны, у которых имя Java-класса содержит эту строку.",
                        "Для CustomNPCs подходит 'GuiDialogInteract'. Можно указать несколько через запятую.")
                .define("screenKeyword", "GuiDialogInteract");
        TARGET_FIELD = b.comment(
                        "Имя конкретного поля с текстом диалога. Пусто = автоопределение.",
                        "Включи debug, чтобы увидеть имена полей в чате.")
                .define("targetField", "");
        MIN_LENGTH = b.comment("Короче этого числа символов — не озвучивать.")
                .defineInRange("minLength", 4, 1, 200);
        MAX_LENGTH = b.comment("Длиннее — обрезать.")
                .defineInRange("maxLength", 600, 50, 5000);
        STOP_ON_CLOSE = b.comment("Останавливать озвучку при закрытии окна диалога.")
                .define("stopOnClose", true);
        DEBUG = b.comment("Печатать в свой чат все найденные поля и тексты экрана.")
                .define("debug", false);
        b.pop();

        SPEC = b.build();
    }

    private Config() {}
}
