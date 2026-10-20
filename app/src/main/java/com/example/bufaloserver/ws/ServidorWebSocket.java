package com.example.bufaloserver.ws;

import android.content.Context;
import android.util.Log;

import com.example.bufaloserver.http.ServidorHttp;

import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import java.net.InetSocketAddress;

/**
 * Contraparte del WsClient del proyecto cliente. Mismo formato de mensaje que
 * ya espera el cliente (ver WsClient.java): JSON con un campo "evento" +
 * datos según el tipo — producto_actualizado, stock_actualizado, pedido_nuevo,
 * usuario_actualizado.
 *
 * No mantiene ningún estado de "quién es cada cliente" (rol, sesión, etc.)
 * DESPUÉS de aceptar la conexión — WebSocket acá es solo un canal de
 * broadcast unidireccional (servidor→clientes) para avisar "algo cambió,
 * refrescate". Los datos reales siempre se piden por HTTP a continuación
 * (ver ProductoRepositoryRemoto.actualizarProducto() del lado cliente) —
 * WebSocket nunca manda el dato completo, solo el aviso. Sí valida, en
 * cambio, que quien se conecta tenga una sesión activa (ver
 * onWebsocketHandshakeReceivedAsServer, abajo) — sin eso, cualquier equipo
 * en la red podía conectarse sin loguearse y recibir los avisos igual.
 */
public class ServidorWebSocket extends WebSocketServer {

    private static final String TAG = "ServidorWebSocket";

    public ServidorWebSocket(InetSocketAddress address, Context context) {
        super(address);
        // ★ FIX — mismo criterio que WsClient.InnerWs del lado cliente: sin
        // esto, una conexión "zombie" (el celular perdió la red pero el
        // servidor no se entera) se queda contando como conectada para
        // broadcast() durante mucho más tiempo del que debería, y mientras
        // tanto ese celular no recibe ningún aviso aunque parezca conectado.
        setConnectionLostTimeout(25);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        // A esta altura ya pasó la validación de onWebsocketHandshakeReceivedAsServer
        // (abajo) — si llegamos acá es porque el token era válido.
        Log.i(TAG, "Cliente conectado: " + conn.getRemoteSocketAddress());
    }

    /**
     * ★ FIX SEGURIDAD — antes este WebSocket no validaba nada: cualquier
     * equipo en la misma red que supiera IP:puerto se conectaba a /ws y
     * recibía en vivo todos los avisos de pedidos nuevos/cancelados, cambios
     * de stock y de usuarios, sin loguearse. WsClient del lado cliente YA
     * mandaba "Authorization: Bearer <token>" en el handshake (ver
     * WsClient.abrirConexion), pero onOpen lo ignoraba por completo.
     *
     * Este es el hook correcto de la librería para autenticar ANTES de
     * aceptar la conexión (a diferencia de onOpen, que corre después de que
     * el handshake ya fue aceptado): si el token no viene, no tiene el
     * formato esperado, o no está en SESIONES (nunca existió, expiró por
     * logout, o fue invalidado por revocación de dispositivo/usuario — ver
     * ServidorHttp.tokenSesionValido), se lanza InvalidDataException y la
     * librería corta la conexión con 1008 (POLICY_VALIDATION) sin que
     * onOpen llegue a ejecutarse.
     */
    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
            WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
        ServerHandshakeBuilder builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);

        String auth = request.getFieldValue("Authorization");
        String token = (auth != null && auth.startsWith("Bearer "))
                ? auth.substring("Bearer ".length()).trim()
                : null;

        if (!ServidorHttp.tokenSesionValido(token)) {
            Log.w(TAG, "Handshake rechazado (sin sesión válida): " + conn.getRemoteSocketAddress());
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Token de sesión inválido o ausente");
        }
        return builder;
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.i(TAG, "Cliente desconectado: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // El servidor no espera mensajes entrantes de los clientes por ahora
        // (es un canal de solo-aviso) — se ignora cualquier cosa que llegue.
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "Error en WebSocket", ex);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "WebSocketServer listo");
    }

    // ── API pública para el resto del servidor ──────────────────────────────

    /**
     * Cantidad de conexiones WebSocket abiertas ahora mismo — una por cada
     * tablet/celular cliente que está en modo Conectado con la app abierta
     * (WsClient del cliente abre el socket al entrar y lo mantiene mientras
     * la app está en primer plano). getConnections() ya lo trae resuelto la
     * librería java-websocket, no hace falta llevar la cuenta a mano.
     *
     * OJO: esto cuenta conexiones WebSocket, no "usuarios logueados" — si dos
     * cajeros comparten la misma tablet en momentos distintos, sigue siendo
     * 1 conexión. Y si una tablet pierde wifi sin cerrar la app prolijamente,
     * puede tardar unos segundos en descontarse (timeout del socket).
     */
    public int getCantidadConectados() {
        return getConnections().size();
    }

    public void avisarProductoActualizado(long productoId) {
        emitir(construirEvento("producto_actualizado", "productoId", productoId));
    }

    public void avisarStockActualizado(long productoId) {
        emitir(construirEvento("stock_actualizado", "productoId", productoId));
    }

    public void avisarPedidoNuevo(long pedidoId) {
        emitir(construirEvento("pedido_nuevo", "pedidoId", pedidoId));
    }

    /** ★ NUEVO — evento separado de "pedido_nuevo" a propósito: el cliente
     *  necesita distinguir "hay un pedido nuevo/pagado, refrescá la lista"
     *  de "un pedido se canceló, esto amerita una alerta visual distinta"
     *  (ver WsClient.onPedidoCancelado y VentasDelDiaActivity). */
    public void avisarPedidoCancelado(long pedidoId) {
        emitir(construirEvento("pedido_cancelado", "pedidoId", pedidoId));
    }

    public void avisarUsuarioActualizado(long usuarioId) {
        emitir(construirEvento("usuario_actualizado", "usuarioId", usuarioId));
    }

    /** Sin id — igual criterio que el evento de categorías: al cliente le
     *  alcanza con saber "algo de salsas cambió", y recarga la lista entera
     *  (ver SalsaRepositoryRemoto.recargar(), que ya trae GET /salsas completo,
     *  no hay "una sola salsa" que pedir por separado como sí pasa con productos). */
    public void avisarSalsaActualizada() {
        JSONObject json = new JSONObject();
        try {
            json.put("evento", "salsa_actualizada");
        } catch (Exception ignored) {}
        emitir(json.toString());
    }

    private String construirEvento(String evento, String campoId, long id) {
        JSONObject json = new JSONObject();
        try {
            json.put("evento", evento);
            json.put(campoId, id);
        } catch (Exception ignored) {}
        return json.toString();
    }

    private void emitir(String mensajeJson) {
        // broadcast() ya viene resuelto por la librería: manda el mensaje a
        // todas las conexiones abiertas, sin que el servidor tenga que llevar
        // su propia lista.
        Log.i(TAG, "emitir() — " + mensajeJson + " → " + getConnections().size() + " conexión(es) abierta(s)");
        broadcast(mensajeJson);
    }
}