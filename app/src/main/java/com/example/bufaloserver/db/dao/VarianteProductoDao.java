package com.example.bufaloserver.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.VarianteProducto;

import java.util.List;

@Dao
public interface VarianteProductoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(VarianteProducto variante);

    @Update
    void update(VarianteProducto variante);

    @Delete
    void delete(VarianteProducto variante);

    /** Variantes de un producto, ordenadas por 'orden'. */
    @Query("SELECT * FROM variantes_producto WHERE producto_id = :productoId ORDER BY orden ASC")
    LiveData<List<VarianteProducto>> getPorProducto(long productoId);

    @Query("SELECT * FROM variantes_producto WHERE producto_id = :productoId ORDER BY orden ASC")
    List<VarianteProducto> getPorProductoSync(long productoId);

    /** Solo variantes habilitadas (para el modal de selección de cantidad). */
    @Query("SELECT * FROM variantes_producto WHERE producto_id = :productoId ORDER BY orden ASC")
    List<VarianteProducto> getTodasPorProductoSync(long productoId);

    @Query("UPDATE variantes_producto SET habilitado = :habilitado WHERE id = :id")
    void setHabilitado(long id, boolean habilitado);

    @Query("UPDATE variantes_producto SET precio_centavos = :precio WHERE id = :id")
    void setPrecio(long id, int precio);

    @Query("UPDATE variantes_producto SET max_salsas = :maxSalsas WHERE id = :id")
    void setMaxSalsas(long id, int maxSalsas);

    @Query("UPDATE variantes_producto SET label = :label, precio_centavos = :precio, "
            + "habilitado = :habilitado, max_salsas = :maxSalsas WHERE id = :id")
    void updateCompleto(long id, String label, int precio, boolean habilitado, int maxSalsas);

    @Query("DELETE FROM variantes_producto WHERE producto_id = :productoId")
    void deleteByProducto(long productoId);

    @Query("SELECT COUNT(*) FROM variantes_producto WHERE producto_id = :productoId")
    int countPorProducto(long productoId);

    // ── ★ NUEVO — Inventario por variante ──────────────────────────────────

    /** Activa/desactiva el control de inventario y fija el stock inicial + mínimo. */
    @Query("UPDATE variantes_producto SET tiene_inventario = :tieneInventario, " +
            "stock_actual = :stockActual, stock_minimo = :stockMinimo WHERE id = :id")
    void setInventario(long id, boolean tieneInventario, int stockActual, int stockMinimo);

    /** Solo las variantes de un producto que tienen inventario activado. */
    @Query("SELECT * FROM variantes_producto WHERE producto_id = :productoId " +
            "AND tiene_inventario = 1 ORDER BY orden ASC")
    List<VarianteProducto> getConInventarioPorProductoSync(long productoId);

    /** Todas las variantes con inventario activado de una categoría (join por producto).
     *  ★ Simplificado a pedido del usuario: alcanza con "Con inventario" marcado,
     *  sin exigir además que estén habilitadas — así aparece apenas se activa el check. */
    @Query("SELECT vp.* FROM variantes_producto vp " +
            "INNER JOIN productos p ON p.id = vp.producto_id " +
            "WHERE p.categoria_id = :categoriaId AND vp.tiene_inventario = 1 " +
            "ORDER BY p.id ASC, vp.orden ASC")
    List<VarianteProducto> getConInventarioPorCategoriaSync(long categoriaId);

    /** Cuántas variantes en toda la app están sin stock (para alertas globales). */
    @Query("SELECT COUNT(*) FROM variantes_producto WHERE tiene_inventario = 1 AND stock_actual <= 0")
    int contarSinStock();

    /** Cuántas variantes están con stock bajo (0 < stock <= mínimo). */
    @Query("SELECT COUNT(*) FROM variantes_producto WHERE tiene_inventario = 1 " +
            "AND stock_actual > 0 AND stock_actual <= stock_minimo")
    int contarStockBajo();

    /** Fija manualmente el stock actual (usado al editar desde InventarioActivity). */
    @Query("UPDATE variantes_producto SET stock_actual = :nuevoStock WHERE id = :id")
    void actualizarStock(long id, int nuevoStock);

    /** ★ NUEVO — Fija el stock mínimo (umbral de aviso "bajo"), editable desde Inventario. */
    @Query("UPDATE variantes_producto SET stock_minimo = :nuevoMinimo WHERE id = :id")
    void actualizarStockMinimo(long id, int nuevoMinimo);

    /** ★ NUEVO — Fija el stock máximo (referencia para el Reporte de Inventario), editable desde Inventario. */
    @Query("UPDATE variantes_producto SET stock_maximo = :nuevoMaximo WHERE id = :id")
    void actualizarStockMaximo(long id, int nuevoMaximo);

    /**
     * ★ Suma/resta ATÓMICA al stock (delta puede ser negativo). Solo aplica si la
     * variante tiene inventario y el resultado no queda negativo. Devuelve filas
     * afectadas: 0 = no existe, no lleva inventario, o no alcanza el stock.
     */
    @Query("UPDATE variantes_producto SET stock_actual = stock_actual + :delta " +
            "WHERE id = :id AND tiene_inventario = 1 AND stock_actual + :delta >= 0")
    int aplicarDelta(long id, int delta);

    /**
     * ★ NUEVO — descuento ATÓMICO "solo si alcanza": la condición WHERE se evalúa
     * en el mismo UPDATE (una sola operación de SQLite, sin race condition posible
     * entre dos POST /pedido casi simultáneos de dos tablets distintas). Devuelve
     * cuántas filas afectó — 0 significa "no había suficiente stock, no se
     * descontó nada" (y el llamador debe rechazar ese ítem del pedido).
     */
    @Query("UPDATE variantes_producto SET stock_actual = stock_actual - :cantidad " +
            "WHERE id = :id AND tiene_inventario = 1 AND stock_actual >= :cantidad")
    int descontarStockSiAlcanza(long id, int cantidad);

    /** Buscar una variante por su ID (usado para revisar stock tras una venta). */
    @Query("SELECT * FROM variantes_producto WHERE id = :id LIMIT 1")
    VarianteProducto getByIdSync(long id);

    @Query("SELECT stock_actual FROM variantes_producto WHERE id = :id")
    int getStockActualSync(long id);
}