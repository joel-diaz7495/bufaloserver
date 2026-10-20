package com.example.bufaloserver.db.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.bufaloserver.db.entities.ItemModificadorSeleccionado;

import java.util.List;

@Dao
public interface ItemModificadorSeleccionadoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ItemModificadorSeleccionado modificador);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(List<ItemModificadorSeleccionado> modificadores);

    @Delete
    void delete(ItemModificadorSeleccionado modificador);

    /** Todos los modificadores de un ítem del pedido, en el orden en que fueron agregados. */
    @Query("SELECT * FROM items_modificadores WHERE item_pedido_id = :itemPedidoId ORDER BY id ASC")
    List<ItemModificadorSeleccionado> getPorItemPedidoSync(long itemPedidoId);

    /** Elimina todos los modificadores de un ítem (al editar o borrar la línea). */
    @Query("DELETE FROM items_modificadores WHERE item_pedido_id = :itemPedidoId")
    void deleteByItemPedido(long itemPedidoId);
}
