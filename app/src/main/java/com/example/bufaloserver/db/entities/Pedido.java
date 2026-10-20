package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Representa una orden/pedido completo.
 */
@Entity(tableName = "pedidos")
public class Pedido {

    // ── Constantes de estado ─────────────────────────────────────────────────
    public static final String ESTADO_PENDIENTE  = "pendiente";
    public static final String ESTADO_PAGADO     = "pagado";
    public static final String ESTADO_CANCELADO  = "cancelado";

    // ── Constantes de tipo ───────────────────────────────────────────────────
    public static final String TIPO_AQUI   = "aqui";
    public static final String TIPO_LLEVAR = "llevar";
    /** ★ NUEVO — pedido con platos de los dos tipos mezclados (algunos "aquí",
     *  otros "para llevar"). Antes esto no existía y el pedido se etiquetaba
     *  entero como uno solo de los dos, eligiéndolo mal según el momento —
     *  ver MainActivity.tipoPedidoDesdeItems() del lado cliente, que es
     *  donde realmente se decide este valor a partir de los items reales. */
    public static final String TIPO_MIXTO  = "mixto";

    // ── Constantes de método de pago ─────────────────────────────────────────
    public static final String PAGO_EFECTIVO = "efectivo";
    public static final String PAGO_TARJETA  = "tarjeta";
    public static final String PAGO_QR       = "qr";
    public static final String PAGO_MIXTO    = "mixto";  // ← NUEVO

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Número visible en pantalla (reiniciable, no PK) */
    @ColumnInfo(name = "numero_orden")
    public int numeroOrden;

    @ColumnInfo(name = "nombre_cliente")
    public String nombreCliente = "Sin nombre";

    /** "aqui" o "llevar" */
    @ColumnInfo(name = "tipo")
    public String tipo;

    /** "pendiente" | "pagado" | "cancelado" */
    @ColumnInfo(name = "estado")
    public String estado = ESTADO_PENDIENTE;

    /** "efectivo" | "tarjeta" | "qr" | "mixto" | null (mientras es pendiente) */
    @ColumnInfo(name = "metodo_pago")
    public String metodoPago = null;

    /** Total en centavos para evitar punto flotante */
    @ColumnInfo(name = "total_centavos")
    public int totalCentavos = 0;

    // ── NUEVOS: Descuento ────────────────────────────────────────────────────

    /**
     * Descuento en centavos aplicado al pedido.
     * 0 significa sin descuento.
     */
    @ColumnInfo(name = "descuento_centavos")
    public int descuentoCentavos = 0;

    /**
     * Tipo de descuento: "bs" (monto fijo) | "pct" (porcentaje).
     * Null si no hay descuento.
     */
    @ColumnInfo(name = "descuento_tipo")
    public String descuentoTipo = null;

    // ── NUEVOS: Pago Mixto ───────────────────────────────────────────────────

    /**
     * Monto pagado en efectivo (centavos). 0 si no aplica.
     */
    @ColumnInfo(name = "mixto_efectivo_centavos")
    public int mixtoEfectivoCentavos = 0;

    /**
     * Monto pagado con tarjeta (centavos). 0 si no aplica.
     */
    @ColumnInfo(name = "mixto_tarjeta_centavos")
    public int mixtoTarjetaCentavos = 0;

    /**
     * Monto pagado con QR (centavos). 0 si no aplica.
     */
    @ColumnInfo(name = "mixto_qr_centavos")
    public int mixtoQrCentavos = 0;

    // ── Timestamps ───────────────────────────────────────────────────────────

    @ColumnInfo(name = "creado_en")
    public long creadoEn = System.currentTimeMillis();

    @ColumnInfo(name = "enviado_cocina_en")
    public Long enviadoCocinaEn = null;

    @ColumnInfo(name = "pagado_en")
    public Long pagadoEn = null;

    @ColumnInfo(name = "cancelado_en")
    public Long canceladoEn = null;

    // ── Atribución de usuario (quién vendió qué) ────────────────────────────
    // Se completan SIEMPRE del lado del servidor a partir de la sesión activa
    // (deviceToken -> Dispositivo -> Usuario), nunca de lo que mande el
    // cliente en el JSON — así no se puede falsear quién hizo la venta.

