package com.example.bufaloserver.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.Producto;

import java.util.List;

@Dao
public interface ProductoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Producto producto);

    @Update
    void update(Producto producto);

    @Delete
    void delete(Producto producto);

    /** Todos los productos activos de una categoría */
    @Query("SELECT * FROM productos WHERE categoria_id = :catId AND activo = 1 ORDER BY id ASC")
    LiveData<List<Producto>> getPorCategoria(long catId);

    /** Todos los productos de una categoría (activos e inactivos) */
    @Query("SELECT * FROM productos WHERE categoria_id = :catId ORDER BY id ASC")
    LiveData<List<Producto>> getPorCategoriaEdicion(long catId);

    /** Lista sync */
    @Query("SELECT * FROM productos WHERE categoria_id = :catId ORDER BY id ASC")
    List<Producto> getPorCategoriaSync(long catId);

    /** Buscar por ID */
    @Query("SELECT * FROM productos WHERE id = :id LIMIT 1")
    Producto getByIdSync(long id);

    /** Activar / desactivar */
    @Query("UPDATE productos SET activo = :activo WHERE id = :id")
    void setActivo(long id, boolean activo);

    /** Actualizar nombre y precio */
    @Query("UPDATE productos SET nombre = :nombre, precio_centavos = :precio WHERE id = :id")
    void updateNombrePrecio(long id, String nombre, int precio);

    /** Actualiza si tiene variantes */
    @Query("UPDATE productos SET tiene_variantes = :tieneVariantes WHERE id = :id")
    void setTieneVariantes(long id, boolean tieneVariantes);

    /** Eliminar todos los productos de una categoría */
    @Query("DELETE FROM productos WHERE categoria_id = :catId")
    void deletePorCategoria(long catId);

    // ── ★ NUEVO — Inventario para productos SIN variantes ──────────────────

    /** Activa/desactiva el control de inventario y fija el stock inicial + mínimo. */
    @Query("UPDATE productos SET tiene_inventario = :tieneInventario, " +
            "stock_actual = :stockActual, stock_minimo = :stockMinimo WHERE id = :id")
    void setInventario(long id, boolean tieneInventario, int stockActual, int stockMinimo);

    /** Fija manualmente el stock actual (usado al editar desde InventarioActivity). */
    @Query("UPDATE productos SET stock_actual = :nuevoStock WHERE id = :id")
    void actualizarStock(long id, int nuevoStock);

    /** ★ NUEVO — Fija el stock mínimo (umbral de aviso "bajo"), editable desde Inventario. */
    @Query("UPDATE productos SET stock_minimo = :nuevoMinimo WHERE id = :id")
    void actualizarStockMinimo(long id, int nuevoMinimo);

    /** ★ NUEVO — Fija el stock máximo (referencia para el Reporte de Inventario), editable desde Inventario. */
    @Query("UPDATE productos SET stock_maximo = :nuevoMaximo WHERE id = :id")
    void actualizarStockMaximo(long id, int nuevoMaximo);

    /**
     * ★ Suma/resta ATÓMICA al stock (delta puede ser negativo). Solo aplica si el
     * producto tiene inventario y el resultado no queda negativo. Devuelve filas
     * afectadas: 0 = no existe, no lleva inventario, o no alcanza el stock.
     */
    @Query("UPDATE productos SET stock_actual = stock_actual + :delta " +
            "WHERE id = :id AND tiene_inventario = 1 AND stock_actual + :delta >= 0")
    int aplicarDelta(long id, int delta);

    /**
     * ★ NUEVO — mismo descuento atómico "solo si alcanza" que en
     * VarianteProductoDao (ver ahí el porqué). Devuelve filas afectadas.
     */
    @Query("UPDATE productos SET stock_actual = stock_actual - :cantidad " +
            "WHERE id = :id AND tiene_inventario = 1 AND stock_actual >= :cantidad")
    int descontarStockSiAlcanza(long id, int cantidad);

    /** Todos los productos con inventario activado de una categoría (para InventarioActivity). */
    @Query("SELECT * FROM productos WHERE categoria_id = :categoriaId " +
            "AND tiene_variantes = 0 AND tiene_inventario = 1 ORDER BY id ASC")
    List<Producto> getConInventarioPorCategoriaSync(long categoriaId);

    /** Cuántos productos sin variantes están sin stock (para alertas globales). */
    @Query("SELECT COUNT(*) FROM productos WHERE tiene_variantes = 0 " +
            "AND tiene_inventario = 1 AND stock_actual <= 0")
    int contarSinStock();

    /** Cuántos productos sin variantes están con stock bajo (0 < stock <= mínimo). */
    @Query("SELECT COUNT(*) FROM productos WHERE tiene_variantes = 0 AND tiene_inventario = 1 " +
            "AND stock_actual > 0 AND stock_actual <= stock_minimo")
    int contarStockBajo();
}