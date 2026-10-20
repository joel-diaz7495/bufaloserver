package com.example.bufaloserver.http;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.bufaloserver.db.AppDatabaseServidor;
import com.example.bufaloserver.db.entities.Categoria;
import com.example.bufaloserver.db.entities.Auditoria;
import com.example.bufaloserver.db.entities.Dispositivo;
import com.example.bufaloserver.db.entities.TokenEmparejamiento;
import com.example.bufaloserver.db.entities.GastoDiario;
import com.example.bufaloserver.db.entities.GastoGeneral;
import com.example.bufaloserver.db.entities.GrupoModificador;
import com.example.bufaloserver.db.entities.ItemModificadorSeleccionado;
import com.example.bufaloserver.db.entities.ItemPedido;
import com.example.bufaloserver.db.entities.ItemSalsa;
import com.example.bufaloserver.db.entities.ModificadorOpcion;
import com.example.bufaloserver.db.entities.Pedido;
import com.example.bufaloserver.db.entities.Producto;
import com.example.bufaloserver.db.entities.SalsaGlobal;
import com.example.bufaloserver.db.entities.Usuario;
import com.example.bufaloserver.db.entities.VarianteProducto;
import com.example.bufaloserver.db.entities.MovimientoInventario;
import com.example.bufaloserver.ws.ServidorWebSocket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.security.SecureRandom;

/**
 * Un único router chico en vez de un framework de rutas — a propósito, para
 * mantener el servidor liviano. Cada ruta nueva del contrato se agrega como
 * un método propio y una línea más en el switch de serve().
 *
 * IMPORTANTE — hilo: NanoHTTPD atiende cada request en un hilo propio del
 * pool interno, así que cualquier acceso a Room acá adentro puede ser
 * síncrono sin trabar el resto del servidor.
 */
public class ServidorHttp extends NanoHTTPD {

    private static final String TAG = "ServidorHttp";

    private final Context context;
    private ServidorWebSocket ws;

    /**
     * Lock para asignar numero_orden de forma atómica.
     *
     * ANTES: el numeroOrden lo calculaba cada CLIENTE en MainActivity.
     * inicializarNumeroOrden() (MAX del servidor al abrir la app + contador
     * local que va incrementando). Eso funciona con una sola tablet, pero
     * con dos o más tablets conectadas al mismo servidor, cada una arranca
     * su propio contador independiente → dos pedidos de tablets distintas
     * pueden terminar con el MISMO numeroOrden (o el servidor nunca "ve" el
     * número real, porque simplemente guarda lo que cada cliente le manda).
     *
     * AHORA: el servidor es la única fuente de verdad. crearPedido() ignora
     * el numeroOrden que venga en el JSON y le asigna el que corresponde acá
     * mismo, bajo este lock, para que dos requests concurrentes (NanoHTTPD
     * atiende cada uno en su propio hilo) no calculen el mismo MAX+1 antes
     * de que el otro haya insertado.
     *
     * ★ FIX — este comentario ya describía la intención correcta, pero el
     * código no la cumplía: el synchronized vivía SOLO adentro de
     * siguienteNumeroOrden(), así que se soltaba apenas se calculaba el
     * MAX+1, antes del insert real. Ahora crearPedido() sostiene este lock
     * desde el cálculo hasta que termina el insert (ver el synchronized que
     * envuelve el runInTransaction ahí), que es lo que hace falta para que
     * la garantía de este comentario sea real.
     */
    private static final Object LOCK_NUMERO_ORDEN = new Object();

    // ── Sesiones (dispositivos autorizados) ─────────────────────────────────
    //
    // ★ NUEVO — sistema de dispositivos autorizados. Antes el token de
    // /login se generaba al vuelo (UUID.randomUUID()) y NUNCA se volvía a
    // validar en ningún otro endpoint — cualquiera con el PIN de admin podía
    // entrar desde cualquier celular, y una vez adentro, el "token" no
    // protegía nada. Ahora:
    //
    //   1. Un dispositivo solo puede pedir /login si manda un deviceToken
    //      válido y AUTORIZADO (ver Dispositivo, tabla dispositivos), atado
    //      a un usuarioId fijo — el PIN tiene que coincidir con ESE usuario
    //      específico, no con cualquiera.
    //   2. /login exitoso guarda una Sesion en este mapa en memoria, y ese es
    //      el sessionToken que el cliente manda como "Authorization: Bearer".
    //   3. Revocar un dispositivo (Dispositivo.estado = REVOCADO) además
    //      tira abajo cualquier sesión activa de ese dispositivo acá mismo —
    //      efecto inmediato, sin esperar a que expire nada.
    //
    // Vive en memoria (no en la tabla) a propósito: si el servidor se
    // reinicia, todos vuelven a loguearse — comportamiento aceptable para un
    // servidor POS local, y más simple que persistir sesiones.
    private static final class Sesion {
        final long usuarioId;
        final long dispositivoId;
        final String rol;
        Sesion(long usuarioId, long dispositivoId, String rol) {
            this.usuarioId = usuarioId;
            this.dispositivoId = dispositivoId;
            this.rol = rol;
        }
    }

    private static final Map<String, Sesion> SESIONES = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM_SEGURO = new SecureRandom();

    /** Token largo y aleatorio — se usa tanto para deviceToken como para el
     *  código dentro del QR de emparejamiento. 32 bytes al azar en base64. */
    private static String generarTokenSeguro() {
        byte[] datos = new byte[32];
        RANDOM_SEGURO.nextBytes(datos);
        return android.util.Base64.encodeToString(datos, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    /** Lee "Authorization: Bearer xxx" y devuelve la sesión activa, o null si
     *  no hay header, el token no existe, o fue invalidado (revocación). */
    @Nullable
    private Sesion sesionDe(IHTTPSession session) {
        String auth = session.getHeaders().get("authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return SESIONES.get(auth.substring("Bearer ".length()).trim());
    }

    /** Elimina cualquier sesión activa asociada a un dispositivo — se llama
     *  al revocar, para que el corte de acceso sea inmediato y no dependa de
     *  que el token "expire" solo. */
    private void invalidarSesionesDeDispositivo(long dispositivoId) {
        SESIONES.values().removeIf(s -> s.dispositivoId == dispositivoId);
    }

    /**
     * ★ NUEVO — expone si un token de sesión está vigente AHORA MISMO, sin
     * exponer la clase Sesion en sí (que sigue siendo privada de esta
     * clase). Usado por ServidorWebSocket.onWebsocketHandshakeReceivedAsServer
     * para rechazar la conexión de un cliente que no esté logueado — antes
     * el WebSocket no validaba nada y cualquier equipo en la red se
     * conectaba sin sesión. Comparte exactamente el mismo mapa SESIONES que
     * usa sesionDe() para HTTP, así que un logout, una revocación de
     * dispositivo (invalidarSesionesDeDispositivo) o de usuario
     * (invalidarSesionesDeUsuario) cortan el acceso al WebSocket igual de
     * inmediato que al HTTP.
     */
    public static boolean tokenSesionValido(String token) {
        return token != null && SESIONES.containsKey(token);
    }

    /**
     * ★ FIX SEGURIDAD — mismo criterio que invalidarSesionesDeDispositivo,
     * pero por usuario: antes, eliminar/desactivar un usuario desde Gestión
     * de usuarios NO cortaba ninguna sesión ya abierta de ese usuario — el
     * token seguía siendo válido en SESIONES (vive en memoria, no se
     * revalida contra la tabla usuarios en cada request), así que ese
     * empleado podía seguir mandando pedidos con normalidad hasta que él
     * mismo cerrara sesión manualmente (recién ahí /login lo volvía a
     * chequear y lo rechazaba). El pedido igual terminaba mostrando "Usuario
     * eliminado" en la comanda (ver nombreUsuarioSync), pero el acceso en sí
     * no se cortaba. Se llama desde eliminarUsuario/setActivoUsuario/
     * actualizarUsuario apenas el usuario queda eliminado o inactivo.
     */
    private void invalidarSesionesDeUsuario(long usuarioId) {
        SESIONES.values().removeIf(s -> s.usuarioId == usuarioId);
    }

    /**
     * ★ FIX BUG CRÍTICO — reimpresión múltiple al apagar/prender la
     * impresora: PrinterManager es un singleton de TODA la app, pero
     * ServidorHttp se vuelve a crear cada vez que el servicio se reinicia
     * (watchdog, Android matando el proceso, apagar/prender el servidor
     * desde la app). Antes, cada instancia nueva AGREGABA otra copia de
     * reintentarPedidosNoImpresos() a PrinterManager sin sacar nunca las
     * viejas (no había ningún removeReconnectListener en todo el
     * proyecto) — así que después de varios reinicios del servicio, un
     * solo evento de reconexión disparaba 2, 3, 4+ copias del mismo
     * reintento EN SIMULTÁNEO (la DB usa un pool de 4 hilos, no uno solo),
     * pisándose entre sí antes de que cualquiera terminara de marcar
     * impreso=true — el mismo lote de pedidos salía impreso varias veces
     * seguidas, dando la sensación de "imprime todos los pedidos".
     *
     * Fix: se guardan en variables ESTÁTICAS (sobreviven a la instancia)
     * los listeners que la construcción ANTERIOR de ServidorHttp haya
     * registrado, y se sacan de PrinterManager antes de registrar los
     * nuevos. Así solo queda activa siempre la copia de la instancia más
     * reciente — que es exactamente lo que se quiere, porque el context
     * que usa reintentarPedidosNoImpresos() siempre es applicationContext
     * de cualquier forma (da lo mismo qué instancia quede viva).
     */
    private static com.example.bufaloserver.print.PrinterManager.ReconnectListener reintentoListenerActivo;
    private static com.example.bufaloserver.print.PrinterManager.DisconnectListener sonidoDesconexionListenerActivo;
    private static com.example.bufaloserver.print.PrinterManager.ReconnectListener sonidoReconexionListenerActivo;

    public ServidorHttp(Context context, int puerto) {
        super(puerto);
        this.context = context.getApplicationContext();

        com.example.bufaloserver.print.PrinterManager pm =
                com.example.bufaloserver.print.PrinterManager.get(this.context);

        // Saca los listeners de la instancia anterior (si hubo una) antes
        // de registrar los de esta — ver comentario arriba.
        if (reintentoListenerActivo != null) pm.removeReconnectListener(reintentoListenerActivo);
        if (sonidoDesconexionListenerActivo != null) pm.removeDisconnectListener(sonidoDesconexionListenerActivo);
        if (sonidoReconexionListenerActivo != null) pm.removeReconnectListener(sonidoReconexionListenerActivo);

        // ★ NUEVO — arregla el hueco real que había con la impresora
        // desconectada: antes, si un pedido se creaba/cobraba sin que la
        // impresora estuviera conectada, imprimirTicketPedido() marcaba
        // impreso=false (ver marcarImpresoAsync) y ahí quedaba PARA SIEMPRE
        // — a diferencia de "se acabó el papel" (que el propio hardware de
        // la impresora bufferea y saca solo apenas se le pone papel), acá
        // no había ningún reintento: el pedido seguía guardado en la base,
        // pero su ticket ya nunca salía solo. Con este listener, apenas
        // PrinterManager detecta que la impresora volvió a conectarse
        // (Bluetooth reconectado, no "hay papel o no"), se buscan todos los
        // pedidos de las últimas 24h con impreso=0 y se reimprimen en orden,
        // sin que nadie tenga que darse cuenta ni tocar nada.
        reintentoListenerActivo = this::reintentarPedidosNoImpresos;
        pm.addReconnectListener(reintentoListenerActivo);

        // ★ NUEVO — alertas sonoras de conexión de la impresora: un tono de
        // error apenas se cae la conexión, y un doble-beep "positivo" apenas
        // vuelve a conectar (justo cuando, gracias al listener de arriba,
        // ya se están reimprimiendo solos los pedidos pendientes).
        sonidoDesconexionListenerActivo = com.example.bufaloserver.servicio.SonidosServidor::impresoraDesconectada;
        sonidoReconexionListenerActivo = com.example.bufaloserver.servicio.SonidosServidor::impresoraReconectada;
        pm.addDisconnectListener(sonidoDesconexionListenerActivo);
        pm.addReconnectListener(sonidoReconexionListenerActivo);
    }

    /**
     * ★ NUEVO — ver comentario en el constructor. Se llama automáticamente
     * cada vez que PrinterManager pasa de desconectado a conectado. Reimprime,
     * en el mismo orden en que se crearon, todos los pedidos de las últimas
     * 24h que quedaron con impreso=0 (no cancelados) — mismo camino que
     * imprimirTicketPedido() ya usa para /pedido y /pedido/{id}/reimprimir,
     * así que si ESTE intento también falla (la impresora se volvió a caer
     * a los 2 segundos de reconectar, por ejemplo), simplemente vuelve a
     * quedar impreso=0 y se reintentará solo la PRÓXIMA vez que reconecte
     * — no hace falta ningún caso especial acá.
     *
     * ★ FIX — red de seguridad adicional: reimprimiendoEnCurso evita que dos
     * disparos casi simultáneos de este método (por la razón que sea, ya sea
     * la carrera que existía en PrinterManager.intentarConectarSincrono —
     * arreglada por separado — o cualquier otra que aparezca) terminen
     * leyendo la misma lista de "pedidos sin imprimir" y reimprimiéndola dos
     * veces antes de que la primera pasada llegue a marcar impreso=true. Si
     * ya hay una pasada en curso, la segunda se ignora directamente: cuando
     * la primera termine, cualquier pedido genuinamente nuevo que haya
     * quedado sin imprimir se agarra solo en la PRÓXIMA reconexión.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean reimprimiendoEnCurso =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void reintentarPedidosNoImpresos() {
        if (!reimprimiendoEnCurso.compareAndSet(false, true)) {
            android.util.Log.i(TAG, "Ya hay una reimpresión de pendientes en curso — se ignora este evento duplicado");
            return;
        }
        AppDatabaseServidor.dbExecutor.execute(() -> {
            try {
                long desde = System.currentTimeMillis() - (24L * 60 * 60 * 1000);
                List<Pedido> pendientes = db().pedidoDao().getNoImpresosSync(desde);
                if (pendientes.isEmpty()) return;
                android.util.Log.i(TAG, "Impresora reconectada: reimprimiendo " + pendientes.size()
                        + " pedido(s) que habían quedado sin imprimir");
                for (Pedido pedido : pendientes) {
                    List<ItemPedido> items = db().itemPedidoDao().getPorPedidoSync(pedido.id);
                    cargarDetalleItems(items);
                    imprimirTicketPedido(pedido, items);
                }
            } finally {
                reimprimiendoEnCurso.set(false);
            }
        });
    }

    /** Se setea desde ServidorService una vez que ambos servidores están arriba
     *  (hay un momento en el arranque en que ServidorHttp existe pero el
     *  WebSocket todavía no — por eso esto no va en el constructor). Si nunca
     *  se llama (ej. tests), los avisos simplemente no se emiten. */
    public void setWebSocket(ServidorWebSocket ws) {
        this.ws = ws;
    }

    private AppDatabaseServidor db() {
        return AppDatabaseServidor.getInstance(context);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method metodo = session.getMethod();

        try {
            if (uri.equals("/login") && metodo == Method.POST) {
                return manejarLogin(session);
            }
            if (uri.equals("/admin/inicial") && metodo == Method.POST) {
                return manejarAdminInicial(session);
            }
            if (uri.equals("/dispositivos/emparejar/generar") && metodo == Method.POST) {
                return manejarGenerarTokenEmparejamiento(session);
            }
            if (uri.equals("/emparejar") && metodo == Method.POST) {
                return manejarEmparejar(session);
            }
            if (uri.equals("/dispositivos") && metodo == Method.GET) {
                return manejarListarDispositivos(session);
            }
            if (uri.matches("/dispositivos/\\d+/revocar") && metodo == Method.POST) {
                return manejarRevocarDispositivo(session, idPenultimoDesdeUri(uri));
            }
            if (uri.matches("/dispositivos/\\d+/reautorizar") && metodo == Method.POST) {
                return manejarReautorizarDispositivo(session, idPenultimoDesdeUri(uri));
            }
            if (uri.equals("/auditoria") && metodo == Method.GET) {
                return manejarAuditoria(session);
            }
            // ★ NUEVO — limpieza de datos antiguos (ver servicio/LimpiezaDatos).
            //   GET  /limpieza/estado        cualquier sesión (Reportes lo usa para limitar ◀)
            //   GET  /resumenes-mensuales    cualquier sesión que pueda ver reportes
            //   POST /limpieza/simular       solo administrador (no borra nada)
            //   POST /limpieza/ejecutar      solo administrador
            if (uri.equals("/limpieza/estado") && metodo == Method.GET) {
                return manejarLimpiezaEstado(session);
            }
            if (uri.equals("/resumenes-mensuales") && metodo == Method.GET) {
                return manejarResumenesMensuales(session);
            }
            if (uri.equals("/limpieza/simular") && metodo == Method.POST) {
                return manejarLimpiezaSimularOEjecutar(session, false);
            }
            if (uri.equals("/limpieza/ejecutar") && metodo == Method.POST) {
                return manejarLimpiezaSimularOEjecutar(session, true);
            }
            if (uri.equals("/menu") && metodo == Method.GET) {
                return manejarMenu(session);
            }
            if (uri.equals("/gastos") && metodo == Method.GET) {
                return listarGastos(session);
            }
            if (uri.equals("/gastos") && metodo == Method.POST) {
                return crearGasto(session);
            }
            if (uri.startsWith("/gastos/pendientes") && metodo == Method.POST) {
                return crearGastoPendiente(session);
            }
            if (uri.matches("^/gastos/\\d+$") && metodo == Method.PUT) {
                return actualizarGasto(session, idDesdeUri(uri));
            }
            if (uri.matches("^/gastos/\\d+$") && metodo == Method.DELETE) {
                return eliminarGasto(session, idDesdeUri(uri));
            }
            // ── /inventario — Entrada/Salida/Historial (pantalla InventarioActivity) ──
            // Contrato:
            //   POST /inventario/movimiento     {productoId|varianteId, tipo, cantidad, motivo}
            //   POST /inventario/stock-minimo   {productoId|varianteId, minimo}
            //   POST /inventario/stock-maximo   {productoId|varianteId, maximo}   ★ NUEVO
            //   GET  /inventario/movimientos?productoId=X  ó  ?varianteId=X
            if (uri.equals("/inventario/movimiento") && metodo == Method.POST) {
                return registrarMovimientoInventario(session);
            }
            if (uri.equals("/inventario/stock-minimo") && metodo == Method.POST) {
                return actualizarStockMinimoInventario(session);
            }
            if (uri.equals("/inventario/stock-maximo") && metodo == Method.POST) {
                return actualizarStockMaximoInventario(session);
            }
            if (uri.equals("/inventario/movimientos") && metodo == Method.GET) {
                return listarMovimientosInventario(session);
            }
            // ── /gastos-generales — "Gastos generales" (pestaña ② de GastosActivity) ──
            // ★ Reemplaza a /gastos-fijos + /gastos-fijos/pagos (2 endpoints
            // para una sola idea). Cada fila es un registro INDEPENDIENTE de
            // un mes/año — no hay relación padre-hijo entre meses, así que
            // borrar una fila jamás afecta a las demás (antes: borrar el
            // gasto fijo completo borraba en cascada TODOS sus pagos,
            // incluidos los de meses anteriores ya cerrados — ese era el bug
            // reportado).
            if (uri.equals("/gastos-generales") && metodo == Method.GET) {
                return listarGastosGenerales(session);
            }
            if (uri.equals("/gastos-generales") && metodo == Method.POST) {
                return crearGastoGeneral(session);
            }
            if (uri.matches("^/gastos-generales/\\d+$") && metodo == Method.PUT) {
                return actualizarGastoGeneral(session, idDesdeUri(uri));
            }
            if (uri.matches("^/gastos-generales/\\d+$") && metodo == Method.DELETE) {
                return eliminarGastoGeneral(session, idDesdeUri(uri));
            }
            if (uri.equals("/salsas") && metodo == Method.GET) {
                return listarSalsas(session);
            }
            if (uri.equals("/salsas") && metodo == Method.POST) {
                return crearSalsa(session);
            }
            if (uri.matches("^/salsas/\\d+/activo$") && metodo == Method.PUT) {
                return setActivoSalsa(session, idPenultimoDesdeUri(uri));
            }
            if (uri.matches("^/salsas/\\d+/nombre$") && metodo == Method.PUT) {
                return setNombreSalsa(session, idPenultimoDesdeUri(uri));
            }
            if (uri.matches("^/salsas/\\d+$") && metodo == Method.DELETE) {
                return eliminarSalsa(idDesdeUri(uri));
            }
            if (uri.equals("/pedido") && metodo == Method.POST) {
                return crearPedido(session);
            }
            if (uri.matches("^/pedido/\\d+$") && metodo == Method.PUT) {
                return actualizarPedido(session, idDesdeUri(uri));
            }
            if (uri.matches("^/pedido/\\d+/reimprimir$") && metodo == Method.POST) {
                return reimprimirPedido(session, idPenultimoDesdeUri(uri));
            }
            if (uri.matches("^/pedido/\\d+/cancelar$") && metodo == Method.POST) {
                return cancelarPedido(session, idPenultimoDesdeUri(uri));
            }
            // ★ NUEVO — detalle completo (con salsas/modificadores) de UN
            // pedido puntual, para "Ventas del día" (ver obtenerPedido). Va
            // después del match de /reimprimir para que "^/pedido/\d+$" no
            // le gane la carrera a esa ruta más específica.
            if (uri.matches("^/pedido/\\d+$") && metodo == Method.GET) {
                return obtenerPedido(session, idDesdeUri(uri));
            }
            if (uri.equals("/pedidos") && metodo == Method.GET) {
                return listarPedidos(session);
            }
            if (uri.equals("/pedidos/pendientes") && metodo == Method.GET) {
                return listarPedidosPendientes();
            }
            if (uri.equals("/pedidos/max-numero-orden") && metodo == Method.GET) {
                return maxNumeroOrden();
            }
            // ★ NUEVO — "Ventas del día": pantalla del menú. Devuelve TODOS los pedidos de hoy (cualquier
            // estado) con su detalle completo de items, para poder rastrear
            // cualquier orden puntual (ver manejarVentasDelDia más abajo).
            if (uri.equals("/pedidos/hoy") && metodo == Method.GET) {
                return manejarVentasDelDia(session);
            }
            // ★ NUEVO — botón "🖨️ Imprimir" del "Reporte del día" / "Ventas
            // del día" en modo Conectado: le pide al SERVIDOR que calcule el
            // resumen del día (mismo criterio que ReportesActivity.
            // cargarReporteDia()) y lo imprima en SU propia impresora, igual
            // patrón que /pedido/{id}/reimprimir — el cliente no manda nada
            // en el body, la impresión la hace el servidor.
            if (uri.equals("/reportes/dia/imprimir") && metodo == Method.POST) {
                return imprimirResumenDia(session);
            }
            // ★ NUEVO — previsualización de los 3 reportes de impresora (resumen del día,
            // caja e inventario): devuelve las líneas del MISMO ticket que se imprime,
            // para mostrarlas en pantalla antes de mandar a imprimir. No imprime nada y
            // no exige impresora configurada (eso se valida recién al imprimir).
            if (uri.equals("/reportes/dia/previsualizar") && metodo == Method.GET) {
                return previsualizarResumenDia(session);
            }
            if (uri.equals("/reportes/caja/previsualizar") && metodo == Method.GET) {
                return previsualizarReporteCaja(session);
            }
            if (uri.equals("/reportes/inventario/previsualizar") && metodo == Method.GET) {
                return previsualizarReporteInventario(session);
            }
            // ★ NUEVO — botón "🖨️ Reporte de Caja": separado del resumen del
            // día de arriba. Ese calcula UTILIDAD (vendido − gastos); este
            // calcula EFECTIVO EN CAJA (vendido − qr − tarjeta − gastos del
            // día − adelantos), para contar la caja física.
            if (uri.equals("/reportes/caja/imprimir") && metodo == Method.POST) {
                return imprimirReporteCaja(session);
            }
            // ★ NUEVO — botón "🖨️ Inventario": imprime el estado ACTUAL del inventario
            // (actual / máximo / faltante por producto). El cliente no manda nada: el servidor
            // lee su propia base e imprime en su impresora, igual patrón que el reporte de caja.
            if (uri.equals("/reportes/inventario/imprimir") && metodo == Method.POST) {
                return imprimirReporteInventario(session);
            }
            if (uri.equals("/usuarios") && metodo == Method.GET) {
                return listarUsuarios();
            }
            if (uri.equals("/categorias") && metodo == Method.POST) {
                return crearCategoria(session);
            }
            if (uri.matches("^/categorias/\\d+/activo$") && metodo == Method.PUT) {
                return setActivoCategoria(session, idPenultimoDesdeUri(uri));
            }
            if (uri.matches("^/categorias/\\d+/imprimir$") && metodo == Method.PUT) {
                return setImprimirCategoria(session, idPenultimoDesdeUri(uri));
            }
            if (uri.matches("^/categorias/\\d+$") && metodo == Method.DELETE) {
                return eliminarCategoria(idDesdeUri(uri));
            }
            if (uri.matches("^/productos/\\d+/activo$") && metodo == Method.PUT) {
                return setActivoProducto(session, idPenultimoDesdeUri(uri));
            }
            if (uri.matches("^/productos/\\d+$") && metodo == Method.GET) {
                return obtenerProducto(idDesdeUri(uri));
            }
            if (uri.equals("/productos") && metodo == Method.POST) {
                return crearProducto(session);
            }
            if (uri.matches("^/productos/\\d+$") && metodo == Method.PUT) {
                return actualizarProducto(session, idDesdeUri(uri));
            }
            if (uri.matches("^/productos/\\d+$") && metodo == Method.DELETE) {
                return eliminarProducto(idDesdeUri(uri));
            }
            if (uri.equals("/usuarios") && metodo == Method.POST) {
                return crearUsuario(session);
            }
            if (uri.matches("^/usuarios/\\d+/activo$") && metodo == Method.PUT) {
                return setActivoUsuario(session, idPenultimoDesdeUri(uri));
            }
            if (uri.matches("^/usuarios/\\d+$") && metodo == Method.PUT) {
                return actualizarUsuario(session, idDesdeUri(uri));
            }
            // ★ NUEVO — borrado real de usuario (antes solo existía
            // desactivar/setActivo). Ver eliminarUsuario() para la
            // protección del primer administrador.
            if (uri.matches("^/usuarios/\\d+$") && metodo == Method.DELETE) {
                return eliminarUsuario(idDesdeUri(uri));
            }

            // Ruta no reconocida — placeholder mientras se suman el resto de
            // endpoints del contrato uno por uno.
            return jsonError(Status.NOT_FOUND, "Ruta no implementada: " + metodo + " " + uri);

        } catch (Exception e) {
            // ★ FIX — antes esta excepción se tragaba en silencio: el
            // cliente veía "código 500" en el toast, pero en el Logcat del
            // SERVIDOR no quedaba ni rastro de qué la causó. Ahora queda
            // logueada completa (con stack trace) bajo el tag "ServidorHttp",
            // filtrable desde Android Studio o `adb logcat -s ServidorHttp`.
            Log.e(TAG, "Error atendiendo " + metodo + " " + uri, e);
            return jsonError(Status.INTERNAL_ERROR, "Error interno: " + e.getMessage());
        }
    }

    private long idDesdeUri(String uri) {
        String[] partes = uri.split("/");
        return Long.parseLong(partes[partes.length - 1]);
    }

    /** Para rutas tipo /usuarios/{id}/activo, donde el id no es el último segmento. */
    private long idPenultimoDesdeUri(String uri) {
        String[] partes = uri.split("/");
        return Long.parseLong(partes[partes.length - 2]);
    }

    private String primerParametro(Map<String, List<String>> params, String clave) {
        List<String> valores = params.get(clave);
        return (valores != null && !valores.isEmpty()) ? valores.get(0) : null;
    }

    // ── /login ───────────────────────────────────────────────────────────────
    /**
     * ★ CAMBIO DE SEGURIDAD — antes esto solo miraba el PIN: cualquiera que
     * supiera el PIN de administrador entraba como administrador desde
     * CUALQUIER teléfono. Ahora exige ADEMÁS un deviceToken de un
     * dispositivo previamente autorizado (ver /emparejar), y ese
     * deviceToken tiene que estar atado JUSTAMENTE al usuario dueño del PIN
     * — un PIN correcto tecleado desde el celular de otra persona ya no
     * alcanza.
     */
    private Response manejarLogin(IHTTPSession session) throws IOException, ResponseException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");
        String pin = entrada.optString("pin", "");
        String deviceToken = entrada.optString("deviceToken", "");

        if (deviceToken.isEmpty()) {
            return jsonError(Status.FORBIDDEN, "DISPOSITIVO_NO_EMPAREJADO");
        }

        Dispositivo dispositivo = db().dispositivoDao().getByTokenSync(deviceToken);
        if (dispositivo == null) {
            return jsonError(Status.FORBIDDEN, "DISPOSITIVO_NO_EMPAREJADO");
        }
        if (!dispositivo.estaAutorizado()) {
            return jsonError(Status.FORBIDDEN, "DISPOSITIVO_REVOCADO");
        }

        Usuario usuario = db().usuarioDao().getByIdSync(dispositivo.usuarioId);
        if (usuario == null || !usuario.activo) {
            return jsonError(Status.UNAUTHORIZED, "Usuario desactivado");
        }
        // El PIN tiene que coincidir con el usuario DUEÑO de este dispositivo
        // en particular — comparar contra el PIN de ESE usuario en vez de
        // volver a buscar "cualquier usuario con este pin" es justamente lo
        // que cierra el hueco: da igual si otro empleado te dice su PIN, tu
        // celular solo puede entrar como vos.
        // ★ FIX — antes "usuario.pin.equals(pin)" contra el PIN en texto
        // plano; ver Usuario.coincideConPin / PinHasher.
        long tVerif = android.os.SystemClock.elapsedRealtime();
        boolean[] desdeCache = new boolean[1];
        boolean pinOk = usuario.coincideConPinRapido(pin, desdeCache);
        long msVerif = android.os.SystemClock.elapsedRealtime() - tVerif;
        android.util.Log.i("LoginTiempo", "verificar PIN usuario " + usuario.id + ": " + msVerif
                + " ms (" + (pinOk ? "ok" : "incorrecto") + (desdeCache[0] ? ", caché" : "") + ")");
        if (!pinOk) {
            return jsonError(Status.UNAUTHORIZED, "PIN incorrecto");
        }
        // ★ FIX LOGIN LENTO — hash guardado con el costo viejo (120k): se
        // re-hashea UNA vez con el costo nuevo, así los próximos logins son
        // rápidos. Si falla, no importa: el login ya es válido.
        if (!desdeCache[0] && usuario.pinNecesitaActualizacion()) {
            try {
                usuario.establecerPin(pin);
                db().usuarioDao().update(usuario);
                android.util.Log.i("LoginTiempo", "PIN del usuario " + usuario.id + " re-hasheado con el costo nuevo");
            } catch (Exception e) {
                android.util.Log.w("LoginTiempo", "No se pudo actualizar el hash del PIN", e);
            }
        }

        String sessionToken = generarTokenSeguro();
        SESIONES.put(sessionToken, new Sesion(usuario.id, dispositivo.id, usuario.rol));
        // ★ MEJORA VELOCIDAD — la escritura de "último uso" ya no bloquea la
        // respuesta del login (era un write a disco síncrono, lento en flash viejo).
        final long idDispositivo = dispositivo.id;
        final long ahora = System.currentTimeMillis();
        AppDatabaseServidor.dbExecutor.execute(() -> {
            try { db().dispositivoDao().marcarUso(idDispositivo, ahora); }
            catch (Exception e) { android.util.Log.w("LoginTiempo", "marcarUso falló", e); }
        });

        JSONObject respuesta = new JSONObject();
        try {
            respuesta.put("usuarioId", usuario.id);
            respuesta.put("nombre", usuario.nombre);
            respuesta.put("rol", usuario.rol);
            respuesta.put("token", sessionToken);
        } catch (JSONException ignored) {}
        return jsonOk(respuesta.toString());
    }

    // ── /admin/inicial ───────────────────────────────────────────────────────
    /**
     * Crea el primer administrador. Solo funciona mientras la tabla
     * "usuarios" esté completamente vacía — así se puede exponer sin
     * autenticación (todavía no hay nadie que se pueda autenticar) sin que
     * quede como puerta trasera permanente: apenas existe un usuario, este
     * endpoint se cierra solo.
     */
    private Response manejarAdminInicial(IHTTPSession session) throws IOException, ResponseException, JSONException {
        if (!db().usuarioDao().getTodosSync().isEmpty()) {
            return jsonError(Status.FORBIDDEN, "Ya existe al menos un usuario — usá Gestión de usuarios para crear más.");
        }
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        String nombre = entrada.optString("nombre", "").trim();
        String pin = entrada.optString("pin", "");
        if (nombre.isEmpty()) return jsonError(Status.BAD_REQUEST, "El nombre es obligatorio");
        if (!pin.matches("\\d{4}")) return jsonError(Status.BAD_REQUEST, "El PIN tiene que ser de 4 dígitos");

        Usuario admin = new Usuario(nombre, pin, com.example.bufaloserver.db.entities.RolUsuario.ADMINISTRADOR);
        long id = db().usuarioDao().insert(admin);

        JSONObject respuesta = new JSONObject();
        respuesta.put("usuarioId", id);
        respuesta.put("nombre", nombre);
        // Nota: esto NO devuelve token de sesión ni deviceToken — el admin
        // recién creado todavía tiene que emparejar SU dispositivo como
        // cualquier otro (ver /dispositivos/emparejar/generar + /emparejar).
        // Evita que crear el usuario sea, de paso, un login automático.
        return jsonOk(respuesta.toString());
    }

    // ── /dispositivos/emparejar/generar ─────────────────────────────────────
    /**
     * Genera un código de emparejamiento. Normalmente exige sesión de
     * administrador — PERO hay una excepción de arranque: si TODAVÍA no hay
     * ningún dispositivo autorizado en todo el sistema, se permite sin
     * sesión. Sin esto, el primer administrador queda trabado: no puede
     * loguearse (no tiene dispositivo emparejado) para generar el código que
     * necesita justamente para emparejar su dispositivo.
     *
     * La ventana se cierra sola apenas se empareja el primer dispositivo —
     * de ahí en más este endpoint vuelve a exigir sesión de administrador
     * como cualquier otro. (También sirve como recuperación de emergencia si
     * alguna vez se revocaran TODOS los dispositivos por error: se puede
     * volver a generar un código sin quedar bloqueado para siempre — mismo
     * principio que /admin/inicial con la tabla de usuarios.)
     */
    private Response manejarGenerarTokenEmparejamiento(IHTTPSession session) throws IOException, ResponseException, JSONException {
        boolean sinNingunDispositivoTodavia = db().dispositivoDao().getTodosSync().isEmpty();
        if (!sinNingunDispositivoTodavia) {
            Sesion sesion = sesionDe(session);
            if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
            if (!com.example.bufaloserver.db.entities.RolUsuario.ADMINISTRADOR.equals(sesion.rol)) {
                return jsonError(Status.FORBIDDEN, "Solo un administrador puede autorizar dispositivos");
            }
        }

        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");
        long usuarioId = entrada.optLong("usuarioId", 0);

        Usuario usuario = db().usuarioDao().getByIdSync(usuarioId);
        if (usuario == null || !usuario.activo) {
            return jsonError(Status.BAD_REQUEST, "Usuario inválido o desactivado");
        }

        // ★ FIX — TokenEmparejamientoDao.borrarVencidos() existía desde hace
        // rato (el comentario del propio método ya decía "se llama de tanto
        // en tanto") pero no lo llamaba nadie en todo el proyecto — cada
        // código QR generado, se llegara a escanear o no, quedaba para
        // siempre en tokens_emparejamiento. No es un problema de seguridad
        // (esValido()/reclamarSiValido() siempre chequean expira_en, así que
        // un código vencido nunca se puede usar aunque la fila siga ahí),
        // pero la tabla crecía sin límite con filas muertas. Se aprovecha
        // este mismo endpoint (que ya corre cada vez que se genera un código
        // nuevo, o sea, con poca frecuencia) para ir limpiando de paso.
        db().tokenEmparejamientoDao().borrarVencidos(System.currentTimeMillis());

        TokenEmparejamiento t = new TokenEmparejamiento();
        t.token = generarTokenSeguro();
        t.usuarioId = usuarioId;
        t.creadoEn = System.currentTimeMillis();
        t.expiraEn = t.creadoEn + TokenEmparejamiento.EXPIRA_MS;
        db().tokenEmparejamientoDao().insert(t);

        JSONObject respuesta = new JSONObject();
        respuesta.put("token", t.token);
        respuesta.put("expiraEn", t.expiraEn);
        respuesta.put("usuarioNombre", usuario.nombre);
        return jsonOk(respuesta.toString());
    }

    // ── /emparejar ───────────────────────────────────────────────────────────
    /** El dispositivo nuevo escanea el QR y llega acá con el token que
     *  contenía. Sin sesión previa — todavía no está autorizado, es
     *  justamente lo que este endpoint le da. */
    private Response manejarEmparejar(IHTTPSession session) throws IOException, ResponseException, JSONException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");
        String token = entrada.optString("token", "");
        String nombreDispositivo = entrada.optString("nombreDispositivo", "").trim();

        TokenEmparejamiento t = db().tokenEmparejamientoDao().getByTokenSync(token);
        if (t == null || !t.esValido()) {
            return jsonError(Status.UNAUTHORIZED, "Código inválido o vencido — pedile al administrador que genere uno nuevo");
        }

        Usuario usuario = db().usuarioDao().getByIdSync(t.usuarioId);
        if (usuario == null || !usuario.activo) {
            return jsonError(Status.BAD_REQUEST, "El usuario para el que se generó este código ya no está activo");
        }

        if (nombreDispositivo.isEmpty()) nombreDispositivo = "Dispositivo de " + usuario.nombre;

        // ★ FIX — antes el token se consumía DESPUÉS de crear el Dispositivo
        // (t.usado=true + update(t) acá abajo), con el chequeo esValido() de
        // arriba como única guardia. Eso dejaba una ventana entre "leer
        // esValido()==true" y "escribir usado=true" donde dos /emparejar
        // simultáneos con el mismo código pasaban los dos — dos Dispositivo
        // para un código de un solo uso. Ahora el reclamo es lo PRIMERO que
        // se hace después de validar que el usuario existe, de forma
        // atómica (ver TokenEmparejamientoDao.reclamarSiValido) — si falla,
        // alguien más ya lo usó en el medio, y no se crea ningún Dispositivo.
        int filasReclamadas = db().tokenEmparejamientoDao().reclamarSiValido(t.id, System.currentTimeMillis());
        if (filasReclamadas == 0) {
            return jsonError(Status.UNAUTHORIZED, "Código inválido o vencido — pedile al administrador que genere uno nuevo");
        }

        Dispositivo d = new Dispositivo(generarTokenSeguro(), usuario.id, nombreDispositivo);
        long idDispositivo = db().dispositivoDao().insert(d);

        JSONObject respuesta = new JSONObject();
        respuesta.put("deviceToken", d.deviceToken);
        respuesta.put("dispositivoId", idDispositivo);
        respuesta.put("usuarioNombre", usuario.nombre);
        respuesta.put("rol", usuario.rol);
        return jsonOk(respuesta.toString());
    }

    // ── /dispositivos ────────────────────────────────────────────────────────
    private Response manejarListarDispositivos(IHTTPSession session) throws IOException, ResponseException, JSONException {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.ADMINISTRADOR.equals(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "Solo un administrador puede ver los dispositivos");
        }

        JSONArray arreglo = new JSONArray();
        for (Dispositivo d : db().dispositivoDao().getTodosSync()) {
            Usuario u = db().usuarioDao().getByIdSync(d.usuarioId);
            JSONObject o = new JSONObject();
            o.put("id", d.id);
            o.put("nombre", d.nombre);
            o.put("estado", d.estado);
            o.put("usuarioId", d.usuarioId);
            o.put("usuarioNombre", u != null ? u.nombre : "(usuario eliminado)");
            o.put("rol", u != null ? u.rol : "");
            o.put("creadoEn", d.creadoEn);
            o.put("ultimoUsoEn", d.ultimoUsoEn);
            arreglo.put(o);
        }
        return jsonOk(arreglo.toString());
    }

