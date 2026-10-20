package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Gasto general — reemplaza a GastoFijo + GastoFijoPago (antes 2 tablas para
 * una sola idea: "un gasto que se repite mes a mes"). Ahora cada fila es un
 * registro INDEPENDIENTE de un mes/año concreto: no existe ninguna tabla
 * "padre" de la que dependan, así que borrar una fila NUNCA afecta a las
 * demás (★ esto es justamente lo que arregla el bug reportado: antes,
 * borrar el gasto fijo completo borraba en cascada TODOS sus pagos, incluidos
 * los de meses anteriores ya cerrados).
 *
 * - recurrente = true  → se espera pagar TODOS los meses (ej. Alquiler, Luz,
 *   Sueldo). Si el mes que se está viendo no tiene fila propia,
 *   GastoGeneralDao.getVisiblesEnMes() trae la última fila registrada de ese
 *   nombre como referencia "pendiente de este mes" (de solo lectura, hasta
 *   que se registre el pago real de ESE mes, que crea una fila nueva).
 * - recurrente = false → gasto de un solo mes (ej. "Reparación del horno").
 *   Solo existe la fila de cuando se cargó; nunca se vuelve a sugerir sola.
 *
 * usuarioId (opcional, 0 = ninguno) = EMPLEADO al que corresponde este gasto
 * (ej. "Sueldo Raúl" → usuarioId = id de Raúl). Sirve para 2 cosas:
 *   1) Mostrar un desplegable de usuarios al crear un gasto general tipo
 *      sueldo, vinculándolo a ese empleado.
 *   2) Descontar automáticamente, al ver el mes en curso, los "adelantos"
 *      (ver GastoDiario.esAdelanto) que ese mismo empleado recibió durante
 *      ese mes — ver GastosActivity.renderGastosGenerales().
 */
@Entity(tableName = "gastos_generales")
public class GastoGeneral {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "nombre")
    public String nombre;

    /** Monto de ESTE registro puntual: lo pagado ese mes, o el de referencia si aún es "pendiente". */
    @ColumnInfo(name = "monto_centavos")
    public int montoCentavos;

    @ColumnInfo(name = "anio")
    public int anio;

    /** 1–12 */
    @ColumnInfo(name = "mes")
    public int mes;

    @ColumnInfo(name = "recurrente", defaultValue = "1")
    public boolean recurrente = true;

    /** Empleado vinculado (sueldo/adelantos). 0 = ningún empleado (gasto genérico como Luz/Alquiler). */
    @ColumnInfo(name = "usuario_id", defaultValue = "0")
    public long usuarioId = 0;

    @ColumnInfo(name = "creado_en")
    public long creadoEn = System.currentTimeMillis();

    /**
     * ★ NUEVO — clave de idempotencia de POST /gastos-generales, mismo
     * criterio que Pedido.uuidCliente y GastoDiario.uuidOffline: el cliente
     * genera un UUID una sola vez por gasto (al armarlo, antes de mandarlo)
     * y lo reenvía tal cual en cada reintento. Sin esto, si ApiClient corta
     * por timeout (TIMEOUT_MS = 5000) DESPUÉS de que el servidor ya guardó
     * el gasto pero ANTES de que la respuesta llegue, un reintento manual
     * duplicaba la fila — un gasto general cargado dos veces infla los
     * reportes mensuales sin que sea evidente (a diferencia de un pedido
     * duplicado, que un cajero notaría al ver una comanda de más).
     */
    @ColumnInfo(name = "uuid_cliente")
    public String uuidCliente = null;

    public GastoGeneral() {}

    @Ignore
    public GastoGeneral(String nombre, int montoCentavos, int anio, int mes, boolean recurrente, long usuarioId) {
        this.nombre        = nombre;
        this.montoCentavos = montoCentavos;
        this.anio          = anio;
        this.mes           = mes;
        this.recurrente    = recurrente;
        this.usuarioId     = usuarioId;
        this.creadoEn      = System.currentTimeMillis();
    }

    public String getMontoLabel() {
        if (montoCentavos % 100 == 0) return "Bs " + (montoCentavos / 100);
        return String.format("Bs %.2f", montoCentavos / 100.0);
    }
}