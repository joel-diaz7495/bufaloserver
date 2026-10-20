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

    /** Cada cuánto se revisa. 10 min: suficientemente seguido para no dejar
     *  el servidor caído mucho rato, sin ser tan agresivo como para gastar
     *  batería de más despertando el equipo todo el tiempo. */
    private static final long INTERVALO_MS = 10 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String accion = intent.getAction();
        Log.i(TAG, "onReceive: " + accion);

        if (Intent.ACTION_BOOT_COMPLETED.equals(accion)
                || "android.intent.action.QUICKBOOT_POWERON".equals(accion)) {
            DiagnosticoLog.registrar(context, "🔄 Tablet reiniciada — levantando servidor");
            arrancarServidorSiHaceFalta(context);
            programarProximoTick(context);
            return;
        }

        if (ACCION_TICK.equals(accion)) {
            arrancarServidorSiHaceFalta(context);
            programarProximoTick(context); // se reprograma a sí mismo — cadena infinita mientras la app exista
        }
    }

    private void arrancarServidorSiHaceFalta(Context context) {
        if (ServidorService.getInstancia() != null) {
            // Seguía vivo — se deja constancia igual, así en el registro se
            // ve una línea cada 10 min confirmando que no hubo cortes, en
            // vez de solo silencio (que podría ser "todo bien" o "la app
            // ni se está ejecutando", indistinguibles a simple vista).
            DiagnosticoLog.registrar(context, "✓ watchdog: servidor sigue activo");
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Dispara incluso en Doze profundo — es justo el caso que nos importa
            // (tablet con pantalla apagada hace rato, sin nadie usándola).
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, cuando, pi);
        } else {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, cuando, pi);
        }
    }
}