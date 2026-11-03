-- ============================================================================
-- Búfalo Server — esquema central Supabase (PostgreSQL)
--
-- Objetivo: respaldo, consolidación multi-sucursal y reportes remotos.
--
-- Clave global: (sucursal_id, id)
--   - sucursal_id identifica la instalación (local/sucursal).
--   - id es el id autoincremental LOCAL de Room; no se modifica al sincronizar.
--   - Así se evitan colisiones entre sucursales sin migrar los IDs locales.
--
-- Convenciones:
--   - Dinero: bigint en centavos.
--   - Fechas: bigint epoch en milisegundos (igual que Room).
--   - Booleanos: boolean.
--   - actualizado_en: se completa en el servidor al hacer upsert (LWW).
--   - Se respetan los nombres de columnas locales, incluidos los camelCase
--     (entre comillas) para que el payload Java sea 1:1.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Sucursales / instalaciones
-- ---------------------------------------------------------------------------
create table if not exists public.sucursales (
    id              text primary key,
    nombre          text not null,
    grupo           text,                       -- agrupa instalaciones de un mismo negocio
    sync_token_hash text not null unique,       -- sha256 hex del token de sync
    zona_horaria    text not null default 'America/Mexico_City',
    activa          boolean not null default true,
    creado_en       bigint not null default 0,
    ultimo_sync_en  bigint not null default 0
);

-- ---------------------------------------------------------------------------
-- Catálogo
-- ---------------------------------------------------------------------------
create table if not exists public.categorias (
    sucursal_id        text not null references public.sucursales(id) on delete cascade,
    id                 bigint not null,
    nombre             text,
    emoji              text,
    orden              integer not null default 0,
    activo             boolean not null default true,
    imprimir_en_cocina boolean not null default true,
    actualizado_en     bigint not null default 0,
    primary key (sucursal_id, id)
);

create table if not exists public.productos (
    sucursal_id             text not null references public.sucursales(id) on delete cascade,
    id                      bigint not null,
    categoria_id            bigint not null,
    nombre                  text,
    precio_centavos         bigint not null default 0,
    es_pollo                boolean not null default false,
    es_combo                boolean not null default false,
    es_salsas_modal         boolean not null default false,
    es_promo                boolean not null default false,
    es_refresco             boolean not null default false,
    tiene_salsas            boolean not null default false,
    max_salsas              integer not null default -1,
    tiene_inventario        boolean not null default false,
    stock_actual            integer not null default 0,
    stock_minimo            integer not null default 5,
    stock_maximo            integer not null default 0,
    tiene_variantes         boolean not null default false,
    etiqueta_caracteristica text,
    activo                  boolean not null default true,
    creado_en               bigint not null default 0,
    actualizado_en          bigint not null default 0,
    primary key (sucursal_id, id),
    foreign key (sucursal_id, categoria_id)
        references public.categorias (sucursal_id, id) on delete cascade
);

create table if not exists public.variantes_producto (
    sucursal_id             text not null references public.sucursales(id) on delete cascade,
    id                      bigint not null,
    producto_id             bigint not null,
    label                   text,
    precio_centavos         bigint not null default 0,
    habilitado              boolean not null default true,
    max_salsas              integer not null default -1,
    orden                   integer not null default 0,
    tiene_caracteristica    boolean not null default false,
    etiqueta_caracteristica text,
    tiene_inventario        boolean not null default false,
    stock_actual            integer not null default 0,
    stock_minimo            integer not null default 5,
    stock_maximo            integer not null default 0,
    actualizado_en          bigint not null default 0,
    primary key (sucursal_id, id),
    foreign key (sucursal_id, producto_id)
        references public.productos (sucursal_id, id) on delete cascade
);

create table if not exists public.salsas_globales (
    sucursal_id    text not null references public.sucursales(id) on delete cascade,
    id             bigint not null,
    nombre         text,
    orden          integer not null default 0,
    activo         boolean not null default true,
    actualizado_en bigint not null default 0,
    primary key (sucursal_id, id)
);

