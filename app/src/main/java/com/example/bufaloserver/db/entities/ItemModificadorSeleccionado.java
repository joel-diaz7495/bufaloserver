package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Modificador seleccionado para un ítem de pedido (ej: "Queso extra",
 * "Sin cebolla").
 *
 * Una fila por modificador elegido — mismo criterio que ItemSalsa. El
 * precio se guarda en centavos, como el resto de los montos del servidor
 * (ver precioCentavosSnapshot en ItemPedido); los modificadores sin costo
 * simplemente quedan con precioExtraCentavos = 0.
 */
@Entity(
        tableName = "items_modificadores",
        foreignKeys = @ForeignKey(
                entity        = ItemPedido.class,
                parentColumns = "id",
                childColumns  = "item_pedido_id",
                onDelete      = ForeignKey.CASCADE
        ),
        indices = { @Index("item_pedido_id") }
)
public class ItemModificadorSeleccionado {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "item_pedido_id")
    public long itemPedidoId;

    /** Nombre del modificador (ej: "Queso extra", "Sin cebolla") */
    @ColumnInfo(name = "nombre")
    public String nombre;

    /** Precio extra en centavos. 0 si no tiene costo. */
    @ColumnInfo(name = "precio_extra_centavos")
    public int precioExtraCentavos;

    // ── Constructor que Room usará ───────────────────────────

    public ItemModificadorSeleccionado() {}

    // ── Constructor auxiliar ─────────────────────────────────

    @Ignore
    public ItemModificadorSeleccionado(long itemPedidoId, String nombre, int precioExtraCentavos) {
        this.itemPedidoId = itemPedidoId;
        this.nombre = nombre;
        this.precioExtraCentavos = precioExtraCentavos;
    }
}
