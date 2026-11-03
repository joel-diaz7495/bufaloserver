# Integración con Supabase — Búfalo Server

Plan e implementación para **respaldo en la nube**, **consolidación multi-sucursal** y **reportes remotos** sin dejar de funcionar sin internet.

> Estado: scaffolding creado en `supabase/` (esquema, RLS, vistas y Edge Function). La app Android **todavía no** se modificó: los fragmentos Java de este documento son la guía de integración.

---

## 1. Arquitectura

```
┌──────────────────────────────┐        HTTPS + token por sucursal
│  Tablet / servidor local     │  ───────────────────────────────┐
│  Room + NanoHTTPD + WS       │                                 │
│  (fuente de verdad)          │                                 ▼
│                              │                    ┌─────────────────────────┐
│  ServidorHttp escribe        │                    │ Edge Function `sync`    │
│  → sync_outbox (pendiente)   │                    │ valida token (SHA-256)  │
└──────────────────────────────┘                    │ usa service_role        │
                                                    └───────────┬─────────────┘
                                                                │ upsert
                                                                ▼
                                                    ┌─────────────────────────┐
                                                    │ Postgres (Supabase)     │
                                                    │ PK (sucursal_id, id)    │
                                                    │ RLS de solo lectura     │
                                                    └───────────┬─────────────┘
                                                                │
                                              ┌─────────────────┴─────────────────┐
                                              ▼                                   ▼
                                    Supabase Auth + RLS                 Vistas de reportes
                                    (dueño / encargado)                 (ventas, caja, inventario)
```

Decisiones clave:

- **Local-first / push-only (v1).** SQLite sigue siendo la fuente de verdad. La nube es respaldo y consolidado; no se edita allá ni se baja de vuelta automáticamente.
- **Clave global `(sucursal_id, id)`.** Se reutiliza el `id` autoincremental local, sin migrar claves ni romper relaciones.
- **Token por sucursal.** La app nunca usa `service_role`; solo la Edge Function. El token se guarda hasheado (SHA-256) en `sucursales`.
- **Reportes con Supabase Auth + RLS.** Cada usuario de reportes tiene una fila en `perfiles` que lo asocia a una sucursal.
- **Sin dependencias nuevas obligatorias.** El envío puede hacerse con `HttpURLConnection` y `org.json` (ya disponibles en Android).

---

## 2. Archivos creados

| Archivo | Contenido |
|---|---|
| `supabase/migrations/20261020000100_schema.sql` | 21 tablas (20 locales + `sucursales`), PK compuestas, FKs, índices |
| `supabase/migrations/20261020000200_rls.sql` | `perfiles`, funciones `mi_sucursal()` / `es_admin_central()`, políticas RLS |
| `supabase/migrations/20261020000300_views.sql` | `v_ventas_diarias`, `v_ventas_mensuales`, `v_productos_vendidos`, `v_caja_por_dia`, `v_inventario_bajo`, `v_ultimos_pedidos` |
| `supabase/functions/sync/index.ts` | Recepción de lotes, validación de token, upsert idempotente |

Las tablas conservan los nombres de columna de Room. Las columnas camelCase van entre comillas en Postgres (`"varianteId"`, `"productoId"`, `"grupoId"`, `"multiSeleccion"`, `"deviceToken"`, `"usuarioId"`, `"stockActual"`, `"stockInicial"`).

Cada tabla agrega `actualizado_en` (epoch ms) que **solo** escribe el servidor. No existe en Room y no se debe enviar desde la app.

---

## 3. Puesta en marcha

### 3.1 Requisitos

- Proyecto Supabase (Postgres 15 o superior; Supabase cumple).
- Supabase CLI (`npm i -g supabase` o binario).
- Un token aleatorio por sucursal.

### 3.2 Desplegar esquema, RLS y vistas

```bash
cd /home/joel/bufaloserver
supabase login
supabase link --project-ref <PROJECT_REF>
supabase db push
```

### 3.3 Desplegar la Edge Function

```bash
supabase functions deploy sync --no-verify-jwt
```