    private Response manejarRevocarDispositivo(IHTTPSession session, long id) throws IOException, ResponseException {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.ADMINISTRADOR.equals(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "Solo un administrador puede revocar dispositivos");
        }
        db().dispositivoDao().setEstado(id, Dispositivo.REVOCADO);
        // Corte inmediato: aunque el celular revocado tenga una sesión
        // vigente en este mismo momento, deja de servir en el próximo request.
        invalidarSesionesDeDispositivo(id);
        return jsonOk("{\"ok\":true}");
    }

    private Response manejarReautorizarDispositivo(IHTTPSession session, long id) throws IOException, ResponseException {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.ADMINISTRADOR.equals(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "Solo un administrador puede reautorizar dispositivos");
        }
        db().dispositivoDao().setEstado(id, Dispositivo.AUTORIZADO);
        return jsonOk("{\"ok\":true}");
    }

    // ── /pedidos/hoy — "Ventas del día" ──────────────────────────────────────
    /**
     * ★ NUEVO — reemplaza a "📈 Ventas por usuario" en el menú: en vez de un
     * resumen agregado por usuario, esto devuelve cada pedido del día por
     * separado (número de orden, vendedor, tipo aquí/llevar/mixto, estado,
     * método de pago, e items detallados), para poder:
     *   - rastrear un pedido puntual si se perdió el ticket físico
     *   - encontrar pedidos que quedaron con impreso=false (no se imprimieron)
     *   - ver quién vendió qué, orden por orden
     /**
     * Por fecha de CREACIÓN (creado_en), no de pago — a diferencia de
     * /pedidos (que solo trae pagados): acá interesan también los pendientes
     * y cancelados de hoy, porque son justamente los que más urge poder
     * encontrar. ★ CAMBIO — ya no es solo Admin: Mesero/Cajero/Admin, ver
     * RolUsuario.puedeVerVentasDelDia (necesitan poder rastrear/cancelar un
     * pedido puntual, no solo el Admin).
     *
     * ★ LIVIANO A PROPÓSITO — no llama a cargarDetalleItems() (que hace 2
     * queries extra por CADA ítem para traer salsas/modificadores desde sus
     * tablas propias). Con ~300 pedidos de 5 ítems cada uno, eso son ~3000
     * micro-queries solo para abrir la pantalla, aunque el admin termine
     * mirando 2 órdenes. Acá se manda lo que ya es gratis (nombreSnapshot,
     * cantidad, total, bañado, esLlevar, notaCocina — son columnas propias
     * de items_pedido, sin join) y el desglose completo de salsas/mods se
     * pide recién al abrir una orden puntual, ver GET /pedido/{id} más abajo.
     */
    private Response manejarVentasDelDia(IHTTPSession session) throws IOException, ResponseException {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        // ★ FIX — este gate seguía en "solo Admin" (criterio viejo, de cuando
        // la pantalla era solo para Admin). Se actualizó el menú del cliente
        // y RolUsuario.puedeVerVentasDelDia (los 3 roles) pero se había
        // quedado afuera ESTE endpoint, que es el que de verdad manda los
        // datos — por eso Cajero/Mesero veían el ítem de menú pero el
        // servidor los rechazaba con 403 al abrir la pantalla.
        if (!com.example.bufaloserver.db.entities.RolUsuario.puedeVerVentasDelDia(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "No tenés permiso para ver Ventas del día");
        }

        Map<String, List<String>> params = session.getParameters();
        long inicio, fin;
        if (params.containsKey("desde") && params.containsKey("hasta")) {
            inicio = Long.parseLong(primerParametro(params, "desde"));
            fin = Long.parseLong(primerParametro(params, "hasta"));
        } else {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            inicio = cal.getTimeInMillis();
            fin = System.currentTimeMillis();
        }

        List<Pedido> pedidos = db().pedidoDao().getDelDiaSync(inicio, fin);
        JSONArray arr = new JSONArray();
        try {
            for (Pedido p : pedidos) {
                JSONObject pJson = pedidoAJson(p);
                List<ItemPedido> items = db().itemPedidoDao().getPorPedidoSync(p.id);
                JSONArray itemsJson = new JSONArray();
                for (ItemPedido ip : items) {
                    JSONObject itemJson = new JSONObject();
                    itemJson.put("nombreSnapshot", ip.nombreSnapshot);
                    itemJson.put("cantidad", ip.cantidad);
                    // ★ FIX — precio BASE del producto (sin modificadores), igual
                    // que la comanda de cocina (ver EscPosBuilder.escribirGrupoProducto,
                    // que usa precioCentavosSnapshot * cantidad para la línea del
                    // producto y lista los modificadores con costo aparte). Antes
                    // el cliente solo tenía totalCentavos (que YA incluye los
                    // modificadores, ver ItemPedido.recalcTotal) y lo mostraba como
                    // "precio del producto", duplicando visualmente el costo de los
                    // modificadores que se listaban debajo.
                    itemJson.put("precioCentavosSnapshot", ip.precioCentavosSnapshot);
                    itemJson.put("totalCentavos", ip.totalCentavos);
                    itemJson.put("banado", ip.banado);
                    itemJson.put("esLlevar", ip.esLlevar);
                    itemJson.put("notaCocina", ip.notaCocina);
                    // ★ Sin salsas/modificadoresConPrecio acá — llegan vacíos
                    // a propósito. El cliente los pide completos recién si
                    // el admin abre esta orden puntual (GET /pedido/{id}).
                    itemJson.put("salsas", new JSONArray());
                    itemJson.put("modificadoresConPrecio", new JSONArray());
                    itemsJson.put(itemJson);
                }
                pJson.put("items", itemsJson);
                arr.put(pJson);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error armando /pedidos/hoy", e);
            return jsonError(Status.INTERNAL_ERROR, "Error listando ventas del día: " + e.getMessage());
        }
        return jsonOk(arr.toString());
    }

    /**
     * ★ NUEVO — GET /pedido/{id}: detalle COMPLETO de una sola orden (con
     * salsas/modificadores vía cargarDetalleItems), para "Ventas del día".
     * Se pide solo cuando se abre esa tarjeta puntual — así el costo de
     * cargarDetalleItems (2 queries extra por ítem) se paga una vez por
     * orden que se mira, no una vez por CADA orden del día. Mismo criterio
     * de permisos que /pedidos/hoy — Mesero/Cajero/Admin, ver
     * RolUsuario.puedeVerVentasDelDia.
     */
    private Response obtenerPedido(IHTTPSession session, long id) throws IOException, ResponseException {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        // ★ FIX — mismo problema que manejarVentasDelDia: este endpoint es el
        // que trae el detalle completo (salsas/mods) al expandir una orden
        // en "Ventas del día", y se había quedado con el gate viejo de
        // solo-Admin.
        if (!com.example.bufaloserver.db.entities.RolUsuario.puedeVerVentasDelDia(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "No tenés permiso para ver este pedido");
        }
        Pedido p = db().pedidoDao().getByIdSync(id);
        if (p == null) return jsonError(Status.NOT_FOUND, "No existe el pedido " + id);

        List<ItemPedido> items = db().itemPedidoDao().getPorPedidoSync(id);
        cargarDetalleItems(items);
        try {
            JSONObject pJson = pedidoAJson(p);
            JSONArray itemsJson = new JSONArray();
            for (ItemPedido ip : items) {
                JSONObject itemJson = new JSONObject();
                itemJson.put("nombreSnapshot", ip.nombreSnapshot);
                itemJson.put("cantidad", ip.cantidad);
                // ★ FIX — ver el mismo comentario en manejarVentasDelDia: precio
                // BASE del producto, sin modificadores, para que el cliente arme
                // el desglose igual que la comanda de cocina.
                itemJson.put("precioCentavosSnapshot", ip.precioCentavosSnapshot);
                itemJson.put("totalCentavos", ip.totalCentavos);
                itemJson.put("banado", ip.banado);
                itemJson.put("esLlevar", ip.esLlevar);
                itemJson.put("notaCocina", ip.notaCocina);
                itemJson.put("salsas", new JSONArray(ip.salsas));
                JSONArray modsJson = new JSONArray();
                for (ItemModificadorSeleccionado m : ip.modificadoresConPrecio) {
                    JSONObject mJson = new JSONObject();
                    mJson.put("nombre", m.nombre);
                    mJson.put("precioExtraCentavos", m.precioExtraCentavos);
                    modsJson.put(mJson);
                }
                itemJson.put("modificadoresConPrecio", modsJson);
                itemsJson.put(itemJson);
            }
            pJson.put("items", itemsJson);
            return jsonOk(pJson.toString());
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error armando el pedido: " + e.getMessage());
        }
    }

    /** ★ NUEVO — nombre de usuario para mostrar/imprimir, con fallback si el
     *  id es 0 (pedido viejo sin sesión detectada) o el usuario ya no existe
     *  (se eliminó/desactivó pero el pedido histórico se conserva). */
    private String nombreUsuarioSync(long usuarioId) {
        if (usuarioId <= 0) return "Sin registrar";
        Usuario u = db().usuarioDao().getByIdSync(usuarioId);
        return u != null ? u.nombre : "Usuario eliminado";
    }

    // ── /menu ────────────────────────────────────────────────────────────────
    /**
     * ★ FIX — antes esta ruta SIEMPRE ocultaba categorías/productos con
     * activo=false, sin excepción. Eso está bien para MainActivity (vender
     * solo debe mostrar lo activo), pero EditarMenuActivity la usaba también
     * para poblar su propia lista de edición — resultado: en cuanto el admin
     * desactivaba un producto (el switch "ocultar", NO "eliminar"), el
     * siguiente refresco del menú lo hacía desaparecer de la pantalla de
     * edición como si lo hubiera borrado, sin ninguna forma de reactivarlo
     * desde la UI. Con ?todo=1 se manda todo, activo e inactivo — lo usa
     * ProductoRepositoryRemoto.obtenerMenuCompleto(), que a propósito NO
     * toca el caché compartido con MainActivity (ver ese método).
     */
    private Response manejarMenu(IHTTPSession session) {
        boolean incluirInactivos = "1".equals(primerParametro(session.getParameters(), "todo"));
        // ★ DIAGNÓSTICO — si esto NUNCA aparece con "todo=true" en el Logcat
        // del servidor (tag ServidorHttp) mientras EditarMenuActivity está
        // abierto, el cliente no está pegándole a este código nuevo —
        // problema de despliegue, no de lógica.
        android.util.Log.d(TAG, "GET /menu — todo=" + incluirInactivos);

        List<Categoria> categorias = db().categoriaDao().getTodasSync();
        JSONArray categoriasJson = new JSONArray();

        try {
            for (Categoria categoria : categorias) {
                if (!incluirInactivos && !categoria.activo) continue;

                JSONObject catJson = new JSONObject();
                catJson.put("id", categoria.id);
                catJson.put("nombre", categoria.nombre);
                if (categoria.emoji != null) catJson.put("emoji", categoria.emoji);
                catJson.put("orden", categoria.orden);
                catJson.put("activo", categoria.activo);
                catJson.put("imprimirEnCocina", categoria.imprimirEnCocina);

                JSONArray productosJson = new JSONArray();
                List<Producto> productos = db().productoDao().getPorCategoriaSync(categoria.id);
                for (Producto producto : productos) {
                    if (!incluirInactivos && !producto.activo) continue;
                    productosJson.put(productoAJson(producto));
                }
                catJson.put("productos", productosJson);

                categoriasJson.put(catJson);
            }
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error armando el menú: " + e.getMessage());
        }

        return jsonOk(categoriasJson.toString());
    }

    /**
     * ★ FIX — esta serialización tenía 3 desajustes con lo que el cliente
     * REALMENTE lee (ver ProductoRepositoryRemoto.parsearVariantesYModificadores
     * y parsearGrupos): mandaba "modificadores" pero el cliente busca
     * "gruposModificador"; mandaba "nombre" en la variante pero el cliente
     * busca "label"; mandaba "precioExtraCentavos" en la opción pero el
     * cliente busca "precioExtra". Con esos nombres mal, el cliente igual
     * "funcionaba" (optJSONArray/optString devuelven vacío/null en vez de
     * tirar excepción) pero variantes y modificadores nunca aparecían. Y le
     * faltaban los flags (esPollo/esCombo/etc.) que EditarMenuActivity
     * necesita para poder reabrir un producto y volver a guardarlo sin
     * perderlos — por eso "editar" tampoco andaba bien.
     */
    private JSONObject productoAJson(Producto producto) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", producto.id);
        json.put("categoriaId", producto.categoriaId);
        json.put("nombre", producto.nombre);
        json.put("precioCentavos", producto.precioCentavos);
        json.put("esPollo", producto.esPollo);
        json.put("esCombo", producto.esCombo);
        json.put("esSalsasModal", producto.esSalsasModal);
        json.put("esPromo", producto.esPromo);
        json.put("esRefresco", producto.esRefresco);
        json.put("tieneSalsas", producto.tieneSalsas);
        json.put("maxSalsas", producto.maxSalsas);
        json.put("tieneInventario", producto.tieneInventario);
        json.put("stockActual", producto.stockActual);
        json.put("stockMinimo", producto.stockMinimo);
        json.put("stockMaximo", producto.stockMaximo);
        json.put("tieneVariantes", producto.tieneVariantes);
        if (producto.etiquetaCaracteristica != null) {
            json.put("etiquetaCaracteristica", producto.etiquetaCaracteristica);
        }
        json.put("activo", producto.activo);

        JSONArray variantesJson = new JSONArray();
        List<VarianteProducto> variantes = db().varianteProductoDao().getPorProductoSync(producto.id);
        for (VarianteProducto variante : variantes) {
            JSONObject vJson = new JSONObject();
            vJson.put("id", variante.id);
            vJson.put("label", variante.label);
            vJson.put("precioCentavos", variante.precioCentavos);
            vJson.put("habilitado", variante.habilitado);
            vJson.put("maxSalsas", variante.maxSalsas);
            vJson.put("orden", variante.orden);
            vJson.put("tieneCaracteristica", variante.tieneCaracteristica);
            if (variante.etiquetaCaracteristica != null) {
                vJson.put("etiquetaCaracteristica", variante.etiquetaCaracteristica);
            }
            vJson.put("tieneInventario", variante.tieneInventario);
            vJson.put("stockActual", variante.stockActual);
            vJson.put("stockMinimo", variante.stockMinimo);
            vJson.put("stockMaximo", variante.stockMaximo);
            vJson.put("gruposModificador", gruposAJson(db().grupoModificadorDao().getPorVarianteSync(variante.id)));
            variantesJson.put(vJson);
        }
        json.put("variantes", variantesJson);

        // Grupos de modificadores: cuelgan de la variante si el producto tiene
        // variantes (ya resueltos arriba, dentro de cada variante), o directo
        // del producto si no tiene — mismo criterio que usa GrupoModificadorDao
        // del lado cliente (varianteId=0 vs productoId=0).
        if (variantes.isEmpty()) {
            json.put("gruposModificador", gruposAJson(db().grupoModificadorDao().getPorProductoSync(producto.id)));
        }

        return json;
    }

    private JSONArray gruposAJson(List<GrupoModificador> grupos) throws JSONException {
        JSONArray gruposJson = new JSONArray();
        for (GrupoModificador grupo : grupos) {
            JSONObject gJson = new JSONObject();
            gJson.put("id", grupo.id);
            gJson.put("nombre", grupo.nombre);
            gJson.put("multiSeleccion", grupo.multiSeleccion);
            gJson.put("orden", grupo.orden);

            JSONArray opcionesJson = new JSONArray();
            for (ModificadorOpcion opcion : db().modificadorOpcionDao().getPorGrupoSync(grupo.id)) {
                JSONObject oJson = new JSONObject();
                oJson.put("id", opcion.id);
                oJson.put("nombre", opcion.nombre);
                // ★ FIX — "precioExtra" (Bs, double) renombrado a
                // "precioExtraCentavos" (int) para que el wire format del
                // catálogo sea consistente con el de los items de pedido,
                // que ya mandan "precioExtraCentavos" (ver más abajo, GET
                // /pedidos y líneas de ItemModificadorSeleccionado). Este
                // cambio va junto con el mismo rename en el cliente
                // (ProductoRepositoryRemoto, EditarMenuActivity) — hay que
                // actualizar los dos lados a la vez.
                oJson.put("precioExtraCentavos", opcion.precioExtraCentavos);
                oJson.put("orden", opcion.orden);
                opcionesJson.put(oJson);
            }
            gJson.put("opciones", opcionesJson);
            gruposJson.put(gJson);
        }
        return gruposJson;
    }

    // ── /categorias ──────────────────────────────────────────────────────────
    private Response crearCategoria(IHTTPSession session) throws IOException, ResponseException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        Categoria categoria = new Categoria();
        categoria.nombre = entrada.optString("nombre", "");
        categoria.emoji = entrada.isNull("emoji") ? null : entrada.optString("emoji", null);
        categoria.orden = entrada.optInt("orden", 0);
        categoria.activo = true;
        categoria.imprimirEnCocina = entrada.optBoolean("imprimirEnCocina", true);

        long id = db().categoriaDao().insert(categoria);
        // Reusa el mismo evento que productos: desde el fix en
        // ProductoRepositoryRemoto.actualizarProducto(), cualquier aviso de
        // este tipo dispara un refresh COMPLETO de /menu en los otros
        // celulares — que también trae categorías nuevas/cambiadas. El id
        // no se usa para nada en ese refresh, por eso el 0.
        if (ws != null) ws.avisarProductoActualizado(0);
        return jsonOk(idComoJson(id));
    }

    private Response setActivoCategoria(IHTTPSession session, long id) throws IOException, ResponseException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");
        boolean activo = entrada.optBoolean("activo", true);
        db().categoriaDao().setActivo(id, activo);
        if (ws != null) ws.avisarProductoActualizado(0); // ver comentario en crearCategoria
        return jsonOk("{}");
    }

    /** ★ PUT /categorias/{id}/imprimir {"imprimirEnCocina": bool} — para categorías que ya existen. */
    private Response setImprimirCategoria(IHTTPSession session, long id) throws IOException, ResponseException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");
        if (db().categoriaDao().getByIdSync(id) == null) {
            return jsonError(Status.NOT_FOUND, "No existe la categoría " + id);
        }
        db().categoriaDao().setImprimirEnCocina(id, entrada.optBoolean("imprimirEnCocina", true));
        if (ws != null) ws.avisarProductoActualizado(0); // refresca el menú en las demás tablets
        return jsonOk("{}");
    }

    /**
     * Borra la categoría y, en cascada, sus productos y variantes (Producto y
     * VarianteProducto ya tienen @ForeignKey(onDelete=CASCADE) hacia arriba,
     * Room deja el pragma foreign_keys=ON por defecto). Lo que NO cascadea
     * solo porque GrupoModificador/ModificadorOpcion no declaran @ForeignKey
     * son sus grupos de modificadores — por eso se limpian a mano acá, antes
     * de borrar la categoría, para no dejar filas huérfanas.
     */
    private Response eliminarCategoria(long id) {
        List<Producto> productos = db().productoDao().getPorCategoriaSync(id);
        for (Producto p : productos) limpiarModificadoresDeProducto(p.id);

        Categoria categoria = db().categoriaDao().getByIdSync(id);
        if (categoria != null) db().categoriaDao().delete(categoria);
        if (ws != null) ws.avisarProductoActualizado(0); // ver comentario en crearCategoria
        return jsonOk("{}");
    }

    // ── /productos ───────────────────────────────────────────────────────────
    private Response obtenerProducto(long id) throws JSONException {
        Producto p = db().productoDao().getByIdSync(id);
        if (p == null) return jsonError(Status.NOT_FOUND, "No existe el producto " + id);
        return jsonOk(productoAJson(p).toString());
    }

    private Response crearProducto(IHTTPSession session) throws IOException, ResponseException, JSONException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        long categoriaId = entrada.optLong("categoriaId", -1);
        if (categoriaId <= 0 || db().categoriaDao().getByIdSync(categoriaId) == null) {
            // ★ FIX — Producto tiene una foreign key real hacia categorias (ver
            // entidad Producto). Si categoriaId no existe (típicamente: el
            // cliente tenía en caché una categoría que ya no está, por ejemplo
            // después de reiniciar el servidor con la base vacía durante
            // pruebas), Room tiraba SQLiteConstraintException sin capturar,
            // y eso salía como "código 500" sin ninguna pista de la causa.
            // Ahora se valida ANTES de tocar la base, con un mensaje que sí
            // explica qué pasó y qué hacer.
            return jsonError(Status.BAD_REQUEST,
                    "La categoría " + categoriaId + " no existe en el servidor — "
                            + "cerrá y volvé a abrir 'Editar menú' para refrescar las categorías.");
        }

        Producto producto = productoDesdeJson(entrada, null);
        long id = db().productoDao().insert(producto);
        guardarVariantesYModificadores(entrada, id);
        db().productoDao().setTieneVariantes(id, tieneVariantesEnJson(entrada));

        if (ws != null) ws.avisarProductoActualizado(id);
        return jsonOk(idComoJson(id));
    }

    private Response actualizarProducto(IHTTPSession session, long id) throws IOException, ResponseException, JSONException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        Producto existente = db().productoDao().getByIdSync(id);
        if (existente == null) return jsonError(Status.NOT_FOUND, "No existe el producto " + id);

        long categoriaId = entrada.optLong("categoriaId", existente.categoriaId);
        if (categoriaId <= 0 || db().categoriaDao().getByIdSync(categoriaId) == null) {
            // ★ FIX — mismo caso que en crearProducto: validar antes de que
            // Room tire la excepción de foreign key sin explicación.
            return jsonError(Status.BAD_REQUEST,
                    "La categoría " + categoriaId + " no existe en el servidor — "
                            + "cerrá y volvé a abrir 'Editar menú' para refrescar las categorías.");
        }

        final Producto actualizado = productoDesdeJson(entrada, existente);
        actualizado.id = id;
        // PUT = el JSON trae TODAS las variantes que debe tener el producto después de
        // guardar. guardarVariantesYModificadores hace upsert por id (conserva ids,
        // stock e historial); todo en una transacción para no dejar el menú a medias.
        try {
            db().runInTransaction(() -> {
                db().productoDao().update(actualizado);
                try {
                    guardarVariantesYModificadores(entrada, id);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                db().productoDao().setTieneVariantes(id, tieneVariantesEnJson(entrada));
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof JSONException) {
                return jsonError(Status.BAD_REQUEST, "JSON de producto inválido: " + e.getCause().getMessage());
            }
            throw e;
        }

        if (ws != null) ws.avisarProductoActualizado(id);
        return jsonOk("{}");
    }

    private Response eliminarProducto(long id) {
        eliminarProductoCascada(id);
        if (ws != null) ws.avisarProductoActualizado(id);
        return jsonOk("{}");
    }

    private Response setActivoProducto(IHTTPSession session, long id) throws IOException, ResponseException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");
        boolean activo = entrada.optBoolean("activo", true);
        db().productoDao().setActivo(id, activo);
        if (ws != null) ws.avisarProductoActualizado(id);
        return jsonOk("{}");
    }

    private boolean tieneVariantesEnJson(JSONObject prodJson) {
        JSONArray variantesJson = prodJson.optJSONArray("variantes");
        return variantesJson != null && variantesJson.length() > 0;
    }

    private Producto productoDesdeJson(JSONObject json, @Nullable Producto base) {
        Producto p = base != null ? base : new Producto();
        // ★ FIX — el stock real vive en el servidor: el cliente manda el que cargó al abrir
        // "Editar menú", que puede estar viejo (ventas/entradas ocurridas en el medio).
        // Solo se acepta como stock INICIAL (producto nuevo, o inventario recién activado).
        final boolean teniaInventario = base != null && base.tieneInventario;
        p.categoriaId = json.optLong("categoriaId", p.categoriaId);
        p.nombre = json.optString("nombre", p.nombre != null ? p.nombre : "");
        p.precioCentavos = json.optInt("precioCentavos", p.precioCentavos);
        p.esPollo = json.optBoolean("esPollo", false);
        p.esCombo = json.optBoolean("esCombo", false);
        p.esSalsasModal = json.optBoolean("esSalsasModal", false);
        p.esPromo = json.optBoolean("esPromo", false);
        p.esRefresco = json.optBoolean("esRefresco", false);
        p.tieneSalsas = json.optBoolean("tieneSalsas", false);
        p.maxSalsas = json.optInt("maxSalsas", -1);
        final boolean quiereInventario = json.optBoolean("tieneInventario", false);
        p.tieneInventario = quiereInventario;
        if (base == null || (quiereInventario && !teniaInventario)) {
            p.stockActual = json.optInt("stockActual", 0);
        }
        p.stockMinimo = json.optInt("stockMinimo", 5);
        // ★ NUEVO — el stock máximo se gestiona desde Inventario, no desde Editar menú: si el
        // JSON no lo trae (cliente viejo / Editar menú), se conserva el que ya tenía.
        p.stockMaximo = Math.max(0, json.optInt("stockMaximo", p.stockMaximo));
        p.etiquetaCaracteristica = json.isNull("etiquetaCaracteristica")
                ? null : json.optString("etiquetaCaracteristica", null);
        p.activo = json.optBoolean("activo", true);
        return p;
    }

    /**
     * ★ FIX — antes borraba TODAS las variantes y las reinsertaba con ids nuevos, lo que
     * rompía el historial de inventario (movimientos huérfanos), dejaba items_pedido.variante_id
     * en NULL (la cancelación ya no devolvía stock) y dejaba a las tablets con ids viejos.
     *
     * Ahora es un UPSERT: cada variante del JSON se enlaza con una existente por "id"
     * (o, para clientes viejos que no mandan "id", por label) y se ACTUALIZA conservando su
     * id y su stock; las nuevas se insertan; solo se borran las que ya no vienen en el JSON.
     * Los grupos+opciones de modificadores sí se reconstruyen (nadie los referencia por id).
     * Si el producto no tiene variantes, los grupos cuelgan directo de él.
     */
    private void guardarVariantesYModificadores(JSONObject prodJson, long productoId) throws JSONException {
        limpiarModificadoresDeProducto(productoId);

        List<VarianteProducto> existentes = db().varianteProductoDao().getPorProductoSync(productoId);
        java.util.Set<Long> conservadas = new java.util.HashSet<>();

        JSONArray variantesJson = prodJson.optJSONArray("variantes");
        if (variantesJson != null && variantesJson.length() > 0) {
            for (int i = 0; i < variantesJson.length(); i++) {
                JSONObject vJson = variantesJson.getJSONObject(i);
                VarianteProducto v = buscarVarianteExistente(existentes, conservadas, vJson);
                final boolean esNueva = (v == null);
                final boolean teniaInventario = !esNueva && v.tieneInventario;
                if (esNueva) {
                    v = new VarianteProducto();
                    v.productoId = productoId;
                }
                v.label = vJson.optString("label", "");
                v.precioCentavos = vJson.optInt("precioCentavos", 0);
                v.habilitado = vJson.optBoolean("habilitado", true);
                v.maxSalsas = vJson.optInt("maxSalsas", -1);
                v.orden = vJson.optInt("orden", i);
                v.tieneCaracteristica = vJson.optBoolean("tieneCaracteristica", false);
                v.etiquetaCaracteristica = vJson.isNull("etiquetaCaracteristica")
                        ? null : vJson.optString("etiquetaCaracteristica", null);
                final boolean quiereInventario = vJson.optBoolean("tieneInventario", false);
                v.tieneInventario = quiereInventario;
                // Stock: solo como valor INICIAL (variante nueva o inventario recién activado).
                // Si ya existía, manda el del servidor (el del cliente puede estar viejo).
                if (esNueva || (quiereInventario && !teniaInventario)) {
                    v.stockActual = vJson.optInt("stockActual", 0);
                }
                v.stockMinimo = vJson.optInt("stockMinimo", 5);
                v.stockMaximo = Math.max(0, vJson.optInt("stockMaximo", v.stockMaximo)); // ★ NUEVO — se conserva si no viene

                long varianteId;
                if (esNueva) {
                    varianteId = db().varianteProductoDao().insert(v);
                } else {
                    db().varianteProductoDao().update(v);
                    varianteId = v.id;
                }
                conservadas.add(varianteId);
                guardarGrupos(vJson.optJSONArray("gruposModificador"), varianteId, 0);
            }
        } else {
            guardarGrupos(prodJson.optJSONArray("gruposModificador"), 0, productoId);
        }

        // Solo se borran las variantes que el cliente ya no manda.
        for (VarianteProducto e : existentes) {
            if (!conservadas.contains(e.id)) db().varianteProductoDao().delete(e);
        }
    }

    /** Busca la variante existente que corresponde a este JSON: por "id" si viene;
     *  si el JSON no trae el campo "id" (cliente viejo), por label (sin mayúsculas). */
    @Nullable
    private VarianteProducto buscarVarianteExistente(List<VarianteProducto> existentes,
                                                     java.util.Set<Long> yaUsadas, JSONObject vJson) {
        if (vJson.has("id")) {
            long idJson = vJson.optLong("id", 0);
            if (idJson <= 0) return null;
            for (VarianteProducto e : existentes) {
                if (e.id == idJson && !yaUsadas.contains(e.id)) return e;
            }
            return null; // id que ya no existe → se trata como variante nueva
        }
        String label = vJson.optString("label", "");
        for (VarianteProducto e : existentes) {
            if (!yaUsadas.contains(e.id) && e.label != null && e.label.equalsIgnoreCase(label)) return e;
        }
        return null;
    }

    private void guardarGrupos(@Nullable JSONArray gruposJson, long varianteId, long productoId) throws JSONException {
        if (gruposJson == null) return;
        for (int i = 0; i < gruposJson.length(); i++) {
            JSONObject gJson = gruposJson.getJSONObject(i);
            GrupoModificador g = new GrupoModificador();
            g.varianteId = varianteId;
            g.productoId = productoId;
            g.nombre = gJson.optString("nombre", "");
            g.multiSeleccion = gJson.optBoolean("multiSeleccion", false);
            g.orden = gJson.optInt("orden", i);
            long grupoId = db().grupoModificadorDao().insert(g);

            JSONArray opcionesJson = gJson.optJSONArray("opciones");
            if (opcionesJson != null) {
                for (int j = 0; j < opcionesJson.length(); j++) {
                    JSONObject oJson = opcionesJson.getJSONObject(j);
                    ModificadorOpcion o = new ModificadorOpcion();
                    o.grupoId = grupoId;
                    o.nombre = oJson.optString("nombre", "");
                    o.precioExtraCentavos = oJson.optInt("precioExtraCentavos", 0);
                    o.orden = oJson.optInt("orden", j);
                    db().modificadorOpcionDao().insert(o);
                }
            }
        }
    }

    /** Borra grupos+opciones colgados de cualquiera de las variantes de este
     *  producto, y los que cuelguen directo del producto (caso sin variantes).
     *  NO borra las variantes en sí — eso lo hace el caller según corresponda. */
    private void limpiarModificadoresDeProducto(long productoId) {
        for (VarianteProducto v : db().varianteProductoDao().getPorProductoSync(productoId)) {
            for (GrupoModificador g : db().grupoModificadorDao().getPorVarianteSync(v.id)) {
                db().modificadorOpcionDao().deleteByGrupo(g.id);
            }
            db().grupoModificadorDao().deleteByVariante(v.id);
        }
        for (GrupoModificador g : db().grupoModificadorDao().getPorProductoSync(productoId)) {
            db().modificadorOpcionDao().deleteByGrupo(g.id);
        }
        db().grupoModificadorDao().deleteByProducto(productoId);
    }

    private void eliminarProductoCascada(long productoId) {
        limpiarModificadoresDeProducto(productoId);
        db().varianteProductoDao().deleteByProducto(productoId); // explícito, aunque el FK ya cascadearía
        Producto p = db().productoDao().getByIdSync(productoId);
        if (p != null) db().productoDao().delete(p);
    }

    // ── /gastos ──────────────────────────────────────────────────────────────
    private Response listarGastos(IHTTPSession session) {
        Map<String, List<String>> params = session.getParameters();
        List<GastoDiario> gastos;
        if (params.containsKey("desde") && params.containsKey("hasta")) {
            long desde = Long.parseLong(params.get("desde").get(0));
            long hasta = Long.parseLong(params.get("hasta").get(0));
            gastos = db().gastoDiarioDao().getPorFechaSync(desde, hasta);
        } else {
            gastos = db().gastoDiarioDao().getTodosSync();
        }

        JSONArray arr = new JSONArray();
        try {
            for (GastoDiario g : gastos) arr.put(gastoAJson(g));
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error listando gastos: " + e.getMessage());
        }
        return jsonOk(arr.toString());
    }

    private Response crearGasto(IHTTPSession session) throws IOException, ResponseException {
        // ★ FIX SEGURIDAD — mismo caso que crearPedido: no exigía sesión.
        Sesion sesionGasto = sesionDe(session);
        if (sesionGasto == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        GastoDiario gasto = new GastoDiario();
        gasto.detalle = entrada.optString("detalle", "");
        gasto.montoCentavos = entrada.optInt("montoCentavos", 0);
        gasto.fecha = entrada.optLong("fecha", System.currentTimeMillis());
        gasto.esAdelanto = entrada.optBoolean("esAdelanto", false); // ★ NUEVO
        gasto.creadoEn = System.currentTimeMillis();

        if (gasto.esAdelanto) {
            // ★ En un adelanto, usuarioId es el EMPLEADO que lo recibe (elegido
            // en el diálogo por el Admin) — no se pisa con la sesión.
            gasto.usuarioId = entrada.optLong("usuarioId", 0);
        } else {
            gasto.usuarioId = sesionGasto.usuarioId;
        }

        long id = db().gastoDiarioDao().insert(gasto);

        registrarAuditoria(gasto.usuarioId, sesionGasto.rol, Auditoria.ACCION_GASTO_CREADO,
                (gasto.esAdelanto ? "Registró adelanto: " : "Cargó gasto: ") + gasto.detalle + " (Bs " + (gasto.montoCentavos / 100.0) + ")",
                "gasto_diario", id);

        return jsonOk(idComoJson(id));
    }

    private Response actualizarGasto(IHTTPSession session, long id) throws IOException, ResponseException {
        // ★ FIX SEGURIDAD — mismo caso que crearPedido: no exigía sesión.
        Sesion sesionGasto = sesionDe(session);
        if (sesionGasto == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        GastoDiario existente = db().gastoDiarioDao().getByIdSync(id);
        if (existente == null) return jsonError(Status.NOT_FOUND, "No existe el gasto " + id);

        existente.detalle = entrada.optString("detalle", existente.detalle);
        existente.montoCentavos = entrada.optInt("montoCentavos", existente.montoCentavos);
        existente.fecha = entrada.optLong("fecha", existente.fecha);
        db().gastoDiarioDao().update(existente);

        registrarAuditoria(sesionGasto.usuarioId, sesionGasto.rol, Auditoria.ACCION_GASTO_EDITADO,
                "Editó gasto: " + existente.detalle + " (Bs " + (existente.montoCentavos / 100.0) + ")",
                "gasto_diario", id);

        return jsonOk("{}");
    }

    private Response eliminarGasto(IHTTPSession session, long id) {
        // ★ FIX SEGURIDAD — mismo caso que crearPedido: no exigía sesión.
        Sesion sesionGasto = sesionDe(session);
        if (sesionGasto == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        GastoDiario existente = db().gastoDiarioDao().getByIdSync(id);
        db().gastoDiarioDao().deleteById(id);

        if (existente != null) {
            registrarAuditoria(sesionGasto.usuarioId, sesionGasto.rol, Auditoria.ACCION_GASTO_ELIMINADO,
                    "Eliminó gasto: " + existente.detalle + " (Bs " + (existente.montoCentavos / 100.0) + ")",
                    "gasto_diario", id);
        }

        return jsonOk("{}");
    }

    // ── /gastos/pendientes — "Gasto rápido sin conexión" del cliente ───────────
    // Ver GastoRepositoryRemoto.crearPendiente() y RegistroGastoRapidoActivity
    // del lado cliente. NO es una tabla aparte: cae en la misma gastos_diarios,
    // marcado con origen_offline=true, e idempotente por uuid (reintentos de
    // red no duplican el gasto).
    //
    // ★ CAMBIO — antes aceptaba un "compradoPor" en texto libre porque el
    // gasto se capturaba sin login. Ahora, como la sincronización SOLO corre
    // cuando ya hay conexión (SincronizadorPendientes se dispara tras el
    // login o con el server ya disponible), este endpoint exige la misma
    // sesión que cualquier otro endpoint protegido, y usa sesion.usuarioId —
    // el usuario real que quedó identificado al loguearse con PIN — en vez de
    // confiar en un nombre tipeado a mano. Como cada dispositivo está atado a
    // un único usuario fijo (ver Dispositivo.usuarioId), esto identifica
    // correctamente quién hizo cada gasto sin volver a pedir PIN acá.
    private Response crearGastoPendiente(IHTTPSession session) throws IOException, ResponseException {
        Sesion sesion = sesionDe(session);
        if (sesion == null) {
            return jsonError(Status.UNAUTHORIZED, "SESION_REQUERIDA — hay que estar logueado para sincronizar gastos pendientes");
        }

        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        String uuid = entrada.optString("uuid", null);
        if (uuid == null || uuid.isEmpty()) {
            return jsonError(Status.BAD_REQUEST, "Falta uuid");
        }

        GastoDiario existente = db().gastoDiarioDao().getByUuidOfflineSync(uuid);
        if (existente != null) {
            // Ya se procesó antes (reintento) — se devuelve el mismo id, sin duplicar.
            return jsonOk(idComoJson(existente.id));
        }

        long creadoEn = entrada.optLong("creadoEn", System.currentTimeMillis());

        GastoDiario gasto = new GastoDiario();
        gasto.detalle = entrada.optString("descripcion", "");
        gasto.montoCentavos = entrada.optInt("montoCentavos", 0);
        gasto.fecha = inicioDelDia(creadoEn);
        gasto.creadoEn = creadoEn;
        gasto.usuarioId = sesion.usuarioId;
        gasto.capturadoOffline = true;
        gasto.compradoPor = null; // ya no se usa texto libre — ver usuarioId
        gasto.uuidOffline = uuid;

        long id = db().gastoDiarioDao().insert(gasto);
        return jsonOk(idComoJson(id));
    }

    /** Trunca un timestamp al inicio de su día (00:00:00.000) — mismo criterio
     *  que usa GastoDiario.fecha en el resto del sistema, así el gasto aparece
     *  en el reporte del día real en que se compró, no el día en que llegó la
     *  sincronización. */
    private long inicioDelDia(long timestampMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestampMs);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private JSONObject gastoAJson(GastoDiario g) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", g.id);
        json.put("detalle", g.detalle);
        json.put("montoCentavos", g.montoCentavos);
        json.put("fecha", g.fecha);
        json.put("creadoEn", g.creadoEn);
        json.put("usuarioId", g.usuarioId);
        json.put("origenOffline", g.capturadoOffline);
        json.put("esAdelanto", g.esAdelanto); // ★ NUEVO
        if (g.compradoPor != null) json.put("compradoPor", g.compradoPor);
        return json;
    }

    private String idComoJson(long id) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
        } catch (JSONException ignored) {}
        return json.toString();
    }

    // ── /gastos-generales — "Gastos generales" ─────────────────────────────────
    // ★ Reemplaza a GastoFijo + GastoFijoPago (2 tablas para una sola idea).
    // Cada fila es un registro INDEPENDIENTE de un mes/año concreto: no hay
    // relación padre-hijo entre meses, así que borrar una fila jamás afecta
    // a las demás (antes: borrar el gasto fijo completo borraba en cascada
    // TODOS sus pagos, incluidos los de meses anteriores ya cerrados — ese
    // era el bug reportado). Sin caché en el cliente — se pide fresco cada vez.
    private Response listarGastosGenerales(IHTTPSession session) {
        Map<String, List<String>> params = session.getParameters();
        String anioStr = primerParametro(params, "anio");
        String mesStr = primerParametro(params, "mes");
        if (anioStr == null || mesStr == null) {
            return jsonError(Status.BAD_REQUEST, "Faltan parámetros anio/mes");
        }
        List<GastoGeneral> gastos = db().gastoGeneralDao().getVisiblesEnMesSync(Integer.parseInt(anioStr), Integer.parseInt(mesStr));
        JSONArray arr = new JSONArray();
        try {
            for (GastoGeneral g : gastos) arr.put(gastoGeneralAJson(g));
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error listando gastos generales: " + e.getMessage());
        }
        return jsonOk(arr.toString());
    }

    private Response crearGastoGeneral(IHTTPSession session) throws IOException, ResponseException {
        // ★ FIX SEGURIDAD — mismo caso que crearPedido: no exigía sesión.
        Sesion sesionGg = sesionDe(session);
        if (sesionGg == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        // ★ NUEVO — idempotencia (ver GastoGeneral.uuidCliente): si el
        // cliente ya mandó este mismo gasto antes (mismo uuid) y el servidor
        // lo llegó a crear, pero la respuesta nunca le llegó (timeout, red
        // cortada), un reintento no debe crear una fila duplicada. Mismo
        // criterio que ya se aplica en crearPedido.
        String uuidCliente = entrada.optString("uuidCliente", null);
        if (uuidCliente != null && !uuidCliente.isEmpty()) {
            GastoGeneral yaCreado = db().gastoGeneralDao().getByUuidClienteSync(uuidCliente);
            if (yaCreado != null) {
                return jsonOk(idComoJson(yaCreado.id));
            }
        }

        GastoGeneral gasto = new GastoGeneral();
        gasto.nombre = entrada.optString("nombre", "");
        gasto.montoCentavos = entrada.optInt("montoCentavos", 0);
        gasto.anio = entrada.optInt("anio", 0);
        gasto.mes = entrada.optInt("mes", 0);
        gasto.recurrente = entrada.optBoolean("recurrente", true);
        gasto.usuarioId = entrada.optLong("usuarioId", 0); // empleado vinculado (sueldo/adelantos), 0 = ninguno
        gasto.creadoEn = System.currentTimeMillis();
        gasto.uuidCliente = uuidCliente;

        long id;
        try {
            id = db().gastoGeneralDao().insert(gasto);
        } catch (android.database.sqlite.SQLiteConstraintException e) {
            // ★ FIX — el chequeo de arriba ("¿ya existe?") y este insert NO
            // son atómicos entre sí: si dos requests con el MISMO uuidCliente
            // llegan genuinamente en paralelo (el escenario real que esto
            // cubre es servidor lento + reintento del cliente por timeout,
            // donde la primera petición puede seguir en vuelo cuando llega
            // la segunda), los dos pueden pasar el chequeo antes de que
            // ninguno inserte. El índice único sobre uuid_cliente
            // (MIGRATION_13_14) evita que se guarden dos filas, pero el
            // insert que pierde la carrera tira esta excepción en vez de
            // fallar en silencio — se la interpreta como "alguien más already
            // ganó la carrera" y se devuelve ESE resultado, en vez de dejar
            // que la excepción suba como un 500.
            GastoGeneral yaCreadoPorOtro = db().gastoGeneralDao().getByUuidClienteSync(uuidCliente);
            if (yaCreadoPorOtro != null) {
                return jsonOk(idComoJson(yaCreadoPorOtro.id));
            }
            throw e; // constraint distinta a uuid_cliente — no hay nada más que hacer acá.
        }

        registrarAuditoria(sesionGg.usuarioId, sesionGg.rol, Auditoria.ACCION_GASTO_GENERAL_CREADO,
                "Cargó gasto general: " + gasto.nombre + " (Bs " + (gasto.montoCentavos / 100.0) + ", " + gasto.mes + "/" + gasto.anio + ")",
                "gasto_general", id);

        return jsonOk(idComoJson(id));
    }

    private Response actualizarGastoGeneral(IHTTPSession session, long id) throws IOException, ResponseException {
        // ★ FIX SEGURIDAD — mismo caso que crearPedido: no exigía sesión.
        Sesion sesionGg = sesionDe(session);
        if (sesionGg == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        GastoGeneral existente = db().gastoGeneralDao().getByIdSync(id);
        if (existente == null) return jsonError(Status.NOT_FOUND, "No existe el gasto general " + id);

        existente.nombre = entrada.optString("nombre", existente.nombre);
        existente.montoCentavos = entrada.optInt("montoCentavos", existente.montoCentavos);
        existente.recurrente = entrada.optBoolean("recurrente", existente.recurrente);
        existente.usuarioId = entrada.optLong("usuarioId", existente.usuarioId);
        db().gastoGeneralDao().update(existente);

        registrarAuditoria(sesionGg.usuarioId, sesionGg.rol, Auditoria.ACCION_GASTO_GENERAL_EDITADO,
                "Editó gasto general: " + existente.nombre + " (Bs " + (existente.montoCentavos / 100.0) + ", " + existente.mes + "/" + existente.anio + ")",
                "gasto_general", id);

        return jsonOk("{}");
    }

    private Response eliminarGastoGeneral(IHTTPSession session, long id) {
        // ★ FIX SEGURIDAD — mismo caso que crearPedido: no exigía sesión.
        Sesion sesionGg = sesionDe(session);
        if (sesionGg == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        GastoGeneral existente = db().gastoGeneralDao().getByIdSync(id);
        // ★ Borra SOLO esta fila — no hay cascada posible: en este diseño
        // cada fila es un registro de un único mes, independiente de las
        // demás (esto es justamente lo que arregla el bug reportado).
        if (existente != null) db().gastoGeneralDao().delete(existente);

        if (existente != null) {
            registrarAuditoria(sesionGg.usuarioId, sesionGg.rol, Auditoria.ACCION_GASTO_GENERAL_ELIMINADO,
                    "Eliminó gasto general: " + existente.nombre + " (" + existente.mes + "/" + existente.anio + ", Bs " + (existente.montoCentavos / 100.0) + ")",
                    "gasto_general", id);
        }

        return jsonOk("{}");
    }

    private JSONObject gastoGeneralAJson(GastoGeneral g) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", g.id);
        json.put("nombre", g.nombre);
        json.put("montoCentavos", g.montoCentavos);
        json.put("anio", g.anio);
        json.put("mes", g.mes);
        json.put("recurrente", g.recurrente);
        json.put("usuarioId", g.usuarioId);
        json.put("creadoEn", g.creadoEn);
        return json;
    }

    // ── /salsas ──────────────────────────────────────────────────────────────
    // Devuelve solo las activas, mismo criterio que /menu con categorías y
    // productos (activo=false se oculta del cliente que arma pedidos; si en
    // el futuro se necesita administrar salsas inactivas desde el cliente,
    // esa pantalla debería pedir una ruta separada, no reusar esta).
    // ★ FIX — mismo caso que /menu: sin ?todo=1 solo activas (lo que necesita
    // el modal de venta); con ?todo=1, todas (lo que necesita la pantalla de
    // gestión de salsas para poder reactivar una que se desactivó).
    private Response listarSalsas(IHTTPSession session) {
        boolean incluirInactivas = "1".equals(primerParametro(session.getParameters(), "todo"));
        List<SalsaGlobal> salsas = incluirInactivas
                ? db().salsaGlobalDao().getTodasSync()
                : db().salsaGlobalDao().getActivasSync();
        JSONArray arr = new JSONArray();
        try {
            for (SalsaGlobal s : salsas) arr.put(salsaAJson(s));
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error listando salsas: " + e.getMessage());
        }
        return jsonOk(arr.toString());
    }

    private JSONObject salsaAJson(SalsaGlobal s) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", s.id);
        json.put("nombre", s.nombre);
        json.put("orden", s.orden);
        json.put("activo", s.activo);
        return json;
    }

    private Response crearSalsa(IHTTPSession session) throws IOException, ResponseException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        SalsaGlobal salsa = new SalsaGlobal();
        salsa.nombre = entrada.optString("nombre", "");
        salsa.orden = entrada.optInt("orden", 0);
        salsa.activo = true;

        long id = db().salsaGlobalDao().insert(salsa);
        if (ws != null) ws.avisarSalsaActualizada();
        return jsonOk(idComoJson(id));
    }

    private Response setActivoSalsa(IHTTPSession session, long id) throws IOException, ResponseException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");
        boolean activo = entrada.optBoolean("activo", true);
        db().salsaGlobalDao().setActivo(id, activo);
        if (ws != null) ws.avisarSalsaActualizada();
        return jsonOk("{}");
    }

    private Response setNombreSalsa(IHTTPSession session, long id) throws IOException, ResponseException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");
        String nombre = entrada.optString("nombre", "").trim();
        if (nombre.isEmpty()) return jsonError(Status.BAD_REQUEST, "El nombre no puede estar vacío");
        db().salsaGlobalDao().setNombre(id, nombre);
        if (ws != null) ws.avisarSalsaActualizada();
        return jsonOk("{}");
    }

    private Response eliminarSalsa(long id) {
        // Room's @Delete matea por primary key — no hace falta traer la fila
        // completa primero, alcanza con un objeto con el id seteado.
        SalsaGlobal salsa = new SalsaGlobal();
        salsa.id = id;
        db().salsaGlobalDao().delete(salsa);
        if (ws != null) ws.avisarSalsaActualizada();
        return jsonOk("{}");
    }

    // ── /pedido, /pedido/{id}, /pedidos ──────────────────────────────────────
    private Response crearPedido(IHTTPSession session) throws IOException, ResponseException, JSONException {
        // ★ FIX SEGURIDAD — antes esto NO exigía sesión: si no había Bearer
        // válido, el pedido se creaba igual atribuido a "usuario
        // desconocido" (usuarioCreadorId=0) en vez de rechazarse. Ese era
        // justamente el hueco que dejaba a un empleado eliminado/desactivado
        // seguir mandando pedidos con total normalidad — ahora, con
        // invalidarSesionesDeUsuario ya cortando el token apenas lo
        // eliminan/desactivan, ESTE chequeo es el que efectivamente lo
        // bloquea (antes solo se veía "Usuario eliminado" en la comanda,
        // pero el pedido se creaba de todos modos).
        Sesion sesionCreador = sesionDe(session);
        if (sesionCreador == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        Pedido pedido = pedidoDesdeJson(entrada, null);

        // ★ NUEVO — idempotencia (ver Pedido.uuidCliente): si el cliente ya
        // mandó este mismo pedido antes (mismo uuid) y el servidor lo llegó
        // a crear, pero la respuesta nunca le llegó (timeout, red cortada),
        // un reintento no debe crear un segundo pedido. Se corta ACÁ, antes
        // de tocar numero_orden o inventario — nada de lo que sigue en este
        // método llega a ejecutarse en un reintento detectado.
        if (pedido.uuidCliente != null && !pedido.uuidCliente.isEmpty()) {
            Pedido yaCreado = db().pedidoDao().getByUuidClienteSync(pedido.uuidCliente);
            if (yaCreado != null) {
                JSONObject respuestaRepetida = new JSONObject();
                respuestaRepetida.put("id", yaCreado.id);
                respuestaRepetida.put("numeroOrden", yaCreado.numeroOrden);
                // Los avisos de inventario ("agotado"/"bajo") se calcularon en
                // el intento original, no se recalculan acá — si el cliente no
                // llegó a verlos la primera vez, se pierden esta vez también,
                // pero eso es cosmético (un Toast), no afecta el stock real.
                respuestaRepetida.put("avisos", new JSONArray());
                return jsonOk(respuestaRepetida.toString());
            }
        }

        // ★ FIX — antes se aceptaba el "creadoEn" que mandara el cliente tal
        // cual (pedidoDesdeJson lo toma del JSON si viene). Como creadoEn es
        // lo que decide tanto el período de numeración (siguienteNumeroOrden,
        // abajo) como el día en que el pedido aparece en los reportes, una
        // tablet con la hora mal configurada podía descuadrar los dos. Mismo
        // criterio que ya se aplicaba a numeroOrden: el servidor es la única
        // fuente de verdad para el momento de creación, así que se pisa acá
        // con la hora del servidor, se ignore lo que haya venido en el JSON.
        pedido.creadoEn = System.currentTimeMillis();

        // Atribución de venta: quién tomó este pedido. Se saca de la sesión
        // activa del que hizo el request, nunca de un campo que mande el
        // cliente en el JSON (eso se podría falsear).
        pedido.usuarioCreadorId = sesionCreador.usuarioId;
        if (Pedido.ESTADO_PAGADO.equals(pedido.estado)) {
            // Venta directa (sin pasar por Entregas): quien lo creó es,
            // de una, quien cobró.
            pedido.usuarioCobradorId = pedido.usuarioCreadorId;
        }

        List<ItemPedido> itemsSinId = new java.util.ArrayList<>();
        JSONArray itemsJson = entrada.optJSONArray("items");
        if (itemsJson != null) {
            for (int i = 0; i < itemsJson.length(); i++) {
                // pedidoId todavía no existe (se asigna recién con el insert de
                // abajo) — se completa dentro de la transacción.
                itemsSinId.add(itemPedidoDesdeJson(itemsJson.getJSONObject(i), 0));
            }
        }

        // ★ FIX — antes el "synchronized(LOCK_NUMERO_ORDEN)" de
        // siguienteNumeroOrden() SOLO envolvía el cálculo de MAX+1: el lock
        // se soltaba apenas esa llamada retornaba, bastante ANTES del
        // insert real (que recién pasa más abajo, dentro de
        // runInTransaction). Con dos tablets mandando POST /pedido casi al
        // mismo tiempo, las dos podían leer el mismo MAX, calcular el mismo
        // "siguiente" número, y las dos insertar — no había ningún índice
        // UNIQUE en numero_orden que lo impidiera. El comentario original en
        // LOCK_NUMERO_ORDEN decía que esto evitaba justo ese caso, pero tal
        // como estaba escrito no lo hacía.
        //
        // Ahora el synchronized envuelve TODO el tramo crítico: calcular el
        // número Y hacer el insert (vía runInTransaction), como un solo
        // bloque. Así, el segundo request de dos simultáneos queda
        // bloqueado esperando a que el primero termine de insertar, y recién
        // ahí calcula su propio MAX+1 — que para ese momento ya incluye el
        // insert del primero. siguienteNumeroOrden() sigue teniendo su
        // propio synchronized sobre el mismo LOCK_NUMERO_ORDEN; se deja así
        // a propósito (los locks de Java son reentrantes por hilo, no hay
        // deadlock) para que el método siga siendo seguro de llamar por su
        // cuenta si en el futuro se usa desde otro lado.
        //
        // ★ NUEVO — Todo esto va en UNA sola transacción SQLite: insertar el
        // pedido, insertar los items, y descontar inventario ítem por ítem
        // con un UPDATE atómico "solo si alcanza" (ver descontarStockSiAlcanza
        // en las DAOs). Si algún ítem ya no tiene stock suficiente (por
        // ejemplo, otra tablet vendió lo último unos milisegundos antes),
        // se corta con StockInsuficienteException y Room deshace TODO —
        // no queda ni el pedido, ni los items, ni ningún descuento a medias.
        // Esto es lo que evita la sobreventa real: antes, dos POST /pedido
        // casi simultáneos por el mismo último producto podían los dos
        // "descontar" (el UPDATE viejo solo saturaba en 0, nunca rechazaba).
        final JSONArray[] avisosHolder = new JSONArray[1];
        final long[] idHolder = new long[1];
        // ★ FIX — productos cuyo stock cambió: el aviso WebSocket se emite DESPUÉS del commit
        // (antes salía dentro de la transacción: las tablets recargaban el menú y leían stock
        // viejo, o avisaban de cambios que luego se deshacían por StockInsuficienteException).
        final java.util.Set<Long> productosAfectados = new java.util.LinkedHashSet<>();
        try {
            synchronized (LOCK_NUMERO_ORDEN) {
                // ★ El servidor es quien asigna numero_orden — el valor que
                // haya mandado el cliente en el JSON se descarta
                // (pedidoDesdeJson lo puso en pedido.numeroOrden, pero acá
                // lo pisamos). Ver comentario en LOCK_NUMERO_ORDEN sobre por
                // qué esto tiene que vivir en el servidor y no en cada
                // cliente. Se calcula ACÁ ADENTRO del synchronized, no antes,
                // para que quede pegado al insert (ver arriba).
                pedido.numeroOrden = siguienteNumeroOrden(pedido.creadoEn);

                db().runInTransaction(() -> {
                    long idPedido = db().pedidoDao().insert(pedido);
                    idHolder[0] = idPedido;
                    // ★ FIX — Room NO actualiza automáticamente el id del objeto
                    // "pedido" en memoria después de insert() (solo lo devuelve
                    // como long). Sin esto, pedido.id se quedaba en 0 para
                    // siempre, y todo lo que pasa "pedido" más abajo
                    // (imprimirTicketPedido -> reservarParaImprimirAutomatico,
                    // marcarImpresoAsync) terminaba operando sobre un id=0 que
                    // no existe en la tabla — el reclamo nunca encontraba la
                    // fila real y devolvía 0 filas, así que el pedido JAMÁS se
                    // mandaba a imprimir. Antes de este fix esto ya pasaba
                    // silenciosamente con marcarImpresoAsync (el campo impreso
                    // de un pedido recién creado nunca se actualizaba de
                    // verdad), pero no se notaba porque no bloqueaba la
                    // impresión en sí — ahora que reservarParaImprimirAutomatico
                    // SÍ depende de matchear el id real, este bug pasó de
                    // "silencioso" a "no imprime nada".
                    pedido.id = idPedido;
                    List<ItemPedido> items = new java.util.ArrayList<>();
                    for (ItemPedido ip : itemsSinId) {
                        ip.pedidoId = idPedido;
                        items.add(ip);
                    }
                    if (!items.isEmpty()) {
                        // ★ insertAll (no insert uno por uno) por rendimiento, pero
                        // eso significa que hay que recuperar los ids generados a
                        // mano y asignárselos a cada ItemPedido en memoria — si no,
                        // item.id se queda en 0 y guardarDetalleItem() de abajo
                        // guardaría todas las salsas/modificadores con
                        // item_pedido_id=0 (pisándose entre sí).
                        List<Long> idsGenerados = db().itemPedidoDao().insertAll(items);
                        for (int i = 0; i < items.size(); i++) {
                            items.get(i).id = idsGenerados.get(i);
                        }
                        // ★ NUEVO — recién con los ids ya asignados, guardar el
                        // detalle de salsas/modificadores de cada línea.
                        for (ItemPedido ip : items) guardarDetalleItem(ip);
                    }
                    try {
                        avisosHolder[0] = descontarInventario(items, pedido.usuarioCobradorId, productosAfectados);
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                });
            } // fin synchronized (LOCK_NUMERO_ORDEN)
            // Ya hizo commit: recién ahora se avisa (1 evento por producto, no por ítem).
            if (ws != null) for (Long pid : productosAfectados) ws.avisarStockActualizado(pid);
        } catch (StockInsuficienteException e) {
            // ★ Nada se guardó — el cliente debe sacar este ítem de la orden
            // en construcción y avisarle al mesero, no reintentar tal cual.
            return jsonError(Status.BAD_REQUEST, e.getMessage());
        } catch (android.database.sqlite.SQLiteConstraintException e) {
            // ★ FIX — el chequeo de idempotencia de arriba ("¿ya existe este
            // uuidCliente?") y el insert de acá adentro NO son atómicos entre
            // sí, ni siquiera estando los dos bajo el mismo synchronized: el
            // chequeo corre ANTES de entrar al synchronized. Dos requests con
            // el MISMO uuidCliente, genuinamente en paralelo (el caso real es
            // un cliente que reintenta por timeout MIENTRAS el primer intento
            // todavía sigue en vuelo del lado del servidor, no necesariamente
            // después de que termine), pueden los dos pasar ese chequeo antes
            // de que ninguno haya insertado. El índice único sobre
            // pedidos.uuid_cliente (MIGRATION_10_11) evita que se guarden DOS
            // pedidos, pero el segundo insert en perder la carrera tira esta
            // excepción en vez de fallar en silencio. Se la interpreta como
            // "alguien más ya ganó la carrera con este mismo uuid" y se
            // devuelve ESE pedido, en vez de dejar que suba como un 500.
            if (pedido.uuidCliente != null && !pedido.uuidCliente.isEmpty()) {
                Pedido yaCreadoPorOtro = db().pedidoDao().getByUuidClienteSync(pedido.uuidCliente);
                if (yaCreadoPorOtro != null) {
                    JSONObject respuestaRepetida = new JSONObject();
                    respuestaRepetida.put("id", yaCreadoPorOtro.id);
                    respuestaRepetida.put("numeroOrden", yaCreadoPorOtro.numeroOrden);
                    respuestaRepetida.put("avisos", new JSONArray());
                    return jsonOk(respuestaRepetida.toString());
                }
            }
            throw e; // constraint distinta a uuid_cliente — no hay nada más que hacer acá.
        }

        long id = idHolder[0];
        List<ItemPedido> items = itemsSinId; // ya tienen pedidoId asignado

        if (ws != null) ws.avisarPedidoNuevo(id);

        // ★ Imprimir el ticket de cocina desde LA IMPRESORA DEL SERVIDOR
        // (no la del cliente que mandó el pedido). Se dispara para pedidos
        // "pendiente" (recién enviados a cocina) y "pagado" (venta directa
        // sin pasar por Entregas) — mismos dos casos en los que el cliente,
        // en modo Independiente, imprimía al tocar el botón "Imprimir". La
        // actualización posterior a "pagado" vía PUT /pedido/{id} (ver
        // actualizarPedido) NO reimprime — igual que el cliente tampoco
        // reimprime automáticamente al marcar "Entregado".
        imprimirTicketPedido(pedido, items);

        JSONObject respuesta = new JSONObject();
        respuesta.put("id", id);
        // ★ El cliente puede leer este campo para mostrar/registrar el
        // número real asignado por el servidor en vez de confiar en su
        // contador local (ver PedidoRepositoryRemoto.crear en el cliente).
        respuesta.put("numeroOrden", pedido.numeroOrden);
        // ★ avisos de inventario ("agotado"/"bajo") para que el cliente que
        // mandó ESTE pedido muestre el mismo Toast que ya mostraba en modo
        // Independiente (ver PedidoRepositoryRemoto.crear y el uso de este
        // campo en MainActivity). El resto de tablets se enteran del cambio
        // de stock por WebSocket (avisarStockActualizado), no por este campo.
        respuesta.put("avisos", avisosHolder[0] != null ? avisosHolder[0] : new JSONArray());
        return jsonOk(respuesta.toString());
    }

    /**
     * ★ NUEVO — lanzada dentro de la transacción de crearPedido() cuando un
     * ítem del pedido ya no tiene stock suficiente. RuntimeException a
     * propósito (Room.runInTransaction exige Runnable, sin checked
     * exceptions) — al propagarse, Room hace ROLLBACK de toda la transacción.
     */
    private static class StockInsuficienteException extends RuntimeException {
        StockInsuficienteException(String mensaje) { super(mensaje); }
    }

    /**
     * ★ NUEVO — lanzada dentro de la transacción de cancelarPedido() cuando
     * el UPDATE condicional de PedidoDao.cancelar() afecta 0 filas: significa
     * que otro request ya canceló este mismo pedido en el medio. RuntimeException
     * a propósito (mismo motivo que StockInsuficienteException, arriba) para
     * que Room haga ROLLBACK y revertirInventario no llegue a correr.
     */
    private static class YaCanceladoException extends RuntimeException {}

    /**
     * ★ NUEVO — POST /inventario/movimiento: aplica una entrada o salida
     * manual de stock (desde InventarioActivity en cualquier tablet), deja
     * el registro en el historial, y si una ENTRADA saca al producto/
     * variante de "sin stock" lo reactiva solo — simétrico al apagado
     * automático de descontarInventario() de más abajo. Avisa por WebSocket
     * a todas las tablets, igual que hace una venta, para que el menú se
     * actualice en tiempo real en todos lados.
     */
    private Response registrarMovimientoInventario(IHTTPSession session) throws IOException, ResponseException, JSONException {
        // ★ FIX — antes no se validaba la sesión y el usuarioId venía del body (falsificable).
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        final Long productoId = entrada.isNull("productoId") ? null : entrada.optLong("productoId");
        final Long varianteId = entrada.isNull("varianteId") ? null : entrada.optLong("varianteId");
        final String tipo     = entrada.optString("tipo", "");
        final int cantidad    = entrada.optInt("cantidad", 0);
        String motivoCrudo    = entrada.optString("motivo", null);
        final String motivo   = (motivoCrudo == null || motivoCrudo.isEmpty()) ? null : motivoCrudo;
        final String nombre   = entrada.optString("nombre", "producto");
        final long usuarioId  = sesion.usuarioId;

        if (cantidad <= 0) return jsonError(Status.BAD_REQUEST, "La cantidad debe ser mayor a 0");
        final boolean esEntrada = MovimientoInventario.TIPO_ENTRADA.equals(tipo);
        if (!esEntrada && !MovimientoInventario.TIPO_SALIDA.equals(tipo)) {
            return jsonError(Status.BAD_REQUEST, "tipo inválido (esperaba \"entrada\" o \"salida\")");
        }
        if ((productoId == null) == (varianteId == null)) {
            return jsonError(Status.BAD_REQUEST, "Debe venir exactamente uno de productoId/varianteId");
        }

        // ★ FIX — lectura + cálculo + escritura ahora son UNA operación atómica dentro de una
        // transacción (UPDATE ... SET stock = stock + delta con tope en 0), igual que la venta.
        // Antes: leer → calcular → escribir sin transacción (una venta en el medio se perdía),
        // la salida mayor al stock se recortaba a 0 pero el historial guardaba la cantidad
        // completa, y se podían registrar movimientos de productos inexistentes.
        final int delta = esEntrada ? cantidad : -cantidad;
        final int[] stockHolder = new int[1];
        final boolean[] reactivadoHolder = new boolean[1];
        final long[] productoAvisar = new long[1];

        try {
            db().runInTransaction(() -> {
                if (varianteId != null) {
                    VarianteProducto antes = db().varianteProductoDao().getByIdSync(varianteId);
                    if (antes == null) throw new InventarioInvalidoException("La variante " + varianteId + " no existe");
                    if (!antes.tieneInventario) throw new InventarioInvalidoException("Esta variante no tiene inventario activado");
                    if (db().varianteProductoDao().aplicarDelta(varianteId, delta) == 0) {
                        throw new InventarioInvalidoException("Stock insuficiente: hay " + antes.stockActual
                                + " y se intentó sacar " + cantidad);
                    }
                    VarianteProducto despues = db().varianteProductoDao().getByIdSync(varianteId);
                    stockHolder[0] = despues.stockActual;
                    // Reactivar solo si estaba agotada y tiene precio (no sacar a la venta "Por definir" a Bs 0).
                    if (esEntrada && antes.stockActual <= 0 && despues.stockActual > 0
                            && !despues.habilitado && despues.precioCentavos > 0) {
                        db().varianteProductoDao().setHabilitado(varianteId, true);
                        reactivadoHolder[0] = true;
                    }
                    db().movimientoInventarioDao().insert(new MovimientoInventario(
                            null, varianteId, tipo, cantidad, despues.stockActual, motivo, nombre, usuarioId));
                    productoAvisar[0] = antes.productoId;
                } else {
                    Producto antes = db().productoDao().getByIdSync(productoId);
                    if (antes == null) throw new InventarioInvalidoException("El producto " + productoId + " no existe");
                    if (!antes.tieneInventario) throw new InventarioInvalidoException("Este producto no tiene inventario activado");
                    if (db().productoDao().aplicarDelta(productoId, delta) == 0) {
                        throw new InventarioInvalidoException("Stock insuficiente: hay " + antes.stockActual
                                + " y se intentó sacar " + cantidad);
                    }
                    Producto despues = db().productoDao().getByIdSync(productoId);
                    stockHolder[0] = despues.stockActual;
                    if (esEntrada && antes.stockActual <= 0 && despues.stockActual > 0 && !despues.activo) {
                        db().productoDao().setActivo(productoId, true);
                        reactivadoHolder[0] = true;
                    }
                    db().movimientoInventarioDao().insert(new MovimientoInventario(
                            productoId, null, tipo, cantidad, despues.stockActual, motivo, nombre, usuarioId));
                    productoAvisar[0] = productoId;
                }
            });
        } catch (InventarioInvalidoException e) {
            return jsonError(Status.BAD_REQUEST, e.getMessage());
        }

        // Post-commit.
        if (ws != null) ws.avisarStockActualizado(productoAvisar[0]);

        JSONObject resp = new JSONObject();
        resp.put("nuevoStock", stockHolder[0]);
        resp.put("reactivado", reactivadoHolder[0]);
        return jsonOk(resp.toString());
    }

    /** ★ NUEVO — validación fallida en POST /inventario/movimiento. RuntimeException a propósito
     *  (mismo motivo que StockInsuficienteException): hace rollback de la transacción de Room. */
    private static class InventarioInvalidoException extends RuntimeException {
        InventarioInvalidoException(String mensaje) { super(mensaje); }
    }

    /** ★ NUEVO — POST /inventario/stock-minimo: edita el umbral de aviso "stock bajo". */
    private Response actualizarStockMinimoInventario(IHTTPSession session) throws IOException, ResponseException, JSONException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        Long productoId = entrada.isNull("productoId") ? null : entrada.optLong("productoId");
        Long varianteId = entrada.isNull("varianteId") ? null : entrada.optLong("varianteId");
        int minimo = Math.max(0, entrada.optInt("minimo", 5));

        if ((productoId == null) == (varianteId == null)) {
            return jsonError(Status.BAD_REQUEST, "Debe venir exactamente uno de productoId/varianteId");
        }
        if (varianteId != null) db().varianteProductoDao().actualizarStockMinimo(varianteId, minimo);
        else db().productoDao().actualizarStockMinimo(productoId, minimo);

        return jsonOk("{}");
    }

    /**
     * ★ NUEVO — POST /inventario/stock-maximo: fija el stock MÁXIMO de un producto/variante
     * (cuántas unidades hay cuando está "lleno", ej. 24 botellas por entrega). Es la referencia
     * del Reporte de Inventario: faltante = máximo − actual. 0 = "sin máximo definido".
     * Requiere sesión (a diferencia de stock-minimo, que hoy no la pide).
     */
    private Response actualizarStockMaximoInventario(IHTTPSession session) throws IOException, ResponseException, JSONException {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        Long productoId = entrada.isNull("productoId") ? null : entrada.optLong("productoId");
        Long varianteId = entrada.isNull("varianteId") ? null : entrada.optLong("varianteId");
        int maximo = entrada.optInt("maximo", -1);

        if ((productoId == null) == (varianteId == null)) {
            return jsonError(Status.BAD_REQUEST, "Debe venir exactamente uno de productoId/varianteId");
        }
        if (maximo < 0) {
            return jsonError(Status.BAD_REQUEST, "El stock máximo debe ser un número mayor o igual a 0");
        }

        long productoAfectado;
        if (varianteId != null) {
            VarianteProducto vp = db().varianteProductoDao().getByIdSync(varianteId);
            if (vp == null) return jsonError(Status.NOT_FOUND, "No existe la variante " + varianteId);
            db().varianteProductoDao().actualizarStockMaximo(varianteId, maximo);
            productoAfectado = vp.productoId;
        } else {
            Producto p = db().productoDao().getByIdSync(productoId);
            if (p == null) return jsonError(Status.NOT_FOUND, "No existe el producto " + productoId);
            db().productoDao().actualizarStockMaximo(productoId, maximo);
            productoAfectado = productoId;
        }
        if (ws != null) ws.avisarStockActualizado(productoAfectado);

        return jsonOk("{}");
    }

    /** ★ NUEVO — GET /inventario/movimientos?productoId=X ó ?varianteId=X: historial. */
    private Response listarMovimientosInventario(IHTTPSession session) {
        Map<String, List<String>> params = session.getParameters();
        String productoIdStr = primerParametro(params, "productoId");
        String varianteIdStr = primerParametro(params, "varianteId");

        List<MovimientoInventario> movimientos;
        if (varianteIdStr != null) {
            movimientos = db().movimientoInventarioDao().getPorVarianteSync(Long.parseLong(varianteIdStr));
        } else if (productoIdStr != null) {
            movimientos = db().movimientoInventarioDao().getPorProductoSync(Long.parseLong(productoIdStr));
        } else {
            return jsonError(Status.BAD_REQUEST, "Falta productoId o varianteId");
        }

        JSONArray arr = new JSONArray();
        try {
            for (MovimientoInventario m : movimientos) arr.put(movimientoAJson(m));
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error listando movimientos: " + e.getMessage());
        }
        return jsonOk(arr.toString());
    }

    private JSONObject movimientoAJson(MovimientoInventario m) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", m.id);
        json.put("productoId", m.productoId);
        json.put("varianteId", m.varianteId);
        json.put("tipo", m.tipo);
        json.put("cantidad", m.cantidad);
        json.put("stockResultante", m.stockResultante);
        json.put("motivo", m.motivo);
        json.put("nombreSnapshot", m.nombreSnapshot);
        json.put("creadoEn", m.creadoEn);
        json.put("usuarioId", m.usuarioId);
        return json;
    }

    /**
     * ★ FIX — este Javadoc vivía por error encima de
     * registrarMovimientoInventario() (más arriba), que tiene el suyo
     * propio — este método se había quedado sin ninguno.
     *
     * Descuenta 1×cantidad del stock de cada línea del pedido que corresponda
     * a una variante o producto con inventario activado, de forma ATÓMICA
     * (descontarStockSiAlcanza) — si algún ítem no alcanza, corta todo con
     * StockInsuficienteException (ver crearPedido, que corre esto dentro de
     * una transacción y hace rollback completo).
     *
     * Réplica, del lado servidor, de MainActivity.descontarInventarioYGuardarItems
     * (modo Independiente): si el stock llega a 0, deshabilita automáticamente
     * la variante (o desactiva el producto) para que desaparezca del menú; si
     * queda en stock bajo (pero > 0), no se oculta, solo se avisa.
     *
     * Por cada variante/producto afectado emite "stock_actualizado" por
     * WebSocket — WsClient.java del lado cliente ya sabe reaccionar a ese
     * evento recargando el menú completo (ver ProductoRepositoryRemoto.
     * actualizarProducto), así que el ocultamiento se ve en tiempo real en
     * TODAS las tablets conectadas, no solo en la que mandó el pedido.
     *
     * Devuelve un JSONArray de strings tipo "🚫 Se agotó: X" / "⚠️ Stock
     * bajo: Y (quedan N)", en el mismo formato de texto que ya usaba el
     * Toast del modo Independiente, para que el cliente lo muestre tal cual.
     */
    private JSONArray descontarInventario(List<ItemPedido> items, long usuarioCobradorId,
                                          java.util.Set<Long> productosAfectados) throws JSONException {
        // ★ FIX — estado FINAL de cada producto/variante tras TODAS las líneas del pedido.
        // Antes se deduplicaba por productoId y el apagado ("se agotó") quedaba DENTRO de ese
        // dedupe: si el mismo producto aparecía en dos líneas, o dos variantes del mismo
        // producto llegaban a 0, la segunda nunca se deshabilitaba. Ahora el apagado ocurre
        // siempre que el stock llega a 0, y el dedupe solo aplica al texto del aviso.
        class EstadoStock { String nombre; int stock; int minimo; }
        java.util.Map<String, EstadoStock> finales = new java.util.LinkedHashMap<>();

        for (ItemPedido ip : items) {
            int cantidad = Math.max(1, ip.cantidad);
            String nombre = ip.nombreSnapshot != null ? ip.nombreSnapshot : "producto";

            if (ip.varianteId != null) {
                VarianteProducto antes = db().varianteProductoDao().getByIdSync(ip.varianteId);
                if (antes != null && antes.tieneInventario) {
                    int filas = db().varianteProductoDao().descontarStockSiAlcanza(ip.varianteId, cantidad);
                    if (filas == 0) {
                        throw new StockInsuficienteException(
                                "🚫 Ya no hay stock suficiente de \"" + nombre + "\" (quedan " +
                                        Math.max(antes.stockActual, 0) + "). Sacalo del pedido e intentá de nuevo.");
                    }
                    VarianteProducto vp = db().varianteProductoDao().getByIdSync(ip.varianteId);
                    if (vp != null) {
                        db().movimientoInventarioDao().insert(new MovimientoInventario(
                                null, ip.varianteId, MovimientoInventario.TIPO_VENTA,
                                cantidad, vp.stockActual, "Venta", nombre, usuarioCobradorId));
                        if (vp.stockActual <= 0) {
                            db().varianteProductoDao().setHabilitado(ip.varianteId, false);
                        }
                        productosAfectados.add(vp.productoId);
                        EstadoStock e = new EstadoStock();
                        e.nombre = nombre; e.stock = vp.stockActual; e.minimo = vp.stockMinimo;
                        finales.put("v" + ip.varianteId, e);
                    }
                }
            } else if (ip.productoId != null) {
                Producto antes = db().productoDao().getByIdSync(ip.productoId);
                if (antes != null && antes.tieneInventario) {
                    int filas = db().productoDao().descontarStockSiAlcanza(ip.productoId, cantidad);
                    if (filas == 0) {
                        throw new StockInsuficienteException(
                                "🚫 Ya no hay stock suficiente de \"" + nombre + "\" (quedan " +
                                        Math.max(antes.stockActual, 0) + "). Sacalo del pedido e intentá de nuevo.");
                    }
                    Producto p = db().productoDao().getByIdSync(ip.productoId);
                    if (p != null) {
                        db().movimientoInventarioDao().insert(new MovimientoInventario(
                                ip.productoId, null, MovimientoInventario.TIPO_VENTA,
                                cantidad, p.stockActual, "Venta", nombre, usuarioCobradorId));
                        if (p.stockActual <= 0 && p.activo) {
                            // setActivo (no update(p) completo): no pisa otras columnas.
                            db().productoDao().setActivo(p.id, false);
                        }
                        productosAfectados.add(p.id);
                        EstadoStock e = new EstadoStock();
                        e.nombre = nombre; e.stock = p.stockActual; e.minimo = p.stockMinimo;
                        finales.put("p" + p.id, e);
                    }
                }
            }
        }

        List<String> avisosAgotado = new java.util.ArrayList<>();
        List<String> avisosBajo    = new java.util.ArrayList<>();
        for (EstadoStock e : finales.values()) {
            if (e.stock <= 0) {
                avisosAgotado.add(e.nombre);
            } else if (e.stock <= e.minimo) {
                avisosBajo.add(e.nombre + " (quedan " + e.stock + ")");
            }
        }

        JSONArray avisos = new JSONArray();
        if (!avisosAgotado.isEmpty()) {
            avisos.put("🚫 Se agotó: " + android.text.TextUtils.join(", ", avisosAgotado));
        }
        if (!avisosBajo.isEmpty()) {
            avisos.put("⚠️ Stock bajo: " + android.text.TextUtils.join(", ", avisosBajo));
        }
        return avisos;
    }

    /**
     * Calcula el próximo numero_orden: MAX(numero_orden) + 1 entre los
     * pedidos creados DESDE el inicio del período actual (mínimo 100).
     *
     * ★ FIX (antes reiniciaba mal): la versión anterior guardaba la fecha
     * del último pedido en SharedPreferences y, si coincidía con "hoy",
     * usaba getMaxNumeroOrden() SIN filtrar por fecha — eso traía el
     * máximo de TODA la tabla (histórico), no el del día. Resultado: el
     * primer pedido del período reiniciaba bien a 100, pero el segundo
     * pedido ya "veía" el máximo histórico (ej. 265 de un período
     * anterior) y seguía contando desde ahí. Ahora se consulta
     * directamente el máximo DENTRO del período (getMaxNumeroOrdenDesde),
     * así el reinicio es automático y consistente en todos los pedidos,
     * sin depender de un estado guardado aparte que se podía desincronizar.
     *
     * ★ CAMBIO: el período de reinicio pasó de diario a MENSUAL — el
     * número vuelve a 100 con el primer pedido de cada mes calendario en
     * vez de cada día.
     *
     * Sincronizado sobre LOCK_NUMERO_ORDEN para que dos POST /pedido
     * concurrentes (de dos tablets distintas) nunca calculen el mismo
     * MAX+1 antes de que el primero termine de insertar.
     */
    private int siguienteNumeroOrden(long timestampPedido) {
        synchronized (LOCK_NUMERO_ORDEN) {
            long inicioPeriodo = inicioDelMes(timestampPedido);
            int maxActual = db().pedidoDao().getMaxNumeroOrdenDesde(inicioPeriodo);
            return Math.max(maxActual + 1, 100);
        }
    }

    /** Timestamp del primer instante (00:00:00.000) del mes calendario que contiene timestamp. */
    private long inicioDelMes(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /**
     * Imprime el ticket de cocina desde la impresora Bluetooth conectada al
     * SERVIDOR. No bloquea la respuesta HTTP: PrinterManager.imprimir() es
     * asíncrono y el pedido ya quedó guardado antes de llamar esto — si la
     * impresora está apagada o sin papel, el pedido no se pierde ni se
     * duplica, solo queda logueado el fallo (a diferencia del cliente en
     * modo Independiente, acá no hay un cajero mirando la pantalla del
     * servidor para tocar "Reintentar").
     *
     * ★ FIX — estas eran dos Javadoc separados apilados uno encima del otro
     * (el segundo, sobre el "Camino AUTOMÁTICO", era el único que las
     * herramientas asociaban de verdad a este método — el primero quedaba
     * huérfano). Se unen en un solo comentario.
     *
     * Camino AUTOMÁTICO (pedido nuevo entrando por /pedido, y el reintento
     * de reconexión en reintentarPedidosNoImpresos): exige impreso=0 para
     * reclamar. Es justamente la carrera entre estos dos caminos la que
     * producía los duplicados, así que acá el reclamo es estricto.
     */
    private void imprimirTicketPedido(Pedido pedido, List<ItemPedido> items) {
        long ahora = System.currentTimeMillis();
        int filasReclamadas = db().pedidoDao().reservarParaImprimirAutomatico(
                pedido.id, ahora, ahora - RECLAMO_IMPRESION_VENCE_MS);
        if (filasReclamadas == 0) {
            android.util.Log.i("ServidorHttp", "Pedido #" + pedido.numeroOrden
                    + " ya está impreso o alguien más lo está imprimiendo ahora mismo — se omite este intento");
            return;
        }
        enviarTicketAImpresora(pedido, items, false);
    }

    /**
     * Camino MANUAL (botón "🖨️ Reimprimir" del cliente, POST
     * /pedido/{id}/reimprimir): es una acción explícita del usuario, así que
     * NO exige impreso=0 — tiene que poder reimprimir un ticket que ya salió
     * bien antes (ej. el papel se perdió, se manchó, etc.). El reclamo acá
     * solo evita que dos toques casi simultáneos del mismo botón (doble tap,
     * dos cajeros reimprimiendo el mismo pedido a la vez) manden el ticket
     * dos veces en paralelo — no depende del estado de impreso.
     */
    private void reimprimirTicketManual(Pedido pedido, List<ItemPedido> items) {
        long ahora = System.currentTimeMillis();
        int filasReclamadas = db().pedidoDao().reservarParaReimprimirManual(
                pedido.id, ahora, ahora - RECLAMO_IMPRESION_VENCE_MS);
        if (filasReclamadas == 0) {
            android.util.Log.i("ServidorHttp", "Pedido #" + pedido.numeroOrden
                    + " ya se está reimprimiendo ahora mismo — se omite este toque duplicado");
            return;
        }
        enviarTicketAImpresora(pedido, items, true);
    }

    /**
     * ★ FIX BUG DE VERDAD — margen de seguridad del reclamo (ver
     * PedidoDao.reservarParaImprimirAutomatico / reservarParaReimprimirManual):
     * cuánto tiempo puede quedar "vivo" un reclamo sin que nadie lo libere
     * antes de considerarlo huérfano y dejar que otro intento lo agarre
     * igual. Generoso a propósito (no es el mecanismo anti-duplicado
     * principal, ese es el propio UPDATE atómico) — solo cubre el caso raro
     * de que el proceso muera a mitad de un intento sin llegar a
     * marcarImpresoAsync ni a liberarReclamoImpresion en el catch/onError.
     */
    private static final long RECLAMO_IMPRESION_VENCE_MS = 2 * 60 * 1000; // 2 minutos

    /** Cuerpo real de "construir el ticket y mandarlo a la impresora" —
     *  compartido por imprimirTicketPedido() y reimprimirTicketManual(), que
     *  ya se encargaron de ganar el reclamo antes de llegar acá. */
    private void enviarTicketAImpresora(Pedido pedido, List<ItemPedido> items, boolean esManual) {
        // ★ Categorías con "Imprimir en cocina" apagado (ej. Refrescos): el pedido ya está
        // guardado y contado igual, pero NO sale comanda si TODO es de esas categorías. Si hay
        // al menos un producto de otra categoría, la comanda sale COMPLETA (con los refrescos
        // incluidos). Una reimpresión MANUAL (botón 🖨️) siempre sale: es una acción explícita.
        boolean sinNadaParaCocina = !esManual && !items.isEmpty() && itemsParaCocina(items).isEmpty();
        if (sinNadaParaCocina) {
            android.util.Log.i("ServidorHttp", "Pedido #" + pedido.numeroOrden
                    + " solo tiene categorías sin impresión en cocina — se registra sin imprimir comanda");
            marcarImpresoAsync(pedido.id, true); // "nada pendiente": que no reintente al reconectar
            return;
        }

        // ★ FIX — si el servidor está en Modo Registro (o nunca se configuró
        // una impresora), acá se cortaba con un simple "return" y el pedido
        // quedaba con impreso=false PARA SIEMPRE, porque marcarImpresoAsync
        // nunca se llegaba a llamar. Eso hacía que "Ventas del día" mostrara
        // "⚠️ No se imprimió" en TODOS los pedidos sin excepción — un aviso
        // que sobraba de cuando el servidor sí tenía impresora conectada,
        // no un problema real: acá directamente no se intenta imprimir a
        // propósito. Se marca impreso=true ("no aplica, nada pendiente")
        // para que el aviso solo aparezca cuando de verdad se intentó
        // imprimir y falló (ver el onError más abajo).
        if (!com.example.bufaloserver.print.PrinterPrefs.esModoImpresora(context)) {
            marcarImpresoAsync(pedido.id, true);
            return;
        }
        if (!com.example.bufaloserver.print.PrinterPrefs.tieneMacGuardada(context)) {
            marcarImpresoAsync(pedido.id, true);
            return;
        }

        com.example.bufaloserver.print.TicketData ticket = new com.example.bufaloserver.print.TicketData();
        ticket.numeroOrden   = pedido.numeroOrden;
        ticket.cliente       = pedido.nombreCliente;
        // ★ NUEVO — quién tomó el pedido, para que quede impreso en la
        // comanda (ver EscPosBuilder).
        ticket.vendedor      = nombreUsuarioSync(pedido.usuarioCreadorId);
        // ★ FIX — el ticket nunca mostraba quién había COBRADO, solo quién
        // había TOMADO el pedido — aunque el servidor ya distingue los dos
        // roles (Pedido.usuarioCobradorId, ver actualizarPedido:
        // "mesero toma, cajero cobra"). Esto se notaba sobre todo al
        // reimprimir un pedido ya pagado con 🖨️ Reimprimir: el ticket
        // reimpreso seguía sin mencionar al cajero que efectivamente cobró.
        // Solo se llena si YA está pagado y es una persona distinta de quien
        // tomó el pedido — si es la misma, o si todavía no se cobró, no
        // aporta nada repetir el nombre.
        boolean hayCobradorDistinto = Pedido.ESTADO_PAGADO.equals(pedido.estado)
                && pedido.usuarioCobradorId > 0
                && pedido.usuarioCobradorId != pedido.usuarioCreadorId;
        ticket.cajero = hayCobradorDistinto ? nombreUsuarioSync(pedido.usuarioCobradorId) : null;
        ticket.items         = items;
        ticket.estado        = pedido.estado;
        ticket.metodoPago    = pedido.metodoPago;
        ticket.subtotal      = pedido.totalCentavos / 100.0;
        ticket.descuento     = pedido.descuentoCentavos / 100.0;
        ticket.total         = pedido.getTotalConDescuentoCentavos() / 100.0;
        ticket.mixtoEfectivo = pedido.mixtoEfectivoCentavos / 100.0;
        ticket.mixtoTarjeta  = pedido.mixtoTarjetaCentavos / 100.0;
        ticket.mixtoQr       = pedido.mixtoQrCentavos / 100.0;
        ticket.fechaMillis   = pedido.creadoEn;

        byte[] ticketBytes = com.example.bufaloserver.print.EscPosBuilder.build(ticket);
        com.example.bufaloserver.print.PrinterManager.get(context).imprimir(ticketBytes,
                new com.example.bufaloserver.print.PrinterManager.PrintCallback() {
                    @Override
                    public void onImpreso() {
                        android.util.Log.i("ServidorHttp", "Ticket #" + pedido.numeroOrden + " impreso OK");
                        reproducirSonidoImpresion();
                        marcarImpresoAsync(pedido.id, true);
                    }

                    @Override
                    public void onError(String motivo) {
                        android.util.Log.w("ServidorHttp",
                                "No se pudo imprimir el ticket #" + pedido.numeroOrden + ": " + motivo);
                        marcarImpresoAsync(pedido.id, false);
                        // ★ NUEVO — libera el reclamo apenas se sabe que este
                        // intento falló, para que el PRÓXIMO evento de
                        // reconexión (aunque sea 2 segundos después) pueda
                        // reintentar sin tener que esperar a que venza
                        // RECLAMO_IMPRESION_VENCE_MS (ese margen es solo la
                        // red de seguridad para el caso de que el proceso
                        // muera antes de llegar a este callback).
                        liberarReclamoImpresionAsync(pedido.id);
                    }
                });
    }

    /**
     * ★ true si este ítem debe salir en la comanda de cocina, según el check "Imprimir en
     * cocina" de su categoría. Ante la duda (ítem libre, producto o categoría borrados)
     * devuelve true: es preferible imprimir de más que perder un plato.
     */
    private boolean itemSeImprimeEnCocina(ItemPedido ip) {
        Long productoId = ip.productoId;
        if (productoId == null && ip.varianteId != null) {
            VarianteProducto vp = db().varianteProductoDao().getByIdSync(ip.varianteId);
            if (vp != null) productoId = vp.productoId;
        }
        if (productoId == null) return true;
        Producto p = db().productoDao().getByIdSync(productoId);
        if (p == null) return true;
        Categoria c = db().categoriaDao().getByIdSync(p.categoriaId);
        return c == null || c.imprimirEnCocina;
    }

    /** Ítems de la lista que sí van a cocina (vacío = el pedido no necesita comanda). */
    private List<ItemPedido> itemsParaCocina(List<ItemPedido> items) {
        List<ItemPedido> resultado = new java.util.ArrayList<>();
        for (ItemPedido ip : items) if (itemSeImprimeEnCocina(ip)) resultado.add(ip);
        return resultado;
    }

    /** ★ NUEVO — misma razón que marcarImpresoAsync: no bloquear el hilo que
     *  disparó el callback (main thread) con una escritura a Room. */
    private void liberarReclamoImpresionAsync(long pedidoId) {
        com.example.bufaloserver.db.AppDatabaseServidor.dbExecutor.execute(
                () -> db().pedidoDao().liberarReclamoImpresion(pedidoId));
    }

    /**
     * ★ NUEVO — persiste Pedido.impreso fuera del hilo principal: el
     * callback de PrinterManager (onImpreso/onError) corre en el main
     * thread (ver reproducirSonidoImpresion, que depende de eso), así que
     * la escritura a Room se manda al mismo executor que usa el resto de
     * ServidorHttp para no bloquearlo.
     */
    private void marcarImpresoAsync(long pedidoId, boolean impreso) {
        com.example.bufaloserver.db.AppDatabaseServidor.dbExecutor.execute(
                () -> db().pedidoDao().marcarImpreso(pedidoId, impreso));
    }

    /**
     * Beep corto cada vez que un ticket termina de imprimirse — el servidor
     * normalmente no tiene a nadie mirando la pantalla, así que sirve como
     * confirmación audible de que salió el ticket. Se ejecuta en el hilo
     * principal (mainHandler.post en PrinterManager.imprimir).
     *
     * ★ Movido a SonidosServidor (antes tenía el ToneGenerator acá mismo,
     * con STREAM_NOTIFICATION — ver comentario en esa clase sobre por qué
     * eso no sonaba en el parlante conectado al jack de audífonos en varias
     * tablets/POS).
     */
    private void reproducirSonidoImpresion() {
        com.example.bufaloserver.servicio.SonidosServidor.pedidoImpreso();
    }

    private Response actualizarPedido(IHTTPSession session, long id) throws IOException, ResponseException, JSONException {
        // ★ FIX SEGURIDAD — mismo caso que crearPedido: antes esto no exigía
        // sesión, así que un usuario ya eliminado/desactivado podía seguir
        // marcando pedidos como pagados, cambiándoles el método de pago,
        // etc. con total normalidad.
        Sesion sesionEditor = sesionDe(session);
        if (sesionEditor == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        Pedido existente = db().pedidoDao().getByIdSync(id);
        if (existente == null) return jsonError(Status.NOT_FOUND, "No existe el pedido " + id);

        boolean pasaAPagadoAhora = !Pedido.ESTADO_PAGADO.equals(existente.estado)
                && Pedido.ESTADO_PAGADO.equals(entrada.optString("estado", existente.estado));

        Pedido actualizado = pedidoDesdeJson(entrada, existente);
        actualizado.id = id;

        // ★ NUEVO — atribución de venta: quién cobró. Puede ser distinto de
        // quien tomó el pedido (mesero toma, cajero cobra) — por eso se
        // pisa recién en el momento exacto en que el estado pasa a
        // "pagado", con la sesión de QUIEN HIZO ESTE PUT, no la del creador.
        if (pasaAPagadoAhora) {
            actualizado.usuarioCobradorId = sesionEditor.usuarioId;
        }
        // ★ numero_orden es autoritativo del servidor desde que se crea el
        // pedido (ver crearPedido/siguienteNumeroOrden) — un PUT nunca debe
        // poder pisarlo con el valor que el cliente tenga guardado
        // localmente (su contador puede haber quedado desactualizado si hay
        // más de una tablet conectada).
        actualizado.numeroOrden = existente.numeroOrden;
        // ★ FIX — mismo criterio que numeroOrden, arriba: un PUT (por ejemplo
        // al marcar "pagado") no debe poder correr la fecha de creación del
        // pedido hacia otro día/mes solo porque el cliente mandó un
        // "creadoEn" distinto en el body (pedidoDesdeJson lo hubiera aceptado
        // tal cual). La fecha de creación se fija una sola vez, en
        // crearPedido, y no cambia con las actualizaciones posteriores.
        actualizado.creadoEn = existente.creadoEn;
        // ★ NUEVO — mismo criterio: uuidCliente es la clave de idempotencia
        // de la CREACIÓN (ver Pedido.uuidCliente); un PUT no debe poder
        // pisarlo ni vaciarlo.
        actualizado.uuidCliente = existente.uuidCliente;
        db().pedidoDao().update(actualizado);

        // Mismo evento que al crear — el cliente solo necesita saber "algo
        // cambió en pedidos, refrescate", no distingue alta de edición.
        if (ws != null) ws.avisarPedidoNuevo(id);
        return jsonOk("{}");
    }

    /**
     * POST /reportes/dia/imprimir — botón "🖨️ Imprimir" del "Reporte del
     * día" en modo Conectado. Calcula el resumen del día con las mismas
     * cuentas que ReportesActivity.cargarReporteDiaConectado() (venta total,
     * desglose efectivo/tarjeta/qr, gastos del día detallados, utilidad) y
     * lo imprime en la impresora del servidor — no en la tablet que aprieta
     * el botón, mismo criterio que imprimirTicketPedido/reimprimirPedido.
     */
    private Response imprimirResumenDia(IHTTPSession session) {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.puedeVerReportes(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "No tenés permiso para imprimir el reporte del día");
        }
        if (!com.example.bufaloserver.print.PrinterPrefs.esModoImpresora(context)) {
            return jsonError(Status.BAD_REQUEST, "El servidor está en modo Registro (sin impresora activa)");
        }
        if (!com.example.bufaloserver.print.PrinterPrefs.tieneMacGuardada(context)) {
            return jsonError(Status.BAD_REQUEST, "No hay ninguna impresora configurada en el servidor");
        }

        com.example.bufaloserver.print.ResumenDiaTicketData ticket = construirResumenDia(sesion);

        byte[] ticketBytes = com.example.bufaloserver.print.EscPosResumenDiaBuilder.build(ticket);
        com.example.bufaloserver.print.PrinterManager.get(context).imprimir(ticketBytes,
                new com.example.bufaloserver.print.PrinterManager.PrintCallback() {
                    @Override public void onImpreso() {
                        Log.i(TAG, "Resumen del día impreso OK (pedido por " + ticket.impresoPor + ")");
                    }
                    @Override public void onError(String motivo) {
                        Log.w(TAG, "Fallo al imprimir resumen del día: " + motivo);
                    }
                });

        registrarAuditoria(sesion.usuarioId, sesion.rol, Auditoria.ACCION_RESUMEN_DIA_IMPRESO,
                "Imprimió el resumen del día (Bs " + (ticket.totalVendidoCentavos / 100.0) + ")",
                "resumen_dia", 0);

        return jsonOk("{}");
    }

    /** Arma los datos del resumen del día (compartido por imprimir y previsualizar). */
    private com.example.bufaloserver.print.ResumenDiaTicketData construirResumenDia(Sesion sesion) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long inicio = cal.getTimeInMillis();
        long fin = System.currentTimeMillis();

        int totalVendidoCentavos = db().pedidoDao().sumaTotalPagadoSync(inicio, fin);
        int cantOrdenes          = db().pedidoDao().contarPagadosSync(inicio, fin);
        int descuentosCentavos   = db().pedidoDao().sumaDescuentosSync(inicio, fin);
        int efectivoCentavos     = db().pedidoDao().sumaEfectivoSync(inicio, fin)
                + db().pedidoDao().sumaMixtoEfectivoSync(inicio, fin);
        int tarjetaCentavos      = db().pedidoDao().sumaTarjetaSync(inicio, fin)
                + db().pedidoDao().sumaMixtoTarjetaSync(inicio, fin);
        int qrCentavos           = db().pedidoDao().sumaQrSync(inicio, fin)
                + db().pedidoDao().sumaMixtoQrSync(inicio, fin);

        List<GastoDiario> gastos = db().gastoDiarioDao().getPorFechaSync(inicio, fin);
        int totalGastosCentavos = db().gastoDiarioDao().sumaMontoPorFecha(inicio, fin);
        int utilidadCentavos = totalVendidoCentavos - totalGastosCentavos;

        com.example.bufaloserver.print.ResumenDiaTicketData ticket =
                new com.example.bufaloserver.print.ResumenDiaTicketData();
        ticket.fechaLabel = new SimpleDateFormat("EEEE dd 'de' MMMM yyyy", new java.util.Locale("es", "BO"))
                .format(new java.util.Date(inicio));
        ticket.impresoPor = nombreUsuarioSync(sesion.usuarioId);
        ticket.rolImpresor = sesion.rol;
        ticket.cantOrdenes = cantOrdenes;
        ticket.totalVendidoCentavos = totalVendidoCentavos;
        ticket.descuentosCentavos = descuentosCentavos;
        ticket.efectivoCentavos = efectivoCentavos;
        ticket.tarjetaCentavos = tarjetaCentavos;
        ticket.qrCentavos = qrCentavos;
        ticket.gastos = gastos;
        ticket.totalGastosCentavos = totalGastosCentavos;
        ticket.utilidadCentavos = utilidadCentavos;
        return ticket;
    }

    /** GET /reportes/dia/previsualizar — líneas del ticket de resumen del día, sin imprimir. */
    private Response previsualizarResumenDia(IHTTPSession session) {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.puedeVerReportes(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "No tenés permiso para ver el reporte del día");
        }
        byte[] bytes = com.example.bufaloserver.print.EscPosResumenDiaBuilder.build(construirResumenDia(sesion));
        return respuestaPrevisualizacion("Resumen del día", bytes);
    }

    /** Respuesta común de las 3 previsualizaciones: {"titulo": ..., "lineas": [...]}. */
    private Response respuestaPrevisualizacion(String titulo, byte[] ticketBytes) {
        try {
            JSONObject json = new JSONObject();
            json.put("titulo", titulo);
            json.put("lineas", com.example.bufaloserver.print.EscPosPreview.aLineas(ticketBytes));
            return jsonOk(json.toString());
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "No se pudo armar la vista previa: " + e.getMessage());
        }
    }

    /**
     * POST /reportes/caja/imprimir — botón "🖨️ Reporte de Caja". Calcula
     * cuánto EFECTIVO FÍSICO debería haber en la caja en este momento:
     *
     *   EFECTIVO EN CAJA = Total vendido − QR − Tarjeta − Gastos del día − Adelantos
     *
     * QR y Tarjeta se restan porque, aunque son ventas que sí entraron al
     * total vendido, ese dinero nunca llegó físicamente a la caja. Gastos
     * del día y adelantos se restan porque SÍ salieron del efectivo físico
     * (alguien los pagó con la plata que había en caja). Lo que queda es
     * plata para CONTAR la caja, no la ganancia neta del negocio (eso lo
     * calcula imprimirResumenDia/UTILIDAD).
     */
    private Response imprimirReporteCaja(IHTTPSession session) {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.puedeVerReportes(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "No tenés permiso para imprimir el reporte de caja");
        }
        if (!com.example.bufaloserver.print.PrinterPrefs.esModoImpresora(context)) {
            return jsonError(Status.BAD_REQUEST, "El servidor está en modo Registro (sin impresora activa)");
        }
        if (!com.example.bufaloserver.print.PrinterPrefs.tieneMacGuardada(context)) {
            return jsonError(Status.BAD_REQUEST, "No hay ninguna impresora configurada en el servidor");
        }

        com.example.bufaloserver.print.ReporteCajaTicketData ticket = construirReporteCaja(sesion);

        byte[] ticketBytes = com.example.bufaloserver.print.EscPosReporteCajaBuilder.build(ticket);
        com.example.bufaloserver.print.PrinterManager.get(context).imprimir(ticketBytes,
                new com.example.bufaloserver.print.PrinterManager.PrintCallback() {
                    @Override public void onImpreso() {
                        Log.i(TAG, "Reporte de caja impreso OK (pedido por " + ticket.impresoPor + ")");
                    }
                    @Override public void onError(String motivo) {
                        Log.w(TAG, "Fallo al imprimir reporte de caja: " + motivo);
                    }
                });

        registrarAuditoria(sesion.usuarioId, sesion.rol, Auditoria.ACCION_RESUMEN_DIA_IMPRESO,
                "Imprimió el reporte de caja (efectivo en caja: Bs " + (ticket.efectivoEnCajaCentavos / 100.0) + ")",
                "reporte_caja", 0);

        return jsonOk("{}");
    }

    /** Arma los datos del reporte de caja (compartido por imprimir y previsualizar). */
    private com.example.bufaloserver.print.ReporteCajaTicketData construirReporteCaja(Sesion sesion) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long inicio = cal.getTimeInMillis();
        long fin = System.currentTimeMillis();

        int totalVendidoCentavos = db().pedidoDao().sumaTotalPagadoSync(inicio, fin);
        int cantOrdenes          = db().pedidoDao().contarPagadosSync(inicio, fin);
        int tarjetaCentavos      = db().pedidoDao().sumaTarjetaSync(inicio, fin)
                + db().pedidoDao().sumaMixtoTarjetaSync(inicio, fin);
        int qrCentavos           = db().pedidoDao().sumaQrSync(inicio, fin)
                + db().pedidoDao().sumaMixtoQrSync(inicio, fin);

        // Los gastos del día se separan en dos grupos: adelantos (se
        // detallan uno por uno con el nombre de quién lo recibió) y el
        // resto (compras, insumos, etc. — se muestran como un solo total).
        List<GastoDiario> gastosDelDia = db().gastoDiarioDao().getPorFechaSync(inicio, fin);
        int gastosDiaCentavos = 0;
        int adelantosCentavos = 0;
        List<com.example.bufaloserver.print.ReporteCajaTicketData.AdelantoDetalle> adelantos = new java.util.ArrayList<>();
        for (GastoDiario g : gastosDelDia) {
            if (g.esAdelanto) {
                adelantosCentavos += g.montoCentavos;
                String nombre = g.usuarioId > 0 ? nombreUsuarioSync(g.usuarioId) : g.detalle;
                adelantos.add(new com.example.bufaloserver.print.ReporteCajaTicketData.AdelantoDetalle(nombre, g.montoCentavos));
            } else {
                gastosDiaCentavos += g.montoCentavos;
            }
        }

        int efectivoEnCajaCentavos = totalVendidoCentavos - qrCentavos - tarjetaCentavos
                - gastosDiaCentavos - adelantosCentavos;

        com.example.bufaloserver.print.ReporteCajaTicketData ticket = new com.example.bufaloserver.print.ReporteCajaTicketData();
        ticket.fechaLabel = new SimpleDateFormat("EEEE dd 'de' MMMM yyyy", new java.util.Locale("es", "BO"))
                .format(new java.util.Date(inicio));
        ticket.impresoPor = nombreUsuarioSync(sesion.usuarioId);
        ticket.rolImpresor = sesion.rol;
        ticket.cantOrdenes = cantOrdenes;
        ticket.totalVendidoCentavos = totalVendidoCentavos;
        ticket.qrCentavos = qrCentavos;
        ticket.tarjetaCentavos = tarjetaCentavos;
        ticket.gastosDiaCentavos = gastosDiaCentavos;
        ticket.adelantos = adelantos;
        ticket.adelantosCentavos = adelantosCentavos;
        ticket.efectivoEnCajaCentavos = efectivoEnCajaCentavos;
        return ticket;
    }

    /** GET /reportes/caja/previsualizar — líneas del ticket de caja, sin imprimir. */
    private Response previsualizarReporteCaja(IHTTPSession session) {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.puedeVerReportes(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "No tenés permiso para ver el reporte de caja");
        }
        byte[] bytes = com.example.bufaloserver.print.EscPosReporteCajaBuilder.build(construirReporteCaja(sesion));
        return respuestaPrevisualizacion("Reporte de caja", bytes);
    }

    /**
     * ★ NUEVO — POST /reportes/inventario/imprimir — botón "🖨️ Inventario" de Reportes.
     * Imprime el estado ACTUAL del inventario de todo lo que lo tiene activado: por
     * producto/variante, stock actual, stock máximo y cuánto falta para llegar al máximo
     * (lo que hay que pedir al proveedor). No depende de fechas: es una foto de "hoy".
     *
     * Mismas reglas de acceso y de impresora que el reporte de caja (Cajero/Admin).
     */
    private Response imprimirReporteInventario(IHTTPSession session) {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.puedeVerReportes(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "No tenés permiso para imprimir el reporte de inventario");
        }
        if (!com.example.bufaloserver.print.PrinterPrefs.esModoImpresora(context)) {
            return jsonError(Status.BAD_REQUEST, "El servidor está en modo Registro (sin impresora activa)");
        }
        if (!com.example.bufaloserver.print.PrinterPrefs.tieneMacGuardada(context)) {
            return jsonError(Status.BAD_REQUEST, "No hay ninguna impresora configurada en el servidor");
        }

        com.example.bufaloserver.print.ReporteInventarioTicketData ticket = construirReporteInventario(sesion);
        if (ticket == null) {
            return jsonError(Status.BAD_REQUEST, "No hay ningún producto con inventario activado");
        }

        byte[] ticketBytes = com.example.bufaloserver.print.EscPosReporteInventarioBuilder.build(ticket);
        com.example.bufaloserver.print.PrinterManager.get(context).imprimir(ticketBytes,
                new com.example.bufaloserver.print.PrinterManager.PrintCallback() {
                    @Override public void onImpreso() {
                        Log.i(TAG, "Reporte de inventario impreso OK (pedido por " + ticket.impresoPor + ")");
                    }
                    @Override public void onError(String motivo) {
                        Log.w(TAG, "Fallo al imprimir reporte de inventario: " + motivo);
                    }
                });

        registrarAuditoria(sesion.usuarioId, sesion.rol, Auditoria.ACCION_RESUMEN_DIA_IMPRESO,
                "Imprimió el reporte de inventario (" + ticket.cantSinStock + " sin stock, "
                        + ticket.cantBajo + " bajo, a reponer: " + ticket.totalFaltante + " und)",
                "reporte_inventario", 0);

        return jsonOk("{}");
    }

    /** Arma los datos del reporte de inventario, o null si no hay nada con inventario activado
     *  (compartido por imprimir y previsualizar). */
    private com.example.bufaloserver.print.ReporteInventarioTicketData construirReporteInventario(Sesion sesion) {
        com.example.bufaloserver.print.ReporteInventarioTicketData ticket = new com.example.bufaloserver.print.ReporteInventarioTicketData();
        int totalItems = 0;

        for (com.example.bufaloserver.db.entities.Categoria cat : db().categoriaDao().getTodasSync()) {
            com.example.bufaloserver.print.ReporteInventarioTicketData.CategoriaInv catInv =
                    new com.example.bufaloserver.print.ReporteInventarioTicketData.CategoriaInv(cat.nombre);

            for (Producto p : db().productoDao().getPorCategoriaSync(cat.id)) {
                List<VarianteProducto> variantes = db().varianteProductoDao().getPorProductoSync(p.id);
                if (!variantes.isEmpty()) {
                    // Con variantes: el inventario vive en cada variante (como en InventarioActivity).
                    for (VarianteProducto vp : variantes) {
                        if (!vp.tieneInventario) continue;
                        String nombre = (vp.label != null && !vp.label.trim().isEmpty())
                                ? p.nombre + " - " + vp.label : p.nombre;
                        catInv.items.add(new com.example.bufaloserver.print.ReporteInventarioTicketData.ItemInv(
                                nombre, vp.stockActual, vp.stockMinimo, vp.stockMaximo));
                    }
                } else if (p.tieneInventario) {
                    catInv.items.add(new com.example.bufaloserver.print.ReporteInventarioTicketData.ItemInv(
                            p.nombre, p.stockActual, p.stockMinimo, p.stockMaximo));
                }
            }

            if (catInv.items.isEmpty()) continue;
            for (com.example.bufaloserver.print.ReporteInventarioTicketData.ItemInv it : catInv.items) {
                totalItems++;
                if (it.sinStock())      ticket.cantSinStock++;
                else if (it.bajo())     ticket.cantBajo++;
                else                    ticket.cantOk++;
                if (it.tieneMaximo())   ticket.totalFaltante += it.faltante();
                else                    ticket.cantSinMaximo++;
            }
            ticket.categorias.add(catInv);
        }

        if (totalItems == 0) return null; // nada con inventario activado

        ticket.fechaLabel = new SimpleDateFormat("EEEE dd 'de' MMMM yyyy", new java.util.Locale("es", "BO"))
                .format(new java.util.Date(ticket.fechaMillis));
        ticket.impresoPor = nombreUsuarioSync(sesion.usuarioId);
        ticket.rolImpresor = sesion.rol;
        return ticket;
    }

    /** GET /reportes/inventario/previsualizar — líneas del ticket de inventario, sin imprimir. */
    private Response previsualizarReporteInventario(IHTTPSession session) {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.puedeVerReportes(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "No tenés permiso para ver el reporte de inventario");
        }
        com.example.bufaloserver.print.ReporteInventarioTicketData ticket = construirReporteInventario(sesion);
        if (ticket == null) {
            return jsonError(Status.BAD_REQUEST, "No hay ningún producto con inventario activado");
        }
        byte[] bytes = com.example.bufaloserver.print.EscPosReporteInventarioBuilder.build(ticket);
        return respuestaPrevisualizacion("Reporte de inventario", bytes);
    }

    /**
     * POST /pedido/{id}/reimprimir — botón "🖨️ Reimprimir" del cliente en
     * modo Conectado. A diferencia del modo Independiente (donde el cliente
     * reconstruye el ticket desde el OrderItem que tiene en memoria), acá
     * simplemente se relee el pedido + items YA guardados en el servidor y
     * se reimprime en la impresora del servidor — no hace falta que el
     * cliente mande nada en el body.
     */
    private Response reimprimirPedido(IHTTPSession session, long id) {
        // ★ FIX SEGURIDAD — mismo caso que crearPedido/actualizarPedido:
        // esta ruta no pedía sesión para nada, ni siquiera recibía el
        // IHTTPSession como parámetro.
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        Pedido pedido = db().pedidoDao().getByIdSync(id);
        if (pedido == null) return jsonError(Status.NOT_FOUND, "No existe el pedido " + id);
        List<ItemPedido> items = db().itemPedidoDao().getPorPedidoSync(id);
        cargarDetalleItems(items); // ★ NUEVO — sin esto, la reimpresión perdería salsas/modificadores
        reimprimirTicketManual(pedido, items);
        return jsonOk("{}");
    }

    /**
     * ★ NUEVO — reversión de descontarInventario(), llamada al cancelar un
     * pedido (pendiente O pagado). Sin esto, cancelar dejaba el stock
     * descontado "en el aire": se resta al CREAR el pedido (no al pagarlo,
     * ver descontarInventario/crearPedido), así que un pendiente cancelado
     * nunca devolvía nada — quedaba el inventario mal aunque el pedido no
     * se hubiera cobrado. Mismo patrón de reactivar producto/variante que
     * registrarMovimientoInventario() al hacer una entrada manual: si
     * estaba "sin stock" (apagado automático) y la devolución lo saca de 0,
     * se reactiva solo (salvo variantes con precio 0). Se registra como
     * TIPO_DEVOLUCION: suma stock pero no es una compra nueva, y el historial
     * lo muestra en verde con "+" (como TIPO_AJUSTE se veía "-3" en rojo).
     */
    private void revertirInventario(List<ItemPedido> items, long pedidoId, long usuarioId,
                                    java.util.Set<Long> productosAfectados) {
        String motivo = "Cancelación pedido #" + pedidoId;
        for (ItemPedido ip : items) {
            int cantidad = Math.max(1, ip.cantidad);
            String nombre = ip.nombreSnapshot != null ? ip.nombreSnapshot : "producto";

            if (ip.varianteId != null) {
                VarianteProducto antes = db().varianteProductoDao().getByIdSync(ip.varianteId);
                if (antes != null && antes.tieneInventario
                        && db().varianteProductoDao().aplicarDelta(ip.varianteId, cantidad) > 0) {
                    VarianteProducto despues = db().varianteProductoDao().getByIdSync(ip.varianteId);
                    int nuevoStock = despues != null ? despues.stockActual : antes.stockActual + cantidad;
                    // Reactivar solo si estaba agotada y tiene precio: una variante "Por definir"
                    // (precio 0, deshabilitada) no debe salir a la venta a Bs 0.
                    if (antes.stockActual <= 0 && nuevoStock > 0 && !antes.habilitado
                            && antes.precioCentavos > 0) {
                        db().varianteProductoDao().setHabilitado(ip.varianteId, true);
                    }
                    db().movimientoInventarioDao().insert(new MovimientoInventario(
                            null, ip.varianteId, MovimientoInventario.TIPO_DEVOLUCION,
                            cantidad, nuevoStock, motivo, nombre, usuarioId));
                    productosAfectados.add(antes.productoId);
                }
            } else if (ip.productoId != null) {
                Producto antes = db().productoDao().getByIdSync(ip.productoId);
                if (antes != null && antes.tieneInventario
                        && db().productoDao().aplicarDelta(ip.productoId, cantidad) > 0) {
                    Producto despues = db().productoDao().getByIdSync(ip.productoId);
                    int nuevoStock = despues != null ? despues.stockActual : antes.stockActual + cantidad;
                    if (antes.stockActual <= 0 && nuevoStock > 0 && !antes.activo) {
                        db().productoDao().setActivo(ip.productoId, true);
                    }
                    db().movimientoInventarioDao().insert(new MovimientoInventario(
                            ip.productoId, null, MovimientoInventario.TIPO_DEVOLUCION,
                            cantidad, nuevoStock, motivo, nombre, usuarioId));
                    productosAfectados.add(ip.productoId);
                }
            }
        }
    }

    /**
     * ★ CAMBIO — ahora cancela tanto "pendiente" como "pagado", con reglas
     * distintas según cuál era (decisión de negocio charlada aparte, no algo
     * que se pueda inferir del código):
     *
     *   PENDIENTE → cualquiera de los 3 roles, motivo opcional. Es lo mismo
     *   que ya había: se avisó a cocina pero todavía no se cobró nada.
     *
     *   PAGADO → solo Cajero/Admin (no Mesero — el dinero ya cambió de
     *   manos, es una operación más sensible) y el motivo pasa a ser
     *   OBLIGATORIO, porque implica una devolución real de plata que el
     *   Admin va a tener que cruzar contra caja. El sistema NO devuelve el
     *   dinero solo (no hay módulo de caja/efectivo) — lo único que hace
     *   automático es que el pedido cancelado deja de sumar en cualquier
     *   reporte de ventas (todas las queries de estadísticas ya filtran
     *   estado='pagado', ver PedidoDao), y deja bien registrado en
     *   auditoría CUÁNTO y por qué método había que devolver, para que
     *   quien cierre caja lo sepa.
     *
     * En AMBOS casos: se revierte el inventario (revertirInventario, arriba
     * — antes no se hacía para nada) y se imprime una comanda de aviso a
     * cocina + suena la alerta, aunque el texto de la comanda cambia según
     * si ya estaba cobrado (ver imprimirTicketCancelacion).
     */
    private Response cancelarPedido(IHTTPSession session, long id) throws IOException, ResponseException {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");

        Pedido pedido = db().pedidoDao().getByIdSync(id);
        if (pedido == null) return jsonError(Status.NOT_FOUND, "No existe el pedido " + id);
        if (Pedido.ESTADO_CANCELADO.equals(pedido.estado)) {
            return jsonError(Status.BAD_REQUEST, "Este pedido ya estaba cancelado");
        }

        boolean estabaPagado = Pedido.ESTADO_PAGADO.equals(pedido.estado);

        if (estabaPagado && !com.example.bufaloserver.db.entities.RolUsuario.puedeCancelarPedidoPagado(sesion.rol)) {
            return jsonError(Status.FORBIDDEN,
                    "Un pedido ya cobrado solo lo puede cancelar Cajero o Administrador");
        }

        JSONObject entrada = leerBody(session);
        String motivo = entrada != null ? entrada.optString("motivo", "").trim() : "";
        if (estabaPagado && motivo.isEmpty()) {
            return jsonError(Status.BAD_REQUEST,
                    "El motivo es obligatorio para cancelar un pedido que ya estaba cobrado");
        }

        long ts = System.currentTimeMillis();

        // ★ FIX — todo lo que decide y ejecuta la cancelación va en UNA
        // transacción: el UPDATE condicional (ver PedidoDao.cancelar) más la
        // reversión de inventario. Antes había una ventana entre "leer
        // pedido.estado" (arriba) y el UPDATE sin condición de más abajo —
        // un doble toque o dos tablets cancelando a la vez pasaban los dos
        // el chequeo de arriba y los dos revertían inventario. Ahora, si el
        // UPDATE afecta 0 filas (porque alguien más ya canceló este pedido
        // en el medio), se aborta la transacción entera y no se toca stock
        // ni se duplica nada.
        List<ItemPedido> items = db().itemPedidoDao().getPorPedidoSync(id);
        cargarDetalleItems(items);

        final java.util.Set<Long> productosAfectados = new java.util.LinkedHashSet<>();
        try {
            db().runInTransaction(() -> {
                int filas = db().pedidoDao().cancelar(id, ts);
                if (filas == 0) {
                    throw new YaCanceladoException();
                }
                revertirInventario(items, id, sesion.usuarioId, productosAfectados);
            });
        } catch (YaCanceladoException e) {
            return jsonError(Status.BAD_REQUEST, "Este pedido ya estaba cancelado");
        }
        // ★ Post-commit (antes se emitía dentro de la transacción).
        if (ws != null) for (Long pid : productosAfectados) ws.avisarStockActualizado(pid);

        String nombreQuienCancela = nombreUsuarioSync(sesion.usuarioId);

        pedido.estado = Pedido.ESTADO_CANCELADO;
        pedido.canceladoEn = ts;
        imprimirTicketCancelacion(pedido, items, nombreQuienCancela, estabaPagado);
        // La alerta sonora solo si cocina debe enterarse (no para pedidos solo de refrescos).
        if (items.isEmpty() || !itemsParaCocina(items).isEmpty()) reproducirSonidoCancelacion();

        String detalle = "Canceló el pedido #" + String.format("%03d", pedido.numeroOrden)
                + (estabaPagado
                ? " — YA ESTABA COBRADO: Bs " + (pedido.getTotalConDescuentoCentavos() / 100.0)
                + " en " + etiquetaMetodoPago(pedido.metodoPago) + " — Motivo: " + motivo
                : (motivo.isEmpty() ? "" : " — Motivo: " + motivo));
        registrarAuditoria(sesion.usuarioId, sesion.rol, Auditoria.ACCION_PEDIDO_CANCELADO,
                detalle, "pedido", id);

        if (ws != null) ws.avisarPedidoCancelado(id);
        return jsonOk("{}");
    }

    /** ★ NUEVO — nombre legible del método de pago para el detalle de
     *  auditoría ("efectivo" ya es legible, pero "qr"/"mixto" se leen mejor
     *  con mayúscula/expandido). */
    private String etiquetaMetodoPago(String metodoPago) {
        if (metodoPago == null) return "?";
        switch (metodoPago) {
            case "efectivo": return "efectivo";
            case "tarjeta":  return "tarjeta";
            case "qr":       return "QR";
            case "mixto":    return "pago mixto";
            default:         return metodoPago;
        }
    }

    /**
     * ★ NUEVO — variante de imprimirTicketPedido() para la comanda de
     * cancelación. No reusa imprimirTicketPedido tal cual porque esa función
     * arma el TicketData completo (totales, método de pago, etc. — datos que
     * no aportan nada en un aviso de cancelación) y no acepta el nombre de
     * quién cancela. Comparte igual el gate de PrinterPrefs y el mismo
     * EscPosBuilder/PrinterManager por debajo.
     */
    private void imprimirTicketCancelacion(Pedido pedido, List<ItemPedido> items, String canceladoPor, boolean estabaPagado) {
        if (!com.example.bufaloserver.print.PrinterPrefs.esModoImpresora(context)) return;
        if (!com.example.bufaloserver.print.PrinterPrefs.tieneMacGuardada(context)) return;
        // ★ Mismo criterio que la comanda original: si el pedido era solo de categorías sin
        // impresión en cocina, nunca llegó a cocina y no hay nada que cancelar allá. Si era
        // mixto, el aviso sale completo (igual que salió la comanda).
        if (!items.isEmpty() && itemsParaCocina(items).isEmpty()) return;

        com.example.bufaloserver.print.TicketData ticket = new com.example.bufaloserver.print.TicketData();
        ticket.numeroOrden  = pedido.numeroOrden;
        ticket.cliente      = pedido.nombreCliente;
        ticket.vendedor     = nombreUsuarioSync(pedido.usuarioCreadorId);
        ticket.items        = items;
        ticket.estado       = Pedido.ESTADO_CANCELADO;
        ticket.canceladoPor = canceladoPor;
        ticket.canceladoEstabaPagado = estabaPagado; // ★ NUEVO
        ticket.subtotal     = pedido.totalCentavos / 100.0;
        ticket.descuento    = pedido.descuentoCentavos / 100.0;
        ticket.total        = pedido.getTotalConDescuentoCentavos() / 100.0;
        ticket.fechaMillis  = pedido.creadoEn;

        byte[] ticketBytes = com.example.bufaloserver.print.EscPosBuilder.build(ticket);
        com.example.bufaloserver.print.PrinterManager.get(context).imprimir(ticketBytes,
                new com.example.bufaloserver.print.PrinterManager.PrintCallback() {
                    @Override
                    public void onImpreso() {
                        android.util.Log.i("ServidorHttp", "Comanda de CANCELACIÓN #" + pedido.numeroOrden + " impresa OK");
                    }

                    @Override
                    public void onError(String motivo) {
                        android.util.Log.w("ServidorHttp",
                                "No se pudo imprimir la comanda de cancelación #" + pedido.numeroOrden + ": " + motivo);
                    }
                });
    }

    /**
     * ★ NUEVO — sonido de alerta de cancelación: a propósito MÁS largo e
     * insistente que reproducirSonidoImpresion(), para que se note la
     * diferencia con el sonido de "ticket impreso normal" que ya está
     * acostumbrada a escuchar la cocina.
     *
     * ★ Movido a SonidosServidor — mismo motivo que reproducirSonidoImpresion.
     */
    private void reproducirSonidoCancelacion() {
        com.example.bufaloserver.servicio.SonidosServidor.pedidoCancelado();
    }

    /**
     * ★ NUEVO — inserta una fila en la tabla auditoria. Fuera del hilo de la
     * request (dbExecutor), igual criterio que marcarImpresoAsync: esto no
     * tiene que hacer esperar la respuesta HTTP al cajero/mesero que hizo la
     * acción. nombreUsuario y rol quedan "congelados" en el momento — ver
     * javadoc de Auditoria.
     */
    private void registrarAuditoria(long usuarioId, String rol, String accion, String detalle,
                                    String entidadTipo, long entidadId) {
        String nombre = nombreUsuarioSync(usuarioId);
        AppDatabaseServidor.dbExecutor.execute(() -> {
            Auditoria a = new Auditoria();
            a.usuarioId = usuarioId;
            a.nombreUsuario = nombre;
            a.rol = rol;
            a.accion = accion;
            a.detalle = detalle;
            a.entidadTipo = entidadTipo;
            a.entidadId = entidadId;
            a.creadoEn = System.currentTimeMillis();
            db().auditoriaDao().insert(a);
        });
    }

    // ── ★ NUEVO — limpieza de datos antiguos ─────────────────────────────────

    private Response manejarLimpiezaEstado(IHTTPSession session) {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        try {
            return jsonOk(com.example.bufaloserver.servicio.LimpiezaDatos
                    .estadoComoJson(context).toString());
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error armando el estado: " + e.getMessage());
        }
    }

    private Response manejarResumenesMensuales(IHTTPSession session) {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.puedeVerReportes(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "No tenés permiso para ver reportes");
        }
        try {
            JSONArray arr = new JSONArray();
            for (com.example.bufaloserver.db.entities.ResumenMensual r : db().resumenMensualDao().getTodosSync()) {
                JSONObject o = new JSONObject();
                o.put("anio", r.anio);
                o.put("mes", r.mes);
                o.put("ventasCentavos", r.ventasCentavos);
                o.put("pedidosPagados", r.pedidosPagados);
                o.put("pedidosCancelados", r.pedidosCancelados);
                o.put("descuentosCentavos", r.descuentosCentavos);
                o.put("efectivoCentavos", r.efectivoCentavos);
                o.put("tarjetaCentavos", r.tarjetaCentavos);
                o.put("qrCentavos", r.qrCentavos);
                o.put("gastosDiariosCentavos", r.gastosDiariosCentavos);
                o.put("gastosFijosCentavos", r.gastosFijosCentavos);
                arr.put(o);
            }
            return jsonOk(arr.toString());
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error listando resúmenes: " + e.getMessage());
        }
    }

    /** POST /limpieza/simular (no borra) y POST /limpieza/ejecutar. Solo administrador. */
    private Response manejarLimpiezaSimularOEjecutar(IHTTPSession session, boolean ejecutar) {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.ADMINISTRADOR.equals(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "Solo un administrador puede limpiar los datos");
        }
        try {
            com.example.bufaloserver.servicio.LimpiezaDatos.Informe inf = ejecutar
                    ? com.example.bufaloserver.servicio.LimpiezaDatos.ejecutar(
                            context, sesion.usuarioId, nombreUsuarioSync(sesion.usuarioId), sesion.rol)
                    : com.example.bufaloserver.servicio.LimpiezaDatos.simular(context);
            if (inf.error != null) return jsonError(Status.INTERNAL_ERROR, inf.error);
            return jsonOk(com.example.bufaloserver.servicio.LimpiezaDatos.informeComoJson(inf).toString());
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error armando la respuesta: " + e.getMessage());
        }
    }

    /**
     * ★ NUEVO — GET /auditoria?desde=X&hasta=Y (sin desde/hasta, trae todo).
     * Solo Administrador — mismo criterio de permisos que
     * manejarVentasPorUsuario: es información sensible de "quién hizo qué",
     * no algo que un Mesero/Cajero deba poder revisar sobre sus propios
     * compañeros.
     */
    private Response manejarAuditoria(IHTTPSession session) throws IOException, ResponseException {
        Sesion sesion = sesionDe(session);
        if (sesion == null) return jsonError(Status.UNAUTHORIZED, "Sesión inválida o vencida");
        if (!com.example.bufaloserver.db.entities.RolUsuario.ADMINISTRADOR.equals(sesion.rol)) {
            return jsonError(Status.FORBIDDEN, "Solo un administrador puede ver la auditoría");
        }

        Map<String, List<String>> params = session.getParameters();
        List<Auditoria> filas;
        if (params.containsKey("desde") && params.containsKey("hasta")) {
            long desde = Long.parseLong(params.get("desde").get(0));
            long hasta = Long.parseLong(params.get("hasta").get(0));
            filas = db().auditoriaDao().getPorFechaSync(desde, hasta);
        } else {
            filas = db().auditoriaDao().getTodasSync();
        }

        JSONArray arr = new JSONArray();
        try {
            for (Auditoria a : filas) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("usuarioId", a.usuarioId);
                o.put("nombreUsuario", a.nombreUsuario);
                o.put("rol", a.rol);
                o.put("accion", a.accion);
                o.put("detalle", a.detalle);
                o.put("entidadTipo", a.entidadTipo);
                o.put("entidadId", a.entidadId);
                o.put("creadoEn", a.creadoEn);
                arr.put(o);
            }
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error listando auditoría: " + e.getMessage());
        }
        return jsonOk(arr.toString());
    }

    /**
     * GET /pedidos/pendientes — ★ NUEVO. Devuelve TODOS los pedidos con
     * estado "pendiente" que hay en el servidor ahora mismo, cada uno con
     * su lista de items. Esta es la pieza que faltaba para que "Pedidos sin
     * cobrar" se vea igual en todas las tablets conectadas: antes esa
     * pantalla se armaba solo con lo que el propio dispositivo tenía en
     * memoria (ver PedidoPendiente/pedidosPendientes en MainActivity), así
     * que un pedido mandado desde la tablet A nunca aparecía en la B. Ahora
     * el servidor es la fuente de verdad también para esto — el cliente
     * debe llamar acá al abrir la pantalla de "sin cobrar" (y lo ideal es
     * que también refresque cuando llegue el evento WebSocket
     * "pedido_nuevo", para que se actualice sola sin tener que reabrir la
     * pantalla).
     */
    private Response listarPedidosPendientes() {
        List<Pedido> pendientes = db().pedidoDao().getPendientesSync();
        JSONArray arr = new JSONArray();
        try {
            for (Pedido p : pendientes) {
                JSONObject pJson = pedidoAJson(p);
                List<ItemPedido> items = db().itemPedidoDao().getPorPedidoSync(p.id);
                cargarDetalleItems(items); // ★ NUEVO
                JSONArray itemsJson = new JSONArray();
                for (ItemPedido ip : items) {
                    JSONObject itemJson = new JSONObject();
                    itemJson.put("id", ip.id);
                    itemJson.put("productoId", ip.productoId);
                    itemJson.put("varianteId", ip.varianteId);
                    itemJson.put("nombreSnapshot", ip.nombreSnapshot);
                    itemJson.put("precioCentavosSnapshot", ip.precioCentavosSnapshot);
                    itemJson.put("cantidad", ip.cantidad);
                    itemJson.put("totalCentavos", ip.totalCentavos);
                    itemJson.put("banado", ip.banado);
                    itemJson.put("esLlevar", ip.esLlevar);
                    itemJson.put("notaCocina", ip.notaCocina);
                    // ★ NUEVO — mismo formato que itemPedidoDesdeJson espera
                    // de vuelta, para que el ida-y-vuelta sea simétrico.
                    itemJson.put("salsas", new JSONArray(ip.salsas));
                    JSONArray modsJson = new JSONArray();
                    for (ItemModificadorSeleccionado m : ip.modificadoresConPrecio) {
                        JSONObject mJson = new JSONObject();
                        mJson.put("nombre", m.nombre);
                        mJson.put("precioExtraCentavos", m.precioExtraCentavos);
                        modsJson.put(mJson);
                    }
                    itemJson.put("modificadoresConPrecio", modsJson);
                    itemsJson.put(itemJson);
                }
                pJson.put("items", itemsJson);
                arr.put(pJson);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error armando /pedidos/pendientes", e);
            return jsonError(Status.INTERNAL_ERROR, "Error listando pendientes: " + e.getMessage());
        }
        return jsonOk(arr.toString());
    }

    private Response listarPedidos(IHTTPSession session) {
        Map<String, List<String>> params = session.getParameters();
        if (!params.containsKey("desde") || !params.containsKey("hasta")) {
            return jsonError(Status.BAD_REQUEST, "Faltan los parámetros desde/hasta");
        }
        long desde = Long.parseLong(primerParametro(params, "desde"));
        long hasta = Long.parseLong(primerParametro(params, "hasta"));
        // ★ CAMBIO — antes solo devolvía los pagados: un pedido cancelado
        // desaparecía del todo de los reportes y solo quedaba rastreable
        // desde Auditoría. Ahora se agregan también los cancelados EN ESE
        // MISMO rango de fechas (por cancelado_en), para que el reporte los
        // siga mostrando marcados como cancelados — el cliente
        // (ReportesActivity) es quien decide no sumarlos a los totales de
        // venta, usando Pedido.estado.
        List<Pedido> pedidos = new java.util.ArrayList<>(db().pedidoDao().getPagadosRangoSync(desde, hasta));
        pedidos.addAll(db().pedidoDao().getCanceladosRangoSync(desde, hasta));

        JSONArray arr = new JSONArray();
        try {
            for (Pedido p : pedidos) arr.put(pedidoAJson(p));
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error listando pedidos: " + e.getMessage());
        }
        return jsonOk(arr.toString());
    }

    private Response maxNumeroOrden() {
        int max = db().pedidoDao().getMaxNumeroOrden();
        JSONObject json = new JSONObject();
        try {
            json.put("maxNumero", max);
        } catch (JSONException ignored) {}
        return jsonOk(json.toString());
    }

    private Pedido pedidoDesdeJson(JSONObject json, Pedido base) throws JSONException {
        Pedido p = base != null ? base : new Pedido();
        p.numeroOrden = json.optInt("numeroOrden", p.numeroOrden);
        p.nombreCliente = json.optString("nombreCliente", p.nombreCliente != null ? p.nombreCliente : "Sin nombre");
        p.tipo = json.optString("tipo", p.tipo);
        p.estado = json.optString("estado", p.estado != null ? p.estado : Pedido.ESTADO_PENDIENTE);
        p.metodoPago = json.isNull("metodoPago") ? null : json.optString("metodoPago", null);
        p.totalCentavos = json.optInt("totalCentavos", p.totalCentavos);
        p.descuentoCentavos = json.optInt("descuentoCentavos", p.descuentoCentavos);
        p.descuentoTipo = json.isNull("descuentoTipo") ? null : json.optString("descuentoTipo", null);
        p.mixtoEfectivoCentavos = json.optInt("mixtoEfectivoCentavos", p.mixtoEfectivoCentavos);
        p.mixtoTarjetaCentavos = json.optInt("mixtoTarjetaCentavos", p.mixtoTarjetaCentavos);
        p.mixtoQrCentavos = json.optInt("mixtoQrCentavos", p.mixtoQrCentavos);
        p.creadoEn = json.optLong("creadoEn", p.creadoEn != 0 ? p.creadoEn : System.currentTimeMillis());
        if (!json.isNull("enviadoCocinaEn") && json.has("enviadoCocinaEn")) {
            p.enviadoCocinaEn = json.optLong("enviadoCocinaEn");
        }
        if (!json.isNull("pagadoEn") && json.has("pagadoEn")) {
            p.pagadoEn = json.optLong("pagadoEn");
        }
        // ★ NUEVO — ver Pedido.uuidCliente. Solo importa en creación (base ==
        // null); en una actualización, crearPedido/actualizarPedido lo dejan
        // como venga por defecto acá y actualizarPedido lo vuelve a fijar al
        // valor original de todos modos (ver ahí), así que no hace falta
        // tratarlo distinto según base.
        if (json.has("uuidCliente") && !json.isNull("uuidCliente")) {
            p.uuidCliente = json.optString("uuidCliente", p.uuidCliente);
        }
        return p;
    }

    private ItemPedido itemPedidoDesdeJson(JSONObject json, long pedidoId) throws JSONException {
        ItemPedido item = new ItemPedido();
        item.pedidoId = pedidoId;
        if (!json.isNull("productoId")) item.productoId = json.optLong("productoId");
        if (!json.isNull("varianteId")) item.varianteId = json.optLong("varianteId");
        item.nombreSnapshot = json.optString("nombreSnapshot", "");
        item.precioCentavosSnapshot = json.optInt("precioCentavosSnapshot", 0);
        // ★ FIX — antes se guardaba la cantidad cruda del JSON tal cual,
        // incluso 0 o negativa (solo descontarInventario aplicaba un
        // Math.max(1,...) puertas adentro, para el cálculo de stock — la
        // línea del pedido en sí quedaba con la cantidad inválida y un total
        // calculado sobre esa misma cantidad inválida). Ahora el piso de 1 se
        // aplica acá, una sola vez, en el punto donde se arma el ItemPedido
        // que después se guarda Y se usa para descontar stock — así ambos
        // quedan consistentes entre sí.
        item.cantidad = Math.max(1, json.optInt("cantidad", 1));
        item.totalCentavos = json.optInt("totalCentavos", 0);
        item.banado = json.optBoolean("banado", false);
        item.esLlevar = json.optBoolean("esLlevar", false);
        item.notaCocina = json.optString("notaCocina", "");

        // ★ NUEVO — salsas: array de strings, ej ["BBQ", "Miel mostaza"].
        // Van a la tabla items_salsas recién cuando el ItemPedido tenga id
        // (ver guardarDetalleItem en crearPedido).
        JSONArray salsasJson = json.optJSONArray("salsas");
        if (salsasJson != null) {
            for (int i = 0; i < salsasJson.length(); i++) {
                String nombreSalsa = salsasJson.optString(i, null);
                if (nombreSalsa != null && !nombreSalsa.isEmpty()) item.salsas.add(nombreSalsa);
            }
        }

        // ★ NUEVO — modificadoresConPrecio: array de {"nombre":..,
        // "precioExtraCentavos":..}. Cubre tanto los modificadores con costo
        // como los que no (precioExtraCentavos = 0) — EscPosBuilder decide
        // cómo mostrar cada caso, acá solo se guarda tal cual llega.
        JSONArray modsJson = json.optJSONArray("modificadoresConPrecio");
        if (modsJson != null) {
            for (int i = 0; i < modsJson.length(); i++) {
                JSONObject m = modsJson.optJSONObject(i);
                if (m == null) continue;
                String nombreMod = m.optString("nombre", null);
                if (nombreMod == null || nombreMod.isEmpty()) continue;
                item.modificadoresConPrecio.add(new ItemModificadorSeleccionado(
                        0, nombreMod, m.optInt("precioExtraCentavos", 0)));
            }
        }
        return item;
    }

    /**
     * ★ NUEVO — guarda las salsas y modificadores (ya parseados por
     * itemPedidoDesdeJson en item.salsas / item.modificadoresConPrecio)
     * en sus tablas propias, una vez que item.id ya existe. Se llama desde
     * dentro de la misma transacción de crearPedido, después de
     * itemPedidoDao().insertAll().
     */
    private void guardarDetalleItem(ItemPedido item) {
        if (item.salsas != null && !item.salsas.isEmpty()) {
            List<ItemSalsa> filas = new java.util.ArrayList<>();
            for (String nombre : item.salsas) filas.add(new ItemSalsa(item.id, nombre));
            db().itemSalsaDao().insertAll(filas);
        }
        if (item.modificadoresConPrecio != null && !item.modificadoresConPrecio.isEmpty()) {
            List<ItemModificadorSeleccionado> filas = new java.util.ArrayList<>();
            for (ItemModificadorSeleccionado m : item.modificadoresConPrecio) {
                filas.add(new ItemModificadorSeleccionado(item.id, m.nombre, m.precioExtraCentavos));
            }
            db().itemModificadorSeleccionadoDao().insertAll(filas);
        }
    }

    /**
     * ★ NUEVO — carga desde la base el detalle (salsas/modificadores) de
     * cada ítem en la lista, llenando sus campos @Ignore. Se usa en
     * reimprimirPedido() y listarPedidosPendientes(), donde los ItemPedido
     * vienen de itemPedidoDao().getPorPedidoSync() y por lo tanto NO traen
     * ese detalle (no son columnas de items_pedido) — a diferencia de
     * crearPedido(), donde el detalle ya está en memoria recién parseado
     * del JSON de entrada y no hace falta releerlo.
     */
    private void cargarDetalleItems(List<ItemPedido> items) {
        for (ItemPedido ip : items) {
            for (ItemSalsa s : db().itemSalsaDao().getPorItemPedidoSync(ip.id)) {
                ip.salsas.add(s.nombre);
            }
            ip.modificadoresConPrecio.addAll(
                    db().itemModificadorSeleccionadoDao().getPorItemPedidoSync(ip.id));
        }
    }

    private JSONObject pedidoAJson(Pedido p) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", p.id);
        json.put("numeroOrden", p.numeroOrden);
        json.put("nombreCliente", p.nombreCliente);
        json.put("tipo", p.tipo);
        json.put("estado", p.estado);
        json.put("metodoPago", p.metodoPago);
        json.put("totalCentavos", p.totalCentavos);
        json.put("descuentoCentavos", p.descuentoCentavos);
        json.put("descuentoTipo", p.descuentoTipo);
        json.put("mixtoEfectivoCentavos", p.mixtoEfectivoCentavos);
        json.put("mixtoTarjetaCentavos", p.mixtoTarjetaCentavos);
        json.put("mixtoQrCentavos", p.mixtoQrCentavos);
        json.put("creadoEn", p.creadoEn);
        json.put("enviadoCocinaEn", p.enviadoCocinaEn);
        json.put("pagadoEn", p.pagadoEn);
        json.put("usuarioCreadorId", p.usuarioCreadorId);
        json.put("usuarioCobradorId", p.usuarioCobradorId);
        // ★ NUEVO — para "Ventas del día": nombre listo para mostrar (sin que
        // el cliente tenga que resolver el id contra /usuarios) y si el
        // ticket se imprimió con éxito la última vez que se intentó.
        json.put("nombreVendedor", nombreUsuarioSync(p.usuarioCreadorId));
        json.put("nombreCobrador", p.usuarioCobradorId > 0 ? nombreUsuarioSync(p.usuarioCobradorId) : null);
        json.put("impreso", p.impreso);
        return json;
    }

    // ── /usuarios, /usuarios/{id}, /usuarios/{id}/activo ─────────────────────
    private Response listarUsuarios() {
        List<Usuario> usuarios = db().usuarioDao().getTodosSync();
        JSONArray arr = new JSONArray();
        try {
            for (Usuario u : usuarios) arr.put(usuarioAJson(u));
        } catch (JSONException e) {
            return jsonError(Status.INTERNAL_ERROR, "Error listando usuarios: " + e.getMessage());
        }
        return jsonOk(arr.toString());
    }

    /**
     * ★ FIX — reemplaza a UsuarioDao.contarConPin(pin, idExcluir), que hacía
     * "WHERE pin = :pin" en SQL. Con el PIN hasheado y salado POR USUARIO
     * (ver Usuario.pinHash/pinSalt), dos usuarios con el mismo PIN NO
     * producen el mismo hash guardado — así que la única forma de saber "¿ya
     * hay alguien con este PIN?" es probarlo, uno por uno, contra el hash de
     * cada usuario (Usuario.coincideConPin). Se recorren TODOS los usuarios
     * (activos e inactivos), igual que hacía la consulta SQL original —
     * reactivar un usuario viejo no debería poder "heredar" un PIN que ya
     * está en uso por otro.
     */
    private boolean existeOtroUsuarioConPin(String pin, long idExcluir) {
        for (Usuario u : db().usuarioDao().getTodosSync()) {
            if (u.id != idExcluir && u.coincideConPin(pin)) return true;
        }
        return false;
    }

    private Response crearUsuario(IHTTPSession session) throws IOException, ResponseException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        String pin = entrada.optString("pin", "");
        if (existeOtroUsuarioConPin(pin, 0)) {
            return jsonError(Status.BAD_REQUEST, "Ya existe un usuario con ese PIN");
        }

        Usuario usuario = new Usuario();
        usuario.nombre = entrada.optString("nombre", "");
        usuario.establecerPin(pin);
        usuario.rol = entrada.optString("rol", "");
        usuario.activo = entrada.optBoolean("activo", true);
        usuario.creadoEn = System.currentTimeMillis();

        long id = db().usuarioDao().insert(usuario);
        if (ws != null) ws.avisarUsuarioActualizado(id);
        return jsonOk(idComoJson(id));
    }

    private Response actualizarUsuario(IHTTPSession session, long id) throws IOException, ResponseException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        Usuario existente = db().usuarioDao().getByIdSync(id);
        if (existente == null) return jsonError(Status.NOT_FOUND, "No existe el usuario " + id);

        // ★ FIX — antes: "entrada.optString('pin', existente.pin)" usaba el
        // PIN VIEJO EN TEXTO PLANO como default cuando el body no traía
        // "pin" (edición que no cambia el PIN). Ya no es posible ni tiene
        // sentido: existente.pin no existe más, y un hash no se puede "leer
        // de vuelta" para reusarlo como default de un optString. Ahora se
        // distingue explícitamente "vino pin en el body" (cambiar) de "no
        // vino" (dejar como está) — el chequeo de duplicado y el hasheo
        // solo corren en el primer caso.
        boolean cambiaPin = entrada.has("pin") && !entrada.isNull("pin");
        String pinNuevo = cambiaPin ? entrada.optString("pin", "") : null;
        if (cambiaPin && existeOtroUsuarioConPin(pinNuevo, id)) {
            return jsonError(Status.BAD_REQUEST, "Ya existe otro usuario con ese PIN");
        }

        String rolAnterior = existente.rol;
        String rolNuevo = entrada.optString("rol", existente.rol);
        boolean activoNuevo = entrada.optBoolean("activo", existente.activo);

        // ★ FIX — mismo motivo que protege a eliminarUsuario() y ahora
        // también a setActivoUsuario() contra tocar al primer administrador:
        // este PUT genérico podía lograr el mismo resultado por otra puerta,
        // desactivándolo (activo=false, igual que el switch) O sacándole el
        // rol de administrador (que es igual de grave — un primer admin
        // ACTIVO pero degradado a Mesero deja de poder entrar a "Gestión de
        // usuarios", y si era el único admin, nadie más puede devolvérselo).
        if (id == db().usuarioDao().getIdPrimerUsuario()
                && (!activoNuevo || !com.example.bufaloserver.db.entities.RolUsuario.ADMINISTRADOR.equals(rolNuevo))) {
            return jsonError(Status.BAD_REQUEST,
                    "No se puede desactivar ni cambiarle el rol al primer administrador del sistema.");
        }

        existente.nombre = entrada.optString("nombre", existente.nombre);
        if (cambiaPin) existente.establecerPin(pinNuevo);
        existente.rol = rolNuevo;
        existente.activo = activoNuevo;
        db().usuarioDao().update(existente);
        // ★ FIX SEGURIDAD — invalida sesión si quedó inactivo O si le
        // cambiaron el rol: una sesión ya abierta guarda el rol EN EL
        // MOMENTO DEL LOGIN (ver clase Sesion) y ningún endpoint lo
        // revalida después — si a un Administrador lo bajan a Mesero, sin
        // esto seguiría teniendo privilegios de admin en su sesión actual
        // hasta que cerrara sesión por su cuenta.
        if (!existente.activo || !existente.rol.equals(rolAnterior)) invalidarSesionesDeUsuario(id);
        if (ws != null) ws.avisarUsuarioActualizado(id);
        return jsonOk("{}");
    }

    private Response setActivoUsuario(IHTTPSession session, long id) throws IOException, ResponseException {
        JSONObject entrada = leerBody(session);
        if (entrada == null) return jsonError(Status.BAD_REQUEST, "JSON inválido");

        boolean activo = entrada.optBoolean("activo", true);

        // ★ FIX — mismo motivo que ya protegía a eliminarUsuario() contra
        // borrar al primer administrador (ver ahí): desactivarlo deja
        // exactamente el mismo agujero. manejarLogin rechaza a un usuario
        // con activo=false igual que si no existiera, así que apagar al
        // primer admin es, en la práctica, tan irreversible como borrarlo —
        // nadie podría volver a entrar a "Gestión de usuarios" para
        // reactivarlo. Antes esto NO estaba protegido acá (solo el borrado
        // lo estaba), así que el switch "Activo" de la lista sí lo dejaba
        // hacer.
        if (!activo && id == db().usuarioDao().getIdPrimerUsuario()) {
            return jsonError(Status.BAD_REQUEST, "No se puede desactivar al primer administrador del sistema.");
        }

        db().usuarioDao().setActivo(id, activo);
        if (!activo) invalidarSesionesDeUsuario(id);
        if (ws != null) ws.avisarUsuarioActualizado(id);
        return jsonOk("{}");
    }

    /**
     * ★ NUEVO — borrado real (antes de esto, "eliminar" solo era desactivar).
     * Protegido: nunca se puede borrar al primer administrador (el usuario
     * con el id más chico de la tabla — siempre el creado por
     * EstadoServidorActivity.verificarPrimerAdmin() al instalar el
     * servidor). Sin ese usuario, si alguien se queda sin acceso no hay
     * forma de volver a entrar a "Gestión de usuarios" para arreglarlo.
     */
    private Response eliminarUsuario(long id) {
        Usuario existente = db().usuarioDao().getByIdSync(id);
        if (existente == null) return jsonError(Status.NOT_FOUND, "No existe el usuario " + id);

        long idPrimerAdmin = db().usuarioDao().getIdPrimerUsuario();
        if (id == idPrimerAdmin) {
            return jsonError(Status.BAD_REQUEST, "No se puede eliminar al primer administrador del sistema.");
        }

        db().usuarioDao().delete(existente);
        invalidarSesionesDeUsuario(id);
        if (ws != null) ws.avisarUsuarioActualizado(id);
        return jsonOk("{}");
    }

    /** Nunca incluye el PIN — el cliente no lo necesita para listar, y así no
     *  circula en texto plano por la red más de lo estrictamente necesario. */
    private JSONObject usuarioAJson(Usuario u) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", u.id);
        json.put("nombre", u.nombre);
        json.put("rol", u.rol);
        json.put("activo", u.activo);
        json.put("creadoEn", u.creadoEn);
        return json;
    }

    // ── Helpers de request/respuesta ─────────────────────────────────────────
    /**
     * ★ FIX — bug que dejaba estancado cualquier PUT (marcar pedido pagado,
     * activar/desactivar usuario, editar gasto): NanoHTTPD NO guarda el body
     * bajo "postData" salvo en POST. En PUT/DELETE lo vuelca a un archivo
     * temporal y deja la RUTA de ese archivo bajo la clave "content" — ver
     * HTTPSession.parseBody() de fi.iki.elonen.NanoHTTPD. Como acá solo se
     * leía "postData", todo PUT llegaba con entrada=null y el servidor
     * respondía 400 sin tocar la base — el cliente veía error, el pedido
     * nunca pasaba a "pagado", y por eso ni aparecía en Reportes ni salía de
     * "Sin cobrar".
     */
    private JSONObject leerBody(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> body = new java.util.HashMap<>();
        session.parseBody(body);

        String jsonCrudo = body.get("postData");
        if (jsonCrudo == null) {
            String rutaTemporal = body.get("content");
            if (rutaTemporal != null) {
                jsonCrudo = leerArchivoTemporal(rutaTemporal);
            }
        }
        if (jsonCrudo == null || jsonCrudo.isEmpty()) return null;

        try {
            return new JSONObject(jsonCrudo);
        } catch (Exception e) {
            return null;
        }
    }

    private String leerArchivoTemporal(String ruta) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(ruta),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = reader.readLine()) != null) sb.append(linea);
        }
        return sb.toString();
    }

    private Response jsonOk(String json) {
        return newFixedLengthResponse(Status.OK, "application/json", json);
    }

    private Response jsonError(Status status, String mensaje) {
        JSONObject error = new JSONObject();
        try {
            error.put("error", mensaje);
        } catch (Exception ignored) {}
        return newFixedLengthResponse(status, "application/json", error.toString());
    }
}