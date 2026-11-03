// ============================================================================
// Búfalo Server — Edge Function `sync`
//
// Recibe lotes de filas desde una sucursal y hace upsert idempotente en
// Postgres. La app Android sigue siendo la fuente de verdad (v1 push-only).
//
// Seguridad:
//   * El cliente envía el token de su sucursal en `x-sync-token`.
//   * Aquí se calcula SHA-256 y se compara con `sucursales.sync_token_hash`.
//   * `service_role` nunca sale del servidor: solo se usa en esta función.
//   * No se permite escribir `sucursales` ni `perfiles`.
//
// Variables de entorno (Supabase > Edge Functions > Secrets):
//   SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY
//   INCLUIR_CREDENCIALES = "true" (opcional, por defecto los PIN no se suben)
// ============================================================================

import { createClient } from "npm:@supabase/supabase-js@2";

const MAX_FILAS_POR_TABLA = 1000;
const MAX_FILAS_TOTAL = 5000;
const MAX_BODY_BYTES = 4_000_000;

// Orden topológico: una tabla nunca se sube antes que sus dependencias.
const TABLAS = [
  "categorias",
  "productos",
  "variantes_producto",
  "salsas_globales",
  "grupos_modificador",
  "modificador_opciones",
  "usuarios",
  "dispositivos",
  "tokens_emparejamiento",
  "pedidos",
  "items_pedido",
  "items_salsas",
  "items_modificadores",
  "entregas",
  "gastos_diarios",
  "gastos_generales",
  "items_variante",
  "movimientos_inventario",
  "auditoria",
  "resumen_mensual",
] as const;

type Tabla = (typeof TABLAS)[number];

const ORDEN: Record<string, number> = Object.fromEntries(
  TABLAS.map((t, i) => [t, i]),
);

// Columnas INTEGER 0/1 en Room que en Postgres son BOOLEAN.
const BOOLEANAS: Record<string, string[]> = {
  categorias: ["activo", "imprimir_en_cocina"],
  productos: [
    "es_pollo",
    "es_combo",
    "es_salsas_modal",
    "es_promo",
    "es_refresco",
    "tiene_salsas",
    "tiene_inventario",
    "tiene_variantes",
    "activo",
  ],
  variantes_producto: ["habilitado", "tiene_caracteristica", "tiene_inventario"],
  salsas_globales: ["activo"],
  grupos_modificador: ["multiSeleccion"],
  pedidos: ["impreso"],
  items_pedido: ["banado", "es_llevar"],
  gastos_diarios: ["origen_offline", "es_adelanto"],
  gastos_generales: ["recurrente"],
  usuarios: ["activo"],
  tokens_emparejamiento: ["usado"],
};

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type, x-sync-token, x-sucursal-id",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" },
  });
}