`--no-verify-jwt` es intencional: la autenticación es el token propio de sucursal. La función **no** debe quedar pública sin ese token.

Verificar secretos (Supabase los inyecta por defecto; si faltan, configurarlos):

```bash
supabase secrets list
# SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY deben existir
# Opcional: permitir subir pin_hash / pin_salt
supabase secrets set INCLUIR_CREDENCIALES=false
```

### 3.4 Registrar la sucursal

Generar token y hash en la máquina (nunca en la tablet):

```bash
TOKEN=$(openssl rand -hex 32)
HASH=$(printf %s "$TOKEN" | sha256sum | cut -d' ' -f1)
echo "TOKEN=$TOKEN"
echo "HASH=$HASH"
```

En el SQL Editor de Supabase:

```sql
insert into public.sucursales (id, nombre, grupo, sync_token_hash, zona_horaria)
values ('sucursal-centro', 'Sucursal Centro', 'bufalo', '<HASH>', 'America/Mexico_City');
```

- `id`: identificador estable de **esta instalación** (no reutilizar si se reinstala sin restaurar).
- `grupo`: agrupa varias instalaciones del mismo negocio para reportes consolidados.
- `zona_horaria`: define el corte de día/mes de las vistas.

Guardar `TOKEN`, la URL del proyecto y `sucursal-centro` en la tablet (ver §6).

### 3.5 Crear usuarios de reportes

1. Invitar al usuario en **Authentication > Users**.
2. Copiar su UUID y asociarlo:

```sql
insert into public.perfiles (user_id, sucursal_id, rol, nombre)
values ('<UUID_AUTH>', 'sucursal-centro', 'admin_sucursal', 'Joel');
```

Roles: `admin_central` (ve todo), `admin_sucursal`, `cajero`, `consulta` (los tres últimos ven solo su sucursal).

### 3.6 Probar la función

```bash
curl -sS -X POST "https://<REF>.supabase.co/functions/v1/sync" \
  -H "Content-Type: application/json" \
  -H "x-sync-token: $TOKEN" \
  -d '{
    "sucursal_id": "sucursal-centro",
    "lote": [
      {"tabla": "categorias", "filas": [{"id": 1, "nombre": "Pollo", "emoji": "🍗", "orden": 0, "activo": 1, "imprimir_en_cocina": 1}]}
    ]
  }'
```

Respuesta esperada: `{"ok":true,"sucursal_id":"sucursal-centro","filas":1,...}`.

---

## 4. Protocolo de sincronización

### 4.1 Petición

```
POST /functions/v1/sync
x-sync-token: <token aleatorio>
Content-Type: application/json
```

```json
{
  "sucursal_id": "sucursal-centro",
  "lote": [
    { "tabla": "pedidos", "filas": [ { "id": 123, "...": "..." } ] },
    { "tabla": "items_pedido", "filas": [ { "id": 456, "...": "..." } ] }
  ]
}
```

Reglas:

- `tabla` debe ser una de las 20 permitidas; `sucursales` y `perfiles` están excluidas.
- `id` entero positivo y obligatorio.
- Máximo 1.000 filas por tabla y 5.000 por petición; cuerpo máximo 4 MB.
- El servidor ordena las tablas según dependencias antes del upsert.
- La función es **idempotente**: subir la misma fila dos veces no duplica (PK compuesta).
- En `usuarios`, `pin_hash` y `pin_salt` se descartan salvo `INCLUIR_CREDENCIALES=true`.
- Los enteros 0/1 se convierten a `boolean` en las columnas correspondientes.

### 4.2 Respuesta

```json
{
  "ok": true,
  "sucursal_id": "sucursal-centro",
  "filas": 5,
  "tablas": { "categorias": 1, "pedidos": 4 },
  "actualizado_en": 1760990400000
}
```

Errores: `401` token inválido, `403` token de otra sucursal, `400` tabla/JSON inválidos, `413` lote demasiado grande, `500` error de base.

---

## 5. Cambios locales necesarios antes de sincronizar

