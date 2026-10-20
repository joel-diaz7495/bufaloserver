package com.example.bufaloserver.db.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.GrupoModificador;

import java.util.List;

@Dao
public interface GrupoModificadorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(GrupoModificador grupo);

    @Update
    void update(GrupoModificador grupo);

    @Delete
    void delete(GrupoModificador grupo);

    /** Obtener todos los grupos de una variante, ordenados */
    @Query("SELECT * FROM grupos_modificador WHERE varianteId = :varianteId ORDER BY orden ASC")
    List<GrupoModificador> getPorVarianteSync(long varianteId);

    /** Eliminar todos los grupos de una variante (al reemplazar variantes al guardar) */
    @Query("DELETE FROM grupos_modificador WHERE varianteId = :varianteId")
    void deleteByVariante(long varianteId);

    // ── ★ NUEVO — Grupos que cuelgan directo de un producto SIN variantes ──

    /** Obtener todos los grupos de un producto sin variantes, ordenados */
    @Query("SELECT * FROM grupos_modificador WHERE productoId = :productoId ORDER BY orden ASC")
    List<GrupoModificador> getPorProductoSync(long productoId);

    /** Eliminar todos los grupos de un producto sin variantes (al re-guardar el formulario) */
    @Query("DELETE FROM grupos_modificador WHERE productoId = :productoId")
    void deleteByProducto(long productoId);
}
