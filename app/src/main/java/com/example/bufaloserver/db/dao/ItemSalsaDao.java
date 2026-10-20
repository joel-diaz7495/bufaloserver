package com.example.bufaloserver.db.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.bufaloserver.db.entities.ItemSalsa;

import java.util.List;

@Dao
public interface ItemSalsaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ItemSalsa salsa);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(List<ItemSalsa> salsas);

    @Delete
    void delete(ItemSalsa salsa);

    /** Todas las salsas de un ítem del pedido, en el orden en que fueron agregadas. */
    @Query("SELECT * FROM items_salsas WHERE item_pedido_id = :itemPedidoId ORDER BY id ASC")
    List<ItemSalsa> getPorItemPedidoSync(long itemPedidoId);

    /** Elimina todas las salsas de un ítem (al editar o borrar la línea). */
    @Query("DELETE FROM items_salsas WHERE item_pedido_id = :itemPedidoId")
    void deleteByItemPedido(long itemPedidoId);
}
