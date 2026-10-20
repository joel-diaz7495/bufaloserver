package com.example.bufaloserver.db;

import com.example.bufaloserver.db.entities.Categoria;
import com.example.bufaloserver.db.entities.Producto;
import com.example.bufaloserver.db.entities.SalsaGlobal;
import com.example.bufaloserver.db.entities.VarianteProducto;

/**
 * Inserta todos los datos iniciales del menú la PRIMERA vez que se crea la BD
 * del SERVIDOR. Mismo menú que DatabaseSeeder del cliente (com.example.myapplication.db),
 * con los precios actualizados que se confirmaron en el chat — ver comentarios
 * "★ PRECIO NUEVO" en cada ítem que cambió.
 *
 * ★ Lo que NO se mencionó como cambio se dejó IGUAL que en el seeder del
 * cliente (Extras, Pollo Broaster, Salsas por unidad, Fingers 10/12/18/24 und,
 * Agua 600ml) — no se tocó nada de eso a propósito, tal como se pidió.
 *
 * ★ Salsas globales: se fusionaron las dos combinaciones del cliente
 * ("Buffalo / Barbacoa" y "Buffalo / Blue cheese") en un solo ítem
 * ("Buffalo / Barbacoa / Blue cheese"), quedando 10 salsas en vez de 11.
 *
 * Este método corre en un hilo de fondo (via AppDatabaseServidor.dbExecutor).
 */
public class DatabaseSeeder {

