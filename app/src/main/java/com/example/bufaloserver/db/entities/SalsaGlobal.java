package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

/**
 * Lista global de salsas disponibles para todos los productos.
 *
 * Estas salsas son las mismas para toda la app.
 * Qué salsas ofrece cada variante se controla desde la UI
 * (todas las variantes tienen acceso a esta lista).
 *
 * Salsas actuales:
 *   Brava, Tabasco, Buffalo, Barbacoa picante, Barbacoa,
 *   Miel y mostaza, Albahaca, Blue cheese, Ajo parmesano,
 *   Buffalo / Barbacoa, Buffalo / Blue cheese
 */
@Entity(tableName = "salsas_globales")
public class SalsaGlobal {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "nombre")
    public String nombre;

    /** Orden de aparición en el modal de selección de salsas */
    @ColumnInfo(name = "orden")
    public int orden = 0;

    /** false = oculta del modal pero no se borra */
    @ColumnInfo(name = "activo")
    public boolean activo = true;

    // ── Constructor ──────────────────────────────────────────────────────────

    public SalsaGlobal() {}
    @Ignore
    public SalsaGlobal(String nombre, int orden) {
        this.nombre = nombre;
        this.orden  = orden;
        this.activo = true;
    }
}