create table if not exists public.grupos_modificador (
    sucursal_id     text not null references public.sucursales(id) on delete cascade,
    id              bigint not null,
    "varianteId"    bigint not null default 0,
    "productoId"    bigint not null default 0,
    nombre          text,
    "multiSeleccion" boolean not null default false,
    orden           integer not null default 0,
    actualizado_en  bigint not null default 0,
    primary key (sucursal_id, id)
);

create table if not exists public.modificador_opciones (
    sucursal_id           text not null references public.sucursales(id) on delete cascade,
    id                    bigint not null,
    "grupoId"             bigint not null,
    nombre                text,
    precio_extra_centavos bigint not null default 0,
    orden                 integer not null default 0,
    actualizado_en        bigint not null default 0,
    primary key (sucursal_id, id)
);

-- ---------------------------------------------------------------------------
-- Pedidos
-- ---------------------------------------------------------------------------
create table if not exists public.pedidos (
    sucursal_id            text not null references public.sucursales(id) on delete cascade,
    id                     bigint not null,
    numero_orden           integer not null default 0,
    nombre_cliente         text,
    tipo                   text,
    estado                 text,
    metodo_pago            text,
    total_centavos         bigint not null default 0,
    descuento_centavos     bigint not null default 0,
    descuento_tipo         text,
    mixto_efectivo_centavos bigint not null default 0,
    mixto_tarjeta_centavos bigint not null default 0,
    mixto_qr_centavos      bigint not null default 0,
    creado_en              bigint not null default 0,
    enviado_cocina_en      bigint,
    pagado_en              bigint,
    cancelado_en           bigint,
    usuario_creador_id     bigint not null default 0,
    usuario_cobrador_id    bigint not null default 0,
    impreso                boolean not null default false,
    intento_impresion_en   bigint,
    uuid_cliente           text,
    actualizado_en         bigint not null default 0,
    primary key (sucursal_id, id)
);

create unique index if not exists pedidos_uuid_cliente_uq
    on public.pedidos (sucursal_id, uuid_cliente)
    where uuid_cliente is not null;

create index if not exists pedidos_creado_en_idx   on public.pedidos (sucursal_id, creado_en);
create index if not exists pedidos_pagado_en_idx   on public.pedidos (sucursal_id, pagado_en);
create index if not exists pedidos_cancelado_en_idx on public.pedidos (sucursal_id, cancelado_en);
create index if not exists pedidos_estado_idx      on public.pedidos (sucursal_id, estado);

create table if not exists public.items_pedido (
    sucursal_id              text not null references public.sucursales(id) on delete cascade,
    id                       bigint not null,
    pedido_id                bigint not null,
    producto_id              bigint,
    variante_id              bigint,
    nombre_snapshot          text,
    precio_centavos_snapshot bigint not null default 0,
    cantidad                 integer not null default 1,
    total_centavos           bigint not null default 0,
    banado                   boolean not null default false,
    es_llevar                boolean not null default false,
    nota_cocina              text,
    actualizado_en           bigint not null default 0,
    primary key (sucursal_id, id),
    foreign key (sucursal_id, pedido_id)
        references public.pedidos (sucursal_id, id) on delete cascade,
    foreign key (sucursal_id, producto_id)
        references public.productos (sucursal_id, id) on delete set null (producto_id),
    foreign key (sucursal_id, variante_id)
        references public.variantes_producto (sucursal_id, id) on delete set null (variante_id)
);

create index if not exists items_pedido_pedido_idx   on public.items_pedido (sucursal_id, pedido_id);
create index if not exists items_pedido_producto_idx on public.items_pedido (sucursal_id, producto_id);

