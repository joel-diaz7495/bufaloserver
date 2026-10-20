package com.example.bufaloserver.db.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

/**
 * Grupo de modificadores vinculado a una variante de producto.
 *
 * Ejemplo: variante "6 und" puede tener un grupo llamado "Complementos"
 * con opciones: "Con arroz", "Con fideo".
 *
 * El grupo vive dentro de una variante (varianteId).
 */
@Entity(tableName = "grupos_modificador")
public class GrupoModificador {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Variante a la que pertenece este grupo (0 si pertenece directo a un producto sin variantes). */
    public long varianteId;

    /**
     * ★ NUEVO — Producto al que pertenece este grupo cuando el producto NO tiene
     * variantes (0 si pertenece a una variante — en ese caso se usa varianteId).
     * Un grupo siempre pertenece a UNO de los dos, nunca a ambos.
     */
    public long productoId;

    /** Nombre visible del grupo, ej: "Complementos", "Tamaño de porción" */
    public String nombre;

    /**
     * ★ NUEVO — true: se pueden marcar varias opciones de este grupo a la vez
     * (ej. "Extras": tocino Y huevo). false (por defecto): solo una opción a
     * la vez, como funcionaba antes (ej. "Tamaño"). No afecta grupos ya
     * existentes, que quedan en false automáticamente tras la migración.
     */
    public boolean multiSeleccion = false;

    /** Orden de aparición */
    public int orden;

    public GrupoModificador() {}

    @Ignore
    public GrupoModificador(long varianteId, String nombre, int orden) {
        this.varianteId = varianteId;
        this.nombre     = nombre;
        this.orden      = orden;
    }

    /** ★ NUEVO — Constructor para grupos que cuelgan directo de un producto sin variantes. */
    public static GrupoModificador paraProducto(long productoId, String nombre, int orden) {
        GrupoModificador g = new GrupoModificador();
        g.productoId = productoId;
        g.nombre     = nombre;
        g.orden      = orden;
        return g;
    }
}