    public static void seed(AppDatabaseServidor db) {

        // ── Salsas globales ──────────────────────────────────────────────────
        // ★ CAMBIO — las dos combinaciones que traía el cliente por separado
        // ("Buffalo / Barbacoa" y "Buffalo / Blue cheese") se fusionan en un
        // solo ítem: "Buffalo / Barbacoa / Blue cheese". Albahaca se
        // mantiene sin cambios (ya estaba). Total: 10 salsas (antes 11).
        String[] salsas = {
            "Brava", "Tabasco", "Buffalo", "Barbacoa picante", "Barbacoa",
            "Miel y mostaza", "Albahaca", "Blue cheese", "Ajo parmesano",
            "Buffalo / Barbacoa / Blue cheese"
        };
        for (int i = 0; i < salsas.length; i++) {
            db.salsaGlobalDao().insert(new SalsaGlobal(salsas[i], i));
        }

        // ── Categoría: Pollo ─────────────────────────────────────────────────
        long catPollo = db.categoriaDao().insert(new Categoria("Pollo", "🍗", 0));

        // Fingers — variantes de cantidad + salsas + bañado
        // ★ PRECIO NUEVO: 4/6/8 und. Sin dato de 10/12/18/24 und → se dejan
        // igual que el cliente (10 und Bs47; 12/18/24 "por definir").
        Producto fingers = new Producto();
        fingers.categoriaId  = catPollo;
        fingers.nombre       = "Fingers";
        fingers.esPollo      = true;
        fingers.tieneSalsas  = true;
        fingers.tieneVariantes = true;
        fingers.precioCentavos = 2800; // ★ nuevo precio mínimo para label "Desde Bs 28"
        long fingersId = db.productoDao().insert(fingers);

        insertVariante(db, fingersId, "4 und",  2800, true,  1, 0); // ★ nuevo: antes 2600
        insertVariante(db, fingersId, "6 und",  3100, true,  1, 1); // ★ nuevo: antes 2950
        insertVariante(db, fingersId, "8 und",  4000, true,  2, 2); // ★ nuevo: antes 3700
        insertVariante(db, fingersId, "10 und", 4700, true,  2, 3); // sin cambio
        insertVariante(db, fingersId, "12 und", 0,    false, 3, 4); // por definir, sin cambio
        insertVariante(db, fingersId, "18 und", 0,    false, 4, 5); // por definir, sin cambio
        insertVariante(db, fingersId, "24 und", 0,    false, 6, 6); // por definir, sin cambio

        // Alas — variantes de cantidad + salsas + bañado
        // ★ PRECIO NUEVO en las 7 variantes.
        Producto alas = new Producto();
        alas.categoriaId  = catPollo;
        alas.nombre       = "Alas";
        alas.esPollo      = true;
        alas.tieneSalsas  = true;
        alas.tieneVariantes = true;
        alas.precioCentavos = 2700; // ★ nuevo precio mínimo para label "Desde Bs 27"
        long alasId = db.productoDao().insert(alas);

        insertVariante(db, alasId, "4 und",  2700, true,  1, 0);  // ★ nuevo: antes 2500
        insertVariante(db, alasId, "6 und",  3000, true,  1, 1);  // ★ nuevo: antes 2850
        insertVariante(db, alasId, "8 und",  3800, true,  2, 2);  // ★ nuevo: antes 3600
        insertVariante(db, alasId, "10 und", 4700, true,  2, 3);  // ★ nuevo: antes 4600
        insertVariante(db, alasId, "12 und", 6000, true,  3, 4);  // ★ nuevo: antes 5600
        insertVariante(db, alasId, "18 und", 9100, true,  4, 5);  // ★ nuevo: antes 8000
        insertVariante(db, alasId, "24 und", 11800, true, 6, 6);  // ★ nuevo: antes 11000

        // Pollo Broaster — sin variantes, sin salsas, sin bañado. Sin cambio.
        Producto broaster = new Producto();
        broaster.categoriaId    = catPollo;
        broaster.nombre         = "Pollo Broaster";
        broaster.precioCentavos = 2300;
        db.productoDao().insert(broaster);

        // ── Categoría: Extras ────────────────────────────────────────────────
        // Sin cambios mencionados — igual que el cliente.
        long catExtras = db.categoriaDao().insert(new Categoria("Extras", "🍟", 1));

        Producto papas = new Producto();
        papas.categoriaId    = catExtras;
        papas.nombre         = "Porción de Papas";
        papas.precioCentavos = 1400;
        db.productoDao().insert(papas);

        // Salsas — modal especial, precio por unidad. Confirmado sin cambio (Bs4 c/u).
        Producto salsasItem = new Producto();
        salsasItem.categoriaId    = catExtras;
        salsasItem.nombre         = "Salsas";
        salsasItem.precioCentavos = 400;  // Bs 4 c/u
        salsasItem.esSalsasModal  = true;
        salsasItem.tieneSalsas    = true;
        db.productoDao().insert(salsasItem);

        Producto tocino = new Producto();
        tocino.categoriaId    = catExtras;
        tocino.nombre         = "Tocino";
        tocino.precioCentavos = 500;
        db.productoDao().insert(tocino);

        // ── Categoría: Hamburguesas ──────────────────────────────────────────
        // ★ PRECIO NUEVO en las 6.
        long catHamb = db.categoriaDao().insert(new Categoria("Hamburguesas", "🍔", 2));

        insertProductoSimple(db, catHamb, "Tradicional",    2500); // ★ antes 2300
        insertProductoSimple(db, catHamb, "Búfalo",         3000); // ★ antes 2700
        insertProductoSimple(db, catHamb, "Chesse Extreme", 3200); // ★ antes 2900
        insertProductoSimple(db, catHamb, "Mexicana",       3500); // ★ antes 3100
        insertProductoSimple(db, catHamb, "Salchiburger",   3300); // ★ antes 3200
        insertProductoSimple(db, catHamb, "Interminable",   4000); // ★ antes 3500

        // ── Categoría: Combos ────────────────────────────────────────────────
        // ★ PRECIO NUEVO: Combo Búfalo. Combo Dúo y Combo Dúo Extremo: sin cambio.
        long catCombos = db.categoriaDao().insert(new Categoria("Combos", "🥡", 3));

        Producto cbufalo = new Producto();
        cbufalo.categoriaId    = catCombos;
        cbufalo.nombre         = "Combo Búfalo";
        cbufalo.precioCentavos = 4000; // ★ antes 3600
        cbufalo.esCombo        = true;
        cbufalo.tieneSalsas    = true;
        long cbufaloId = db.productoDao().insert(cbufalo);
        insertVariante(db, cbufaloId, "estándar", 4000, true, 1, 0); // ★ antes 3600

        Producto cduo = new Producto();
        cduo.categoriaId    = catCombos;
        cduo.nombre         = "Combo Dúo";
        cduo.precioCentavos = 8000; // sin cambio
        cduo.esCombo        = true;
        cduo.tieneSalsas    = true;
        long cduoId = db.productoDao().insert(cduo);
        insertVariante(db, cduoId, "estándar", 8000, true, 2, 0);

        Producto cduoEx = new Producto();
        cduoEx.categoriaId    = catCombos;
        cduoEx.nombre         = "Combo Dúo Extremo";
        cduoEx.precioCentavos = 8500; // sin cambio
        cduoEx.esCombo        = true;
        cduoEx.tieneSalsas    = true;
        long cduoExId = db.productoDao().insert(cduoEx);
        insertVariante(db, cduoExId, "estándar", 8500, true, 2, 0);

        // ── Categoría: Salchipapa ────────────────────────────────────────────
        // ★ PRECIO NUEVO en las 4.
        long catSalchi = db.categoriaDao().insert(new Categoria("Salchipapa", "🍟", 4));

        insertProductoSimple(db, catSalchi, "Salchipapa",       2300); // ★ antes 2000
        insertProductoSimple(db, catSalchi, "Pipocas de Pollo", 2500); // ★ antes 2200
        insertProductoSimple(db, catSalchi, "Salchi Carne",     3200); // ★ antes 3000
        insertProductoSimple(db, catSalchi, "Salchi-Extreme",   3600); // sin cambio

        // ── Categoría: Refrescos ─────────────────────────────────────────────
        // ★ PRECIO NUEVO: "personal Bs2.50, popular Bs7" aplicado a las 3
        // marcas (Coca-Cola, Pepsi, Sprite). Agua 600ml: sin dato → se deja
        // igual que el cliente (Bs3).
        long catRef = db.categoriaDao().insert(new Categoria("Refrescos", "🥤", 5));

        insertRefresco(db, catRef, "Coca-Cola 1L",        700); // ★ "popular": antes 800
        insertRefresco(db, catRef, "Coca-Cola Personal",  250); // ★ antes 500
        insertRefresco(db, catRef, "Pepsi 1L",             700); // ★ "popular": antes 700 (sin cambio)
        insertRefresco(db, catRef, "Pepsi Personal",       250); // ★ antes 400
        insertRefresco(db, catRef, "Sprite 1L",            700); // ★ "popular": antes 700 (sin cambio)
        insertRefresco(db, catRef, "Sprite Personal",      250); // ★ antes 400
        insertRefresco(db, catRef, "Agua 600ml",           300); // sin dato → sin cambio
    }

    // ── Helpers privados ──────────────────────────────────────────────────────

    private static void insertVariante(AppDatabaseServidor db, long productoId,
                                       String label, int precioCentavos,
                                       boolean habilitado, int maxSalsas, int orden) {
        db.varianteProductoDao().insert(
            new VarianteProducto(productoId, label, precioCentavos, habilitado, maxSalsas, orden)
        );
    }

    private static void insertProductoSimple(AppDatabaseServidor db, long catId,
                                             String nombre, int precioCentavos) {
        Producto p = new Producto();
        p.categoriaId    = catId;
        p.nombre         = nombre;
        p.precioCentavos = precioCentavos;
        db.productoDao().insert(p);
    }

    private static void insertRefresco(AppDatabaseServidor db, long catId,
                                       String nombre, int precioCentavos) {
        Producto p = new Producto();
        p.categoriaId    = catId;
        p.nombre         = nombre;
        p.precioCentavos = precioCentavos;
        p.esRefresco     = true;
        db.productoDao().insert(p);
    }
}
