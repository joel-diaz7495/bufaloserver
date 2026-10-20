// ═══════════════════════════════════════════════════════════════════════════
// PedidoDao.java  — REEMPLAZA el archivo existente
// ═══════════════════════════════════════════════════════════════════════════
package com.example.bufaloserver.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.Pedido;

import java.util.List;

@Dao
public interface PedidoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Pedido pedido);

    @Update
    void update(Pedido pedido);

    @Query("SELECT * FROM pedidos WHERE id = :id LIMIT 1")
    Pedido getByIdSync(long id);

    @Query("SELECT * FROM pedidos WHERE estado = 'pendiente' ORDER BY creado_en DESC")
    LiveData<List<Pedido>> getPendientes();

    /**
     * ★ NUEVO — versión síncrona de getPendientes(), para usar desde
     * ServidorHttp (que corre en el hilo de la request, no puede observar
     * LiveData). Esto es lo que faltaba para que GET /pedidos/pendientes
     * pueda existir: sin esto, ningún dispositivo (ni siquiera el que NO
     * creó el pedido) podía enterarse de qué está pendiente de cobro en
     * modo Conectado — cada tablet solo veía lo que ELLA misma había
     * mandado, en memoria, en esa sesión.
     */
    @Query("SELECT * FROM pedidos WHERE estado = 'pendiente' ORDER BY creado_en DESC")
    List<Pedido> getPendientesSync();

    @Query("SELECT * FROM pedidos ORDER BY creado_en DESC LIMIT 50")
    LiveData<List<Pedido>> getRecientes();

    // ★ FIX — se sacó pagar(long, String, String, long): un UPDATE sin
    // ningún llamador en todo el proyecto (el pago real siempre pasó por
    // update(), vía actualizarPedido() en ServidorHttp). Además tenía el
    // mismo problema que tenía cancelar() antes de su fix (ver arriba): sin
    // condición sobre el estado actual. Al no tener ningún caller no había
    // ningún bug activo, pero tampoco valía la pena dejarlo como para que
    // alguien lo empiece a usar tal cual está.

    /**
     * ★ FIX — antes este UPDATE no tenía condición sobre el estado actual, así
     * que dos cancelaciones del mismo pedido (doble toque, o dos tablets a la
     * vez) pasaban ambas: revertirInventario corría dos veces (stock
     * inflado), se registraban dos auditorías y se imprimían dos comandas.
     * El "AND estado != 'cancelado'" hace que solo la primera cancelación
     * afecte una fila — la segunda ve 0 filas afectadas y el caller
     * (cancelarPedido en ServidorHttp) aborta sin repetir la reversión.
     */
    @Query("UPDATE pedidos SET estado = 'cancelado', cancelado_en = :ts WHERE id = :id AND estado != 'cancelado'")
    int cancelar(long id, long ts);

    /** Número de la última orden (para autoincremento del número visible). */
    @Query("SELECT COALESCE(MAX(numero_orden), 0) FROM pedidos")
    int getMaxNumeroOrden();

    /**
     * ★ FIX — máximo numero_orden pero SOLO entre pedidos creados desde
     * :desde en adelante (inicio del período de reinicio: antes era el
     * inicio del día, ahora el inicio del mes). Antes se usaba
     * getMaxNumeroOrden() (sin filtro) para el caso "mismo período", lo que
     * hacía que el 2do pedido del período saltara al máximo HISTÓRICO de
     * toda la tabla (ej. 265 de un mes anterior) en vez de continuar desde
     * el máximo del período actual. Ver ServidorHttp.siguienteNumeroOrden.
     */
    @Query("SELECT COALESCE(MAX(numero_orden), 0) FROM pedidos WHERE creado_en >= :desde")
    int getMaxNumeroOrdenDesde(long desde);

    /** Idempotencia de POST /pedido: si ya existe un pedido con este
     *  uuid_cliente, crearPedido() lo devuelve tal cual en vez de crear uno
     *  nuevo (ver Pedido.uuidCliente). */
    @Query("SELECT * FROM pedidos WHERE uuid_cliente = :uuid LIMIT 1")
    Pedido getByUuidClienteSync(String uuid);

    /**
     * ★ NUEVO — TODOS los pedidos del día (cualquier estado: pendiente,
     * pagado o cancelado), por fecha de CREACIÓN (creado_en), no de pago —
     * a diferencia de getPagadosRangoSync. Es la base de "Ventas del día":
     * un pedido pendiente o cancelado también tiene que poder encontrarse
     * ahí (ej. "se perdió un pedido" o "se armó y nunca se cobró").
     * Orden descendente por número de orden = más reciente primero.
     */
    @Query("SELECT * FROM pedidos WHERE creado_en BETWEEN :inicio AND :fin ORDER BY numero_orden DESC")
    List<Pedido> getDelDiaSync(long inicio, long fin);

    /** ★ NUEVO — persiste si el último intento de impresión del ticket salió
     *  bien o no (ver imprimirTicketPedido en ServidorHttp). */
    @Query("UPDATE pedidos SET impreso = :impreso WHERE id = :id")
    void marcarImpreso(long id, boolean impreso);

    /**
     * ★ NUEVO — pedidos que quedaron con impreso=0 (el ticket nunca salió:
     * la impresora estaba desconectada, sin papel más allá de lo que el
     * buffer aguanta, etc.) y que todavía tiene sentido reimprimir (no
     * cancelados). Se usa para el reintento AUTOMÁTICO cuando
     * PrinterManager recupera la conexión — ver
     * ServidorHttp.reintentarPedidosNoImpresos().
     *
     * Acotado a las últimas 24h (:desde) a propósito: sin este límite, un
     * pedido viejo que quedó impreso=0 por algún motivo raro se reimprimiría
     * cada vez que la impresora se reconecta, para siempre. Orden ASC
     * (el más viejo primero) para reimprimir en el mismo orden en que se
     * habrían impreso en su momento.
     */
    @Query("SELECT * FROM pedidos WHERE impreso = 0 AND estado != 'cancelado' AND creado_en >= :desde ORDER BY creado_en ASC")
    List<Pedido> getNoImpresosSync(long desde);

    /**
     * ★ NUEVO — "reclamo" atómico de impresión (reemplaza los locks en
     * memoria como defensa real contra duplicados, ver comentario completo
     * en Pedido.intentoImpresionEn).
     *
     * Camino AUTOMÁTICO — pedido nuevo entrando por POST /pedido, y el
     * reintento automático al reconectar la impresora
     * (ServidorHttp.reintentarPedidosNoImpresos). Es la carrera entre estos
     * dos caminos la que producía los duplicados, así que acá el reclamo
     * exige impreso=0: si el pedido ya salió impreso, ninguno de los dos
     * caminos automáticos debe volver a tocarlo.
     *
     * Un solo UPDATE condicional, atómico porque SQLite serializa todas las
     * escrituras:
     *   - Si el pedido ya está impreso=1 → 0 filas afectadas, no imprime.
     *   - Si otro hilo/instancia ya lo reclamó hace menos de :vencidoAntes
     *     (hay un intento en curso ahora mismo) → 0 filas, no imprime.
     *   - En cualquier otro caso → 1 fila afectada, YO gano el reclamo y soy
     *     el único que va a intentar imprimir este pedido ahora.
     *
     * :vencidoAntes es una red de seguridad (no el mecanismo principal): si
     * un reclamo quedara "pegado" porque el proceso murió a mitad de un
     * intento de impresión (ANR, watchdog, el usuario cierra la app) sin
     * llegar a liberarlo con liberarReclamoImpresion(), después de ese
     * tiempo el pedido vuelve a estar disponible para reclamarse igual —
     * así nunca queda bloqueado para siempre por un reclamo huérfano.
     *
     * @return filas afectadas: 1 = gané el reclamo, imprimo; 0 = ya está
     *         impreso o alguien más lo está imprimiendo ahora, no hago nada.
     */
    @Query("UPDATE pedidos SET intento_impresion_en = :ahora " +
            "WHERE id = :id AND impreso = 0 " +
            "AND (intento_impresion_en IS NULL OR intento_impresion_en < :vencidoAntes)")
    int reservarParaImprimirAutomatico(long id, long ahora, long vencidoAntes);

    /**
     * ★ NUEVO — mismo mecanismo que reservarParaImprimirAutomatico(), pero
     * para el camino MANUAL: el botón "🖨️ Reimprimir" del cliente (POST
     * /pedido/{id}/reimprimir). A diferencia del automático, NO exige
     * impreso=0 — es una acción explícita del usuario y tiene que poder
     * reimprimir un ticket que ya salió bien antes (papel perdido,
     * manchado, etc.). El reclamo acá solo evita que dos toques casi
     * simultáneos del mismo botón manden el ticket dos veces en paralelo.
     */
    @Query("UPDATE pedidos SET intento_impresion_en = :ahora " +
            "WHERE id = :id " +
            "AND (intento_impresion_en IS NULL OR intento_impresion_en < :vencidoAntes)")
    int reservarParaReimprimirManual(long id, long ahora, long vencidoAntes);

    /**
     * ★ NUEVO — libera el reclamo cuando el intento de impresión TERMINÓ
     * (con error). No hace falta llamarlo tras un éxito: marcarImpreso(id,
     * true) ya saca al pedido de toda consulta futura (impreso=0 deja de
     * cumplirse), así que el reclamo queda "muerto" sin que nadie lo mire de
     * nuevo. Pero tras un error SÍ hay que liberarlo explícitamente: sin
     * esto, el pedido quedaría inaccesible hasta que venza :vencidoAntes en
     * reservarParaImprimirAutomatico/Manual (el margen de seguridad, pensado
     * para minutos, no para el próximo reintento que puede llegar en
     * segundos).
     *
     * Sin condición sobre "impreso" a propósito: liberar el reclamo siempre
     * es seguro (en el peor caso solo permite reintentar más rápido), y
     * evita una ambigüedad de orden entre esta escritura y la de
     * marcarImpreso() — las dos se mandan al mismo pool de 4 hilos desde el
     * callback de PrinterManager y no hay garantía de en qué orden corren.
     */
    @Query("UPDATE pedidos SET intento_impresion_en = NULL WHERE id = :id")
    void liberarReclamoImpresion(long id);

    // ─────────────────────────────────────────────────────────────────────────
    // NUEVOS: queries para el panel de resumen del día (MainActivity)
    // ─────────────────────────────────────────────────────────────────────────

    /** Pedidos PAGADOS del día en rango de timestamps */
    @Query("SELECT * FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado' ORDER BY pagado_en DESC")
    List<Pedido> getPagadosRangoSync(long inicio, long fin);

    /**
     * ★ NUEVO — pedidos CANCELADOS en un rango de fechas, por fecha de
     * CANCELACIÓN (cancelado_en), para que un pedido cancelado siga
     * apareciendo en el reporte del día/mes en que se canceló — marcado
     * como tal, no contado en los totales de venta — en vez de desaparecer
     * del todo y solo quedar rastreable desde Auditoría. Ver listarPedidos()
     * en ServidorHttp, que combina esto con getPagadosRangoSync.
     */
    @Query("SELECT * FROM pedidos WHERE cancelado_en BETWEEN :inicio AND :fin AND estado = 'cancelado' ORDER BY cancelado_en DESC")
    List<Pedido> getCanceladosRangoSync(long inicio, long fin);

    /** Total ganado en el día (suma de total_centavos − descuento_centavos, solo pagados) */
    @Query("SELECT COALESCE(SUM(total_centavos - descuento_centavos), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado'")
    int sumaTotalPagadoSync(long inicio, long fin);

    /** Suma de lo cobrado en efectivo en el día */
    @Query("SELECT COALESCE(SUM(total_centavos - descuento_centavos), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado' AND metodo_pago = 'efectivo'")
    int sumaEfectivoSync(long inicio, long fin);

    /** Suma de lo cobrado en tarjeta en el día */
    @Query("SELECT COALESCE(SUM(total_centavos - descuento_centavos), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado' AND metodo_pago = 'tarjeta'")
    int sumaTarjetaSync(long inicio, long fin);

    /** Suma de lo cobrado por QR en el día */
    @Query("SELECT COALESCE(SUM(total_centavos - descuento_centavos), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado' AND metodo_pago = 'qr'")
    int sumaQrSync(long inicio, long fin);

    /** Suma de la parte efectivo en pagos mixtos del día */
    @Query("SELECT COALESCE(SUM(mixto_efectivo_centavos), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado' AND metodo_pago = 'mixto'")
    int sumaMixtoEfectivoSync(long inicio, long fin);

    /** Suma de la parte tarjeta en pagos mixtos del día */
    @Query("SELECT COALESCE(SUM(mixto_tarjeta_centavos), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado' AND metodo_pago = 'mixto'")
    int sumaMixtoTarjetaSync(long inicio, long fin);

    /** Suma de la parte QR en pagos mixtos del día */
    @Query("SELECT COALESCE(SUM(mixto_qr_centavos), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado' AND metodo_pago = 'mixto'")
    int sumaMixtoQrSync(long inicio, long fin);

    /** Cuenta de pedidos pagados del día */
    @Query("SELECT COUNT(*) FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado'")
    int contarPagadosSync(long inicio, long fin);

    /** Suma de descuentos aplicados en el día */
    @Query("SELECT COALESCE(SUM(descuento_centavos), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado'")
    int sumaDescuentosSync(long inicio, long fin);

    // ─────────────────────────────────────────────────────────────────────────
    // NUEVOS: queries para ReportesActivity (reporte mensual)
    // ─────────────────────────────────────────────────────────────────────────

    /** Todos los pedidos pagados en un rango de fechas */
    @Query("SELECT * FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado' ORDER BY pagado_en ASC")
    List<Pedido> getPagadosRangoOrdenAscSync(long inicio, long fin);

    /** Total vendido en el mes */
    @Query("SELECT COALESCE(SUM(total_centavos - descuento_centavos), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado'")
    int sumaTotalMesSync(long inicio, long fin);

    /** Cuenta de órdenes pagadas en el mes */
    @Query("SELECT COUNT(*) FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado'")
    int contarOrdenesMesSync(long inicio, long fin);

    /** Suma descuentos en el mes */
    @Query("SELECT COALESCE(SUM(descuento_centavos), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado'")
    int sumaDescuentosMesSync(long inicio, long fin);

    /** Monto efectivo total (simple + parte mixto) en el mes */
    @Query("SELECT COALESCE(SUM(CASE WHEN metodo_pago='efectivo' THEN (total_centavos - descuento_centavos) ELSE 0 END), 0) " +
            "+ COALESCE(SUM(CASE WHEN metodo_pago='mixto' THEN mixto_efectivo_centavos ELSE 0 END), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado'")
    int sumaTotalEfectivoMesSync(long inicio, long fin);

    /** Monto tarjeta total en el mes */
    @Query("SELECT COALESCE(SUM(CASE WHEN metodo_pago='tarjeta' THEN (total_centavos - descuento_centavos) ELSE 0 END), 0) " +
            "+ COALESCE(SUM(CASE WHEN metodo_pago='mixto' THEN mixto_tarjeta_centavos ELSE 0 END), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado'")
    int sumaTotalTarjetaMesSync(long inicio, long fin);

    /** Monto QR total en el mes */
    @Query("SELECT COALESCE(SUM(CASE WHEN metodo_pago='qr' THEN (total_centavos - descuento_centavos) ELSE 0 END), 0) " +
            "+ COALESCE(SUM(CASE WHEN metodo_pago='mixto' THEN mixto_qr_centavos ELSE 0 END), 0) " +
            "FROM pedidos WHERE pagado_en BETWEEN :inicio AND :fin AND estado = 'pagado'")
    int sumaTotalQrMesSync(long inicio, long fin);

    // ══════════════════════════════════════════════════════════════════════
    // ★ NUEVO — Limpieza de datos (servicio/LimpiezaDatos)
    // Solo pedidos CERRADOS (pagado / cancelado) anteriores al corte. Los
    // pendientes (u otros estados) NUNCA se borran. Borrar de `pedidos` arrastra por
    // CASCADE items_pedido, items_salsas, items_modificadores y entregas.
    // ══════════════════════════════════════════════════════════════════════

    @Query("SELECT COUNT(*) FROM pedidos WHERE ((estado = 'pagado' AND COALESCE(pagado_en, creado_en) < :corte) OR (estado = 'cancelado' AND COALESCE(cancelado_en, creado_en) < :corte))")
    int contarBorrablesAntesDe(long corte);

    @Query("SELECT COUNT(*) FROM items_pedido WHERE pedido_id IN (" +
            "SELECT id FROM pedidos WHERE ((estado = 'pagado' AND COALESCE(pagado_en, creado_en) < :corte) OR (estado = 'cancelado' AND COALESCE(cancelado_en, creado_en) < :corte)))")
    int contarItemsBorrablesAntesDe(long corte);

    @Query("DELETE FROM pedidos WHERE ((estado = 'pagado' AND COALESCE(pagado_en, creado_en) < :corte) OR (estado = 'cancelado' AND COALESCE(cancelado_en, creado_en) < :corte))")
    int borrarBorrablesAntesDe(long corte);

    /** Pedidos viejos que NO están cerrados (ej. pendientes olvidados): no se borran, solo se avisa. */
    @Query("SELECT COUNT(*) FROM pedidos WHERE estado NOT IN ('pagado', 'cancelado') AND creado_en < :corte")
    int contarNoCerradosAntesDe(long corte);

    /** Fecha del pedido cerrado más antiguo (pagado_en / cancelado_en), o null si no hay. */
    @Query("SELECT MIN(CASE WHEN estado = 'pagado' THEN COALESCE(pagado_en, creado_en) " +
            "WHEN estado = 'cancelado' THEN COALESCE(cancelado_en, creado_en) END) FROM pedidos")
    Long getFechaCerradoMasAntiguoSync();

    /** Último movimiento de pedidos (creación, pago o cancelación), en ms; null si no hay pedidos.
     *  Lo usa LimpiezaDatos para limpiar solo cuando el local está tranquilo. */
    @Query("SELECT MAX(MAX(COALESCE(creado_en, 0)), MAX(COALESCE(pagado_en, 0)), MAX(COALESCE(cancelado_en, 0))) FROM pedidos")
    Long getUltimaActividadSync();
}
