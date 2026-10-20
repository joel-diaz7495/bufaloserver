// ═══════════════════════════════════════════════════════════════════════════
// EstadisticasDao.java  — NUEVO
// DAO de SOLO LECTURA usado por la pantalla "📊 Estadísticas y Base de Datos".
//
// Pestaña "Base de Datos": métodos dump*() → devuelven TODAS las filas de
//  cada tabla tal cual están guardadas (para poder inspeccionarlas).
//
// Pestaña "Estadísticas": métodos stat*() → agregan datos (SUM/COUNT/GROUP BY)
//  filtrando por rango de fechas [inicio, fin] en milisegundos.
// ═══════════════════════════════════════════════════════════════════════════
package com.example.bufaloserver.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.example.bufaloserver.db.entities.Categoria;
import com.example.bufaloserver.db.entities.Entrega;
import com.example.bufaloserver.db.entities.GastoDiario;
import com.example.bufaloserver.db.entities.GastoGeneral;
import com.example.bufaloserver.db.entities.GrupoModificador;
import com.example.bufaloserver.db.entities.ItemPedido;
import com.example.bufaloserver.db.entities.ItemSalsa;
import com.example.bufaloserver.db.entities.ItemVariante;
import com.example.bufaloserver.db.entities.ModificadorOpcion;
import com.example.bufaloserver.db.entities.Pedido;
import com.example.bufaloserver.db.entities.Producto;
import com.example.bufaloserver.db.entities.SalsaGlobal;
import com.example.bufaloserver.db.entities.VarianteProducto;

import java.util.List;

@Dao
public interface EstadisticasDao {

    // ══════════════════════════════════════════════════════════════════════
    // ── DUMP: todas las filas de cada tabla (pestaña "Base de Datos") ──────
    // ══════════════════════════════════════════════════════════════════════

    @Query("SELECT * FROM categorias ORDER BY orden ASC, id ASC")
    List<Categoria> dumpCategorias();

    @Query("SELECT * FROM productos ORDER BY categoria_id ASC, nombre ASC")
    List<Producto> dumpProductos();

    @Query("SELECT * FROM variantes_producto ORDER BY producto_id ASC, orden ASC")
    List<VarianteProducto> dumpVariantes();

    @Query("SELECT * FROM salsas_globales ORDER BY orden ASC, id ASC")
    List<SalsaGlobal> dumpSalsas();

    @Query("SELECT * FROM pedidos ORDER BY id DESC")
    List<Pedido> dumpPedidos();

    @Query("SELECT * FROM items_pedido ORDER BY id DESC")
    List<ItemPedido> dumpItemsPedido();

    @Query("SELECT * FROM items_salsas ORDER BY id DESC")
    List<ItemSalsa> dumpItemsSalsas();

    @Query("SELECT * FROM entregas ORDER BY id DESC")
    List<Entrega> dumpEntregas();

    @Query("SELECT * FROM gastos_diarios ORDER BY fecha DESC, id DESC")
    List<GastoDiario> dumpGastosDiarios();

    @Query("SELECT * FROM gastos_generales ORDER BY anio DESC, mes DESC, nombre ASC")
    List<GastoGeneral> dumpGastosGenerales();

    @Query("SELECT * FROM grupos_modificador ORDER BY varianteId ASC, orden ASC")
    List<GrupoModificador> dumpGruposModificador();

    @Query("SELECT * FROM modificador_opciones ORDER BY grupoId ASC, orden ASC")
    List<ModificadorOpcion> dumpModificadorOpciones();

    @Query("SELECT * FROM items_variante ORDER BY varianteId ASC, id ASC")
    List<ItemVariante> dumpItemsVariante();

    // ══════════════════════════════════════════════════════════════════════
    // ── ESTADÍSTICAS: agregados por rango de fecha [inicio, fin] ───────────
    // ══════════════════════════════════════════════════════════════════════