Estos puntos ya están documentados en `BASE_DE_DATOS.md`; conviene resolverlos **antes** de confiar en la sincronización.

1. **Índices de idempotencia (crítico).**
   - `Pedido.java` y `GastoGeneral.java` deben declarar `@Index(value = "uuid_cliente", unique = true)`.
   - `GastoDiario.java` debe declarar `@Index(value = "uuid_offline", unique = true)`.
   - Migración v17 → v18 con `CREATE UNIQUE INDEX IF NOT EXISTS`.
2. **`PedidoDao.insert`** debe dejar de usar `OnConflictStrategy.REPLACE` (usar `ABORT`). Con `REPLACE` un UUID duplicado borra el pedido y sus ítems.
3. **Duplicados previos.** Antes de crear el índice único de `gastos_diarios.uuid_offline`, limpiar duplicados existentes:

```sql
DELETE FROM gastos_diarios
 WHERE uuid_offline IS NOT NULL
   AND id NOT IN (
       SELECT MIN(id) FROM gastos_diarios
        WHERE uuid_offline IS NOT NULL
        GROUP BY uuid_offline
   );
```

4. **`gastos_generales`** sigue sin unicidad `(nombre, anio, mes)`; la nube no la impone para no rechazar datos históricos.

---

## 6. Outbox local (recomendado)

La forma más fiable de no perder escrituras es encolar la fila en la **misma transacción** en la que se guarda. El outbox guarda solo `(tabla, entidad_id)`; el envío lee el estado actual de la fila, así los cambios se agrupan.

### 6.1 Entidad Room

```java
@Entity(
    tableName = "sync_outbox",
    indices = {
        @Index(value = {"tabla", "entidad_id"}, unique = true),
        @Index("enviado_en")
    }
)
public class SyncOutbox {
    @PrimaryKey(autoGenerate = true) public long id;
    public String tabla;
    @ColumnInfo(name = "entidad_id") public long entidadId;
    @ColumnInfo(name = "creado_en") public long creadoEn;
    @ColumnInfo(name = "enviado_en") public long enviadoEn; // 0 = pendiente
    public int intentos;
    @ColumnInfo(name = "ultimo_error") public String ultimoError;
}
```

### 6.2 DAO

```java
@Dao
public interface SyncOutboxDao {

    @Query("INSERT INTO sync_outbox (tabla, entidad_id, creado_en, enviado_en, intentos) " +
           "VALUES (:tabla, :id, :ts, 0, 0) " +
           "ON CONFLICT(tabla, entidad_id) DO UPDATE SET enviado_en = 0, creado_en = :ts")
    void marcar(String tabla, long id, long ts);

    @Query("SELECT * FROM sync_outbox WHERE enviado_en = 0 ORDER BY id LIMIT :limite")
    List<SyncOutbox> pendientes(int limite);

    @Query("UPDATE sync_outbox SET enviado_en = :ts WHERE id = :id")
    void marcarEnviado(long id, long ts);

    @Query("UPDATE sync_outbox SET intentos = intentos + 1, ultimo_error = :error WHERE id = :id")
    void marcarError(long id, String error);

    @Query("UPDATE sync_outbox SET enviado_en = 0 WHERE intentos < 5")
    void reintentar();
}
```

### 6.3 Migración v17 → v18

