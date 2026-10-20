package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Representa una categoría del menú (Pollo, Hamburguesas, Extras, etc.)
 */
@Entity(tableName = "categorias")
public class Categoria {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "nombre")
    public String nombre;

    /** Emoji decorativo opcional (ej: "🍗"). Puede ser null. */
    @ColumnInfo(name = "emoji")
    public String emoji;

    /** Orden de aparición en la pantalla del menú */
    @ColumnInfo(name = "orden")
    public int orden = 0;

    /** false = oculta en el menú pero no se borra de la BD */
    @ColumnInfo(name = "activo")
    public boolean activo = true;

    /**
     * ★ true (por defecto) = los pedidos con productos de esta categoría salen en la comanda
     * de cocina. false (ej. Refrescos) = esta categoría NO se imprime: si un pedido tiene solo
     * categorías así, se registra normal (número de orden, ventas, caja, inventario) pero no
     * sale comanda; si es mixto, la comanda lleva solo los productos de las demás categorías.
     */
    @ColumnInfo(name = "imprimir_en_cocina", defaultValue = "1")
    public boolean imprimirEnCocina = true;

    // ── Constructor vacío que Room usará ───────────────────────────────

    public Categoria() {}

    // ── Constructor auxiliar ───────────────────────────────────────────

    @Ignore
    public Categoria(String nombre, String emoji, int orden) {
        this.nombre = nombre;
        this.emoji  = emoji;
        this.orden  = orden;
        this.activo = true;
    }
}