package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

import java.util.ArrayList;
import java.util.List;

/**
 * Una línea dentro de un pedido (un producto con su cantidad, salsas, etc.)
 *
 * Guarda snapshots del nombre y precio al momento de la venta,
 * para que editar el menú luego no altere el historial.
 */
@Entity(
        tableName = "items_pedido",
        foreignKeys = {
                @ForeignKey(
                        entity    = Pedido.class,
                        parentColumns = "id",
                        childColumns  = "pedido_id",
                        onDelete  = ForeignKey.CASCADE
                ),
                @ForeignKey(
                        entity    = Producto.class,
                        parentColumns = "id",
                        childColumns  = "producto_id",
                        onDelete  = ForeignKey.SET_NULL
                ),
                @ForeignKey(
                        entity    = VarianteProducto.class,
                        parentColumns = "id",
                        childColumns  = "variante_id",
                        onDelete  = ForeignKey.SET_NULL
                )
        },
        indices = {
                @Index("pedido_id"),
                @Index("producto_id"),
                @Index("variante_id")
        }
)
public class ItemPedido {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "pedido_id")
    public long pedidoId;

    /** Puede quedar null si el producto fue eliminado del menú */
    @ColumnInfo(name = "producto_id")
    public Long productoId = null;

    /** Puede quedar null si no tiene variante o si fue eliminada */
    @ColumnInfo(name = "variante_id")
    public Long varianteId = null;

    /** Nombre completo tal como se mostró en pantalla (ej: "Fingers 6 und") */
    @ColumnInfo(name = "nombre_snapshot")
    public String nombreSnapshot;

    /** Precio unitario en centavos al momento del pedido */
    @ColumnInfo(name = "precio_centavos_snapshot")
    public int precioCentavosSnapshot;

    @ColumnInfo(name = "cantidad")
    public int cantidad = 1;

    /** Total de esta línea en centavos */
    @ColumnInfo(name = "total_centavos")
    public int totalCentavos;

    /** true = con bañado de salsa */
    @ColumnInfo(name = "banado")
    public boolean banado = false;

    /** true = este ítem específico es para llevar (puede diferir del tipo del pedido) */
    @ColumnInfo(name = "es_llevar")
    public boolean esLlevar = false;

    /** Nota para cocina (ej: "sin cebolla", "extra picante") */
    @ColumnInfo(name = "nota_cocina")
    public String notaCocina = "";

    /**
     * ★ NUEVO — detalle de salsas/modificadores de esta línea. NO son
     * columnas (@Ignore): viven en tablas propias (items_salsas,
     * items_modificadores) relacionadas por item_pedido_id. Estos campos son
     * solo un "carrito" en memoria para pasar el detalle entre el JSON de
     * /pedido, la base, y EscPosBuilder sin tener que ir a buscarlo a mano
     * en cada punto — ver ServidorHttp.itemPedidoDesdeJson,
     * guardarDetalleItem y cargarDetalleItems.
     */
    @Ignore
    public List<String> salsas = new ArrayList<>();

    @Ignore
    public List<ItemModificadorSeleccionado> modificadoresConPrecio = new ArrayList<>();

    // ── Helpers ──────────────────────────────────────────────────────────────

    public double getPrecioDouble() {
        return precioCentavosSnapshot / 100.0;
    }

    public double getTotalDouble() {
        return totalCentavos / 100.0;
    }

    public void recalcTotal() {
        // ★ FIX — antes: precioCentavosSnapshot * cantidad, sin sumar los
        // extras de modificadoresConPrecio (mismo bug que tenía el cliente
        // en itemsPedidoDesdeOrden/descontarInventarioYGuardarItems antes
        // de su fix). Este método no se llama desde ningún lado hoy —
        // ServidorHttp.itemPedidoDesdeJson() confía en el totalCentavos que
        // ya manda el cliente — pero se deja corregido para que si algún
        // día se usa, no reintroduzca el bug en silencio.
        long extrasCentavos = 0;
        if (modificadoresConPrecio != null) {
            for (ItemModificadorSeleccionado m : modificadoresConPrecio) {
                if (m != null) extrasCentavos += (long) m.precioExtraCentavos * cantidad;
            }
        }
        this.totalCentavos = (int) (precioCentavosSnapshot * (long) cantidad + extrasCentavos);
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    public ItemPedido() {}
}