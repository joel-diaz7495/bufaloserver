package com.example.bufaloserver.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.bufaloserver.db.dao.CategoriaDao;
import com.example.bufaloserver.db.dao.AuditoriaDao;
import com.example.bufaloserver.db.dao.ResumenMensualDao;
import com.example.bufaloserver.db.dao.EntregaDao;
import com.example.bufaloserver.db.dao.GastoDiarioDao;
import com.example.bufaloserver.db.dao.GastoGeneralDao;
import com.example.bufaloserver.db.dao.GrupoModificadorDao;
import com.example.bufaloserver.db.dao.ItemModificadorSeleccionadoDao;
import com.example.bufaloserver.db.dao.ItemPedidoDao;
import com.example.bufaloserver.db.dao.ItemSalsaDao;
import com.example.bufaloserver.db.dao.ItemVarianteDao;
import com.example.bufaloserver.db.dao.ModificadorOpcionDao;
import com.example.bufaloserver.db.dao.PedidoDao;
import com.example.bufaloserver.db.dao.ProductoDao;
import com.example.bufaloserver.db.dao.SalsaGlobalDao;
import com.example.bufaloserver.db.dao.UsuarioDao;
import com.example.bufaloserver.db.dao.MovimientoInventarioDao;
import com.example.bufaloserver.db.dao.VarianteProductoDao;
import com.example.bufaloserver.db.dao.DispositivoDao;
import com.example.bufaloserver.db.dao.TokenEmparejamientoDao;

import com.example.bufaloserver.db.entities.Categoria;
import com.example.bufaloserver.db.entities.Auditoria;
import com.example.bufaloserver.db.entities.ResumenMensual;
import com.example.bufaloserver.db.entities.Entrega;
import com.example.bufaloserver.db.entities.GastoDiario;
import com.example.bufaloserver.db.entities.GastoGeneral;
import com.example.bufaloserver.db.entities.GrupoModificador;
import com.example.bufaloserver.db.entities.ItemModificadorSeleccionado;
import com.example.bufaloserver.db.entities.ItemPedido;
import com.example.bufaloserver.db.entities.ItemSalsa;
import com.example.bufaloserver.db.entities.ItemVariante;
import com.example.bufaloserver.db.entities.ModificadorOpcion;
import com.example.bufaloserver.db.entities.Pedido;
import com.example.bufaloserver.db.entities.Producto;
import com.example.bufaloserver.db.entities.SalsaGlobal;
import com.example.bufaloserver.db.entities.Usuario;
import com.example.bufaloserver.db.entities.MovimientoInventario;
import com.example.bufaloserver.db.entities.VarianteProducto;
import com.example.bufaloserver.db.entities.Dispositivo;
import com.example.bufaloserver.db.entities.TokenEmparejamiento;
import com.example.bufaloserver.seguridad.PinHasher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Room del servidor — mismas entidades que AppDatabase del cliente (copiadas
 * de db_e.zip, con el paquete renombrado a com.example.bufaloserver.db).
 *
 * ★ FIX — este comentario decía "arrancó sin migraciones (instalación nueva,
 * version=1)", desactualizado desde hace rato: ya va por MIGRATION_13_14
 * (ver abajo), la versión actual es 14.
 *
 * ★ FIX — exportSchema pasa de false a true (ver abajo). Antes Room no
 * volcaba el schema a un JSON versionado en el build, que es lo que permite
 * a Room VALIDAR cada Migration contra el schema real en tiempo de test
 * (MigrationTestHelper) en vez de confiar en que el SQL de cada migración a
 * mano está bien escrito. Con 12 migraciones manuales encadenadas y ninguna
 * forma de probarlas automáticamente, un error de tipeo en una de ellas
 * (ver MIGRATION_1_2, MIGRATION_11_12 — las que recrean una tabla entera)
 * no se detectaba hasta que un dispositivo real intentaba actualizar y
 * fallaba.
 *
 * Para que esto compile y funcione hace falta, en app/build.gradle.kts,
 * decirle al annotation processor de Room dónde volcar el JSON:
 *
 *   android {
 *       defaultConfig {
 *           javaCompileOptions {
 *               annotationProcessorOptions {
 *                   arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
 *               }
 *           }
 *       }
 *   }
 *
 * Esa carpeta "schemas/" queda con un JSON por versión (1.json, 2.json, ...,
 * 12.json) — conviene versionarla en git, es lo que después usa
 * MigrationTestHelper para validar cada Migration contra el schema real de
 * la versión de destino, en un test instrumentado. Escribir esos tests no
 * es parte de este fix (necesita el entorno de Gradle/Android real, que no
 * tengo acá) — queda como el siguiente paso lógico.
 *
 * Diferencia real con GastoDiario del cliente: acá se agregaron 3 columnas
 * (origen_offline, comprado_por, uuid_offline) para soportar POST /gastos/pendientes
 * — ver GastoDiario.java.
 */
