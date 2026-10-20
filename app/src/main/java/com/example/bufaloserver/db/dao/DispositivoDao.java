package com.example.bufaloserver.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.Dispositivo;

import java.util.List;

@Dao
public interface DispositivoDao {

    @Insert
    long insert(Dispositivo dispositivo);

    @Update
    void update(Dispositivo dispositivo);

    @Query("SELECT * FROM dispositivos ORDER BY creado_en DESC")
    List<Dispositivo> getTodosSync();

    @Query("SELECT * FROM dispositivos WHERE id = :id LIMIT 1")
    Dispositivo getByIdSync(long id);

    @Query("SELECT * FROM dispositivos WHERE deviceToken = :deviceToken LIMIT 1")
    Dispositivo getByTokenSync(String deviceToken);

    @Query("UPDATE dispositivos SET estado = :estado WHERE id = :id")
    void setEstado(long id, String estado);

    @Query("UPDATE dispositivos SET ultimo_uso_en = :momento WHERE id = :id")
    void marcarUso(long id, long momento);
}
