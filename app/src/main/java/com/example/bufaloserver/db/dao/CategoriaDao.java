package com.example.bufaloserver.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.Categoria;

import java.util.List;

@Dao
public interface CategoriaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Categoria categoria);

    @Update
    void update(Categoria categoria);

    @Delete
    void delete(Categoria categoria);

    /** Todas las categorías activas, ordenadas por el campo 'orden'. */
    @Query("SELECT * FROM categorias WHERE activo = 1 ORDER BY orden ASC")
    LiveData<List<Categoria>> getActivas();

    /** Todas las categorías (activas e inactivas) para la pantalla de edición. */
    @Query("SELECT * FROM categorias ORDER BY orden ASC")
    LiveData<List<Categoria>> getTodas();

    @Query("SELECT * FROM categorias ORDER BY orden ASC")
    List<Categoria> getTodasSync();

    @Query("SELECT * FROM categorias WHERE id = :id LIMIT 1")
    Categoria getByIdSync(long id);

    @Query("UPDATE categorias SET activo = :activo WHERE id = :id")
    void setActivo(long id, boolean activo);

    @Query("UPDATE categorias SET orden = :orden WHERE id = :id")
    void setOrden(long id, int orden);

    /** ★ Activa/desactiva que los productos de esta categoría salgan en la comanda de cocina. */
    @Query("UPDATE categorias SET imprimir_en_cocina = :imprimir WHERE id = :id")
    void setImprimirEnCocina(long id, boolean imprimir);
}
