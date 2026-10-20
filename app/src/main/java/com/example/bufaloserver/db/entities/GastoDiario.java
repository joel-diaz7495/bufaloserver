package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

/**
 * Gasto del día: compras de productos perecederos que se adquieren diariamente
 * (tomates, lechuga, pan, etc.).
 *
 * Se registran manualmente cada día y se resetean conceptualmente al siguiente
 * (cada fila tiene su fecha, el reporte filtra por fecha).
 */
@Entity(tableName = "gastos_diarios")
public class GastoDiario {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Descripción libre (ej: "Tomates 2kg", "Pan de hamburguesa x20") */
    @ColumnInfo(name = "detalle")
    public String detalle;

    /** Monto en centavos */
    @ColumnInfo(name = "monto_centavos")
    public int montoCentavos;

    /** Fecha del gasto en milisegundos (inicio del día) */
    @ColumnInfo(name = "fecha")
    public long fecha;

    @ColumnInfo(name = "creado_en")
    public long creadoEn = System.currentTimeMillis();

    /**
     * ★ NUEVO — Quién cargó este gasto (solo aplica en modo Conectado; en modo
     * Independiente, sin login, queda en 0 y no se muestra en ningún lado).
     */
    @ColumnInfo(name = "usuario_id", defaultValue = "0")
    public long usuarioId = 0;

    // ── Columnas para "Gasto rápido sin conexión" (POST /gastos/pendientes) ──
    // No es una tabla aparte: estas filas viven en esta misma tabla, con estas
    // 2 columnas para no perder el origen. Ver GastoRepositoryRemoto.crearPendiente()
    // y RegistroGastoRapidoActivity del lado cliente.

    /** true si este gasto se cargó sin login, desde el celular, y luego se sincronizó. */
    @ColumnInfo(name = "origen_offline", defaultValue = "0")
    public boolean capturadoOffline = false;

    /** Texto libre de quién compró — solo tiene valor cuando capturadoOffline = true. */
    @ColumnInfo(name = "comprado_por")
    public String compradoPor = null;

    /** UUID que generó el cliente al crear el GastoPendiente — clave de idempotencia:
     *  si el mismo uuid llega 2 veces (reintento de red), no se duplica el gasto.
     *  Null en los gastos que no vinieron de /gastos/pendientes. */
    @ColumnInfo(name = "uuid_offline")
    public String uuidOffline = null;

    /**
     * ★ NUEVO — true si esta fila es un ADELANTO de sueldo entregado a un
     * empleado. usuarioId, en ese caso, es el EMPLEADO que lo recibió (no
     * quién lo cargó) — ver ServidorHttp.crearGasto() y GastoGeneral.
     */
    @ColumnInfo(name = "es_adelanto", defaultValue = "0")
    public boolean esAdelanto = false;

    // ── Helpers ──────────────────────────────────────────────────────────────

    public double getMontoDouble() {
        return montoCentavos / 100.0;
    }

    public void setMontoDouble(double monto) {
        this.montoCentavos = (int) Math.round(monto * 100);
    }

    public String getMontoLabel() {
        if (montoCentavos % 100 == 0) return "Bs " + (montoCentavos / 100);
        return String.format("Bs %.2f", montoCentavos / 100.0);
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    public GastoDiario() {}
    @Ignore
    public GastoDiario(String detalle, int montoCentavos, long fecha) {
        this.detalle       = detalle;
        this.montoCentavos = montoCentavos;
        this.fecha         = fecha;
        this.creadoEn      = System.currentTimeMillis();
    }
}
