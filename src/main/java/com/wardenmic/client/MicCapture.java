package com.wardenmic.client;

import net.fabricmc.loader.api.FabricLoader;

import javax.sound.sampled.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Lee el micrófono en un hilo aparte y expone un nivel suavizado de 0.0 a 1.0. */
public class MicCapture {
    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);

    private volatile float level = 0f;
    private volatile boolean running = false;
    private Thread thread;

    public float getLevel() { return level; }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "WardenMic-Capture");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
    }

    /** Lee config/wardenmic.txt: una parte del nombre del micrófono. Vacío = predeterminado. */
    private String readWantedName() {
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve("wardenmic.txt");
            if (!Files.exists(file)) {
                Files.writeString(file,
                        "# Escriba aqui parte del nombre de su microfono (sin importar mayusculas).\n"
                      + "# Ejemplo: Realtek  o  USB  o  Razer\n"
                      + "# Deje esta linea vacia para usar el predeterminado.\n"
                      + "# Los nombres disponibles aparecen en logs/latest.log buscando [WardenMic]\n");
                return "";
            }
            List<String> lines = Files.readAllLines(file);
            for (String l : lines) {
                String t = l.trim();
                if (!t.isEmpty() && !t.startsWith("#")) return t;
            }
        } catch (Exception e) {
            System.err.println("[WardenMic] No se pudo leer la configuracion: " + e);
        }
        return "";
    }

    private TargetDataLine openLine(String wanted) throws Exception {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        Mixer.Info chosen = null;

        System.out.println("[WardenMic] Dispositivos de entrada disponibles:");
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer m = AudioSystem.getMixer(mi);
            if (!m.isLineSupported(info)) continue;
            System.out.println("[WardenMic]   - " + mi.getName());
            if (chosen == null && !wanted.isEmpty()
                    && mi.getName().toLowerCase().contains(wanted.toLowerCase())) {
                chosen = mi;
            }
        }

        if (chosen != null) {
            System.out.println("[WardenMic] Usando: " + chosen.getName());
            return (TargetDataLine) AudioSystem.getMixer(chosen).getLine(info);
        }
        if (!wanted.isEmpty()) {
            System.err.println("[WardenMic] No se encontro '" + wanted + "'. Usando el predeterminado.");
        }
        return (TargetDataLine) AudioSystem.getLine(info);
    }

    private void loop() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                System.err.println("[WardenMic] No se encontro microfono compatible.");
                return;
            }
            TargetDataLine line = openLine(readWantedName());
            line.open(FORMAT);
            line.start();

            byte[] buf = new byte[1024]; // ~32 ms
            while (running) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 0) continue;

                double sum = 0;
                int samples = n / 2;
                for (int i = 0; i < samples; i++) {
                    short s = (short) ((buf[2 * i + 1] << 8) | (buf[2 * i] & 0xFF));
                    double v = s / 32768.0;
                    sum += v * v;
                }
                double rms = Math.sqrt(sum / Math.max(1, samples));
                double db = 20 * Math.log10(Math.max(rms, 1e-6));
                // Mapea -60 dB .. 0 dB a 0 .. 1
                float target = (float) Math.max(0, Math.min(1, (db + 60) / 60));

                // Sube rapido, baja despacio
                float cur = level;
                level = target > cur ? target : cur * 0.85f + target * 0.15f;
            }
            line.stop();
            line.close();
        } catch (Exception e) {
            System.err.println("[WardenMic] Error con el microfono: " + e);
        } finally {
            running = false;
        }
    }
}
