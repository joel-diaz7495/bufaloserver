package com.example.bufaloserver.servicio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Punto único para todos los beeps/alertas sonoras del servidor.
 *
 * ★ REESCRITO — por qué ya no se usa ToneGenerator como método principal:
 *
 * El síntoma era "por el parlante del equipo suena bien, pero con un
 * parlante conectado al jack de audífonos no se escucha (o se escucha mal)".
 * Había tres causas combinadas, y las tres quedan resueltas acá:
 *
 * 1) VOLUMEN POR SALIDA. Android guarda el volumen de MÚSICA por separado
 *    para cada salida (parlante interno, jack, bluetooth...). El volumen del
 *    jack puede estar en 0 o muy bajo aunque el del parlante interno esté
 *    al máximo, y ToneGenerator solo aplica un porcentaje de ESE volumen.
 *    Ahora, antes de cada sonido, se revisa el volumen de la salida activa y
 *    se sube a un mínimo (VOLUMEN_MIN_PCT) si está por debajo o silenciado.
 *
 * 2) SALIDA "DORMIDA". El códec/amplificador del jack (y muchos parlantes
 *    autoamplificados) tardan 100-400 ms en despertar. Los beeps cortos
 *    (150-200 ms) se consumían enteros durante ese despertar y no se oía
 *    nada. Ahora cada sonido lleva delante un colchón de ruido casi
 *    inaudible (CALENTAMIENTO_MS) y detrás un cierre (COLA_MS), para que la
 *    parte audible siempre caiga con la salida ya despierta.
 *
 * 3) ToneGenerator es poco confiable con salidas externas: en varios
 *    equipos crea el stream mono por un camino distinto que no sigue bien
 *    el enrutado al jack. Ahora se genera el audio (ondas con armónico, que
 *    suenan mejor en parlantes chicos) y se reproduce con AudioTrack
 *    estéreo, USAGE_MEDIA, que sí sigue el enrutado estándar de Android.
 *    ToneGenerator queda solo como respaldo si AudioTrack fallara.
 *
 * Todos los métodos públicos son fire-and-forget: no bloquean quien llama
 * (se reproducen en un hilo propio, en cola, sin superponerse) y nunca
 * pueden tirar el servidor si el audio falla.
 *
 * REQUIERE llamar SonidosServidor.init(context) una vez al arrancar el
 * servicio (ya agregado en ServidorService.onCreate). Sin init() sigue
 * sonando, pero no puede revisar/subir el volumen.
 */
public class SonidosServidor {

    private static final String TAG = "SonidosServidor";

    /** Volumen mínimo (% del máximo) que se garantiza en la salida activa. */
    private static final int VOLUMEN_MIN_PCT = 80;
    /** Silencio "activo" antes del beep para despertar el códec/parlante. */
    private static final int CALENTAMIENTO_MS = 350;
    /** Relleno al final para que el buffer de salida no recorte el beep. */
    private static final int COLA_MS = 200;

    private static final int SAMPLE_RATE = 44100;
    private static final int FADE_MS = 6;
    private static final double AMPLITUD = 0.92;

