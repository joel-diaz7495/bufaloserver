package com.example.bufaloserver.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.TokenEmparejamiento;

@Dao
public interface TokenEmparejamientoDao {

    @Insert
    long insert(TokenEmparejamiento token);

    @Update
    void update(TokenEmparejamiento token);

    @Query("SELECT * FROM tokens_emparejamiento WHERE token = :token LIMIT 1")
    TokenEmparejamiento getByTokenSync(String token);

    /**
     * ★ NUEVO — reclamo ATÓMICO de un token de un solo uso. Reemplaza al
     * patrón "leer t.esValido(), y recién más tarde escribir t.usado=true"
     * que usaba manejarEmparejar (ver ServidorHttp) — ese patrón tiene una
     * ventana entre el chequeo y la escritura donde dos /emparejar
     * simultáneos con el MISMO código (alguien lo escanea dos veces, o dos
     * personas con el mismo QR) pasaban los dos el chequeo antes de que
     * ninguno escribiera, y terminaban emparejando DOS dispositivos con un
     * código pensado para uno solo. Acá, la condición "usado = 0 AND
     * expira_en > :ahora" se evalúa y se escribe en el mismo paso atómico de
     * SQLite — el segundo request en llegar ve 0 filas afectadas (el primero
     * ya puso usado=1) y sabe que perdió la carrera, sin que ambos terminen
     * insertando un Dispositivo.
     */
    @Query("UPDATE tokens_emparejamiento SET usado = 1 WHERE id = :id AND usado = 0 AND expira_en > :ahora")
    int reclamarSiValido(long id, long ahora);

    /** Limpieza simple: se llama de tanto en tanto para no dejar crecer la tabla
     *  con códigos ya vencidos que nadie va a volver a usar. */
    @Query("DELETE FROM tokens_emparejamiento WHERE expira_en < :ahora")
    void borrarVencidos(long ahora);

    // ★ NUEVO — Limpieza de datos (servicio/LimpiezaDatos). Son códigos de un solo uso que
    // vencen en minutos: los que YA VENCIERON antes del corte no sirven para nada. (Se usa
    // expira_en y no creado_en: creado_en tiene default 0 y un token vigente podría borrarse.)
    @Query("SELECT COUNT(*) FROM tokens_emparejamiento WHERE expira_en < :corte")
    int contarAntesDe(long corte);

    @Query("DELETE FROM tokens_emparejamiento WHERE expira_en < :corte")
    int borrarAntesDe(long corte);
}
