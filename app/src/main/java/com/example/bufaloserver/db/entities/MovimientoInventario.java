package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * ★ NUEVO — Espejo exacto de MovimientoInventario del cliente. Un movimiento
 * de inventario: entrada (compra/reposición), salida manual (merma, se
 * rompió) o venta (descuento automático al confirmar un pedido — ver
 * ServidorHttp.descontarInventario).
 *
 * Exactamente uno de productoId/varianteId está seteado.
 */
@Entity(tableName = "movimientos_inventario",
        indices = { @Index("producto_id"), @Index("variante_id") })
public class MovimientoInventario {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "producto_id")
    public Long productoId;

    @ColumnInfo(name = "variante_id")
    public Long varianteId;

    @ColumnInfo(name = "tipo")
    public String tipo;

    @ColumnInfo(name = "cantidad")
    public int cantidad;

    @ColumnInfo(name = "stock_resultante")
    public int stockResultante;

    @ColumnInfo(name = "motivo")
    public String motivo;

    @ColumnInfo(name = "nombre_snapshot")
    public String nombreSnapshot;

    @ColumnInfo(name = "creado_en")
    public long creadoEn;

    /** Quién lo hizo — el usuario logueado en el celular que hizo el pedido/movimiento. */
    @ColumnInfo(name = "usuario_id", defaultValue = "0")
    public long usuarioId;

    public static final String TIPO_ENTRADA = "entrada";
    public static final String TIPO_SALIDA  = "salida";
    public static final String TIPO_VENTA   = "venta";
    public static final String TIPO_AJUSTE  = "ajuste";
    /** ★ Stock que vuelve por cancelación de pedido (suma, a diferencia de venta/salida). */
    public static final String TIPO_DEVOLUCION = "devolucion";

    public MovimientoInventario() {}

    @Ignore
    public MovimientoInventario(Long productoId, Long varianteId, String tipo, int cantidad,
                                 int stockResultante, String motivo, String nombreSnapshot, long usuarioId) {
        this.productoId      = productoId;
        this.varianteId      = varianteId;
        this.tipo             = tipo;
        this.cantidad         = cantidad;
        this.stockResultante  = stockResultante;
        this.motivo           = motivo;
        this.nombreSnapshot   = nombreSnapshot;
        this.usuarioId        = usuarioId;
        this.creadoEn         = System.currentTimeMillis();
    }
}
