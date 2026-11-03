-- ============================================================================
-- Búfalo Server — perfiles, RLS y permisos de lectura
--
-- Modelo de acceso:
--   * La app Android escribe SOLO a través de la Edge Function `sync`,
--     autenticada con token por sucursal y `service_role`. Esa función
--     salta RLS, por eso las tablas no otorgan INSERT/UPDATE/DELETE a
--     `anon` ni a `authenticated`.
--   * Los reportes remotos usan Supabase Auth. Cada usuario tiene una fila
--     en `perfiles` que lo asocia a una sucursal (o a administración
--     central). Las políticas solo permiten SELECT y el filtrado por
--     sucursal lo aplica RLS, incluso a través de las vistas.
-- ============================================================================

create table if not exists public.perfiles (
    user_id     uuid primary key references auth.users (id) on delete cascade,
    sucursal_id text references public.sucursales (id) on delete cascade,
    rol         text not null default 'consulta'
                check (rol in ('admin_central', 'admin_sucursal', 'cajero', 'consulta')),
    nombre      text,
    creado_en   bigint not null default 0
);

create index if not exists perfiles_sucursal_idx
    on public.perfiles (sucursal_id);

-- ---------------------------------------------------------------------------
-- Helpers de autorización (leen `perfiles`, por eso son SECURITY DEFINER).
-- `search_path` fijo para evitar secuestro de objetos.
-- ---------------------------------------------------------------------------
create or replace function public.mi_sucursal()
returns text
language sql
stable
security definer
set search_path = public
as $$
    select sucursal_id
      from public.perfiles
     where user_id = auth.uid()
$$;

create or replace function public.es_admin_central()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1
          from public.perfiles
         where user_id = auth.uid()
           and rol = 'admin_central'
    )
$$;

revoke execute on function public.mi_sucursal() from public, anon;
revoke execute on function public.es_admin_central() from public, anon;
grant execute on function public.mi_sucursal() to authenticated;
grant execute on function public.es_admin_central() to authenticated;

-- ---------------------------------------------------------------------------
-- perfiles: cada usuario ve su perfil; admin central ve todos.
-- ---------------------------------------------------------------------------
alter table public.perfiles enable row level security;
revoke all on public.perfiles from anon, authenticated;
grant select on public.perfiles to authenticated;

drop policy if exists perfiles_select_propio on public.perfiles;
create policy perfiles_select_propio
    on public.perfiles
    for select
    to authenticated
    using (user_id = auth.uid() or public.es_admin_central());

-- ---------------------------------------------------------------------------
-- sucursales: solo la propia, salvo admin central.
-- ---------------------------------------------------------------------------
alter table public.sucursales enable row level security;
revoke all on public.sucursales from anon, authenticated;
grant select on public.sucursales to authenticated;

drop policy if exists sucursales_select_propia on public.sucursales;
create policy sucursales_select_propia
    on public.sucursales
    for select
    to authenticated
    using (id = public.mi_sucursal() or public.es_admin_central());

-- ---------------------------------------------------------------------------
-- Todas las tablas con `sucursal_id`: lectura filtrada por sucursal.
-- (Se excluye `perfiles`, que tiene su propia política.)
-- ---------------------------------------------------------------------------
do $$
declare
    t text;
begin
    for t in
        select c.table_name
          from information_schema.columns c
          join information_schema.tables tb
            on tb.table_schema = c.table_schema
           and tb.table_name = c.table_name
         where c.table_schema = 'public'
           and c.column_name = 'sucursal_id'
           and tb.table_type = 'BASE TABLE'
           and c.table_name <> 'perfiles'
         order by c.table_name
    loop
        execute format('alter table public.%I enable row level security', t);
        execute format('revoke all on public.%I from anon, authenticated', t);
        execute format('grant select on public.%I to authenticated', t);

        execute format('drop policy if exists %I on public.%I',
                       t || '_select_sucursal', t);
        execute format(
            'create policy %I on public.%I for select to authenticated '
            'using (sucursal_id = public.mi_sucursal() or public.es_admin_central())',
            t || '_select_sucursal', t);
    end loop;
end
$$;
