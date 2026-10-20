package com.example.bufaloserver.servicio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.bufaloserver.EstadoServidorActivity;
import com.example.bufaloserver.http.ServidorHttp;
import com.example.bufaloserver.ws.ServidorWebSocket;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Foreground Service = el proceso que mantiene vivo todo lo demás.
 *
 * Por qué Foreground Service y no solo una Activity corriendo: Android 8 mata
 * procesos en segundo plano agresivamente (más todavía con 2GB RAM, que
 * empuja al sistema a liberar memoria seguido). Un Foreground Service con
 * notificación fija es la única forma confiable de que el servidor siga
 * respondiendo aunque la pantalla de la tablet esté apagada o el usuario
 * haya cambiado a otra app.
 *
 * Arranca acá adentro:
 *   - ServidorHttp (NanoHTTPD)      → puerto 8080, atiende /login, /menu, etc.
 *   - ServidorWebSocket             → puerto 8081, empuja eventos en tiempo real
 *
 * Deliberadamente NO arranca Room acá — cada componente (ServidorHttp,
 * ServidorWebSocket) pide la instancia de la base con AppDatabaseServidor
 * .getInstance(context) cuando la necesita, mismo patrón singleton que ya
 * usa el cliente. Room ya maneja su propio pool de conexiones, no hace falta
 * gestionarlo a mano acá.
 */
public class ServidorService extends Service {

    private static final String TAG = "ServidorService";
    private static final String CANAL_ID = "servidor_activo";
    private static final int NOTIF_ID = 1;

    /** Puertos fijos a propósito — más simple que negociar puertos libres en
     *  una tablet dedicada exclusivamente a esto. Si alguna vez chocan con
     *  otra app, se cambian acá nomás. */
    public static final int PUERTO_HTTP = 8080;
    public static final int PUERTO_WS = 8081;

    private ServidorHttp servidorHttp;
    /** ★ NUEVO — revisa cada hora si toca la limpieza automática de datos (ver LimpiezaDatos). */
    private java.util.concurrent.ScheduledExecutorService limpiezaScheduler;
    private ServidorWebSocket servidorWebSocket;

    /**
     * PARTIAL_WAKE_LOCK: mantiene la CPU despierta aunque se apague la
     * pantalla, para que NanoHTTPD y el WebSocketServer sigan atendiendo
     * requests sin que el sistema les corte el paso al entrar en suspensión
     * profunda. El permiso WAKE_LOCK ya estaba declarado en el manifest,
     * pero hasta ahora nunca se usaba — declarar el permiso solo no hace
     * nada por sí solo, hay que pedir el lock acá.
     *
     * PARTIAL_ (no FULL ni SCREEN_*) porque solo necesitamos la CPU viva,
     * NO queremos prender la pantalla — eso consumiría batería de más sin
     * necesidad, la tablet puede quedarse con la pantalla apagada
     * tranquilamente mientras el servidor sigue respondiendo.
     */
    @Nullable
    private PowerManager.WakeLock wakeLock;

    /**
     * ★ NUEVO — WifiLock (radio WiFi), distinto del PARTIAL_WAKE_LOCK de
     * arriba (que solo mantiene viva la CPU). Sin esto, en Android 8 el
     * sistema puede apagar o poner en ahorro de energía el radio WiFi apenas
     * se apaga la pantalla — hay un ajuste típico en estas tablets/ROMs
     * ("Wi-Fi activo durante el reposo" → "Solo cargando" / "Nunca") que
     * desconecta el WiFi aunque el Foreground Service y su wake lock sigan
     * vivos. Eso corta TODAS las conexiones WebSocket abiertas de una — el
     * síntoma exacto reportado: los cambios dejan de reflejarse en todos los
     * celulares a la vez, no en uno solo.
     *
     * WIFI_MODE_FULL_HIGH_PERF: evita el ahorro de energía del radio incluso
     * con la pantalla apagada (a diferencia de WIFI_MODE_FULL, que solo
     * evita el modo ahorro cuando la pantalla está prendida). Es justo lo
     * que necesita un servidor que debe seguir respondiendo con la tablet
     * "dormida".
     */
    @Nullable
    private WifiManager.WifiLock wifiLock;

