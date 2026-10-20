package com.example.bufaloserver.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.bufaloserver.db.entities.Auditoria;

import java.util.List;

@Dao
public interface AuditoriaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Auditoria auditoria);

    /** Más reciente primero — es como se lee un log. */
    @Query("SELECT * FROM auditoria ORDER BY creado_en DESC")
    List<Auditoria> getTodasSync();

    @Query("SELECT * FROM auditoria WHERE creado_en BETWEEN :inicioMs AND :finMs ORDER BY creado_en DESC")
    List<Auditoria> getPorFechaSync(long inicioMs, long finMs);

    @Query("SELECT * FROM auditoria WHERE usuario_id = :usuarioId ORDER BY creado_en DESC")
    List<Auditoria> getPorUsuarioSync(long usuarioId);

    // ★ NUEVO — Limpieza de datos (servicio/LimpiezaDatos)
    @Query("SELECT COUNT(*) FROM auditoria WHERE creado_en < :corte")
    int contarAntesDe(long corte);

    @Query("DELETE FROM auditoria WHERE creado_en < :corte")
    int borrarAntesDe(long corte);
}
