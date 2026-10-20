package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

@Entity(
        tableName = "variantes_producto",
        foreignKeys = @ForeignKey(
                entity    = Producto.class,
                parentColumns = "id",
                childColumns  = "producto_id",
                onDelete  = ForeignKey.CASCADE
        ),
        indices = { @Index("producto_id") }
)
public class VarianteProducto {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "producto_id")
    public long productoId;

    @ColumnInfo(name = "label")
    public String label;

    @ColumnInfo(name = "precio_centavos")
    public int precioCentavos = 0;

    @ColumnInfo(name = "habilitado")
    public boolean habilitado = true;

    @ColumnInfo(name = "max_salsas")
    public int maxSalsas = -1;

    @ColumnInfo(name = "orden")
    public int orden = 0;

    @ColumnInfo(name = "tiene_caracteristica")
    public boolean tieneCaracteristica = false;

    @ColumnInfo(name = "etiqueta_caracteristica")
    public String etiquetaCaracteristica = null;

    /**
     * ★ NUEVO — Control de inventario por variante.
     * Reemplaza al viejo sistema de "ítems de stock" (ItemVariante).
     *
     * true  → esta variante se controla por stock. Aparece en InventarioActivity,
     *         agrupada bajo la categoría de su producto. Cada venta resta 1 unidad.
     * false → stock ilimitado (comportamiento original, no aparece en Inventario).
     */
    @ColumnInfo(name = "tiene_inventario", defaultValue = "0")
    public boolean tieneInventario = false;

    /** Unidades disponibles actualmente. Solo relevante si tieneInventario = true. */
    @ColumnInfo(name = "stock_actual", defaultValue = "0")
    public int stockActual = 0;

    /** Umbral para avisar "stock bajo". Por defecto 5 unidades. */
    @ColumnInfo(name = "stock_minimo", defaultValue = "5")
    public int stockMinimo = 5;

    /**
     * ★ NUEVO — Stock MÁXIMO: cuántas unidades hay cuando el inventario está "lleno"
     * (ej. 24 botellas de refresco por entrega). Sirve para el Reporte de Inventario:
     * faltante a reponer = stockMaximo − stockActual. 0 = máximo no definido.
     */
    @ColumnInfo(name = "stock_maximo", defaultValue = "0")
    public int stockMaximo = 0;

    // Constructor vacío para Room
    public VarianteProducto() {}

    // ESTE ES EL QUE DEBES IGNORAR
    @Ignore
    public VarianteProducto(long productoId, String label,
                            int precioCentavos, boolean habilitado,
                            int maxSalsas, int orden) {

        this.productoId     = productoId;
        this.label          = label;
        this.precioCentavos = precioCentavos;
        this.habilitado     = habilitado;
        this.maxSalsas      = maxSalsas;
        this.orden          = orden;
    }

    public double getPrecioDouble() {
        return precioCentavos / 100.0;
    }

    public void setPrecioDouble(double precio) {
        this.precioCentavos = (int) Math.round(precio * 100);
    }

    public String getPrecioLabel() {
        if (!habilitado && precioCentavos == 0) return "Por definir";
        if (precioCentavos % 100 == 0) return "Bs " + (precioCentavos / 100);
        return String.format("Bs %.2f", precioCentavos / 100.0);
    }

    public boolean isPorDefinir() {
        return !habilitado && precioCentavos == 0;
    }

    // ── Helpers de inventario ────────────────────────────────────────────────

    /** true si el inventario está activado y ya no queda stock. */
    public boolean sinStock() {
        return tieneInventario && stockActual <= 0;
    }

    /** true si el inventario está activado y el stock está por agotarse (pero > 0). */
    public boolean stockBajo() {
        return tieneInventario && stockActual > 0 && stockActual <= stockMinimo;
    }
}