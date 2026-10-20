package com.example.bufaloserver.db.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.ItemVariante;

import java.util.List;

@Dao
public interface ItemVarianteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ItemVariante item);

    @Update
    void update(ItemVariante item);

    @Delete
    void delete(ItemVariante item);

    /** Obtener todos los ítems de una variante */
    @Query("SELECT * FROM items_variante WHERE varianteId = :varianteId")
    List<ItemVariante> getPorVarianteSync(long varianteId);

    /**
     * Verificar si la variante tiene algún ítem con stock disponible.
     * Retorna true si hay al menos un ítem con stockActual > 0,
     * o si no tiene ítems (= sin límite).
     */
    @Query("SELECT COUNT(*) FROM items_variante WHERE varianteId = :varianteId")
    int contarItems(long varianteId);

    @Query("SELECT COUNT(*) FROM items_variante WHERE varianteId = :varianteId AND stockActual > 0")
    int contarItemsConStock(long varianteId);

    /** Decrementar stock de todos los ítems con stock de la variante en 1 (FIFO simplificado) */
    @Query("UPDATE items_variante SET stockActual = stockActual - 1 " +
           "WHERE id = (SELECT id FROM items_variante WHERE varianteId = :varianteId AND stockActual > 0 LIMIT 1)")
    void consumirUno(long varianteId);

    /** Reponer stock de un ítem */
    @Query("UPDATE items_variante SET stockActual = :nuevoStock WHERE id = :itemId")
    void actualizarStock(long itemId, int nuevoStock);

    /** Eliminar todos los ítems de una variante */
    @Query("DELETE FROM items_variante WHERE varianteId = :varianteId")
    void deleteByVariante(long varianteId);
}
