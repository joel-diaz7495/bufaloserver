package com.example.bufaloserver.servicio;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * ★ NUEVO — punto único para todos los beeps/alertas sonoras del servidor.
 *
 * IMPORTANTE — por qué STREAM_MUSIC y no STREAM_NOTIFICATION:
 * los beeps originales (impresión y cancelación) usaban
 * AudioManager.STREAM_NOTIFICATION. En muchas tablets/POS (sobre todo
 * clones chinos, que es el tipo de hardware que corre este server) el
 * fabricante fuerza el stream de notificación al parlante INTERNO sin
 * importar qué haya conectado en el jack de audífonos — solo el stream de
 * MÚSICA sigue de verdad el enrutado estándar de Android (sale por el
 * parlante auxiliar apenas está conectado). Por eso el beep "sonaba" pero
 * no se escuchaba en el parlante enchufado al auxiliar. Si en algún
 * dispositivo puntual pasara lo contrario, es cuestión de volver a probar
 * con STREAM_NOTIFICATION — pero STREAM_MUSIC es la opción que funciona en
 * la enorme mayoría de estos equipos.
 *
 * Todos los métodos son fire-and-forget, no bloquean, y nunca deberían
 * poder tirar el servidor si el audio falla por cualquier motivo (por eso
 * el try/catch amplio en cada uno).
 */
public class SonidosServidor {

    private static final String TAG = "SonidosServidor";

    private SonidosServidor() {}

    private static void tono(int tipoTono, int duracionMs, int volumenPct) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, volumenPct);
            tg.startTone(tipoTono, duracionMs);
            // Liberar antes de que termine el tono corta el sonido — se
            // libera un poco después de la duración real.
            new Handler(Looper.getMainLooper()).postDelayed(tg::release, duracionMs + 150);
        } catch (Exception e) {
            Log.w(TAG, "No se pudo reproducir tono (" + tipoTono + "): " + e.getMessage());
        }
    }

    /** Pedido impreso OK — beep corto y neutro, confirmación de rutina. */
    public static void pedidoImpreso() {
        tono(ToneGenerator.TONE_PROP_BEEP2, 200, 100);
    }

    /** Cancelación de pedido — largo e insistente, para que se note la
     *  diferencia con el beep de impresión normal. */
    public static void pedidoCancelado() {
        tono(ToneGenerator.TONE_CDMA_ABBR_ALERT, 1500, 100);
    }

    /**
     * Impresora se desconectó (Bluetooth perdido o nunca conectó).
     * Tono grave de error, más largo — a propósito distinto de los otros
     * dos para que no se confunda con "pedido cancelado".
     */
    public static void impresoraDesconectada() {
        tono(ToneGenerator.TONE_SUP_ERROR, 1000, 100);
    }

    /**
     * Impresora volvió a conectar (y, si había pedidos pendientes, ya se
     * están reimprimiendo solos — ver ServidorHttp.reintentarPedidosNoImpresos).
     * Tono corto y "positivo", doble beep ascendente para diferenciarlo
     * claramente del resto.
     */
    public static void impresoraReconectada() {
        tono(ToneGenerator.TONE_PROP_ACK, 150, 100);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> tono(ToneGenerator.TONE_PROP_ACK, 150, 100), 220);
    }
}
