package com.example.bufaloserver.print;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Envoltorio sobre SharedPreferences para el servidor:
 *  - modo de operación: MODO_IMPRESORA (por defecto) o MODO_REGISTRO
 *  - la dirección MAC de la impresora Bluetooth ya emparejada/elegida en la
 *    tablet/celular que corre bufaloserver.
 *
 * MODO_IMPRESORA: al llegar un POST /pedido nuevo, el servidor intenta
 *                 imprimir el ticket de cocina desde SU propia impresora.
 * MODO_REGISTRO:  el pedido se guarda igual, pero nunca se intenta imprimir
 *                 (útil para probar el servidor sin tener la impresora a mano).
 *
 * A diferencia del cliente (donde MODO_REGISTRO bloquea el guardado si falla
 * la impresión), acá NO hay ningún gate: el pedido ya está guardado y
 * confirmado al cliente antes de que se intente imprimir, así que un fallo de
 * impresión nunca hace perder ni duplicar un pedido — solo queda logueado.
 */
public class PrinterPrefs {

    private static final String PREFS_NAME = "printer_prefs_servidor";
    private static final String KEY_MODE   = "modo";
    private static final String KEY_MAC    = "mac_address";

    public static final String MODO_IMPRESORA = "impresora";
    public static final String MODO_REGISTRO  = "registro";

    private PrinterPrefs() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Modo actual. Por defecto: MODO_IMPRESORA (así el servidor imprime apenas se le configura la MAC). */
    public static String getModo(Context ctx) {
        return prefs(ctx).getString(KEY_MODE, MODO_IMPRESORA);
    }

    public static void setModo(Context ctx, String modo) {
        prefs(ctx).edit().putString(KEY_MODE, modo).apply();
    }

    public static boolean esModoImpresora(Context ctx) {
        return MODO_IMPRESORA.equals(getModo(ctx));
    }

    public static boolean esModoRegistro(Context ctx) {
        return MODO_REGISTRO.equals(getModo(ctx));
    }

    /** MAC de la impresora guardada, o null si nunca se configuró. */
    public static String getMac(Context ctx) {
        return prefs(ctx).getString(KEY_MAC, null);
    }

    public static void setMac(Context ctx, String mac) {
        prefs(ctx).edit().putString(KEY_MAC, mac).apply();
    }

    public static boolean tieneMacGuardada(Context ctx) {
        String mac = getMac(ctx);
        return mac != null && !mac.trim().isEmpty();
    }
}