    /** Quién creó el pedido (mesero/cajero que tomó la orden). 0 = desconocido
     *  (pedidos creados antes de este cambio, o sin sesión detectada). */
    @ColumnInfo(name = "usuario_creador_id", defaultValue = "0")
    public long usuarioCreadorId = 0;

    /** Quién lo marcó como pagado (puede ser distinto de quien lo tomó — ej.
     *  mesero toma el pedido, cajero cobra). 0 si todavía no se cobró. */
    @ColumnInfo(name = "usuario_cobrador_id", defaultValue = "0")
    public long usuarioCobradorId = 0;

    /** ★ NUEVO — true si el ticket de cocina se imprimió con éxito la última
     *  vez que se intentó (creación o reimpresión). Se pisa cada vez que
     *  imprimirTicketPedido() recibe la respuesta de la impresora — así
     *  "Ventas del día" puede marcar en rojo los pedidos que quedaron sin
     *  imprimirse (impresora apagada/sin papel) para que se busquen y se
     *  reimpriman a mano. Default false: recién pasa a true cuando el
     *  callback de PrinterManager confirma onImpreso(). */
    @ColumnInfo(name = "impreso", defaultValue = "0")
    public boolean impreso = false;

    /** ★ NUEVO — "reclamo" de impresión en la propia base de datos: marca el
     *  instante (epoch millis) en que algún hilo/instancia empezó a intentar
     *  imprimir este pedido. null = nadie lo está intentando ahora mismo.
     *
     *  Reemplaza los locks en memoria (synchronized / tryLock / AtomicBoolean
     *  en PrinterManager y ServidorHttp) como mecanismo ANTI-DUPLICADO real:
     *  esos locks solo protegen contra hilos de UN MISMO proceso pisándose,
     *  pero no contra la carrera real que seguía produciendo tickets
     *  duplicados: un pedido nuevo se imprime por el camino directo
     *  (crearPedido -> imprimirTicketPedido) MIENTRAS, casi al mismo
     *  instante, una reconexión real de la impresora dispara
     *  reintentarPedidosNoImpresos(), que lee "impreso=0" y agarra ESE MISMO
     *  pedido (todavía no se marcó impreso=true porque el primer intento no
     *  terminó) y lo manda a imprimir por segunda vez en paralelo.
     *
     *  Con esta columna, PedidoDao.reservarParaImprimir() hace el "quién
     *  imprime este pedido" con un solo UPDATE condicional — atómico porque
     *  SQLite serializa todas las escrituras, sin importar cuántos hilos,
     *  instancias de ServidorHttp o incluso reinicios del proceso lo llamen
     *  al mismo tiempo. Ver comentario completo en PedidoDao. */
    @ColumnInfo(name = "intento_impresion_en")
    public Long intentoImpresionEn = null;

    /**
     * ★ NUEVO — clave de idempotencia para POST /pedido, mismo criterio que
     * GastoDiario.uuidOffline para /gastos/pendientes: el CLIENTE genera un
     * UUID una sola vez por pedido (al armarlo, antes de mandarlo) y lo
     * reenvía tal cual en cada reintento. Antes, si el cliente cortaba por
     * timeout (ApiClient.TIMEOUT_MS = 5000) DESPUÉS de que el servidor ya
     * hubiera guardado el pedido y descontado stock, pero ANTES de que la
     * respuesta llegara, un reintento manual del usuario ("no respondió,
     * probá de nuevo") creaba un SEGUNDO pedido — doble descuento de stock,
     * doble comanda impresa. Con este uuid, crearPedido() puede detectar
     * "este pedido ya existe" y devolver el mismo resultado sin duplicar
     * nada (ver ServidorHttp.crearPedido). Null en pedidos de clientes
     * viejos que todavía no lo mandan — para esos, el comportamiento queda
     * igual que antes (sin protección contra el reintento).
     */
    @ColumnInfo(name = "uuid_cliente")
    public String uuidCliente = null;

    // ── Helpers originales ───────────────────────────────────────────────────

    public double getTotalDouble() {
        return totalCentavos / 100.0;
    }

    // ── Helpers nuevos ───────────────────────────────────────────────────────

    /** Total real = totalCentavos - descuentoCentavos */
    public int getTotalConDescuentoCentavos() {
        return Math.max(0, totalCentavos - descuentoCentavos);
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    public Pedido() {}
}