    /** Ventas netas (total − descuento) de pedidos pagados en el rango. */
    @Query("SELECT COALESCE(SUM(total_centavos - descuento_centavos), 0) " +
            "FROM pedidos WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int statVentasTotalesCentavos(long inicio, long fin);

    @Query("SELECT COUNT(*) FROM pedidos " +
            "WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int statCountPedidosPagados(long inicio, long fin);

    @Query("SELECT COUNT(*) FROM pedidos " +
            "WHERE estado = 'cancelado' AND cancelado_en BETWEEN :inicio AND :fin")
    int statCountPedidosCancelados(long inicio, long fin);

    @Query("SELECT COALESCE(SUM(descuento_centavos), 0) FROM pedidos " +
            "WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int statDescuentosCentavos(long inicio, long fin);

    /**
     * Total cobrado en EFECTIVO: pedidos pagados 100% en efectivo,
     * más la porción en efectivo de los pedidos con pago mixto.
     */
    @Query("SELECT COALESCE(SUM(" +
            "  CASE " +
            "    WHEN metodo_pago = 'efectivo' THEN total_centavos - descuento_centavos " +
            "    WHEN metodo_pago = 'mixto'    THEN mixto_efectivo_centavos " +
            "    ELSE 0 " +
            "  END), 0) " +
            "FROM pedidos WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int statEfectivoCentavos(long inicio, long fin);

    @Query("SELECT COALESCE(SUM(" +
            "  CASE " +
            "    WHEN metodo_pago = 'tarjeta' THEN total_centavos - descuento_centavos " +
            "    WHEN metodo_pago = 'mixto'   THEN mixto_tarjeta_centavos " +
            "    ELSE 0 " +
            "  END), 0) " +
            "FROM pedidos WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int statTarjetaCentavos(long inicio, long fin);

    @Query("SELECT COALESCE(SUM(" +
            "  CASE " +
            "    WHEN metodo_pago = 'qr'    THEN total_centavos - descuento_centavos " +
            "    WHEN metodo_pago = 'mixto' THEN mixto_qr_centavos " +
            "    ELSE 0 " +
            "  END), 0) " +
            "FROM pedidos WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int statQrCentavos(long inicio, long fin);

    @Query("SELECT COALESCE(SUM(monto_centavos), 0) FROM gastos_diarios " +
            "WHERE fecha BETWEEN :inicio AND :fin")
    int statGastosDiariosCentavos(long inicio, long fin);

    @Query("SELECT COALESCE(SUM(monto_centavos), 0) FROM gastos_generales " +
            "WHERE creado_en BETWEEN :inicio AND :fin")
    int statGastosFijosCentavos(long inicio, long fin);

    /** Productos más vendidos (por cantidad) dentro del rango, en pedidos pagados. */
    @Query("SELECT ip.nombre_snapshot AS nombre, " +
            "       SUM(ip.cantidad) AS cantidad, " +
            "       SUM(ip.total_centavos) AS totalCentavos " +
            "FROM items_pedido ip " +
            "INNER JOIN pedidos p ON p.id = ip.pedido_id " +
            "WHERE p.estado = 'pagado' AND p.pagado_en BETWEEN :inicio AND :fin " +
            "GROUP BY ip.nombre_snapshot " +
            "ORDER BY cantidad DESC " +
            "LIMIT :limite")
    List<ProductoVendido> statTopProductos(long inicio, long fin, int limite);

    /** Salsas más pedidas (por número de veces elegidas) dentro del rango. */
    @Query("SELECT s.nombre AS nombre, COUNT(*) AS cantidad " +
            "FROM items_salsas s " +
            "INNER JOIN items_pedido ip ON ip.id = s.item_pedido_id " +
            "INNER JOIN pedidos p ON p.id = ip.pedido_id " +
            "WHERE p.estado = 'pagado' AND p.pagado_en BETWEEN :inicio AND :fin " +
            "GROUP BY s.nombre " +
            "ORDER BY cantidad DESC " +
            "LIMIT :limite")
    List<SalsaContada> statTopSalsas(long inicio, long fin, int limite);

    /**
     * ★ NUEVO — "qué vendió cada quién": agrupa pedidos pagados por
     * usuario_cobrador_id (quien efectivamente cobró, no quien tomó el
     * pedido — ver Pedido.usuarioCobradorId) dentro del rango de fechas.
     * Se hace JOIN contra usuarios para traer el nombre ya resuelto, así el
     * cliente no tiene que pedir la lista de usuarios aparte para armar la
     * pantalla de reporte.
     * ★ FIX — se agrega u.id AS usuarioId: antes no venía ningún id para
     * poder cruzar cada usuario con su propio desglose de productos
     * (statProductosPorUsuario, más abajo); solo traía el nombre.
     */
    @Query("SELECT u.id AS usuarioId, " +
            "       u.nombre AS nombreUsuario, " +
            "       u.rol AS rol, " +
            "       COUNT(*) AS cantidadPedidos, " +
            "       SUM(p.total_centavos - p.descuento_centavos) AS totalCentavos " +
            "FROM pedidos p " +
            "INNER JOIN usuarios u ON u.id = p.usuario_cobrador_id " +
            "WHERE p.estado = 'pagado' AND p.pagado_en BETWEEN :inicio AND :fin " +
            "GROUP BY p.usuario_cobrador_id " +
            "ORDER BY totalCentavos DESC")
    List<VentasPorUsuario> statVentasPorUsuario(long inicio, long fin);

    /**
     * ★ NUEVO — "qué vendió" cada usuario, desglosado por producto, dentro
     * del mismo rango de fechas que statVentasPorUsuario() (mismo join,
     * mismo filtro p.estado='pagado', mismo usuario_cobrador_id como
     * quién-cobró). Devuelve TODOS los productos de cada usuario (sin
     * LIMIT) — la pantalla decide cuántos mostrar de entrada (top 3) y
     * cuántos al expandir "ver todo", sin pedir esto de nuevo al servidor.
     * Viene ordenado por usuarioId y, dentro de cada usuario, por cantidad
     * descendente — para agrupar en el servidor solo hace falta recorrer
     * la lista una vez.
     */
    @Query("SELECT p.usuario_cobrador_id AS usuarioId, " +
            "       ip.nombre_snapshot AS nombre, " +
            "       SUM(ip.cantidad) AS cantidad, " +
            "       SUM(ip.total_centavos) AS totalCentavos " +
            "FROM items_pedido ip " +
            "INNER JOIN pedidos p ON p.id = ip.pedido_id " +
            "WHERE p.estado = 'pagado' AND p.pagado_en BETWEEN :inicio AND :fin " +
            "GROUP BY p.usuario_cobrador_id, ip.nombre_snapshot " +
            "ORDER BY p.usuario_cobrador_id ASC, cantidad DESC")
    List<ProductoPorUsuario> statProductosPorUsuario(long inicio, long fin);

    // ══════════════════════════════════════════════════════════════════════
    // ── POJOs de proyección para las consultas agregadas ───────────────────
    // ══════════════════════════════════════════════════════════════════════

    class ProductoVendido {
        public String nombre;
        public int cantidad;
        public int totalCentavos;
    }

    class SalsaContada {
        public String nombre;
        public int cantidad;
    }

    class VentasPorUsuario {
        public long usuarioId; // ★ NUEVO
        public String nombreUsuario;
        public String rol;
        public int cantidadPedidos;
        public int totalCentavos;
    }

    /** ★ NUEVO — una fila = un producto vendido por un usuario en particular. */
    class ProductoPorUsuario {
        public long usuarioId;
        public String nombre;
        public int cantidad;
        public int totalCentavos;
    }
}