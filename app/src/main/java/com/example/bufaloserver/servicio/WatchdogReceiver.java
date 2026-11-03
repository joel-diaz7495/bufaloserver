package com.example.bufaloserver.servicio;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * ★ NUEVO — red de seguridad contra el administrador de batería de EMUI
 * (Huawei). Con START_STICKY, Android reinicia el servicio solo si LO MATÓ
 * el sistema estándar por presión de memoria — pero cuando es EMUI el que
 * lo mata por su cuenta (fuera del administrador de procesos estándar de
 * Android, ver "Apps protegidas"/"Inicio de aplicaciones" en
 * EstadoServidorActivity), a veces ni siquiera respeta START_STICKY.
 *
 * Este receiver se dispara solo (una alarma que se reprograma a sí misma
 * cada 10 minutos) y en cada disparo revisa si ServidorService sigue vivo;
 * si no, lo vuelve a arrancar. setExactAndAllowWhileIdle hace que la alarma
 * despierte el dispositivo incluso en Doze profundo — no es 100% infalible
 * contra un fabricante empeñado en matar la app, pero es la mejor red de
 * seguridad disponible sin acceso a APIs privadas de Huawei.
 *
 * También atiende BOOT_COMPLETED: si la tablet se reinicia (corte de luz,
 * reinicio manual, etc.), el servidor tiene que volver a levantarse solo,
 * sin que alguien tenga que abrir la app a mano.
 */
public class WatchdogReceiver extends BroadcastReceiver {

    private static final String TAG = "WatchdogReceiver";
    private static final String ACCION_TICK = "com.example.bufaloserver.WATCHDOG_TICK";
    private static final int REQUEST_CODE = 1001;

    /** Cada cuánto se revisa. 3 min: la tablet del local suele estar
     *  enchufada (sin Doze), y este tick es la red de seguridad para cuando
     *  EMUI mata el proceso. En Doze profundo el sistema igual limita las
     *  alarmas allow-while-idle a ~9 min, pero el chequeo de salud interno del
     *  servicio cubre ese hueco sin depender de la alarma. */
    private static final long INTERVALO_MS = 3 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String accion = intent.getAction();
        Log.i(TAG, "onReceive: " + accion);

        if (Intent.ACTION_BOOT_COMPLETED.equals(accion)
                || "android.intent.action.QUICKBOOT_POWERON".equals(accion)) {
            DiagnosticoLog.registrar(context, "🔄 Tablet reiniciada — levantando servidor");
            try {
                arrancarServidorSiHaceFalta(context);
            } catch (Throwable t) {
                Log.e(TAG, "Error al levantar el servidor tras el reinicio", t);
            } finally {
                programarProximoTick(context);
            }
            return;
        }

        if (ACCION_TICK.equals(accion)) {
            try {
                arrancarServidorSiHaceFalta(context);
            } catch (Throwable t) {
                // Si algo falla (p. ej. ForegroundServiceStartNotAllowed),
                // igual hay que reprogramar: si no, esta cadena de
                // auto-recuperación muere para siempre.
                Log.e(TAG, "Error en el tick del watchdog", t);
            } finally {
                programarProximoTick(context);
            }
        }
    }

    private void arrancarServidorSiHaceFalta(Context context) {
        ServidorService servicio = ServidorService.getInstancia();
        if (servicio != null) {
            // ★ FIX — antes solo se comprobaba que la instancia existiera.
            // El caso más grave era justo el contrario: servicio "vivo" pero
            // con los puertos sin abrir, mostrando el servidor como encendido
            // mientras ningún cliente podía conectarse.
            if (!servicio.estaSaludable()) {
                Log.w(TAG, "⚠️ ServidorService vivo pero sin puertos operativos — reparando");
                DiagnosticoLog.registrar(context, "⚠️ watchdog: servidor sin puertos — reparando");
                servicio.reintentarServidores();
            } else {
                // Se deja constancia igual, así en el registro se ve una
                // línea cada tick confirmando que no hubo cortes, en vez de
                // solo silencio (que podría ser "todo bien" o "la app ni se
                // está ejecutando", indistinguibles a simple vista).
                DiagnosticoLog.registrar(context, "✓ watchdog: servidor sigue activo");
            }
            return;
        }
        Log.w(TAG, "⚠️ ServidorService no está corriendo — reiniciando");
        DiagnosticoLog.registrar(context, "⚠️ watchdog: servidor caído — reiniciando");

        // ★ NUEVO — wake lock corto, con timeout automático (nunca manual),
        // solo para cubrir la ventana entre que termina onReceive() (momento
        // en que el AlarmManager suelta su propio wake lock) y el servicio
        // llega a startForeground() + adquiere su propio PARTIAL_WAKE_LOCK
        // en onStartCommand(). Ver AlarmManager#onReceive doc: "your
        // BroadcastReceiver and Service will need to implement a separate
        // wake lock policy" para cubrir ese hueco. En teoría
        // startForegroundService() ya eleva prioridad lo suficiente, pero
        // como acá se está lidiando con un fabricante (EMUI) que no respeta
        // las reglas estándar, es barato sumar esta red de seguridad extra.
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "bufaloserver:watchdog_arranque");
            wl.acquire(10_000L); // 10s con timeout automático, nunca manual
        }

        Intent serviceIntent = new Intent(context, ServidorService.class);
        ContextCompat.startForegroundService(context, serviceIntent);
    }

    /** ★ NUEVO — corta la cadena de auto-recuperación. Se usa SOLO cuando el
     *  usuario apaga el servidor a propósito desde la pantalla de estado; si
     *  no, el watchdog lo volvería a levantar a los pocos minutos. */
    public static void cancelarProximoTick(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, WatchdogReceiver.class);
        intent.setAction(ACCION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
        am.cancel(pi);
        pi.cancel();
    }

    /** Se llama tanto desde acá (para encadenar el próximo tick) como desde
     *  ServidorService.onCreate() (para arrancar la cadena la primera vez). */
    @SuppressLint("ScheduleExactAlarm")
    public static void programarProximoTick(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, WatchdogReceiver.class);
        intent.setAction(ACCION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);

        long cuando = SystemClock.elapsedRealtime() + INTERVALO_MS;
        // ★ FIX ANDROID 14 — con targetSdk 34, setExactAndAllowWhileIdle()
        // lanza SecurityException si el usuario no otorgó "Alarmas y
        // recordatorios" a la app. El @SuppressLint solo silencia el lint,
        // NO evita el crash en runtime; antes eso podía tumbar el receiver
        // justo cuando más se lo necesitaba. Se degrada a una alarma
        // inexacta (que igual se reprograma sola) en vez de abortar.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, cuando, pi);
            } else {
                // Dispara incluso en Doze profundo — es justo el caso que nos
                // importa (tablet con pantalla apagada hace rato).
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, cuando, pi);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Sin permiso de alarmas exactas; se usa alarma inexacta", e);
            try {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, cuando, pi);
            } catch (Throwable t) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, cuando, pi);
            }
        }
    }
}