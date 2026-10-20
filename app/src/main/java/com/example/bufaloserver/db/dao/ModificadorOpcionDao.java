package com.example.bufaloserver.db.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.ModificadorOpcion;

import java.util.List;

@Dao
public interface ModificadorOpcionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ModificadorOpcion opcion);

    @Update
    void update(ModificadorOpcion opcion);

    @Delete
    void delete(ModificadorOpcion opcion);

    /** Obtener todas las opciones de un grupo, ordenadas */
    @Query("SELECT * FROM modificador_opciones WHERE grupoId = :grupoId ORDER BY orden ASC")
    List<ModificadorOpcion> getPorGrupoSync(long grupoId);

    /** Eliminar todas las opciones de un grupo */
    @Query("DELETE FROM modificador_opciones WHERE grupoId = :grupoId")
    void deleteByGrupo(long grupoId);
}