    private static final ExecutorService HILO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SonidosServidor");
        t.setDaemon(true);
        return t;
    });

    private static volatile Context appContext;
    private static volatile String ultimaRutaLogueada = "";

    private SonidosServidor() {}

    /** Llamar una vez (ServidorService.onCreate). Idempotente. */
    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    // ─────────────────────────── Sonidos públicos ───────────────────────────
    // Cada sonido es una lista de {frecuenciaHz, duracionMs}; 0 Hz = silencio.
    // Se mantienen distintos entre sí a propósito (igual que antes) y en
    // frecuencias medias (600-1300 Hz) que reproducen bien los parlantes
    // chicos conectados al jack.

    /** Pedido impreso OK — beep corto y neutro, confirmación de rutina. */
    public static void pedidoImpreso() {
        reproducir(new int[][]{{1300, 220}});
    }

    /** Cancelación — largo e insistente (sirena 900/600 Hz, 1.5 s). */
    public static void pedidoCancelado() {
        reproducir(new int[][]{
                {900, 250}, {600, 250}, {900, 250}, {600, 250}, {900, 250}, {600, 250}});
    }

    /** Impresora desconectada — tres pulsos graves de error (~1.1 s). */
    public static void impresoraDesconectada() {
        reproducir(new int[][]{{420, 300}, {0, 100}, {420, 300}, {0, 100}, {420, 300}});
    }

    /** Impresora reconectada — doble beep ascendente, corto y "positivo". */
    public static void impresoraReconectada() {
        reproducir(new int[][]{{900, 150}, {0, 70}, {1300, 150}});
    }

    // ─────────────────────────────── Motor ──────────────────────────────────

    private static void reproducir(final int[][] segmentos) {
        try {
            HILO.execute(() -> reproducirSync(segmentos));
        } catch (Throwable e) {
            Log.w(TAG, "No se pudo encolar sonido: " + e.getMessage());
        }
    }

    private static void reproducirSync(int[][] segmentos) {
        AudioTrack track = null;
        try {
            asegurarVolumen();

            short[] pcm = generarPcmEstereo(segmentos);
            track = crearTrack(pcm.length * 2);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack no inicializó");
            }
            track.write(pcm, 0, pcm.length);
            track.setStereoVolume(1.0f, 1.0f);
            track.play();

            long duracionMs = (pcm.length / 2) * 1000L / SAMPLE_RATE;
            Thread.sleep(duracionMs + 100);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            Log.w(TAG, "AudioTrack falló, uso ToneGenerator de respaldo: " + e.getMessage());
            respaldoToneGenerator(segmentos);
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private static AudioTrack crearTrack(int bytes) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        return new AudioTrack(attrs, fmt, bytes, AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
    }

    /**
     * [colchón casi inaudible] + [beeps] + [cola casi inaudible], estéreo
     * (mismo dato en L y R: algunos jack/cables mono solo llevan un canal).
     */
    private static short[] generarPcmEstereo(int[][] segmentos) {
        int nCalent = SAMPLE_RATE * CALENTAMIENTO_MS / 1000;
        int nCola = SAMPLE_RATE * COLA_MS / 1000;
        int nBeeps = 0;
        for (int[] s : segmentos) nBeeps += SAMPLE_RATE * s[1] / 1000;

        int total = nCalent + nBeeps + nCola;
        short[] out = new short[total * 2];
        Random rnd = new Random(42);
        int idx = 0;

        // Colchón: ruido de ~-58 dBFS, no se percibe pero mantiene la salida
        // "con señal" (algunos parlantes autoamplificados se duermen con silencio digital).
        for (int i = 0; i < nCalent; i++) {
            short v = (short) (rnd.nextInt(41) - 20);
            out[idx++] = v;
            out[idx++] = v;
        }

        int fade = SAMPLE_RATE * FADE_MS / 1000;
        for (int[] s : segmentos) {
            int hz = s[0];
            int n = SAMPLE_RATE * s[1] / 1000;
            for (int i = 0; i < n; i++) {
                short v;
                if (hz <= 0) {
                    v = (short) (rnd.nextInt(41) - 20);
                } else {
                    double fase = 2.0 * Math.PI * hz * i / SAMPLE_RATE;
                    // fundamental + 3er armónico: más "presencia" en parlantes chicos
                    double onda = (Math.sin(fase) + 0.25 * Math.sin(3 * fase)) / 1.25;
                    double env = 1.0;
                    if (i < fade) env = i / (double) fade;
                    else if (i > n - fade) env = (n - i) / (double) fade;
                    v = (short) (onda * env * AMPLITUD * Short.MAX_VALUE);
                }
                out[idx++] = v;
                out[idx++] = v;
            }
        }

        for (int i = 0; i < nCola; i++) {
            short v = (short) (rnd.nextInt(41) - 20);
            out[idx++] = v;
            out[idx++] = v;
        }
        return out;
    }

    /**
     * Garantiza que la salida ACTIVA (jack o parlante) tenga volumen de
     * música audible. getStreamVolume() ya devuelve el volumen del
     * dispositivo de salida actual, por eso funciona aunque el jack se
     * enchufe/desenchufe entre un sonido y otro.
     */
    private static void asegurarVolumen() {
        Context c = appContext;
        if (c == null) return;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            }

            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int actual = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int minimo = Math.max(1, Math.round(max * VOLUMEN_MIN_PCT / 100f));
            String ruta = describirSalida(am);
            if (actual < minimo) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, minimo, 0);
                registrarRuta(c, ruta + " — volumen música " + actual + "/" + max
                        + " subido a " + minimo);
            } else {
                registrarRuta(c, ruta + " — volumen música " + actual + "/" + max + " OK");
            }
        } catch (Throwable e) {
            Log.w(TAG, "No se pudo revisar/ajustar el volumen: " + e.getMessage());
        }
    }

    /** Texto con las salidas de audio cableadas/BT detectadas (solo diagnóstico). */
    private static String describirSalida(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StringBuilder sb = new StringBuilder("🔊 Salida: ");
                boolean alguna = false;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    String n = null;
                    switch (d.getType()) {
                        case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: n = "jack(audífonos)"; break;
                        case AudioDeviceInfo.TYPE_WIRED_HEADSET:    n = "jack(headset)"; break;
                        case AudioDeviceInfo.TYPE_LINE_ANALOG:      n = "línea analógica"; break;
                        case AudioDeviceInfo.TYPE_USB_DEVICE:
                        case AudioDeviceInfo.TYPE_USB_HEADSET:      n = "USB"; break;
                        case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:   n = "bluetooth A2DP"; break;
                        default: break;
                    }
                    if (n != null) { sb.append(alguna ? ", " : "").append(n); alguna = true; }
                }
                if (!alguna) sb.append("parlante interno");
                return sb.toString();
            }
            return am.isWiredHeadsetOn() ? "🔊 Salida: jack" : "🔊 Salida: parlante interno";
        } catch (Throwable e) {
            return "🔊 Salida: (desconocida)";
        }
    }

    /** Solo escribe al log de diagnóstico cuando cambia algo (no en cada beep). */
    private static void registrarRuta(Context c, String texto) {
        if (texto.equals(ultimaRutaLogueada)) return;
        ultimaRutaLogueada = texto;
        try { DiagnosticoLog.registrar(c, texto); } catch (Throwable ignored) {}
    }

    /** Último recurso: el método original, por si AudioTrack no está disponible. */
    private static void respaldoToneGenerator(int[][] segmentos) {
        try {
            int total = 0;
            for (int[] s : segmentos) total += s[1];
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, Math.max(200, total));
            new Handler(Looper.getMainLooper()).postDelayed(tg::release, total + 400L);
        } catch (Throwable e) {
            Log.w(TAG, "Respaldo ToneGenerator también falló: " + e.getMessage());
        }
    }
}
