package com.example.bufaloserver.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.bufaloserver.db.entities.Usuario;

import java.util.List;

@Dao
public interface UsuarioDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Usuario usuario);

    @Update
    void update(Usuario usuario);

    @Delete
    void delete(Usuario usuario);   // no se usa desde la UI (se prefiere setActivo); queda por si hace falta un borrado real

    /** Todos los usuarios activos, para la pantalla de Gestión de Usuarios (lista). */
    @Query("SELECT * FROM usuarios WHERE activo = 1 ORDER BY nombre ASC")
    LiveData<List<Usuario>> getActivos();

    /** Todos los usuarios (activos e inactivos), por si se necesita mostrar los desactivados también. */
    @Query("SELECT * FROM usuarios ORDER BY nombre ASC")
    LiveData<List<Usuario>> getTodos();

    @Query("SELECT * FROM usuarios ORDER BY nombre ASC")
    List<Usuario> getTodosSync();

    @Query("SELECT * FROM usuarios WHERE id = :id LIMIT 1")
    Usuario getByIdSync(long id);

    /** ★ NUEVO — el id más chico de toda la tabla = el primer usuario que se
     *  creó (siempre el primer administrador, ver EstadoServidorActivity
     *  .verificarPrimerAdmin(), el único punto donde se crea un usuario sin
     *  pasar por "Gestión de usuarios"). Se usa para bloquear su borrado —
     *  ver eliminarUsuario() en ServidorHttp. */
    @Query("SELECT MIN(id) FROM usuarios")
    long getIdPrimerUsuario();

    // ★ FIX — getByPinSync(String pin) y contarConPin(String pin, long)
    // vivían acá, buscando por "WHERE pin = :pin". Con el PIN hasheado y
    // salado POR USUARIO (ver Usuario.pinHash/pinSalt), dos usuarios con el
    // mismo PIN ya NO producen el mismo valor guardado — así que una
    // búsqueda SQL por igualdad de columna ya no puede encontrar "el
    // usuario con este PIN" ni "si el PIN ya está en uso". Ese tipo de
    // chequeo ahora se hace en Java, usuario por usuario, con
    // Usuario.coincideConPin() — ver ServidorHttp.manejarLogin (que de
    // todos modos NO llamaba a getByPinSync: ya ubicaba al usuario por el
    // dispositivo emparejado y comparaba el PIN aparte) y
    // ServidorHttp.existeOtroUsuarioConPin() (reemplaza a contarConPin).

    @Query("UPDATE usuarios SET activo = :activo WHERE id = :id")
    void setActivo(long id, boolean activo);
}