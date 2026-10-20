package com.example.bufaloserver.db.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

/**
 * Item (unidad de stock) vinculado a una variante de producto.
 *
 * Cada registro representa una unidad o lote disponible en cocina.
 * Cuando stockActual llega a 0, el sistema muestra el mensaje
 * "Ya no hay existencias" en el menú de venta.
 *
 * Si una variante NO tiene ítems registrados, se asume stock ilimitado
 * (comportamiento original).
 */
@Entity(tableName = "items_variante")
public class ItemVariante {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Variante a la que pertenece */
    public long varianteId;

    /** Nombre descriptivo del ítem/lote, ej: "Bandeja mañana", "Lote 1" */
    public String nombre;

    /** Cantidad disponible actualmente */
    public int stockActual;

    /** Cantidad máxima / inicial del lote */
    public int stockInicial;

    public ItemVariante() {}

    @Ignore
    public ItemVariante(long varianteId, String nombre, int stock) {
        this.varianteId   = varianteId;
        this.nombre       = nombre;
        this.stockActual  = stock;
        this.stockInicial = stock;
    }

    /** @return true si este ítem ya se agotó */
    public boolean agotado() {
        return stockActual <= 0;
    }
}