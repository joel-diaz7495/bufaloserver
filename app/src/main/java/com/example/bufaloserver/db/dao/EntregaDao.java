package com.example.bufaloserver.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.Entrega;

import java.util.List;

@Dao
public interface EntregaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Entrega entrega);

    @Update
    void update(Entrega entrega);

    @Delete
    void delete(Entrega entrega);

    /** Todas las entregas pendientes, ordenadas por la más antigua primero. */
    @Query("SELECT * FROM entregas WHERE estado = 'pendiente' ORDER BY id ASC")
    LiveData<List<Entrega>> getPendientes();

    @Query("SELECT * FROM entregas WHERE estado = 'pendiente' ORDER BY id ASC")
    List<Entrega> getPendientesSync();

    /** Todas las entregas (para reportes). */
    @Query("SELECT * FROM entregas ORDER BY id DESC")
    LiveData<List<Entrega>> getTodas();

    /** Entrega asociada a un pedido específico. */
    @Query("SELECT * FROM entregas WHERE pedido_id = :pedidoId LIMIT 1")
    Entrega getByPedidoSync(long pedidoId);

    /** Marca una entrega como entregada y registra método de pago final y hora. */
    @Query("UPDATE entregas SET estado = 'entregado', metodo_pago_final = :metodoPago, entregado_en = :timestamp WHERE id = :id")
    void marcarEntregado(long id, String metodoPago, long timestamp);

    /** Cantidad de entregas pendientes (para el badge en el topbar). */
    @Query("SELECT COUNT(*) FROM entregas WHERE estado = 'pendiente'")
    LiveData<Integer> countPendientes();

    @Query("SELECT COUNT(*) FROM entregas WHERE estado = 'pendiente'")
    int countPendientesSync();
}
