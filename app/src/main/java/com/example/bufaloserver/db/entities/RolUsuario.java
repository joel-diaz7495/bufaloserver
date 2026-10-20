package com.example.bufaloserver.db.entities;

/**
 * Constantes de rol compartidas entre :server y :client (viven en :core para
 * que ambas apps compilen contra el mismo valor exacto, sin riesgo de que un
 * lado escriba "admin" y el otro "administrador").
 *
 * Matriz de permisos (referencia — la aplica cada lado en su propio código):
 *
 *                          Mesero   Cajero   Administrador
 *  Tomar pedidos             ✅       ✅          ✅
 *  Ver menú / precios        ✅       ✅          ✅
 *  Editar menú                ❌       ❌          ✅
 *  Activar/desactivar prod.   ❌       ❌          ✅
 *  Ver reportes               ❌       ✅          ✅
 *  Ver ventas del día         ✅       ✅          ✅
 *  Cargar gastos               ❌       ✅          ✅
 *  Ver estadísticas            ❌       ✅          ✅
 *  Gestionar usuarios          ❌       ❌          ✅
 *  Cancelar pedido ya pagado    ❌       ✅          ✅
 */
public final class RolUsuario {

    public static final String MESERO        = "mesero";
    public static final String CAJERO        = "cajero";
    public static final String ADMINISTRADOR = "administrador";

    private RolUsuario() {}   // solo constantes, no se instancia

    public static boolean esValido(String rol) {
        return MESERO.equals(rol) || CAJERO.equals(rol) || ADMINISTRADOR.equals(rol);
    }

    // ── Permisos (única fuente de verdad — usar esto en vez de comparar
    //    strings de rol sueltos en el resto del código, tanto server como client) ──

    public static boolean puedeEditarMenu(String rol)        { return ADMINISTRADOR.equals(rol); }
    public static boolean puedeGestionarUsuarios(String rol) { return ADMINISTRADOR.equals(rol); }
    // ★ CAMBIO — antes también el Cajero; ahora solo Administrador (resumen del día,
    // reporte de caja e inventario, y sus previsualizaciones).
    public static boolean puedeVerReportes(String rol)       { return ADMINISTRADOR.equals(rol); }
    // ★ NUEVO — "Ventas del día" (historial de órdenes de hoy) ahora es
    // visible para los 3 roles, no solo Admin: un mesero/cajero también
    // necesita poder rastrear un pedido puntual o cancelarlo. esValido()
    // es literalmente "cualquiera de los 3 roles válidos" — se usa el
    // helper en vez de repetir el OR de los 3 nombres.
    public static boolean puedeVerVentasDelDia(String rol)   { return esValido(rol); }
    public static boolean puedeCargarGastos(String rol)      { return CAJERO.equals(rol) || ADMINISTRADOR.equals(rol); }
    public static boolean puedeTomarPedidos(String rol)      { return esValido(rol); }   // los 3 roles
    // ★ NUEVO — cancelar un pedido que YA se cobró (no uno pendiente,
    // ver puedeVerVentasDelDia que sí es para los 3 roles). Mesero no
    // entra: implica una devolución real de plata, es más sensible
    // que cancelar algo que todavía no se cobró.
    public static boolean puedeCancelarPedidoPagado(String rol) { return CAJERO.equals(rol) || ADMINISTRADOR.equals(rol); }
}