```java
static final Migration MIGRATION_17_18 = new Migration(17, 18) {
    @Override
    public void migrate(SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`tabla` TEXT NOT NULL, " +
                "`entidad_id` INTEGER NOT NULL, " +
                "`creado_en` INTEGER NOT NULL, " +
                "`enviado_en` INTEGER NOT NULL, " +
                "`intentos` INTEGER NOT NULL, " +
                "`ultimo_error` TEXT)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_tabla_entidad_id` " +
                "ON `sync_outbox` (`tabla`, `entidad_id`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_enviado_en` " +
                "ON `sync_outbox` (`enviado_en`)");

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pedidos_uuid_cliente` " +
                "ON `pedidos` (`uuid_cliente`)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_gastos_generales_uuid_cliente` " +
                "ON `gastos_generales` (`uuid_cliente`)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_gastos_diarios_uuid_offline` " +
                "ON `gastos_diarios` (`uuid_offline`)");
    }
};
```

Recordar: subir `version = 18`, registrar la migración y añadir `SyncOutbox` + `SyncOutboxDao` a `@Database`.

### 6.4 Puntos de escritura a instrumentar

Añadir `db().syncOutboxDao().marcar("pedidos", idPedido, ahora)` dentro de las transacciones existentes de `ServidorHttp`:

| Tabla | Dónde se escribe |
|---|---|
| `pedidos` | `crearPedido` (dentro de `runInTransaction`, ~1997) y cambios de estado (~2740, ~3215) |
| `items_pedido`, `items_salsas`, `items_modificadores` | misma transacción de `crearPedido` |
| `entregas` | flujo de entrega |
| `gastos_diarios`, `gastos_generales` | `crearGasto` (~1503-1630) y edición (~1719-1780) |
| `productos`, `variantes_producto`, `categorias` | alta/edición (~1169-1479) |
| `grupos_modificador`, `modificador_opciones` | edición de producto (~1442-1453) |
| `salsas_globales` | alta/baja (~1844-1873) |
| `movimientos_inventario` | ajustes y descuentos (~2189-2402, ~3127-3141) |
| `usuarios`, `dispositivos`, `tokens_emparejamiento` | administración (~624-783, ~3737-3838) |
| `auditoria` | `registrarAuditoria` (~3338) |
| `resumen_mensual` | `LimpiezaDatos` |
| `items_variante` | sin uso actual; se puede omitir |

Para no ensuciar cada llamada, crear un helper:

```java
static void encolar(AppDatabaseServidor db, String tabla, long id) {
    db.syncOutboxDao().marcar(tabla, id, System.currentTimeMillis());
}
```

### 6.5 Envío

`SyncSender` (referencia, adaptable al proyecto):

```java
public final class SyncSender {

    private static final String[] ORDEN = {
        "categorias", "productos", "variantes_producto", "salsas_globales",
        "grupos_modificador", "modificador_opciones", "usuarios",
        "dispositivos", "tokens_emparejamiento", "pedidos", "items_pedido",
        "items_salsas", "items_modificadores", "entregas", "gastos_diarios",
        "gastos_generales", "items_variante", "movimientos_inventario",
        "auditoria", "resumen_mensual"
    };

    public static void enviarPendientes(Context ctx, AppDatabaseServidor db) {
        SharedPreferences prefs = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE);
        String base = prefs.getString("supabase_url", "");
        String token = prefs.getString("sync_token", "");
        String sucursal = prefs.getString("sucursal_id", "");
        if (base.isEmpty() || token.isEmpty() || sucursal.isEmpty()) return;

        List<SyncOutbox> pendientes = db.syncOutboxDao().pendientes(300);
        if (pendientes.isEmpty()) return;

        Map<String, List<Long>> porTabla = new LinkedHashMap<>();
        for (SyncOutbox o : pendientes) {
            porTabla.computeIfAbsent(o.tabla, k -> new ArrayList<>()).add(o.entidadId);
        }

        JSONArray lote = new JSONArray();
        List<SyncOutbox> enviadas = new ArrayList<>();

        for (String tabla : ORDEN) {
            List<Long> ids = porTabla.get(tabla);
            if (ids == null) continue;
            JSONArray filas = new JSONArray();
            for (long id : ids) {
                JSONObject fila = leerFila(db, tabla, id);
                if (fila != null) filas.put(fila);
            }
            if (filas.length() == 0) continue;
            JSONObject entrada = new JSONObject();
            entrada.put("tabla", tabla);
            entrada.put("filas", filas);
            lote.put(entrada);
            for (SyncOutbox o : pendientes) {
                if (o.tabla.equals(tabla)) enviadas.add(o);
            }
        }

