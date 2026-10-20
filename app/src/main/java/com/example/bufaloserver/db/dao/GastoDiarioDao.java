package com.example.bufaloserver.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.GastoDiario;

import java.util.List;

@Dao
public interface GastoDiarioDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(GastoDiario gasto);

    @Update
    void update(GastoDiario gasto);

    @Delete
    void delete(GastoDiario gasto);

    @Query("DELETE FROM gastos_diarios WHERE id = :id")
    void deleteById(long id);

    @Query("SELECT * FROM gastos_diarios WHERE id = :id LIMIT 1")
    GastoDiario getByIdSync(long id);

    /** Idempotencia de /gastos/pendientes: si ya existe una fila con este uuid,
     *  no se debe insertar de nuevo (reintento de red desde el cliente). */
    @Query("SELECT * FROM gastos_diarios WHERE uuid_offline = :uuid LIMIT 1")
    GastoDiario getByUuidOfflineSync(String uuid);

    /**
     * Gastos del día para una fecha específica.
     * Pasa el inicio del día en milisegundos (00:00:00) y el fin (23:59:59).
     */
    @Query("SELECT * FROM gastos_diarios WHERE fecha BETWEEN :inicioMs AND :finMs ORDER BY creado_en DESC")
    LiveData<List<GastoDiario>> getPorFecha(long inicioMs, long finMs);

    @Query("SELECT * FROM gastos_diarios WHERE fecha BETWEEN :inicioMs AND :finMs ORDER BY creado_en DESC")
    List<GastoDiario> getPorFechaSync(long inicioMs, long finMs);

    /** Suma total de gastos diarios en un rango de fechas (para reportes). */
    @Query("SELECT COALESCE(SUM(monto_centavos), 0) FROM gastos_diarios WHERE fecha BETWEEN :inicioMs AND :finMs")
    int sumaMontoPorFecha(long inicioMs, long finMs);

    /** Suma total del día de hoy (LiveData para el badge en la pantalla de gastos). */
    @Query("SELECT COALESCE(SUM(monto_centavos), 0) FROM gastos_diarios WHERE fecha BETWEEN :inicioMs AND :finMs")
    LiveData<Integer> sumaMontoPorFechaLive(long inicioMs, long finMs);

    /** Todos los gastos diarios, más recientes primero (para pantalla de historial). */
    @Query("SELECT * FROM gastos_diarios ORDER BY fecha DESC, creado_en DESC")
    LiveData<List<GastoDiario>> getTodos();

    @Query("SELECT * FROM gastos_diarios ORDER BY fecha DESC, creado_en DESC")
    List<GastoDiario> getTodosSync();

    // ★ NUEVO — Limpieza de datos (servicio/LimpiezaDatos)
    @Query("SELECT COUNT(*) FROM gastos_diarios WHERE fecha < :corte")
    int contarAntesDe(long corte);

    @Query("DELETE FROM gastos_diarios WHERE fecha < :corte")
    int borrarAntesDe(long corte);

    @Query("SELECT MIN(fecha) FROM gastos_diarios")
    Long getFechaMasAntiguaSync();
}
