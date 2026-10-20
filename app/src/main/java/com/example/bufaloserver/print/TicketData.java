package com.example.bufaloserver.print;

import com.example.bufaloserver.db.entities.ItemPedido;

import java.util.ArrayList;
import java.util.List;

/**
 * Todo lo que EscPosBuilder necesita para armar el ticket, del lado del
 * SERVIDOR.
 *
 * Diferencia clave con el TicketData del cliente (com.example.myapplication.
 * print.TicketData): ese usa List<OrderItem>, el modelo rico en memoria de
 * la pantalla (con sub-órdenes, salsas, modificadores). Acá solo tenemos lo
 * que viaja por el contrato HTTP y ya quedó guardado como ItemPedido
 * (nombreSnapshot, cantidad, precio, bañado, esLlevar, notaCocina) — el
 * detalle de salsas/modificadores/sub-órdenes NO viaja al servidor hoy (no
 * está en el JSON de /pedido), así que el ticket impreso en el servidor es
 * un poco menos detallado que el que imprimía el cliente en modo
 * Independiente. Si más adelante hace falta ese detalle completo, hay que
 * sumarlo primero al contrato (ItemPedido + itemPedidoDesdeJson en
 * ServidorHttp) antes de poder imprimirlo acá.
 */
public class TicketData {

    public int numeroOrden;
    public String cliente;
    public List<ItemPedido> items = new ArrayList<>();

    /** ★ NUEVO — nombre del usuario que tomó/creó el pedido (vendedor), para
     *  que quede impreso en el ticket. Se resuelve en ServidorHttp a partir
     *  de pedido.usuarioCreadorId antes de armar el TicketData. Puede venir
     *  null/vacío en pedidos viejos sin sesión detectada. */
    public String vendedor;

    /**
     * ★ NUEVO — nombre de quien cobró el pedido (cajero), si ya está pagado
     * Y es distinto de quien lo tomó (mesero toma, cajero cobra — ver
     * Pedido.usuarioCobradorId). Se resuelve en ServidorHttp igual que
     * "vendedor", pero solo se llena cuando aplica: null si el pedido
     * todavía no está pagado, o si lo cobró la misma persona que lo tomó
     * (ahí ya alcanza con la línea "Vendio:", repetirla no suma nada).
     */
    public String cajero;

    /** "pendiente" | "pagado" | "cancelado" */
    public String estado;

    /** ★ NUEVO — nombre de quien canceló el pedido (solo aplica cuando
     *  estado="cancelado"), para que quede impreso en la comanda de
     *  cancelación quién lo dio de baja. Ver ServidorHttp.cancelarPedido. */
    public String canceladoPor;

    /** ★ NUEVO — true si el pedido YA ESTABA COBRADO al momento de
     *  cancelarlo (no uno recién enviado a cocina). Cambia el texto del
     *  cartel en EscPosBuilder: para cocina es un aviso distinto ("ya se
     *  cobró, esto es una devolución") que para un pendiente cualquiera. */
    public boolean canceladoEstabaPagado = false;

    /** "efectivo" | "tarjeta" | "qr" | "mixto" | null */
    public String metodoPago;

    public double subtotal;
    public double descuento;
    public double total;

    public double mixtoEfectivo;
    public double mixtoTarjeta;
    public double mixtoQr;

    public long fechaMillis = System.currentTimeMillis();
}