package com.example.npctts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Озвучка через встроенный синтезатор речи операционной системы.
 * Windows -> PowerShell + System.Speech, macOS -> say, Linux -> espeak / spd-say.
 * Ни ключей, ни интернета, ни сторонних библиотек.
 */
public final class TTSManager {

    private static final TTSManager INSTANCE = new TTSManager();

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean WINDOWS = OS.contains("win");
    private static final boolean MAC = OS.contains("mac");

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "npctts-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile Future<?> current;
    private volatile Process process;

    private TTSManager() {}

    public static TTSManager get() {
        return INSTANCE;
    }

    public void speak(String text) {
        stop();
        current = pool.submit(() -> {
            Path textFile = null;
            Path scriptFile = null;
            try {
                textFile = Files.createTempFile("npctts-", ".txt");
                Files.writeString(textFile, text, StandardCharsets.UTF_8);

                List<String> cmd;
                if (WINDOWS) {
                    scriptFile = writeWindowsScript(textFile);
                    cmd = List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                            "-File", scriptFile.toString());
                } else if (MAC) {
                    cmd = macCommand(textFile);
                } else {
                    cmd = linuxCommand(textFile);
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                process = p;
                p.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                System.out.println("[npctts] не удалось запустить синтезатор речи: " + e.getMessage());
            } catch (Throwable t) {
                System.out.println("[npctts] ошибка озвучки: " + t);
            } finally {
                process = null;
                delete(textFile);
                delete(scriptFile);
            }
        });
    }

    public void stop() {
        Future<?> f = current;
        if (f != null) f.cancel(true);
        Process p = process;
        if (p != null) {
            p.destroyForcibly();
            process = null;
        }
    }

    // ------------------------------------------------------------------

    private static Path writeWindowsScript(Path textFile) throws IOException {
        String voice = Config.VOICE.get() == null ? "" : Config.VOICE.get().replace("'", "''");
        int rate = Math.max(-10, Math.min(10, Config.RATE.get()));

        String script = ""
                + "Add-Type -AssemblyName System.Speech\n"
                + "$text = [IO.File]::ReadAllText('" + escapePs(textFile.toString()) + "', [Text.Encoding]::UTF8)\n"
                + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer\n"
                + "$s.Volume = " + Config.VOLUME.get() + "\n"
                + "$s.Rate = " + rate + "\n"
                + (voice.isBlank() ? "" : "try { $s.SelectVoice('" + voice + "') } catch { }\n")
                + "$s.Speak($text)\n";

        Path f = Files.createTempFile("npctts-", ".ps1");
        // BOM, чтобы PowerShell 5 правильно прочитал кириллицу в скрипте
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = script.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        Files.write(f, out);
        return f;
    }

    private static List<String> macCommand(Path textFile) {
        List<String> cmd = new ArrayList<>(List.of("say", "-f", textFile.toString()));
        String voice = Config.VOICE.get();
        if (voice != null && !voice.isBlank()) {
            cmd.add("-v");
            cmd.add(voice);
        }
        int rate = Config.RATE.get();
        if (rate > 0) {
            cmd.add("-r");
            cmd.add(String.valueOf(rate));
        }
        return cmd;
    }

    private static List<String> linuxCommand(Path textFile) {
        String voice = Config.VOICE.get();
        String lang = (voice == null || voice.isBlank()) ? "ru" : voice;
        String bin = exists("espeak-ng") ? "espeak-ng" : "espeak";
        List<String> cmd = new ArrayList<>(List.of(bin, "-v", lang, "-f", textFile.toString()));
        int rate = Config.RATE.get();
        if (rate > 0) {
            cmd.add("-s");
            cmd.add(String.valueOf(rate));
        }
        return cmd;
    }

    private static boolean exists(String bin) {
        try {
            return new ProcessBuilder("which", bin).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String escapePs(String s) {
        return s.replace("'", "''");
    }

    private static void delete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }
}