        try {
            JSONObject cuerpo = new JSONObject();
            cuerpo.put("sucursal_id", sucursal);
            cuerpo.put("lote", lote);

            HttpURLConnection c = (HttpURLConnection)
                    new URL(base + "/functions/v1/sync").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-sync-token", token);

            try (OutputStream os = c.getOutputStream()) {
                os.write(cuerpo.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = c.getResponseCode();
            long ahora = System.currentTimeMillis();

            if (code >= 200 && code < 300) {
                for (SyncOutbox o : enviadas) db.syncOutboxDao().marcarEnviado(o.id, ahora);
            } else {
                for (SyncOutbox o : enviadas) {
                    db.syncOutboxDao().marcarError(o.id, "HTTP " + code);
                }
            }
            c.disconnect();
        } catch (Exception e) {
            for (SyncOutbox o : enviadas) {
                db.syncOutboxDao().marcarError(o.id, e.getMessage());
            }
        }
    }

    private static JSONObject leerFila(AppDatabaseServidor db, String tabla, long id) {
        Cursor c = db.getOpenHelper().getReadableDatabase()
                .query("SELECT * FROM " + tabla + " WHERE id = ?", new Object[]{id});
        try {
            if (!c.moveToFirst()) return null;
            JSONObject o = new JSONObject();
            for (int i = 0; i < c.getColumnCount(); i++) {
                String col = c.getColumnName(i);
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_NULL: o.put(col, JSONObject.NULL); break;
                    case Cursor.FIELD_TYPE_INTEGER: o.put(col, c.getLong(i)); break;
                    case Cursor.FIELD_TYPE_FLOAT: o.put(col, c.getDouble(i)); break;
                    case Cursor.FIELD_TYPE_STRING: o.put(col, c.getString(i)); break;
                    default: break; // BLOB no se sincroniza
                }
            }
            return o;
        } finally {
            c.close();
        }
    }
}
```

Notas del sender:

- Los nombres de tabla usados en SQL son constantes internas, no entrada del usuario.
- `enviado_en` se marca solo si la función responde 2xx; los errores se reintentan.
- Llamar a `enviarPendientes` desde el scheduler de `ServidorService` cada 2–5 minutos y al recuperar conectividad. Si se agrega `WorkManager`, usarlo con restricción `NetworkType.CONNECTED`.
- No sincronizar `sync_outbox` ni `sucursales`.
- Evitar logs con el token.

### 6.6 Backfill inicial

Para subir lo que ya existe (el outbox solo captura cambios nuevos):

```sql
INSERT OR IGNORE INTO sync_outbox (tabla, entidad_id, creado_en, enviado_en, intentos)
SELECT 'pedidos', id, CAST(strftime('%s','now') AS INTEGER) * 1000, 0, 0
  FROM pedidos WHERE pagado_en IS NOT NULL;
```

Repetir para `items_pedido` (solo ítems de pedidos pagados), `items_salsas`, `items_modificadores`, `entregas`, `gastos_diarios`, `gastos_generales`, `movimientos_inventario`, `auditoria`, `resumen_mensual`, `categorias`, `productos`, `variantes_producto`, `salsas_globales`, `usuarios`, `dispositivos`.

Sugerencia: hacer el backfill por meses (últimos 12) para no saturar el primer envío. Los pedidos abiertos se sincronizan al cerrarse.

---

## 7. Reportes

Con RLS activo, un usuario de sucursal solo ve sus filas; `admin_central` ve todo. Ejemplos:

```sql
-- Últimos 30 días de una sucursal
select * from public.v_ventas_diarias
 where dia >= current_date - interval '30 days'
 order by dia desc;

-- Consolidado por grupo de sucursales
select s.grupo, v.dia, sum(v.ventas_centavos) as ventas
  from public.v_ventas_diarias v
  join public.sucursales s on s.id = v.sucursal_id
 group by s.grupo, v.dia
 order by v.dia desc;

-- Caja y neto
select * from public.v_caja_por_dia order by dia desc limit 30;

-- Inventario bajo
select * from public.v_inventario_bajo order by sucursal_id, tipo, nombre;

