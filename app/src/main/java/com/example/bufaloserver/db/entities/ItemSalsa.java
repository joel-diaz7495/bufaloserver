package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

/**
 * Salsa seleccionada para un ítem de pedido.
 *
 * Una fila por salsa (no se agrupan en un string).
 * Si el cliente pide 2x Buffalo, hay dos filas con nombre="Buffalo".
 *
 * Esto permite:
 *  - Contar salsas fácilmente con COUNT(*)
 *  - Agrupar y mostrar "2x Buffalo" en cocina
 *  - Reportes de salsas más pedidas
 */
@Entity(
        tableName = "items_salsas",
        foreignKeys = @ForeignKey(
                entity    = ItemPedido.class,
                parentColumns = "id",
                childColumns  = "item_pedido_id",
                onDelete  = ForeignKey.CASCADE
        ),
        indices = { @Index("item_pedido_id") }
)
public class ItemSalsa {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "item_pedido_id")
    public long itemPedidoId;

    /** Nombre de la salsa */
    @ColumnInfo(name = "nombre")
    public String nombre;

    // ── Constructor que Room usará ───────────────────────────

    public ItemSalsa() {}

    // ── Constructor auxiliar ─────────────────────────────────

    @Ignore
    public ItemSalsa(long itemPedidoId, String nombre) {
        this.itemPedidoId = itemPedidoId;
        this.nombre       = nombre;
    }
}