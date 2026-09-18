package com.example.npctts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Слушает открытие GUI CustomNPCs и озвучивает текст диалога.
 */
public class DialogScreenListener {

    private static final int SCAN_DELAY_TICKS = 2;
    private static final int MAX_DEPTH = 3;

    private Screen pending;
    private int delay;
    private String lastSpoken = "";

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (!Config.ENABLED.get()) return;
        if (!matchesKeyword(event.getScreen())) return;
        this.pending = event.getScreen();
        this.delay = SCAN_DELAY_TICKS;
    }

    @SubscribeEvent
    public void onScreenClose(ScreenEvent.Closing event) {
        if (!matchesKeyword(event.getScreen())) return;
        this.pending = null;
        if (Config.STOP_ON_CLOSE.get()) {
            TTSManager.get().stop();
        }
        this.lastSpoken = "";
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending == null) return;
        if (--delay > 0) return;

        Screen screen = pending;
        pending = null;
        if (Minecraft.getInstance().screen != screen) return;

        String text = extractText(screen);
        if (text == null) return;
        if (text.length() < Config.MIN_LENGTH.get()) return;
        if (text.equals(lastSpoken)) return;

        lastSpoken = text;
        int max = Config.MAX_LENGTH.get();
        TTSManager.get().speak(text.length() > max ? text.substring(0, max) : text);
    }

    // ------------------------------------------------------------------

    private static boolean matchesKeyword(Screen screen) {
        if (screen == null) return false;
        String keywords = Config.SCREEN_KEYWORD.get();
        if (keywords == null || keywords.isBlank()) return true;
        String name = screen.getClass().getName().toLowerCase(Locale.ROOT);
        for (String k : keywords.split(",")) {
            k = k.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && name.contains(k)) return true;
        }
        return false;
    }

    private String extractText(Screen screen) {
        List<Found> found = new ArrayList<>();
        collect(screen, "", 0, found, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));

        if (Config.DEBUG.get()) {
            debug("--- " + screen.getClass().getName() + " (" + found.size() + ") ---");
            for (Found f : found) {
                debug(f.path + " = " + shorten(f.text));
            }
        }
        if (found.isEmpty()) return null;

        String target = Config.TARGET_FIELD.get();
        if (target != null && !target.isBlank()) {
            Found best = null;
            for (Found f : found) {
                if (f.path.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT))
                        && (best == null || f.text.length() > best.text.length())) {
                    best = f;
                }
            }
            if (best != null) return best.text;
        }

        // Автоопределение: поля с "говорящими" именами в приоритете, иначе самый длинный текст.
        Found best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Found f : found) {
            String p = f.path.toLowerCase(Locale.ROOT);
            int score = f.text.length();
            if (p.contains("dialog") || p.contains("text") || p.contains("line")) score += 1000;
            if (p.contains("title") || p.contains("name") || p.contains("narrat")) score -= 500;
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best == null ? null : best.text;
    }

    private void collect(Object obj, String path, int depth, List<Found> out, Set<Object> seen) {
        if (obj == null || depth > MAX_DEPTH || out.size() > 300) return;
        if (!seen.add(obj)) return;

        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String pkg = c.getName();
            // не лезем во внутренности ванилы/джавы глубже, чем нужно
            if (depth > 0 && (pkg.startsWith("java.") || pkg.startsWith("com.mojang."))) return;

            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(obj);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value == null) continue;

                String p = path.isEmpty() ? field.getName() : path + "." + field.getName();
                String text = asText(value);
                if (text != null) {
                    if (!text.isBlank()) out.add(new Found(p, text));
                } else if (!(value instanceof Number) && !(value instanceof Boolean) && !(value instanceof Character)) {
                    collect(value, p, depth + 1, out, seen);
                }
            }
        }
    }

    /** Превращает значение в текст, если это строка / Component / коллекция строк. Иначе null. */
    private static String asText(Object value) {
        if (value instanceof String s) return clean(s);
        if (value instanceof Component c) return clean(c.getString());
        if (value instanceof Collection<?> col) return joinText(col);
        if (value instanceof Map<?, ?> m) return joinText(m.values());
        if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            List<Object> list = new ArrayList<>();
            int n = Array.getLength(value);
            for (int i = 0; i < n; i++) list.add(Array.get(value, i));
            return joinText(list);
        }
        return null;
    }

    private static String joinText(Collection<?> col) {
        if (col.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Object o : col) {
            String part = null;
            if (o instanceof String s) part = clean(s);
            else if (o instanceof Component c) part = clean(c.getString());
            if (part == null) return null; // не коллекция текста — пусть обойдёт рекурсия
            if (part.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part);
        }
        return sb.toString();
    }

    /** Убирает §-цвета, {теги} CustomNPCs и лишние пробелы. */
    private static String clean(String s) {
        if (s == null) return null;
        String r = s.replaceAll("(?i)\u00a7[0-9A-FK-OR]", "")
                .replaceAll("\\{[^}]*}", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // отсекаем технический мусор (пути, id, форматные строки)
        if (r.contains("://") || r.matches("^[a-z0-9_.]+:[a-z0-9_./]+$")) return "";
        return r;
    }

    private static String shorten(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    private static void debug(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[npctts] " + msg), false);
        }
    }

    private static final class Found {
        final String path;
        final String text;
        Found(String path, String text) {
            this.path = path;
            this.text = text;
        }
    }
}