-- Productos más vendidos del mes
select * from public.v_productos_vendidos
 where mes = date_trunc('month', current_date)::date
 order by unidades desc;
```

Las vistas usan `security_invoker = true`, por lo que heredan las políticas de las tablas base. No hace falta conceder permisos directos sobre ellas, pero sí `grant select ... to authenticated` en las tablas (ya incluido en la migración de RLS).

---

## 8. Restauración y respaldo

- **Respaldo nativo:** Supabase realiza backups diarios según el plan. Verificar retención en el panel.
- **Export manual:** `supabase db dump -f respaldo.sql` o `pg_dump "$DATABASE_URL"`.
- **Restauración local:** el esquema local es Room, no Postgres. Opciones:
  1. Reinstalar la app y volver a capturar (el histórico queda en la nube para reportes).
  2. Exportar las tablas de la nube a JSON/CSV y cargarlas con una ruta de importación temporal (no implementada).
  3. Restaurar el archivo `bufaloserver.db` desde un respaldo local y luego reanudar el sync.
- **Regla importante:** si se recrea la base local **sin restaurar**, generar un `sucursal_id` nuevo. De lo contrario, los `id` autoincrementales chocan con los ya subidos y el upsert sobrescribe filas históricas.
- `LimpiezaDatos` puede borrar pedidos locales después de 3 meses; la nube conserva el histórico si ya se sincronizó. No borrar `sync_outbox` pendiente.

---

## 9. Seguridad

- `service_role` **solo** en la Edge Function. Nunca en la APK ni en `local.properties`.
- Token de sucursal en `SharedPreferences` privadas; no imprimirlo en logs ni pantallas.
- Rotación: generar token nuevo, actualizar `sucursales.sync_token_hash`, actualizar la tablet. El token viejo deja de funcionar de inmediato.
- `anon` no tiene permisos de escritura; `authenticated` solo `SELECT` filtrado por RLS.
- Los PIN (`pin_hash`, `pin_salt`) no se suben por defecto. Si se activan, la nube debe tratarse con el mismo cuidado que la base local.
- TLS obligatorio; no habilitar `cleartextTraffic` para Supabase.
- Revisar periódicamente `auth.users`, `perfiles` y tokens activos.
- Auditar rotaciones y accesos desde el panel de Supabase.

---

## 10. Limitaciones y siguientes pasos

Limitaciones de la v1:

- Sin pull ni edición en la nube: un cambio hecho en Supabase no vuelve a la tablet.
- Sin propagación de borrados: la nube conserva lo borrado localmente (archivo histórico).
- Resolución de conflictos simple: gana la última escritura por `actualizado_en` del servidor.
- El outbox requiere tocar los puntos de escritura de `ServidorHttp`; el backfill es manual.
- Depende de que el token y el `sucursal_id` estén bien configurados.

Siguientes pasos sugeridos:

1. Aplicar los fixes locales de §5 y la migración v17 → v18.
2. Implementar `SyncOutbox` + `SyncSender` y probar contra un proyecto de staging.
3. Añadir un panel de estado de sync (pendientes, último envío, último error).
4. Programar la limpieza de `sync_outbox` enviado (por ejemplo, > 30 días).
5. Evaluar pull bidireccional solo para catálogo (productos/precios) en una v2.
6. Configurar alertas de backups y de errores de la Edge Function.

---

## 11. Checklist de despliegue

- [ ] `supabase db push` sin errores.
- [ ] `sync` desplegada con `--no-verify-jwt`.
- [ ] `sucursales` tiene la fila de la instalación con hash correcto.
- [ ] Token guardado en la tablet y probado con `curl`.
- [ ] Usuario de reportes creado en Auth y en `perfiles`.
- [ ] RLS probado: usuario de sucursal A no ve datos de B.
- [ ] Fixes locales de §5 aplicados.
- [ ] Backfill ejecutado y primer envío completado.
- [ ] `select * from v_ventas_diarias` devuelve datos coherentes con la tablet.
- [ ] Backup/export probado al menos una vez.
