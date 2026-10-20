package com.example.bufaloserver.print;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton que administra la conexión Bluetooth Classic (SPP) con la
 * impresora térmica MPT-II — ahora del lado del SERVIDOR.
 *
 * Idéntico en diseño al PrinterManager del cliente (com.example.myapplication.print):
 * reconexión persistente con backoff, imprimir() nunca reintenta sola.
 * Se copia completo en vez de compartir módulo porque bufaloserver y el
 * cliente son dos apps/proyectos Android distintos (paquetes distintos).
 */
public class PrinterManager {

    private static final String TAG = "PrinterManagerSrv";

    /** UUID estándar de Serial Port Profile (SPP), usado por casi todas las impresoras térmicas clon. */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /**
     * ★ AJUSTADO — antes llegaba hasta 20000ms (20s) entre intentos tras
     * varios fallos seguidos. Eso tiene sentido para no saturar un SERVIDOR
     * remoto, pero acá es un periférico Bluetooth LOCAL ya emparejado:
     * reintentar es barato (la tablet está enchufada, no hay costo real de
     * batería/red relevante). Con 20s de espera, si la impresora se apaga
     * un rato largo (varios fallos seguidos escalan el backoff al máximo) y
     * después se prende de nuevo, el hilo persistente podía tardar HASTA 20
     * segundos en darse cuenta — y hasta que no se detecta la reconexión no
     * se disparan los reintentos de los pedidos que quedaron pendientes
     * (ver notifyReconectado()/ReconnectListener). En la práctica, eso hacía
     * que pareciera que "no reconoce" al perif­érico, cuando en realidad
     * solo estaba esperando su turno para volver a intentar. Bajado a un
     * máximo de 3s — detecta la reconexión mucho más rápido sin generar un
     * costo real (el intento fallido en sí es casi instantáneo si el
     * adapter Bluetooth está apagado/el dispositivo no responde).
     */
    private static final long[] BACKOFF_MS = {1000, 2000, 3000};

    /**
     * ★ NUEVO — causa real del bug "el indicador dice conectada pero no lo
     * está, y recién reconecta cuando llega otro pedido": el hilo persistente
     * (ver iniciarConexionPersistente), una vez conectado, antes solo hacía
     * `Thread.sleep(4000)` y volvía a mirar el flag `conectado` — pero ESE
     * flag solo se pone en false cuando falla una escritura real (ver
     * imprimir()). Si no llegaba ningún pedido nuevo, una impresora apagada
     * podía quedar "conectada" para el sistema (y el indicador ●) por tiempo
     * indefinido, porque nada intentaba escribirle nada para descubrir la
     * caída. Con este intervalo, mientras está conectada e inactiva se manda
     * un byte de "ping" cada PING_INTERVAL_MS (ver probarConexionInactiva) —
     * así la caída se detecta sola, sin depender de que entre un pedido.
     */
    private static final long PING_INTERVAL_MS = 3000;

    private static volatile PrinterManager INSTANCE;

