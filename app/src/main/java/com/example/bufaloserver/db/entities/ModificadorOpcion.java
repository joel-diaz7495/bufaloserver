package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

/**
 * Opción individual dentro de un GrupoModificador.
 *
 * Ejemplo: dentro del grupo "Complementos" → "Con arroz", "Con fideo".
 * Se muestra como checkbox en el diálogo de pedido.
 */
@Entity(tableName = "modificador_opciones")
public class ModificadorOpcion {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Grupo al que pertenece esta opción */
    public long grupoId;

    /** Texto visible, ej: "Con arroz" */
    public String nombre;

    /**
     * Precio extra en CENTAVOS que se suma al total cuando el cliente elige
     * esta opción. 0 = no afecta el precio (ej. "Bañado", "Sin cebolla").
     * Positivo = extra con costo (ej. "Con tocino" = 500 centavos = Bs 5).
     *
     * ★ FIX — antes era "double precioExtra" en Bs (ej. 5.0), la única
     * columna de dinero de todo el sistema que NO estaba en centavos
     * enteros como el resto (ItemModificadorSeleccionado.precioExtraCentavos,
     * Pedido.totalCentavos, ItemPedido.precioCentavosSnapshot, etc.). El
     * riesgo real no era que la plata ya guardada estuviera mal — el único
     * punto donde este valor se convertía a centavos (MainActivity,
     * modificadoresConPrecioDesdeOrderItem) ya usaba Math.round bien — sino
     * la inconsistencia en sí: un REAL de punta a punta invita a que algún
     * cálculo futuro haga la conversión distinto y sí introduzca un error
     * de redondeo. Ver MIGRATION_11_12 para la conversión de los datos
     * existentes.
     */
    @ColumnInfo(name = "precio_extra_centavos", defaultValue = "0")
    public int precioExtraCentavos = 0;

    /** Orden dentro del grupo */
    public int orden;

    public ModificadorOpcion() {}

    @Ignore
    public ModificadorOpcion(long grupoId, String nombre, int orden) {
        this.grupoId = grupoId;
        this.nombre  = nombre;
        this.orden   = orden;
    }
}