    /**
     * Referencia estática a la instancia corriendo, SOLO para que
     * EstadoServidorActivity pueda leer getCantidadConectados() sin tener
     * que armar un bindService completo por un solo número. No se usa para
     * nada más — si en algún momento se necesita más comunicación
     * Activity↔Service, ahí sí vale la pena migrar a binder real.
     */
    @Nullable
    private static ServidorService instancia;

    @Nullable
    public static ServidorService getInstancia() {
        return instancia;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instancia = this;
        crearCanalNotificacion();
        DiagnosticoLog.registrar(this, "🟢 Servicio creado (onCreate)");
        // ★ NUEVO — arranca la cadena de auto-chequeo cada 10 min (ver
        // WatchdogReceiver). Si EMUI mata este proceso más adelante, esa
        // alarma ya programada sigue viva en el sistema (no depende de que
        // este proceso siga corriendo) y va a revivir el servicio sola.
        WatchdogReceiver.programarProximoTick(this);

        // ★ NUEVO — limpieza automática de datos viejos. LimpiezaDatos decide sola si corresponde
        // (nunca la primera vez, solo de madrugada, solo si hay algo para borrar), así que acá
        // solo se la invoca cada hora. Primer chequeo a los 5 min de arrancar el servicio.
        limpiezaScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        limpiezaScheduler.scheduleWithFixedDelay(
                () -> com.example.bufaloserver.servicio.LimpiezaDatos
                        .ejecutarAutomaticaSiCorresponde(getApplicationContext()),
                5, 60, java.util.concurrent.TimeUnit.MINUTES);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: si Android igual llega a matar el proceso por presión
        // de memoria, que lo reinicie solo apenas pueda — un servidor de POS
        // no debería quedar caído esperando que alguien abra la app a mano.
        //
        // ★ CAMBIADO — antes esto llamaba startForeground(NOTIF_ID, notif) de
        // 2 argumentos, que en Android 10-13 toma el tipo directo del
        // manifest, pero conviene pasarlo explícito en Android 14+ (donde
        // además ya dejamos de usar dataSync por su límite de 6hs — ver
        // AndroidManifest). Antes de API 29 no existe el overload de 3
        // argumentos, así que se resuelve en tiempo de ejecución.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, construirNotificacion(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, construirNotificacion());
        }
        adquirirWakeLock();
        adquirirWifiLock();
        iniciarServidores();
        iniciarImpresoraSiCorresponde();
        return START_STICKY;
    }

    // NOTA: se sacó el override de onTimeout(int, int) que estaba acá antes.
    // Ese callback de Service recién se agregó en API 35 (Android 15) — no
    // en 34 como se dijo en un momento — y este proyecto compila con
    // compileSdk 34, por eso tiraba "method does not override or implement
    // a method from a supertype". No hacía falta de todos modos: con
    // foregroundServiceType="specialUse" (ver AndroidManifest) el límite de
    // 6 horas que dispara ese callback ni siquiera aplica, así que nunca se
    // iba a llamar. Si en algún momento suben compileSdk/targetSdk a 35, se
    // puede volver a agregar como red de seguridad adicional.

    /**
     * Pide el PARTIAL_WAKE_LOCK (ver campo wakeLock). Es idempotente — si ya
     * está adquirido (ej. Android llama onStartCommand de nuevo por
     * START_STICKY tras matar y reiniciar el proceso) no vuelve a pedirlo.
     */
    private void adquirirWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bufaloserver:servidor_activo");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(); // sin timeout: se libera explícitamente en onDestroy()
    }

    /** Pide el WifiLock (ver campo wifiLock). Idempotente, mismo criterio que adquirirWakeLock(). */
    private void adquirirWifiLock() {
        if (wifiLock != null && wifiLock.isHeld()) return;
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bufaloserver:wifi_activo");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
    }

    /**
     * Arranca la reconexión persistente con la impresora Bluetooth del
     * servidor (si ya se configuró una MAC y el modo activo es Impresora),
     * para que esté conectada (o reintentando conectarse) desde que arranca
     * el servicio, sin depender de que alguien abra
     * PrinterConfigActivityServidor primero. Si todavía no se configuró
     * ninguna impresora, no hace nada — ServidorHttp.imprimirTicketPedido()
     * ya chequea tieneMacGuardada() antes de intentar imprimir.
     */
    private void iniciarImpresoraSiCorresponde() {
        if (com.example.bufaloserver.print.PrinterPrefs.esModoImpresora(this)
                && com.example.bufaloserver.print.PrinterPrefs.tieneMacGuardada(this)) {
            String mac = com.example.bufaloserver.print.PrinterPrefs.getMac(this);
            com.example.bufaloserver.print.PrinterManager.get(this).iniciarConexionPersistente(mac);
        }
    }

    private void iniciarServidores() {
        // ★ MEJORA VELOCIDAD — precalienta el hash del PIN en segundo plano
        // para que el primer login no pague el arranque en frío.
        new Thread(com.example.bufaloserver.seguridad.PinHasher::precalentar, "pin-precalentar").start();

        if (servidorHttp == null) {
            try {
                servidorHttp = new ServidorHttp(this, PUERTO_HTTP);
                servidorHttp.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                Log.i(TAG, "NanoHTTPD escuchando en puerto " + PUERTO_HTTP);
            } catch (IOException e) {
                Log.e(TAG, "No se pudo levantar el servidor HTTP", e);
            }
        }

        if (servidorWebSocket == null) {
            servidorWebSocket = new ServidorWebSocket(new InetSocketAddress(PUERTO_WS), this);
            servidorWebSocket.start();
            Log.i(TAG, "WebSocketServer escuchando en puerto " + PUERTO_WS);
        }

        // Recién acá los dos existen — ServidorHttp usa esto para avisar por
        // WS cuando cambia un pedido/usuario/producto (ver rutas en serve()).
        if (servidorHttp != null && servidorWebSocket != null) {
            servidorHttp.setWebSocket(servidorWebSocket);
        }
    }

    /** Otros componentes (ServidorHttp, al recibir POST /pedido, por ejemplo)
     *  usan esto para avisarle a todos los clientes conectados sin pasar por
     *  otra capa intermedia. */
    @Nullable
    public ServidorWebSocket getServidorWebSocket() {
        return servidorWebSocket;
    }

    private void crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel canal = new NotificationChannel(
                    CANAL_ID, "Servidor activo", NotificationManager.IMPORTANCE_LOW);
            canal.setDescription("Indica que el servidor del local está corriendo");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(canal);
        }
    }

    private Notification construirNotificacion() {
        Intent intentAbrir = new Intent(this, EstadoServidorActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intentAbrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CANAL_ID)
                .setContentTitle("Servidor del local activo")
                .setContentText("Puerto " + PUERTO_HTTP + " · Toca para ver el estado")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pendingIntent)
                .setOngoing(true) // no se puede deslizar para cerrar por accidente
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instancia == this) instancia = null;
        if (limpiezaScheduler != null) limpiezaScheduler.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (servidorHttp != null) servidorHttp.stop();
        if (servidorWebSocket != null) {
            try {
                servidorWebSocket.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        DiagnosticoLog.registrar(this, "🔴 Servicio destruido (onDestroy)");
        Log.i(TAG, "ServidorService detenido");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // no se usa binding — EstadoServidorActivity solo start/stopService
    }
}