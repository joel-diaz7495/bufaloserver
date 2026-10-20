package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import com.example.bufaloserver.seguridad.PinHasher;

/**
 * Usuario del sistema (rol Servidor: administra; rol Cliente: se loguea contra
 * el servidor con PIN). Solo tiene sentido en modo Conectado — en modo
 * Independiente esta tabla existe pero no se usa (ver AppDatabase).
 *
 * Roles válidos (constantes en RolUsuario, no un enum de Room para poder
 * ampliarlos sin migración): "mesero", "cajero", "administrador".
 */
@Entity(tableName = "usuarios")
public class Usuario {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "nombre")
    public String nombre;

    /**
     * ★ FIX — antes "pin" se guardaba en texto plano (ver PinHasher para el
     * porqué del cambio y qué tanto protege esto en la práctica, dado que
     * es un PIN de 4 dígitos). Ahora se guarda el HASH (pinHash) y la SAL
     * usada para calcularlo (pinSalt) — nunca el PIN en sí. No hay forma de
     * recuperar el PIN original a partir de estos dos campos, solo de
     * verificar si un PIN candidato coincide (ver coincideConPin).
     */
    @ColumnInfo(name = "pin_hash")
    public String pinHash;

    @ColumnInfo(name = "pin_salt")
    public String pinSalt;

    /** "mesero" | "cajero" | "administrador" — ver clase RolUsuario. */
    @ColumnInfo(name = "rol")
    public String rol;

    /** true = puede loguearse; false = desactivado (no se borra, para no perder histórico). */
    @ColumnInfo(name = "activo", defaultValue = "1")
    public boolean activo = true;

    @ColumnInfo(name = "creado_en", defaultValue = "0")
    public long creadoEn = System.currentTimeMillis();

    public Usuario() {}

    @Ignore
    public Usuario(String nombre, String pin, String rol) {
        this.nombre   = nombre;
        establecerPin(pin);
        this.rol      = rol;
        this.activo   = true;
        this.creadoEn = System.currentTimeMillis();
    }

    /**
     * ★ NUEVO — genera una sal nueva y hashea el PIN. Se usa tanto al crear
     * el usuario (constructor, arriba) como al cambiarle el PIN después
     * (ver actualizarUsuario en ServidorHttp) — SIEMPRE con una sal nueva,
     * nunca se reutiliza la sal vieja al cambiar el PIN.
     */
    public void establecerPin(String pinNuevo) {
        this.pinSalt = PinHasher.generarSalt();
        this.pinHash = PinHasher.hashear(pinNuevo, this.pinSalt);
    }

    /** ★ NUEVO — true si pinCandidato es el PIN de este usuario (ver PinHasher.verificar). */
    public boolean coincideConPin(String pinCandidato) {
        return PinHasher.verificar(pinCandidato, pinSalt, pinHash);
    }

    /** ★ MEJORA VELOCIDAD — como coincideConPin, pero con caché en memoria (ver PinHasher). */
    public boolean coincideConPinRapido(String pinCandidato, boolean[] desdeCache) {
        return PinHasher.verificarConCache(id, pinCandidato, pinSalt, pinHash, desdeCache);
    }

    /** ★ NUEVO — true si el hash guardado usa un costo viejo y conviene re-hashearlo. */
    public boolean pinNecesitaActualizacion() {
        return PinHasher.necesitaActualizar(pinHash);
    }

    // ── Helpers de rol (evitan comparar Strings sueltos por todo el código) ──

    public boolean esMesero()        { return "mesero".equals(rol); }
    public boolean esCajero()        { return "cajero".equals(rol); }
    public boolean esAdministrador() { return "administrador".equals(rol); }

    /** Etiqueta legible para mostrar en la UI (lista de usuarios, badge de sesión). */
    public String getRolLabel() {
        if (esAdministrador()) return "Administrador";
        if (esCajero())        return "Cajero";
        if (esMesero())        return "Mesero";
        return rol != null ? rol : "";
    }
}