@Database(
        entities = {
                Categoria.class,
                Producto.class,
                VarianteProducto.class,
                SalsaGlobal.class,
                Pedido.class,
                ItemPedido.class,
                ItemSalsa.class,
                ItemModificadorSeleccionado.class,
                Entrega.class,
                GastoDiario.class,
                GastoGeneral.class, // ★ Reemplaza a GastoFijo + GastoFijoPago (ver MIGRATION_8_9)
                GrupoModificador.class,
                ModificadorOpcion.class,
                ItemVariante.class,
                Usuario.class,
                MovimientoInventario.class, // ★ NUEVO — historial de entradas/salidas/ventas de stock
                Dispositivo.class, // ★ NUEVO — dispositivos autorizados a loguearse
                TokenEmparejamiento.class, // ★ NUEVO — códigos de emparejamiento de un solo uso
                Auditoria.class, // ★ NUEVO — log de acciones sensibles (cancelar pedido, gastos) para el Admin
                ResumenMensual.class // ★ NUEVO — totales por mes que sobreviven a la limpieza de datos
        },
        version = 17,
        exportSchema = true
)
public abstract class AppDatabaseServidor extends RoomDatabase {

    public abstract CategoriaDao        categoriaDao();
    public abstract ProductoDao         productoDao();
    public abstract VarianteProductoDao varianteProductoDao();
    public abstract SalsaGlobalDao      salsaGlobalDao();
    public abstract PedidoDao           pedidoDao();
    public abstract ItemPedidoDao       itemPedidoDao();
    public abstract ItemSalsaDao        itemSalsaDao();
    public abstract ItemModificadorSeleccionadoDao itemModificadorSeleccionadoDao();
    public abstract EntregaDao          entregaDao();
    public abstract GastoDiarioDao      gastoDiarioDao();
    public abstract GastoGeneralDao     gastoGeneralDao();
    public abstract GrupoModificadorDao  grupoModificadorDao();
    public abstract ModificadorOpcionDao modificadorOpcionDao();
    public abstract ItemVarianteDao      itemVarianteDao();
    public abstract UsuarioDao           usuarioDao();
    public abstract MovimientoInventarioDao movimientoInventarioDao(); // ★ NUEVO
    public abstract DispositivoDao           dispositivoDao(); // ★ NUEVO
    public abstract TokenEmparejamientoDao   tokenEmparejamientoDao(); // ★ NUEVO
    public abstract AuditoriaDao             auditoriaDao(); // ★ NUEVO
    public abstract ResumenMensualDao        resumenMensualDao(); // ★ NUEVO — limpieza de datos