create table if not exists public.items_salsas (
    sucursal_id    text not null references public.sucursales(id) on delete cascade,
    id             bigint not null,
    item_pedido_id bigint not null,
    nombre         text,
    actualizado_en bigint not null default 0,
    primary key (sucursal_id, id),
    foreign key (sucursal_id, item_pedido_id)
        references public.items_pedido (sucursal_id, id) on delete cascade
);

create index if not exists items_salsas_item_idx
    on public.items_salsas (sucursal_id, item_pedido_id);

create table if not exists public.items_modificadores (
    sucursal_id           text not null references public.sucursales(id) on delete cascade,
    id                    bigint not null,
    item_pedido_id        bigint not null,
    nombre                text,
    precio_extra_centavos bigint not null default 0,
    actualizado_en        bigint not null default 0,
    primary key (sucursal_id, id),
    foreign key (sucursal_id, item_pedido_id)
        references public.items_pedido (sucursal_id, id) on delete cascade
);

create index if not exists items_modificadores_item_idx
    on public.items_modificadores (sucursal_id, item_pedido_id);

create table if not exists public.entregas (
    sucursal_id       text not null references public.sucursales(id) on delete cascade,
    id                bigint not null,
    pedido_id         bigint not null,
    estado            text,
    metodo_pago_final text,
    entregado_en      bigint,
    actualizado_en    bigint not null default 0,
    primary key (sucursal_id, id),
    foreign key (sucursal_id, pedido_id)
        references public.pedidos (sucursal_id, id) on delete cascade
);

create unique index if not exists entregas_pedido_uq
    on public.entregas (sucursal_id, pedido_id);

-- ---------------------------------------------------------------------------
-- Gastos
-- ---------------------------------------------------------------------------
create table if not exists public.gastos_diarios (
    sucursal_id     text not null references public.sucursales(id) on delete cascade,
    id              bigint not null,
    detalle         text,
    monto_centavos  bigint not null default 0,
    fecha           bigint not null default 0,
    creado_en       bigint not null default 0,
    usuario_id      bigint not null default 0,
    origen_offline  boolean not null default false,
    comprado_por    text,
    uuid_offline    text,
    es_adelanto     boolean not null default false,
    actualizado_en  bigint not null default 0,
    primary key (sucursal_id, id)
);

create unique index if not exists gastos_diarios_uuid_offline_uq
    on public.gastos_diarios (sucursal_id, uuid_offline)
    where uuid_offline is not null;

create index if not exists gastos_diarios_fecha_idx
    on public.gastos_diarios (sucursal_id, fecha);

create table if not exists public.gastos_generales (
    sucursal_id    text not null references public.sucursales(id) on delete cascade,
    id             bigint not null,
    nombre         text,
    monto_centavos bigint not null default 0,
    anio           integer not null default 0,
    mes            integer not null default 0,
    recurrente     boolean not null default true,
    usuario_id     bigint not null default 0,
    creado_en      bigint not null default 0,
    uuid_cliente   text,
    actualizado_en bigint not null default 0,
    primary key (sucursal_id, id)
);

create unique index if not exists gastos_generales_uuid_cliente_uq
    on public.gastos_generales (sucursal_id, uuid_cliente)
    where uuid_cliente is not null;

create index if not exists gastos_generales_mes_idx
    on public.gastos_generales (sucursal_id, anio, mes);

-- ---------------------------------------------------------------------------
-- Inventario legacy (se conserva por compatibilidad; no se usa en la app)
-- ---------------------------------------------------------------------------
create table if not exists public.items_variante (
    sucursal_id    text not null references public.sucursales(id) on delete cascade,
    id             bigint not null,
    "varianteId"   bigint not null default 0,
    nombre         text,
    "stockActual"  integer not null default 0,
    "stockInicial" integer not null default 0,
    actualizado_en bigint not null default 0,
    primary key (sucursal_id, id)
);

