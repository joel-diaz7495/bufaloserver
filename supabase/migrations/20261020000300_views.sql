-- ============================================================================
-- Búfalo Server — vistas de reportes
--
-- `security_invoker = true` hace que las vistas respeten las políticas RLS
-- de las tablas base según el usuario autenticado. Por eso NO se conceden
-- permisos especiales: cada usuario ve solo su sucursal (o todas si es
-- admin central).
--
-- Los días/meses se calculan con la zona horaria configurada en
-- `sucursales.zona_horaria`, no en UTC, para que el corte de caja coincida
-- con el día operativo real.
-- ============================================================================

create or replace view public.v_ventas_diarias
with (security_invoker = true)
as
select
    p.sucursal_id,
    (to_timestamp(p.pagado_en / 1000.0) at time zone s.zona_horaria)::date as dia,
    count(*)                                                               as pedidos_pagados,
    sum(p.total_centavos)                                                   as ventas_centavos,
    sum(p.descuento_centavos)                                               as descuentos_centavos,
    sum(case when p.metodo_pago = 'efectivo' then p.total_centavos
             when p.metodo_pago = 'mixto'    then p.mixto_efectivo_centavos
             else 0 end)                                                   as efectivo_centavos,
    sum(case when p.metodo_pago = 'tarjeta' then p.total_centavos
             when p.metodo_pago = 'mixto'   then p.mixto_tarjeta_centavos
             else 0 end)                                                   as tarjeta_centavos,
    sum(case when p.metodo_pago = 'qr' then p.total_centavos
             when p.metodo_pago = 'mixto' then p.mixto_qr_centavos
             else 0 end)                                                   as qr_centavos
from public.pedidos p
join public.sucursales s on s.id = p.sucursal_id
where p.pagado_en is not null
  and p.cancelado_en is null
group by p.sucursal_id, 2;

create or replace view public.v_ventas_mensuales
with (security_invoker = true)
as
select
    p.sucursal_id,
    date_trunc('month',
               to_timestamp(p.pagado_en / 1000.0) at time zone s.zona_horaria)::date as mes,
    count(*)                                                               as pedidos_pagados,
    sum(p.total_centavos)                                                   as ventas_centavos,
    sum(p.descuento_centavos)                                               as descuentos_centavos,
    sum(case when p.metodo_pago = 'efectivo' then p.total_centavos
             when p.metodo_pago = 'mixto'    then p.mixto_efectivo_centavos
             else 0 end)                                                   as efectivo_centavos,
    sum(case when p.metodo_pago = 'tarjeta' then p.total_centavos
             when p.metodo_pago = 'mixto'   then p.mixto_tarjeta_centavos
             else 0 end)                                                   as tarjeta_centavos,
    sum(case when p.metodo_pago = 'qr' then p.total_centavos
             when p.metodo_pago = 'mixto' then p.mixto_qr_centavos
             else 0 end)                                                   as qr_centavos
from public.pedidos p
join public.sucursales s on s.id = p.sucursal_id
where p.pagado_en is not null
  and p.cancelado_en is null
group by p.sucursal_id, 2;

create or replace view public.v_productos_vendidos
with (security_invoker = true)
as
select
    p.sucursal_id,
    date_trunc('month',
               to_timestamp(p.pagado_en / 1000.0) at time zone s.zona_horaria)::date as mes,
    i.producto_id,
    coalesce(i.nombre_snapshot, pr.nombre, 'Producto ' || i.producto_id) as producto,
    sum(i.cantidad)                                                        as unidades,
    sum(i.total_centavos)                                                  as total_centavos
from public.pedidos p
join public.sucursales s on s.id = p.sucursal_id
join public.items_pedido i
  on i.sucursal_id = p.sucursal_id
 and i.pedido_id = p.id
left join public.productos pr
  on pr.sucursal_id = p.sucursal_id
 and pr.id = i.producto_id
where p.pagado_en is not null
  and p.cancelado_en is null
group by p.sucursal_id, 2, i.producto_id, coalesce(i.nombre_snapshot, pr.nombre, 'Producto ' || i.producto_id);

create or replace view public.v_caja_por_dia
with (security_invoker = true)
as
with ventas as (
    select sucursal_id, dia, ventas_centavos, efectivo_centavos,
           tarjeta_centavos, qr_centavos, pedidos_pagados
      from public.v_ventas_diarias
),
gastos as (
    select g.sucursal_id,
           (to_timestamp(g.fecha / 1000.0) at time zone s.zona_horaria)::date as dia,
           sum(g.monto_centavos) as gastos_centavos
      from public.gastos_diarios g
      join public.sucursales s on s.id = g.sucursal_id
     group by g.sucursal_id, 2
)
select
    v.sucursal_id,
    v.dia,
    v.pedidos_pagados,
    v.ventas_centavos,
    coalesce(g.gastos_centavos, 0)                     as gastos_centavos,
    v.ventas_centavos - coalesce(g.gastos_centavos, 0) as neto_centavos,
    v.efectivo_centavos,
    v.tarjeta_centavos,
    v.qr_centavos
from ventas v
left join gastos g
  on g.sucursal_id = v.sucursal_id
 and g.dia = v.dia;

create or replace view public.v_inventario_bajo
with (security_invoker = true)
as
select
    p.sucursal_id,
    'producto'::text as tipo,
    p.id,
    p.nombre,
    p.stock_actual,
    p.stock_minimo,
    p.stock_maximo
from public.productos p
where p.tiene_inventario = true
  and p.activo = true
  and p.stock_actual <= p.stock_minimo
union all
select
    v.sucursal_id,
    'variante'::text as tipo,
    v.id,
    p.nombre || ' / ' || coalesce(v.label, '') as nombre,
    v.stock_actual,
    v.stock_minimo,
    v.stock_maximo
from public.variantes_producto v
join public.productos p
  on p.sucursal_id = v.sucursal_id
 and p.id = v.producto_id
where v.tiene_inventario = true
  and v.habilitado = true
  and v.stock_actual <= v.stock_minimo;

create or replace view public.v_ultimos_pedidos
with (security_invoker = true)
as
select
    p.sucursal_id,
    p.id,
    p.numero_orden,
    p.nombre_cliente,
    p.tipo,
    p.estado,
    p.metodo_pago,
    p.total_centavos,
    p.creado_en,
    p.pagado_en,
    p.cancelado_en,
    (to_timestamp(p.creado_en / 1000.0) at time zone s.zona_horaria) as creado_local
from public.pedidos p
join public.sucursales s on s.id = p.sucursal_id;

-- Las vistas no heredan los GRANT de las tablas base.
grant select on
    public.v_ventas_diarias,
    public.v_ventas_mensuales,
    public.v_productos_vendidos,
    public.v_caja_por_dia,
    public.v_inventario_bajo,
    public.v_ultimos_pedidos
to authenticated;