    /**
     * v1 → v2: precioExtra de modificador_opciones pasa de INTEGER (Bs
     * enteros, truncaba cualquier decimal — mismo bug que tenía el cliente
     * en ModificadorOpcion antes del fix) a REAL. SQLite no permite ALTER
     * COLUMN directo: se recrea la tabla, se copian los datos y se
     * renombra, igual patrón que MIGRATION_7_8 del cliente.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS modificador_opciones_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "grupoId INTEGER NOT NULL, " +
                    "nombre TEXT, " +
                    "precioExtra REAL NOT NULL DEFAULT 0, " +
                    "orden INTEGER NOT NULL)");
            db.execSQL("INSERT INTO modificador_opciones_new (id, grupoId, nombre, precioExtra, orden) " +
                    "SELECT id, grupoId, nombre, precioExtra, orden FROM modificador_opciones");
            db.execSQL("DROP TABLE modificador_opciones");
            db.execSQL("ALTER TABLE modificador_opciones_new RENAME TO modificador_opciones");
        }
    };

    /**
     * v2 → v3: agrega la columna "recurrente" a gastos_fijos (mismo cambio
     * que MIGRATION_8_9 del cliente) — 1 = recurrente (comportamiento de
     * siempre para todos los gastos existentes), 0 = de un solo mes.
     * ALTER TABLE ADD COLUMN directo, sin recrear tabla — a diferencia de
     * MIGRATION_1_2, agregar una columna nueva sí lo permite SQLite.
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE gastos_fijos ADD COLUMN recurrente INTEGER NOT NULL DEFAULT 1");
        }
    };

    /**
     * v3 → v4: crea la tabla movimientos_inventario (historial de
     * entradas/salidas/ventas de stock), mismo diseño que MIGRATION_9_10
     * del cliente. Tabla nueva, no toca ninguna existente.
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS movimientos_inventario (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "producto_id INTEGER, " +
                    "variante_id INTEGER, " +
                    "tipo TEXT, " +
                    "cantidad INTEGER NOT NULL, " +
                    "stock_resultante INTEGER NOT NULL, " +
                    "motivo TEXT, " +
                    "nombre_snapshot TEXT, " +
                    "creado_en INTEGER NOT NULL, " +
                    "usuario_id INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_movimientos_inventario_producto_id ON movimientos_inventario(producto_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_movimientos_inventario_variante_id ON movimientos_inventario(variante_id)");
        }
    };

    /**
     * v4 → v5: crea las tablas "dispositivos" y "tokens_emparejamiento"
     * (login por deviceToken + QR de emparejamiento de "Gestión de
     * usuarios"). Dos tablas nuevas, mismo patrón que MIGRATION_3_4: no
     * tocan ninguna tabla existente.
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS dispositivos (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "deviceToken TEXT, " +
                    "usuarioId INTEGER NOT NULL, " +
                    "nombre TEXT NOT NULL DEFAULT '', " +
                    "estado TEXT NOT NULL DEFAULT 'autorizado', " +
                    "creado_en INTEGER NOT NULL DEFAULT 0, " +
                    "ultimo_uso_en INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_dispositivos_deviceToken ON dispositivos(deviceToken)");

            db.execSQL("CREATE TABLE IF NOT EXISTS tokens_emparejamiento (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "token TEXT, " +
                    "usuarioId INTEGER NOT NULL, " +
                    "creado_en INTEGER NOT NULL DEFAULT 0, " +
                    "expira_en INTEGER NOT NULL DEFAULT 0, " +
                    "usado INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tokens_emparejamiento_token ON tokens_emparejamiento(token)");
        }
    };

    /**
     * v5 → v6: agrega la columna "impreso" a pedidos (1 = el último intento
     * de imprimir el ticket salió bien, 0 = no se sabe / falló). Necesaria
     * para "Ventas del día", que ahora puede marcar qué pedidos quedaron sin
     * imprimirse. ALTER TABLE ADD COLUMN directo, mismo patrón que
     * MIGRATION_2_3 y MIGRATION_3_4 (columna nueva, no hace falta recrear
     * la tabla).
     */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE pedidos ADD COLUMN impreso INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * v6 → v7: crea la tabla "auditoria" (log de acciones sensibles: cancelar
     * pedido, cargar/editar/eliminar gastos). Tabla nueva, no toca ninguna
     * existente — mismo patrón que MIGRATION_3_4 y MIGRATION_4_5.
     */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS auditoria (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "usuario_id INTEGER NOT NULL, " +
                    "nombre_usuario TEXT, " +
                    "rol TEXT, " +
                    "accion TEXT, " +
                    "detalle TEXT, " +
                    "entidad_tipo TEXT NOT NULL DEFAULT '', " +
                    "entidad_id INTEGER NOT NULL DEFAULT 0, " +
                    "creado_en INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_auditoria_creado_en ON auditoria(creado_en)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_auditoria_usuario_id ON auditoria(usuario_id)");
        }
    };

    /**
     * v7 → v8: se elimina el módulo "Insumos" (Insumo / InsumoCompra).
     * Quedó definido en la DB, con DAO propio, pero nunca se expuso ningún
     * endpoint HTTP para él y el cliente (GastosActivity, MainActivity) no
     * lo referencia en ningún lado — código muerto desde que se agregó.
     * Se borran las tablas físicas junto con sus índices.
     */
    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS insumos_compras");
            db.execSQL("DROP TABLE IF EXISTS insumos");
        }
    };

    /**
     * v8 → v9: une gastos_fijos + gastos_fijos_pagos en una sola tabla
     * gastos_generales, donde cada fila es un registro INDEPENDIENTE de un
     * mes/año concreto (sin relación padre-hijo) — mismo cambio que
     * MIGRATION_11_12 del cliente, y misma razón: borrar una fila ya nunca
     * puede arrastrar el historial de otros meses. También agrega
     * es_adelanto a gastos_diarios.
     */
    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS gastos_generales (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "nombre TEXT, " +
                    "monto_centavos INTEGER NOT NULL, " +
                    "anio INTEGER NOT NULL, " +
                    "mes INTEGER NOT NULL, " +
                    "recurrente INTEGER NOT NULL DEFAULT 1, " +
                    "usuario_id INTEGER NOT NULL DEFAULT 0, " +
                    "creado_en INTEGER NOT NULL DEFAULT 0)");

            db.execSQL("INSERT INTO gastos_generales (nombre, monto_centavos, anio, mes, recurrente, usuario_id, creado_en) " +
                    "SELECT gf.nombre, p.monto_pagado_centavos, p.anio, p.mes, gf.recurrente, gf.usuario_id, p.pagado_en " +
                    "FROM gastos_fijos_pagos p JOIN gastos_fijos gf ON gf.id = p.gasto_fijo_id");

            db.execSQL("INSERT INTO gastos_generales (nombre, monto_centavos, anio, mes, recurrente, usuario_id, creado_en) " +
                    "SELECT gf.nombre, gf.monto_estimado_centavos, " +
                    "CAST(strftime('%Y', gf.creado_en/1000, 'unixepoch') AS INTEGER), " +
                    "CAST(strftime('%m', gf.creado_en/1000, 'unixepoch') AS INTEGER), " +
                    "gf.recurrente, gf.usuario_id, gf.creado_en " +
                    "FROM gastos_fijos gf WHERE gf.id NOT IN (SELECT gasto_fijo_id FROM gastos_fijos_pagos)");

            db.execSQL("DROP TABLE IF EXISTS gastos_fijos_pagos");
            db.execSQL("DROP TABLE IF EXISTS gastos_fijos");

            db.execSQL("ALTER TABLE gastos_diarios ADD COLUMN es_adelanto INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * v9 → v10: agrega "intento_impresion_en" a pedidos — el "reclamo" de
     * impresión que reemplaza los locks en memoria como defensa real contra
     * tickets duplicados (ver comentario completo en Pedido.intentoImpresionEn
     * y en PedidoDao.reservarParaImprimir). ALTER TABLE ADD COLUMN directo,
     * mismo patrón que MIGRATION_5_6 (agrega "impreso"): columna nueva,
     * nullable, no hace falta recrear la tabla. Todos los pedidos existentes
     * quedan con intento_impresion_en=NULL (nadie los está imprimiendo ahora
     * mismo), que es exactamente el estado correcto.
     */
    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE pedidos ADD COLUMN intento_impresion_en INTEGER");
        }
    };

    /**
     * v10 → v11: agrega "uuid_cliente" a pedidos — clave de idempotencia de
     * POST /pedido (ver Pedido.uuidCliente para el porqué). ALTER TABLE ADD
     * COLUMN directo, mismo patrón que MIGRATION_5_6/MIGRATION_9_10. El
     * índice es UNIQUE pero con "WHERE uuid_cliente IS NOT NULL" (índice
     * parcial, soportado desde SQLite 3.8.0 — muy por debajo del mínimo de
     * Android que soporta esta app) para que los pedidos viejos, que van a
     * tener uuid_cliente NULL todos, no choquen entre sí por unicidad.
     */
    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE pedidos ADD COLUMN uuid_cliente TEXT");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pedidos_uuid_cliente " +
                    "ON pedidos(uuid_cliente) WHERE uuid_cliente IS NOT NULL");
        }
    };

    /**
     * v11 → v12: modificador_opciones.precioExtra (REAL, en Bs) pasa a
     * precio_extra_centavos (INTEGER, en centavos) — ver el comentario en
     * ModificadorOpcion.precioExtraCentavos sobre el porqué. Mismo patrón
     * "tabla nueva + copiar + dropear + renombrar" que ya usó MIGRATION_1_2
     * para esta misma tabla (no se puede simplemente ALTER TABLE ... ADD
     * COLUMN porque acá además hay que CONVERTIR el dato existente, y
     * DROP COLUMN recién existe desde SQLite 3.35 — no asumible en todos los
     * Android que corren esta app).
     *
     * La conversión (ROUND(precio_extra * 100)) es la MISMA cuenta que ya
     * hacía MainActivity.modificadoresConPrecioDesdeOrderItem() del lado
     * cliente en cada pedido — acá se hace una sola vez, sobre el catálogo,
     * en vez de en cada venta.
     */
    /**
     * v12 → v13: usuarios.pin (TEXT, texto plano) pasa a pin_hash + pin_salt
     * (ver Usuario.pinHash/pinSalt y PinHasher para el porqué). Mismo patrón
     * "tabla nueva + copiar + dropear + renombrar" que MIGRATION_1_2 y
     * MIGRATION_11_12 — pero acá el "copiar" no es un simple SQL
     * INSERT..SELECT con una fórmula (como el *100 de MIGRATION_11_12),
     * porque hashear no es algo que SQL sepa hacer. Por eso esta migración
     * lee cada fila con un Cursor, calcula el hash en JAVA (PinHasher, la
     * misma clase que usa el login normal) y recién ahí inserta en la tabla
     * nueva — el único lugar de todo este archivo que hace esto, porque es
     * el único caso que de verdad lo necesita.
     */
    /**
     * v13 → v14: agrega "uuid_cliente" a gastos_generales — clave de
     * idempotencia de POST /gastos-generales (ver GastoGeneral.uuidCliente).
     * Mismo patrón simple que MIGRATION_10_11 (ALTER TABLE ADD COLUMN +
     * índice único parcial): acá no hace falta recrear la tabla porque no
     * hay que CONVERTIR ningún dato existente, solo agregar la columna
     * nueva (queda NULL en todas las filas viejas, que es justamente el
     * valor que el índice parcial excluye de la unicidad).
     */
    /**
     * ★ v14 → v15: categorias.imprimir_en_cocina. DEFAULT 1 = todas las categorías que ya
     * existen siguen imprimiéndose igual que antes; el admin apaga solo las que quiera
     * (ej. Refrescos) desde Editar menú → Gestionar categorías.
     */
    /**
     * ★ v15 → v16: stock_maximo en productos y variantes_producto (Reporte de Inventario).
     * DEFAULT 0 = "máximo no definido": todo lo que ya existe sigue funcionando igual, y el
     * admin lo va definiendo desde Inventario (✏️ Máximo). No toca stock_actual ni stock_minimo.
     */
    /**
     * ★ v16 → v17: tabla resumen_mensual (totales por mes que se conservan cuando la limpieza
     * borra los pedidos viejos). Solo CREA una tabla nueva: no toca ninguna existente.
     */
    static final Migration MIGRATION_16_17 = new Migration(16, 17) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `resumen_mensual` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`anio` INTEGER NOT NULL, " +
                    "`mes` INTEGER NOT NULL, " +
                    "`ventas_centavos` INTEGER NOT NULL, " +
                    "`pedidos_pagados` INTEGER NOT NULL, " +
                    "`pedidos_cancelados` INTEGER NOT NULL, " +
                    "`descuentos_centavos` INTEGER NOT NULL, " +
                    "`efectivo_centavos` INTEGER NOT NULL, " +
                    "`tarjeta_centavos` INTEGER NOT NULL, " +
                    "`qr_centavos` INTEGER NOT NULL, " +
                    "`gastos_diarios_centavos` INTEGER NOT NULL, " +
                    "`gastos_fijos_centavos` INTEGER NOT NULL, " +
                    "`creado_en` INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_resumen_mensual_anio_mes` " +
                    "ON `resumen_mensual` (`anio`, `mes`)");
        }
    };

    static final Migration MIGRATION_15_16 = new Migration(15, 16) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE productos ADD COLUMN stock_maximo INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE variantes_producto ADD COLUMN stock_maximo INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE categorias ADD COLUMN imprimir_en_cocina INTEGER NOT NULL DEFAULT 1");
        }
    };

    static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE gastos_generales ADD COLUMN uuid_cliente TEXT");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_gastos_generales_uuid_cliente " +
                    "ON gastos_generales(uuid_cliente) WHERE uuid_cliente IS NOT NULL");
        }
    };

    static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS usuarios_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "nombre TEXT, " +
                    "pin_hash TEXT, " +
                    "pin_salt TEXT, " +
                    "rol TEXT, " +
                    "activo INTEGER NOT NULL DEFAULT 1, " +
                    "creado_en INTEGER NOT NULL DEFAULT 0)");

            Cursor cursor = db.query("SELECT id, nombre, pin, rol, activo, creado_en FROM usuarios");
            try {
                while (cursor.moveToNext()) {
                    long id        = cursor.getLong(0);
                    String nombre  = cursor.getString(1);
                    String pinViejo = cursor.getString(2);
                    String rol     = cursor.getString(3);
                    int activo     = cursor.getInt(4);
                    long creadoEn  = cursor.getLong(5);

                    // Mismo hash que calcularía Usuario.establecerPin() —
                    // acá se hace directo con PinHasher porque no conviene
                    // instanciar un Usuario de Room en medio de una
                    // migración solo para usar dos líneas de ese objeto.
                    String salt = PinHasher.generarSalt();
                    String hash = PinHasher.hashear(pinViejo, salt);

                    ContentValues valores = new ContentValues();
                    valores.put("id", id);
                    valores.put("nombre", nombre);
                    valores.put("pin_hash", hash);
                    valores.put("pin_salt", salt);
                    valores.put("rol", rol);
                    valores.put("activo", activo);
                    valores.put("creado_en", creadoEn);
                    db.insert("usuarios_new", SQLiteDatabase.CONFLICT_REPLACE, valores);
                }
            } finally {
                cursor.close();
            }

            db.execSQL("DROP TABLE usuarios");
            db.execSQL("ALTER TABLE usuarios_new RENAME TO usuarios");
        }
    };

    static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS modificador_opciones_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "grupoId INTEGER NOT NULL, " +
                    "nombre TEXT, " +
                    "precio_extra_centavos INTEGER NOT NULL DEFAULT 0, " +
                    "orden INTEGER NOT NULL)");
            db.execSQL("INSERT INTO modificador_opciones_new (id, grupoId, nombre, precio_extra_centavos, orden) " +
                    "SELECT id, grupoId, nombre, CAST(ROUND(precioExtra * 100) AS INTEGER), orden FROM modificador_opciones");
            db.execSQL("DROP TABLE modificador_opciones");
            db.execSQL("ALTER TABLE modificador_opciones_new RENAME TO modificador_opciones");
        }
    };

    private static volatile AppDatabaseServidor instancia;

    /** Mismo patrón que dbExecutor del cliente — usado por ServidorHttp para
     *  no bloquear el hilo de NanoHTTPD mientras resuelve una consulta. */
    public static final ExecutorService dbExecutor = Executors.newFixedThreadPool(4);

    public static AppDatabaseServidor getInstance(Context context) {
        if (instancia == null) {
            synchronized (AppDatabaseServidor.class) {
                if (instancia == null) {
                    instancia = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabaseServidor.class,
                                    "bufaloserver.db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                            .addCallback(SEED_CALLBACK)
                            .build();
                }
            }
        }
        return instancia;
    }

    /**
     * ★ NUEVO — mismo patrón que SEED_CALLBACK del cliente
     * (com.example.myapplication.db.AppDatabase): solo corre onCreate() la
     * PRIMERA vez que Room crea el archivo bufaloserver.db (base de datos
     * nueva, no en cada arranque ni en migraciones). Carga el menú inicial
     * — ver DatabaseSeeder — para que el servidor arranque con el mismo
     * catálogo que el cliente en vez de con las tablas vacías.
     */
    private static final RoomDatabase.Callback SEED_CALLBACK = new RoomDatabase.Callback() {
        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);
            dbExecutor.execute(() -> {
                AppDatabaseServidor database = instancia;
                if (database == null) return;
                DatabaseSeeder.seed(database);
            });
        }
    };
}