package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Representa un producto del menú.
 *
 * Tipos de comportamiento (flags):
 *  - esPollo        → muestra sección cantidad + salsas + bañado en el diálogo
 *  - esCombo        → muestra solo sección de salsas (sin cantidad ni bañado)
 *  - esSalsasModal  → abre modal de salsas sin límite; precio unitario por salsa
 *
 * Si un producto tiene variantes en la tabla `variantes_producto`,
 * el campo `precio` se ignora en pantalla (se usa el precio de la variante).
 *
 * tieneVariantes se mantiene aquí como flag de conveniencia para no tener
 * que hacer JOIN solo para saber si tiene variantes.
 */
@Entity(
        tableName = "productos",
        foreignKeys = @ForeignKey(
                entity    = Categoria.class,
                parentColumns = "id",
                childColumns  = "categoria_id",
                onDelete  = ForeignKey.CASCADE
        ),
        indices = { @Index("categoria_id") }
)
public class Producto {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "categoria_id")
    public long categoriaId;

    @ColumnInfo(name = "nombre")
    public String nombre;

    /**
     * Precio base en centavos (para evitar problemas de punto flotante).
     * Ejemplo: Bs 28.50 → 2850
     * Si el producto tiene variantes, este campo puede ser 0.
     */
    @ColumnInfo(name = "precio_centavos")
    public int precioCentavos = 0;

    /** true si muestra sección cantidad + salsas + bañado */
    @ColumnInfo(name = "es_pollo")
    public boolean esPollo = false;

    /** true si muestra solo sección de salsas (combos) */
    @ColumnInfo(name = "es_combo")
    public boolean esCombo = false;

    /**
     * true si es el ítem especial "Salsas":
     * abre modal de selección múltiple sin límite, precio Bs X c/u
     */
    @ColumnInfo(name = "es_salsas_modal")
    public boolean esSalsasModal = false;

    /** true si el producto aparece en la sección de promociones */
    @ColumnInfo(name = "es_promo")
    public boolean esPromo = false;

    /** true si es refresco (para manejar lógica de llevar/aquí) */
    @ColumnInfo(name = "es_refresco")
    public boolean esRefresco = false;

    /**
     * true si este producto ofrece salsas al pedirlo.
     * Aplica tanto a esPollo como a esCombo y esSalsasModal.
     */
    @ColumnInfo(name = "tiene_salsas")
    public boolean tieneSalsas = false;

    /**
     * ★ NUEVO — Límite de salsas para un producto SIN variantes ("individual").
     * Mismo criterio que VarianteProducto.maxSalsas: -1 = no ofrece salsas,
     * 0 = sin límite, N = límite de N salsas.
     * Solo se usa mientras tieneVariantes = false; al agregar la primera
     * variante, la config de salsas pasa a vivir en cada VarianteProducto.
     */
    @ColumnInfo(name = "max_salsas", defaultValue = "-1")
    public int maxSalsas = -1;

    /**
     * ★ NUEVO — Control de inventario para un producto SIN variantes.
     * Mismo patrón que en VarianteProducto (tieneInventario/stockActual/stockMinimo).
     * Solo aplica mientras tieneVariantes = false.
     */
    @ColumnInfo(name = "tiene_inventario", defaultValue = "0")
    public boolean tieneInventario = false;

    @ColumnInfo(name = "stock_actual", defaultValue = "0")
    public int stockActual = 0;

    @ColumnInfo(name = "stock_minimo", defaultValue = "5")
    public int stockMinimo = 5;

    /**
     * ★ NUEVO — Stock MÁXIMO: cuántas unidades hay cuando el inventario está "lleno"
     * (ej. 24 botellas de refresco por entrega). Sirve para el Reporte de Inventario:
     * faltante a reponer = stockMaximo − stockActual. 0 = máximo no definido.
     */
    @ColumnInfo(name = "stock_maximo", defaultValue = "0")
    public int stockMaximo = 0;

    /**
     * Flag de conveniencia: true si existen registros en variantes_producto
     * para este producto. Evita JOINs innecesarios al cargar la lista del menú.
     */
    @ColumnInfo(name = "tiene_variantes")
    public boolean tieneVariantes = false;

    /**
     * Etiqueta personalizada para la característica de bañado/extra.
     * Si es null o vacío se muestra "Característica" como fallback.
     * Ejemplo: "Bañado", "Extra picante", "Con salsa".
     */
    @ColumnInfo(name = "etiqueta_caracteristica")
    public String etiquetaCaracteristica = null;

    /** false = oculto en el menú, no se borra de la BD */
    @ColumnInfo(name = "activo")
    public boolean activo = true;

    @ColumnInfo(name = "creado_en")
    public long creadoEn = System.currentTimeMillis();

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Retorna el precio como double (ej: 2850 → 28.50) */
    public double getPrecioDouble() {
        return precioCentavos / 100.0;
    }

    /** Asigna precio a partir de un double (ej: 28.50 → 2850) */
    public void setPrecioDouble(double precio) {
        this.precioCentavos = (int) Math.round(precio * 100);
    }

    /** Texto de precio para mostrar en UI */
    public String getPrecioLabel() {
        if (tieneVariantes) return "Desde Bs " + formatCentavos(precioCentavos);
        if (esSalsasModal)  return "Bs " + formatCentavos(precioCentavos) + " c/u";
        if (precioCentavos == 0) return "Por definir";
        return "Bs " + formatCentavos(precioCentavos);
    }

    private static String formatCentavos(int centavos) {
        if (centavos % 100 == 0) return String.valueOf(centavos / 100);
        return String.format("%.2f", centavos / 100.0);
    }

    /** ★ NUEVO — true si el inventario está activado y ya no queda stock (solo aplica sin variantes). */
    public boolean sinStock() {
        return tieneInventario && stockActual <= 0;
    }

    /** ★ NUEVO — true si el inventario está activado y el stock está por agotarse (pero > 0). */
    public boolean stockBajo() {
        return tieneInventario && stockActual > 0 && stockActual <= stockMinimo;
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    public Producto() {}
}