async function sha256Hex(texto: string): Promise<string> {
  const datos = new TextEncoder().encode(texto);
  const hash = await crypto.subtle.digest("SHA-256", datos);
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function igualesSeguro(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

function aBooleano(valor: unknown): boolean {
  return valor === true || valor === 1 || valor === "1" || valor === "true";
}

function normalizarFila(
  tabla: Tabla,
  fila: Record<string, unknown>,
  sucursalId: string,
  ahora: number,
  incluirCredenciales: boolean,
): Record<string, unknown> {
  const limpia: Record<string, unknown> = { ...fila };

  limpia.sucursal_id = sucursalId;
  limpia.actualizado_en = ahora;

  for (const col of BOOLEANAS[tabla] ?? []) {
    if (col in limpia && limpia[col] !== null) {
      limpia[col] = aBooleano(limpia[col]);
    }
  }

  if (tabla === "usuarios" && !incluirCredenciales) {
    delete limpia.pin_hash;
    delete limpia.pin_salt;
  }

  return limpia;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: CORS });
  }

  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) {
    return json({ error: "Función mal configurada: faltan secretos." }, 500);
  }

  const db = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  if (req.method === "GET") {
    return json({ ok: true, servicio: "bufaloserver-sync", version: 1 });
  }

  if (req.method !== "POST") {
    return json({ error: "Método no permitido." }, 405);
  }

  const token = (req.headers.get("x-sync-token") ?? "").trim();
  if (!token) {
    return json({ error: "Falta x-sync-token." }, 401);
  }

  const texto = await req.text();
  if (!texto || texto.length > MAX_BODY_BYTES) {
    return json({ error: "Cuerpo vacío o demasiado grande." }, 413);
  }

  let cuerpo: {
    sucursal_id?: string;
    lote?: Array<{ tabla?: string; filas?: unknown }>;
  };
  try {
    cuerpo = JSON.parse(texto);
  } catch {
    return json({ error: "JSON inválido." }, 400);
  }

  const solicitada = (cuerpo.sucursal_id ?? "").trim();
  if (!solicitada) {
    return json({ error: "Falta sucursal_id." }, 400);
  }

  const hash = await sha256Hex(token);
  const { data: sucursal, error: errorSucursal } = await db
    .from("sucursales")
    .select("id, activa, sync_token_hash")
    .eq("sync_token_hash", hash)
    .maybeSingle();

  if (errorSucursal) {
    console.error("Error consultando sucursal:", errorSucursal.message);
    return json({ error: "Error interno." }, 500);
  }

  if (
    !sucursal ||
    !sucursal.activa ||
    !igualesSeguro(String(sucursal.sync_token_hash), hash)
  ) {
    return json({ error: "Token de sucursal inválido." }, 401);
  }

  if (sucursal.id !== solicitada) {
    return json({ error: "El token no corresponde a la sucursal." }, 403);
  }

  const lote = Array.isArray(cuerpo.lote) ? cuerpo.lote : [];
  if (lote.length === 0) {
    return json({ ok: true, sucursal_id: sucursal.id, tablas: {} });
  }

  // Validar y ordenar tablas antes de tocar la base.
  const pendientes = new Map<Tabla, Record<string, unknown>[]>();
  let totalFilas = 0;

  for (const entrada of lote) {
    const tabla = entrada?.tabla;
    if (!tabla || !(tabla in ORDEN)) {
      return json({ error: `Tabla no permitida: ${tabla ?? "(vacía)"}.` }, 400);
    }
    if (!Array.isArray(entrada.filas)) {
      return json({ error: `Filas inválidas para ${tabla}.` }, 400);
    }
    if (entrada.filas.length > MAX_FILAS_POR_TABLA) {
      return json({ error: `Demasiadas filas para ${tabla}.` }, 413);
    }

    const filas = pendientes.get(tabla as Tabla) ?? [];
    for (const fila of entrada.filas) {
      if (!fila || typeof fila !== "object" || Array.isArray(fila)) {
        return json({ error: `Fila inválida en ${tabla}.` }, 400);
      }
      const id = (fila as Record<string, unknown>).id;
      if (typeof id !== "number" || !Number.isInteger(id) || id <= 0) {
        return json({ error: `id inválido en ${tabla}.` }, 400);
      }
      filas.push(fila as Record<string, unknown>);
      totalFilas++;
    }
    pendientes.set(tabla as Tabla, filas);
  }

  if (totalFilas > MAX_FILAS_TOTAL) {
    return json({ error: "Lote demasiado grande." }, 413);
  }

  const ahora = Date.now();
  const incluirCredenciales =
    (Deno.env.get("INCLUIR_CREDENCIALES") ?? "false").toLowerCase() === "true";

  const resultado: Record<string, number> = {};

  for (const tabla of TABLAS) {
    const filas = pendientes.get(tabla);
    if (!filas || filas.length === 0) continue;

    // Deduplicar por id: la última versión del lote gana.
    const porId = new Map<number, Record<string, unknown>>();
    for (const fila of filas) {
      porId.set(Number(fila.id), fila);
    }

    const normalizadas = Array.from(porId.values()).map((fila) =>
      normalizarFila(tabla, fila, sucursal.id, ahora, incluirCredenciales),
    );

    const { error } = await db
      .from(tabla)
      .upsert(normalizadas, { onConflict: "sucursal_id,id" });

    if (error) {
      console.error(`Error en ${tabla}:`, error.message);
      return json(
        {
          error: `Error guardando ${tabla}.`,
          detalle: error.message,
          tablas: resultado,
        },
        500,
      );
    }

    resultado[tabla] = normalizadas.length;
  }

  const { error: errorUltimoSync } = await db
    .from("sucursales")
    .update({ ultimo_sync_en: ahora })
    .eq("id", sucursal.id);

  if (errorUltimoSync) {
    console.error("No se pudo actualizar ultimo_sync_en:", errorUltimoSync.message);
  }

  return json({
    ok: true,
    sucursal_id: sucursal.id,
    filas: totalFilas,
    tablas: resultado,
    actualizado_en: ahora,
  });
});
