package com.example.bufaloserver.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.ItemPedido;

import java.util.List;

@Dao
public interface ItemPedidoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ItemPedido item);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(List<ItemPedido> items);

    @Update
    void update(ItemPedido item);

    @Delete
    void delete(ItemPedido item);

    /** Todos los ítems de un pedido, en el orden en que fueron agregados. */
    @Query("SELECT * FROM items_pedido WHERE pedido_id = :pedidoId ORDER BY id ASC")
    LiveData<List<ItemPedido>> getPorPedido(long pedidoId);

    @Query("SELECT * FROM items_pedido WHERE pedido_id = :pedidoId ORDER BY id ASC")
    List<ItemPedido> getPorPedidoSync(long pedidoId);

    /** Elimina todos los ítems de un pedido (al cancelar o resetear). */
    @Query("DELETE FROM items_pedido WHERE pedido_id = :pedidoId")
    void deleteByPedido(long pedidoId);

    /** Suma total del pedido en centavos (por si no se guarda en Pedido.totalCentavos). */
    @Query("SELECT COALESCE(SUM(total_centavos), 0) FROM items_pedido WHERE pedido_id = :pedidoId")
    int sumaTotalPorPedido(long pedidoId);

    /** Cantidad de líneas (productos distintos) en un pedido. */
    @Query("SELECT COUNT(*) FROM items_pedido WHERE pedido_id = :pedidoId")
    int countPorPedido(long pedidoId);

    // ★ FIX — se sacó updateCantidad(long, int): sin ningún llamador en todo
    // el proyecto (no hay ningún endpoint que edite la cantidad de UNA línea
    // de un pedido ya creado; un cambio de items pasa por reemplazar el
    // pedido entero vía actualizarPedido() en ServidorHttp). Tampoco
    // descontaba ni devolvía stock al cambiar la cantidad, así que de
    // haberse usado tal cual habría sido otra fuente de inconsistencia
    // entre lo vendido y el inventario.
}