-- ---------------------------------------------------------------------------
-- Usuarios / dispositivos / seguridad
-- ---------------------------------------------------------------------------
create table if not exists public.usuarios (
    sucursal_id    text not null references public.sucursales(id) on delete cascade,
    id             bigint not null,
    nombre         text,
    pin_hash       text,
    pin_salt       text,
    rol            text,
    activo         boolean not null default true,
    creado_en      bigint not null default 0,
    actualizado_en bigint not null default 0,
    primary key (sucursal_id, id)
);

create table if not exists public.dispositivos (
    sucursal_id    text not null references public.sucursales(id) on delete cascade,
    id             bigint not null,
    "deviceToken"  text,
    "usuarioId"    bigint not null default 0,
    nombre         text not null default '',
    estado         text not null default 'autorizado',
    creado_en      bigint not null default 0,
    ultimo_uso_en  bigint not null default 0,
    actualizado_en bigint not null default 0,
    primary key (sucursal_id, id)
);

create unique index if not exists dispositivos_device_token_uq
    on public.dispositivos (sucursal_id, "deviceToken");

create table if not exists public.tokens_emparejamiento (
    sucursal_id    text not null references public.sucursales(id) on delete cascade,
    id             bigint not null,
    token          text,
    "usuarioId"    bigint not null default 0,
    creado_en      bigint not null default 0,
    expira_en      bigint not null default 0,
    usado          boolean not null default false,
    actualizado_en bigint not null default 0,
    primary key (sucursal_id, id)
);

create unique index if not exists tokens_emparejamiento_token_uq
    on public.tokens_emparejamiento (sucursal_id, token);

create table if not exists public.auditoria (
    sucursal_id    text not null references public.sucursales(id) on delete cascade,
    id             bigint not null,
    usuario_id     bigint not null default 0,
    nombre_usuario text,
    rol            text,
    accion         text,
    detalle        text,
    entidad_tipo   text not null default '',
    entidad_id     bigint not null default 0,
    creado_en      bigint not null default 0,
    actualizado_en bigint not null default 0,
    primary key (sucursal_id, id)
);

create index if not exists auditoria_creado_en_idx
    on public.auditoria (sucursal_id, creado_en);

-- ---------------------------------------------------------------------------
-- Movimientos de inventario
-- ---------------------------------------------------------------------------
create table if not exists public.movimientos_inventario (
    sucursal_id      text not null references public.sucursales(id) on delete cascade,
    id               bigint not null,
    producto_id      bigint,
    variante_id      bigint,
    tipo             text,
    cantidad         integer not null default 0,
    stock_resultante integer not null default 0,
    motivo           text,
    nombre_snapshot  text,
    creado_en        bigint not null default 0,
    usuario_id       bigint not null default 0,
    actualizado_en   bigint not null default 0,
    primary key (sucursal_id, id)
);

create index if not exists movimientos_inventario_creado_idx
    on public.movimientos_inventario (sucursal_id, creado_en);

-- ---------------------------------------------------------------------------
-- Resúmenes mensuales
-- ---------------------------------------------------------------------------
create table if not exists public.resumen_mensual (
    sucursal_id             text not null references public.sucursales(id) on delete cascade,
    id                      bigint not null,
    anio                    integer not null,
    mes                     integer not null,
    ventas_centavos         bigint not null default 0,
    pedidos_pagados         integer not null default 0,
    pedidos_cancelados      integer not null default 0,
    descuentos_centavos     bigint not null default 0,
    efectivo_centavos       bigint not null default 0,
    tarjeta_centavos        bigint not null default 0,
    qr_centavos             bigint not null default 0,
    gastos_diarios_centavos bigint not null default 0,
    gastos_fijos_centavos   bigint not null default 0,
    creado_en               bigint not null default 0,
    actualizado_en          bigint not null default 0,
    primary key (sucursal_id, id)
);

create unique index if not exists resumen_mensual_anio_mes_uq
    on public.resumen_mensual (sucursal_id, anio, mes);