    public static PrinterManager get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (PrinterManager.class) {
                if (INSTANCE == null) INSTANCE = new PrinterManager(ctx.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    public interface ConnectCallback {
        void onConectado();
        void onError(String motivo);
    }

    public interface PrintCallback {
        void onImpreso();
        void onError(String motivo);
    }

    public interface StatusListener {
        void onStatusChanged(boolean conectado, boolean intentandoConectar);
    }

    /**
     * ★ NUEVO — se dispara SOLO en la transición desconectado→conectado
     * (a diferencia de StatusListener, que avisa de cualquier cambio,
     * incluido "empezó a intentar conectar"). Es el gancho que usa
     * ServidorHttp para reimprimir automáticamente los pedidos que quedaron
     * con impreso=0 mientras la impresora no estaba disponible — ver
     * ServidorHttp.reintentarPedidosNoImpresos().
     */
    public interface ReconnectListener {
        void onReconectado();
    }

    private final Set<ReconnectListener> reconnectListeners = new CopyOnWriteArraySet<>();

    public void addReconnectListener(ReconnectListener l) {
        reconnectListeners.add(l);
    }

    public void removeReconnectListener(ReconnectListener l) {
        reconnectListeners.remove(l);
    }

    private void notifyReconectado() {
        mainHandler.post(() -> {
            for (ReconnectListener l : reconnectListeners) l.onReconectado();
        });
    }

    /**
     * ★ NUEVO — contraparte de ReconnectListener: se dispara SOLO en la
     * transición conectado→desconectado (no en cada reintento fallido del
     * hilo persistente, que llama a cerrarSocketInterno() constantemente
     * mientras ya está desconectado). Pensado para disparar un sonido de
     * alerta una única vez por caída real, y no un beep cada pocos segundos
     * mientras el hilo de reconexión sigue reintentando en el fondo.
     */
    public interface DisconnectListener {
        void onDesconectado();
    }

    private final Set<DisconnectListener> disconnectListeners = new CopyOnWriteArraySet<>();

    public void addDisconnectListener(DisconnectListener l) {
        disconnectListeners.add(l);
    }

    public void removeDisconnectListener(DisconnectListener l) {
        disconnectListeners.remove(l);
    }

    private void notifyDesconectado() {
        mainHandler.post(() -> {
            for (DisconnectListener l : disconnectListeners) l.onDesconectado();
        });
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Set<StatusListener> listeners = new CopyOnWriteArraySet<>();

    private volatile BluetoothSocket socket;
    private volatile OutputStream outputStream;
    private volatile java.io.InputStream inputStream;
    private volatile String macActual;

    /**
     * ★ NUEVO — verificación de estado real post-impresión (comando ESC/POS
     * estándar "DLE EOT n"). Soluciona el caso "sonó el beep pero no imprimió
     * físicamente" (ej. impresora apagada un instante antes de mandar el
     * ticket): un write()+flush() sin IOException NO prueba que la impresora
     * recibió nada, solo que el socket local aceptó los bytes.
     *
     * null  = todavía no sabemos si esta impresora responde al comando.
     * true  = ya respondió alguna vez → confiamos en la consulta de acá en más.
     * false = nunca respondió en varios intentos seguidos → asumimos que es
     *         un clon que no lo implementa, y dejamos de exigirla (si no,
     *         terminaríamos tratando cada ticket como sospechoso para
     *         siempre y generando reintentos/duplicados sin parar).
     */
    private volatile Boolean soportaConsultaEstado = null;
    private final java.util.concurrent.atomic.AtomicInteger intentosSinRespuestaSeguidos =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private static final byte[] DLE_EOT_ESTADO_IMPRESORA = {0x10, 0x04, 0x01};
    private static final long TIMEOUT_CONSULTA_ESTADO_MS = 700;
    private static final int INTENTOS_SIN_RESPUESTA_PARA_DESISTIR = 3;

    private final AtomicBoolean conectado = new AtomicBoolean(false);
    private final AtomicBoolean intentandoConectar = new AtomicBoolean(false);
    private final AtomicBoolean persistenteActivo = new AtomicBoolean(false);
    private volatile Thread reconnectThread;

    private PrinterManager(Context ctx) {
        this.appContext = ctx;
    }

    // ── Listeners de estado (para el indicador ●/○) ────────────────────────────

    public void addListener(StatusListener l) {
        listeners.add(l);
        l.onStatusChanged(conectado.get(), intentandoConectar.get());
    }

    public void removeListener(StatusListener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        boolean c = conectado.get();
        boolean i = intentandoConectar.get();
        mainHandler.post(() -> {
            for (StatusListener l : listeners) l.onStatusChanged(c, i);
        });
    }

    public boolean isConectado() {
        return conectado.get();
    }

    public boolean isIntentandoConectar() {
        return intentandoConectar.get();
    }

    // ── Permisos ─────────────────────────────────────────────────────────────

    private boolean tienePermisoBluetoothConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true; // en API < 31 el permiso BLUETOOTH normal ya cubre esto
    }

    // ── Conexión persistente en segundo plano ───────────────────────────────

    /**
     * Arranca (si no estaba corriendo) el hilo de reconexión persistente.
     * Se llama desde ServidorService.onStartCommand() si el modo activo es
     * MODO_IMPRESORA y hay una MAC guardada — así la impresora ya está
     * conectada (o reintentando) desde que arranca el servidor, sin esperar
     * a que alguien abra la pantalla de configuración.
     */
    public synchronized void iniciarConexionPersistente(String mac) {
        if (mac == null || mac.trim().isEmpty()) return;
        this.macActual = mac;
        if (persistenteActivo.get()) return; // ya corriendo
        persistenteActivo.set(true);

        reconnectThread = new Thread(() -> {
            int intento = 0;
            while (persistenteActivo.get()) {
                if (!conectado.get()) {
                    boolean ok = intentarConectarSincrono(macActual, 5000);
                    if (ok) {
                        intento = 0;
                    } else {
                        long espera = BACKOFF_MS[Math.min(intento, BACKOFF_MS.length - 1)];
                        intento++;
                        try { Thread.sleep(espera); } catch (InterruptedException e) { return; }
                        continue;
                    }
                }
                // Ya conectado: en vez de solo dormir y confiar en que una
                // impresión fallida sea la única forma de enterarse de una
                // caída real (ver comentario de PING_INTERVAL_MS arriba),
                // hacemos un ping activo periódico. Se manda por ioExecutor
                // — el mismo hilo/cola que usa imprimir() — para que el byte
                // de ping NUNCA se mezcle con los bytes de un ticket real
                // que se esté imprimiendo en simultáneo.
                try { Thread.sleep(PING_INTERVAL_MS); } catch (InterruptedException e) { return; }
                if (conectado.get()) {
                    ioExecutor.execute(PrinterManager.this::probarConexionInactiva);
                }
            }
        }, "PrinterReconnectThreadSrv");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /** Detiene la reconexión persistente y cierra el socket (ej. al cambiar a modo Registro). */
    public synchronized void detenerConexionPersistente() {
        persistenteActivo.set(false);
        if (reconnectThread != null) reconnectThread.interrupt();
        cerrarSocketInterno();
    }

    // ── Conexión manual (desde PrinterConfigActivityServidor) ───────────────

    public void conectar(String mac, ConnectCallback cb) {
        this.macActual = mac;
        ioExecutor.execute(() -> {
            boolean ok = intentarConectarSincrono(mac, 6000);
            mainHandler.post(() -> {
                if (ok) cb.onConectado();
                else cb.onError("No se pudo conectar con la impresora. Verifica que esté encendida, cerca y emparejada.");
            });
        });
    }

    public void desconectar() {
        detenerConexionPersistente();
    }

    /**
     * ★ FIX BUG #2 (v2) — la primera corrección (synchronized a secas) fue
     * una sobrecorrección: como s.connect() es una llamada BLOQUEANTE que en
     * Android puede tardar varios segundos si el dispositivo no responde,
     * synchronized hacía que CUALQUIER intento de conexión (el hilo
     * persistente, un pedido nuevo entrando, o el botón manual "Conectar")
     * tuviera que hacer cola esperando a que el que estuviera corriendo en
     * ese momento terminara — de ahí la lentitud nueva y que el estado
     * (conectado/no conectado) tardara en reflejarse en la UI.
     *
     * La idea correcta no es "esperar a que termine el otro intento", es
     * "si ya hay uno en curso, no hace falta que yo también intente — dejo
     * que termine él". Por eso se cambia a un lock NO bloqueante (tryLock):
     * el hilo que consigue el lock hace el intento real de conectar como
     * siempre (rápido, sin cola); cualquier otro hilo que llegue mientras
     * tanto simplemente se retira al toque con false, sin esperar nada —
     * el llamador ya sabe manejar ese caso (el hilo persistente sigue su
     * loop solo; imprimir() ya tiene su propio manejo de error/reintento).
     * Con esto: un solo intento físico de conexión a la vez (se sigue
     * evitando la doble notifyReconectado() que causaba la reimpresión
     * duplicada), pero SIN bloquear ni enlentecer a nadie.
     */
    private final java.util.concurrent.locks.ReentrantLock connectLock =
            new java.util.concurrent.locks.ReentrantLock();

    @SuppressWarnings("MissingPermission")
    private boolean intentarConectarSincrono(String mac, long timeoutMs) {
        if (mac == null || mac.trim().isEmpty()) return false;
        if (conectado.get()) return true;
        if (!connectLock.tryLock()) {
            // Ya hay otro intento de conexión real en curso — no lo piso ni
            // lo espero, simplemente no hago nada acá. Ver comentario arriba.
            return false;
        }
        try {
            if (conectado.get()) return true; // pudo haber terminado justo antes de entrar acá
            return intentarConectarInterno(mac, timeoutMs);
        } finally {
            connectLock.unlock();
        }
    }

    /** Intento físico de conexión propiamente dicho — solo se llama con connectLock ya tomado. */
    @SuppressWarnings("MissingPermission")
    private boolean intentarConectarInterno(String mac, long timeoutMs) {
        if (!tienePermisoBluetoothConnect()) {
            Log.w(TAG, "Falta permiso BLUETOOTH_CONNECT");
            return false;
        }
        intentandoConectar.set(true);
        notifyListeners();
        cerrarSocketInterno();
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return false;
            }
            BluetoothDevice device = adapter.getRemoteDevice(mac);
            BluetoothSocket s = device.createRfcommSocketToServiceRecord(SPP_UUID);
            adapter.cancelDiscovery();
            conectarConTimeout(s, timeoutMs); // ★ FIX — antes s.connect() a secas podía colgarse sin límite real
            socket = s;
            outputStream = s.getOutputStream();
            inputStream = s.getInputStream();
            // ★ NUEVO — cada conexión física nueva puede ser una impresora
            // distinta (o la misma reiniciada); no arrastramos supuestos
            // viejos sobre si soporta la consulta de estado.
            soportaConsultaEstado = null;
            intentosSinRespuestaSeguidos.set(0);
            // ★ NUEVO — la transición real es acá: justo antes de esta línea
            // conectado.get() todavía es false (o recién se puso false por
            // cerrarSocketInterno() más arriba), así que este chequeo SÍ
            // distingue "recién se conectó" de "ya estaba conectado" (nunca
            // pasa por acá si ya estaba conectado — intentarConectarSincrono
            // solo se llama cuando conectado==false, ver iniciarConexionPersistente/imprimir()).
            conectado.set(true);
            notifyReconectado();
            return true;
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Fallo al conectar: " + e.getMessage());
            cerrarSocketInterno();
            return false;
        } finally {
            intentandoConectar.set(false);
            notifyListeners();
        }
    }

    /**
     * ★ NUEVO — {@link BluetoothSocket#connect()} es bloqueante y, a
     * diferencia de un Socket TCP normal, NO tiene un método setSoTimeout():
     * el parámetro timeoutMs que ya recibían intentarConectarSincrono/
     * intentarConectarInterno nunca se aplicaba a nada realmente — si el
     * dispositivo remoto quedaba en un estado raro (emparejado pero sin
     * responder), connect() podía quedarse colgado mucho más tiempo del
     * esperado, bloqueando ese intento (y con él, el candado connectLock)
     * sin límite real.
     *
     * connect() tampoco reacciona a Thread.interrupt() — la única forma
     * documentada de abortarlo desde afuera es cerrar el socket, lo que le
     * hace tirar IOException al hilo que está esperando adentro. Por eso
     * acá se corre connect() en un hilo aparte: si no termina dentro de
     * timeoutMs, se cierra el socket a la fuerza para desbloquearlo.
     */
    @SuppressWarnings("MissingPermission") // el permiso ya se valida antes, en intentarConectarInterno()
    private void conectarConTimeout(BluetoothSocket s, long timeoutMs) throws IOException {
        final IOException[] errorConexion = new IOException[1];
        final AtomicBoolean terminado = new AtomicBoolean(false);

        Thread hiloConexion = new Thread(() -> {
            try {
                s.connect();
            } catch (IOException e) {
                errorConexion[0] = e;
            } finally {
                terminado.set(true);
            }
        }, "PrinterConnectAttemptThreadSrv");
        hiloConexion.setDaemon(true);
        hiloConexion.start();

        try {
            hiloConexion.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!terminado.get()) {
            // No terminó a tiempo: forzamos que aborte cerrando el socket.
            // connect() no responde a interrupt(), pero SÍ tira IOException
            // en el hilo que está bloqueado adentro si se cierra el socket.
            Log.w(TAG, "connect() no respondió en " + timeoutMs + "ms — se fuerza el cierre para desbloquearlo");
            try { s.close(); } catch (IOException ignored) {}
            try { hiloConexion.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            throw new IOException("Tiempo de espera agotado conectando con la impresora (" + timeoutMs + "ms)");
        }

        if (errorConexion[0] != null) throw errorConexion[0];
    }

    private void cerrarSocketInterno() {
        // ★ NUEVO — getAndSet en vez de set: captura si REALMENTE estaba
        // conectada antes de este cierre. Así distinguimos una caída real
        // (estabaConectado=true, dispara el sonido de alerta una sola vez)
        // de las llamadas "de limpieza" que ya hace el código existente
        // cuando ya estaba desconectada (ej. al arrancar cada intento de
        // reconexión del hilo persistente) — esas NO deben sonar.
        boolean estabaConectado = conectado.getAndSet(false);
        try { if (outputStream != null) outputStream.close(); } catch (IOException ignored) {}
        try { if (inputStream != null) inputStream.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        outputStream = null;
        inputStream = null;
        socket = null;
        notifyListeners();
        if (estabaConectado) notifyDesconectado();
    }

    // ── Impresión ────────────────────────────────────────────────────────────

    /**
     * Envía los bytes del ticket a la impresora.
     *
     * - Si ya hay conexión activa, imprime directo.
     * - Si no hay conexión, intenta UNA reconexión con timeout corto antes de
     *   fallar (sin reintentos en bucle).
     * - Nunca reintenta imprimir automáticamente tras un fallo — a diferencia
     *   del cliente (que tiene un diálogo Reintentar/Cancelar manejado por el
     *   cajero), acá el llamador (ServidorHttp) solo loguea el error: el
     *   pedido YA se guardó y ya le llegó al cliente, así que no hay forma de
     *   bloquear nada esperando que alguien reintente. Ver notas en ServidorHttp.
     */
    public void imprimir(byte[] datos, PrintCallback cb) {
        ioExecutor.execute(() -> {
            if (!conectado.get()) {
                boolean ok = intentarConectarSincrono(macActual, 5000);
                if (!ok) {
                    mainHandler.post(() -> cb.onError(
                            "No se pudo conectar con la impresora. Verifica que esté encendida, cerca y emparejada."));
                    return;
                }
            }
            try {
                outputStream.write(datos);
                outputStream.flush();

                if (verificarImpresionReal()) {
                    mainHandler.post(cb::onImpreso);
                } else {
                    // ★ NUEVO — write()+flush() "salió bien" pero la impresora
                    // no confirmó nada real (ver verificarImpresionReal). Con
                    // la prioridad "evitar perdidos" se trata como un fallo:
                    // el llamador (ServidorHttp) lo deja con impreso=0 y el
                    // pedido entra en la cola de reintentos, en vez de darlo
                    // por impreso cuando puede que no haya salido nada.
                    Log.w(TAG, "Ticket enviado pero sin confirmación real de la impresora — se trata como fallo");
                    mainHandler.post(() -> cb.onError(
                            "No se pudo confirmar que la impresora recibió el ticket. Se reintentará."));
                }
            } catch (IOException e) {
                Log.w(TAG, "Fallo al escribir en la impresora: " + e.getMessage());
                cerrarSocketInterno(); // marca desconectado; el hilo persistente lo reconectará solo
                mainHandler.post(() -> cb.onError(
                        "Se perdió la conexión con la impresora mientras se enviaba el ticket."));
            }
        });
    }

    /**
     * ★ NUEVO — el "ping" periódico que dispara el hilo persistente cada
     * PING_INTERVAL_MS mientras está conectada e inactiva (sin pedidos que
     * imprimir). Corre siempre en ioExecutor (nunca en el hilo del
     * reconnectThread directamente) para no pisarse con un ticket real que
     * se esté escribiendo al mismo tiempo — ver el comentario en el loop de
     * iniciarConexionPersistente.
     *
     * Dos niveles de chequeo:
     *  1. Manda un byte NUL (0x00) — casi todas las impresoras ESC/POS lo
     *     ignoran sin efecto visible en el ticket, se usa solo como "¿seguís
     *     ahí?". Si el write()/flush() tira IOException, es una caída real
     *     y confirmada (el socket ya murió) → se cierra y se reconecta ACÁ
     *     MISMO, sin esperar el próximo ciclo del hilo persistente.
     *  2. Si ya sabemos que esta impresora responde a la consulta de estado
     *     (soportaConsultaEstado == true), además reusa verificarImpresionReal()
     *     para pescar el caso más difícil: el write() "sale bien" (el socket
     *     todavía no se enteró de la desconexión a nivel de radio) pero la
     *     impresora physically ya no está — el mismo escenario que motivó
     *     agregar la consulta DLE EOT en primer lugar, ahora también cubierto
     *     en el chequeo periódico y no solo justo después de imprimir.
     */
    private void probarConexionInactiva() {
        if (!conectado.get()) return;
        OutputStream os = outputStream;
        if (os == null) { cerrarSocketInterno(); return; }

        boolean caidaDetectada = false;
        try {
            os.write(0x00);
            os.flush();
        } catch (IOException e) {
            Log.w(TAG, "Ping de conexión inactivo falló — la impresora ya no responde: " + e.getMessage());
            cerrarSocketInterno();
            caidaDetectada = true;
        }

        if (!caidaDetectada && Boolean.TRUE.equals(soportaConsultaEstado) && !verificarImpresionReal()) {
            if (conectado.get()) { // verificarImpresionReal ya pudo haber cerrado el socket por su cuenta
                Log.w(TAG, "La impresora no respondió al ping de estado — se la da por desconectada");
                cerrarSocketInterno();
            }
            caidaDetectada = true;
        }

        if (caidaDetectada) {
            // No esperamos al próximo ciclo del hilo persistente (hasta
            // PING_INTERVAL_MS después) — reconectamos ya mismo, así el
            // tiempo entre "se detecta la caída" y "vuelve a estar
            // disponible" es el mínimo posible.
            intentarConectarSincrono(macActual, 5000);
        }
    }

    /**
     * Manda el comando ESC/POS estándar "DLE EOT n" (consulta de estado en
     * tiempo real) justo después del ticket, y espera una respuesta corta.
     * CUALQUIER byte de respuesta ya prueba que el ticket anterior SÍ llegó
     * físicamente (no hace falta interpretar los bits exactos del estado
     * para eso — con solo saber que la impresora "contestó algo" alcanza).
     *
     * Maneja el caso de impresoras clon que no implementan este comando
     * (no responden NUNCA): tras {@link #INTENTOS_SIN_RESPUESTA_PARA_DESISTIR}
     * intentos seguidos sin respuesta, deja de exigirla y vuelve al
     * comportamiento viejo (confiar en que write() no tiró error) — para no
     * terminar tratando cada ticket como sospechoso para siempre.
     */
    private boolean verificarImpresionReal() {
        if (Boolean.FALSE.equals(soportaConsultaEstado)) {
            return true; // ya determinamos que esta impresora no responde a esto — no preguntamos más
        }
        try {
            outputStream.write(DLE_EOT_ESTADO_IMPRESORA);
            outputStream.flush();

            long limite = System.currentTimeMillis() + TIMEOUT_CONSULTA_ESTADO_MS;
            while (System.currentTimeMillis() < limite) {
                if (inputStream != null && inputStream.available() > 0) {
                    int respuesta = inputStream.read();
                    if (respuesta >= 0) {
                        soportaConsultaEstado = true;
                        intentosSinRespuestaSeguidos.set(0);
                        return true; // respondió algo → el ticket anterior sí llegó físicamente
                    }
                }
                try { Thread.sleep(20); } catch (InterruptedException ignored) { return true; }
            }
        } catch (IOException e) {
            // El socket ya murió al mandar la consulta — esto sí es un fallo
            // real de conexión, no una ambigüedad de "no soporta el comando".
            Log.w(TAG, "Fallo al consultar estado de la impresora: " + e.getMessage());
            cerrarSocketInterno();
            return false;
        }

        // Sin respuesta dentro del tiempo límite.
        if (soportaConsultaEstado == null) {
            int fallas = intentosSinRespuestaSeguidos.incrementAndGet();
            if (fallas >= INTENTOS_SIN_RESPUESTA_PARA_DESISTIR) {
                // Lo más probable a esta altura es que sea un clon que no
                // implementa el comando (muy común), no que se hayan perdido
                // 3 tickets seguidos — dejamos de exigir la consulta.
                soportaConsultaEstado = false;
                Log.i(TAG, "La impresora nunca respondió a la consulta de estado tras " + fallas
                        + " intentos — se asume que no la soporta, se deja de verificar.");
                return true;
            }
            return false; // todavía no sabemos qué es esto — priorizamos sospechar (evitar perdidos)
        }

        // Ya nos había confirmado antes que SÍ soporta la consulta (respondió
        // alguna vez) y ahora no respondió — señal fuerte de que algo falló
        // de verdad (se apagó, se quedó sin papel, etc.).
        return false;
    }

    public String getMacActual() {
        return macActual;
    }

    /** Lista de dispositivos Bluetooth ya emparejados desde Ajustes del teléfono/tablet del servidor. */
    @SuppressWarnings("MissingPermission")
    public Set<BluetoothDevice> getDispositivosEmparejados() {
        if (!tienePermisoBluetoothConnect()) return java.util.Collections.emptySet();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return java.util.Collections.emptySet();
        try {
            return adapter.getBondedDevices();
        } catch (SecurityException e) {
            return java.util.Collections.emptySet();
        }
    }

    public boolean bluetoothDisponibleYEncendido() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }
}