package com.example.bufaloserver.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.bufaloserver.db.entities.MovimientoInventario;

import java.util.List;

@Dao
public interface MovimientoInventarioDao {

    @Insert
    long insert(MovimientoInventario m);

    @Query("SELECT * FROM movimientos_inventario WHERE variante_id = :varianteId ORDER BY creado_en DESC")
    List<MovimientoInventario> getPorVarianteSync(long varianteId);

    @Query("SELECT * FROM movimientos_inventario WHERE producto_id = :productoId ORDER BY creado_en DESC")
    List<MovimientoInventario> getPorProductoSync(long productoId);

    // ★ NUEVO — Limpieza de datos (servicio/LimpiezaDatos)
    @Query("SELECT COUNT(*) FROM movimientos_inventario WHERE creado_en < :corte")
    int contarAntesDe(long corte);

    @Query("DELETE FROM movimientos_inventario WHERE creado_en < :corte")
    int borrarAntesDe(long corte);
}
