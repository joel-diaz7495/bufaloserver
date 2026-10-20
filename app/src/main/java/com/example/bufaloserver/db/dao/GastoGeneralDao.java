package com.example.bufaloserver.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.GastoGeneral;

import java.util.List;

@Dao
public interface GastoGeneralDao {

    @Insert
    long insert(GastoGeneral g);

    @Update
    void update(GastoGeneral g);

    /** Borra UNA fila puntual de un mes concreto — nunca afecta a otros meses
     *  (no hay cascada posible: en este diseño cada fila es independiente). */
    @Delete
    void delete(GastoGeneral g);

    @Query("SELECT * FROM gastos_generales WHERE id = :id LIMIT 1")
    GastoGeneral getByIdSync(long id);

    /** Idempotencia de POST /gastos-generales — ver GastoGeneral.uuidCliente. */
    @Query("SELECT * FROM gastos_generales WHERE uuid_cliente = :uuid LIMIT 1")
    GastoGeneral getByUuidClienteSync(String uuid);

    /**
     * Gastos generales "visibles" al ver un mes/año: los recurrentes (la
     * última fila registrada de cada nombre, hasta ese mes inclusive — puede
     * ser de un mes anterior, en cuyo caso GastosActivity la muestra como
     * "pendiente de este mes") MÁS los de un solo mes cargados justo ese
     * mes/año.
     * ★ Reemplaza a GastoFijoDao.getVisiblesEnMes() + getPagosPorMesSync():
     * antes 2 tablas y 2 consultas, ahora 1 tabla y 1 consulta.
     */
    @Query("SELECT * FROM gastos_generales g WHERE " +
            "(recurrente = 1 AND (anio * 100 + mes) = (" +
            "   SELECT MAX(g2.anio * 100 + g2.mes) FROM gastos_generales g2 " +
            "   WHERE g2.nombre = g.nombre AND g2.recurrente = 1 " +
            "   AND (g2.anio * 100 + g2.mes) <= (:anio * 100 + :mes)" +
            ")) " +
            "OR (recurrente = 0 AND anio = :anio AND mes = :mes) " +
            "ORDER BY nombre ASC")
    LiveData<List<GastoGeneral>> getVisiblesEnMes(int anio, int mes);

    @Query("SELECT * FROM gastos_generales g WHERE " +
            "(recurrente = 1 AND (anio * 100 + mes) = (" +
            "   SELECT MAX(g2.anio * 100 + g2.mes) FROM gastos_generales g2 " +
            "   WHERE g2.nombre = g.nombre AND g2.recurrente = 1 " +
            "   AND (g2.anio * 100 + g2.mes) <= (:anio * 100 + :mes)" +
            ")) " +
            "OR (recurrente = 0 AND anio = :anio AND mes = :mes) " +
            "ORDER BY nombre ASC")
    List<GastoGeneral> getVisiblesEnMesSync(int anio, int mes);

    /** Todas las filas (para pantallas de administración/estadísticas). */
    @Query("SELECT * FROM gastos_generales ORDER BY nombre ASC, anio DESC, mes DESC")
    List<GastoGeneral> getTodosSync();
}