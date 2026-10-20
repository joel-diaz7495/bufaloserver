package com.example.bufaloserver.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.SalsaGlobal;

import java.util.List;

@Dao
public interface SalsaGlobalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(SalsaGlobal salsa);

    @Update
    void update(SalsaGlobal salsa);

    @Delete
    void delete(SalsaGlobal salsa);

    /** Salsas activas para mostrar en el modal de selección. */
    @Query("SELECT * FROM salsas_globales WHERE activo = 1 ORDER BY orden ASC")
    LiveData<List<SalsaGlobal>> getActivas();

    @Query("SELECT * FROM salsas_globales WHERE activo = 1 ORDER BY orden ASC")
    List<SalsaGlobal> getActivasSync();

    /** Todas las salsas (para la pantalla de edición). */
    @Query("SELECT * FROM salsas_globales ORDER BY orden ASC")
    LiveData<List<SalsaGlobal>> getTodas();

    @Query("SELECT * FROM salsas_globales ORDER BY orden ASC")
    List<SalsaGlobal> getTodasSync();

    @Query("UPDATE salsas_globales SET activo = :activo WHERE id = :id")
    void setActivo(long id, boolean activo);

    @Query("UPDATE salsas_globales SET nombre = :nombre WHERE id = :id")
    void setNombre(long id, String nombre);

    @Query("UPDATE salsas_globales SET orden = :orden WHERE id = :id")
    void setOrden(long id, int orden);
}
