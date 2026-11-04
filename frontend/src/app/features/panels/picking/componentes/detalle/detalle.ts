import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Modal } from '../../../../../shared/ui/modal/modal';
import {
  Carton,
  ESTADO_ORDEN,
  agregar,
  fechaHora,
  nf,
  nomCluster,
  pct,
} from '../../data/picking.logica';
import { PickingStore } from '../../data/picking.store';

const lista = (set: Iterable<string>) => [...set].join(', ') || '—';
const resumen = (m: Map<string, number>) =>
  [...m].sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k} ${nf(v)}`).join(' · ') || '—';

/**
 * Detalle de una orden (sus SKU y cartones) o de un SKU (sus órdenes). Desde
 * un SKU se entra a una orden y se puede volver.
 */
@Component({
  selector: 'app-picking-detalle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Modal],
  templateUrl: './detalle.html',
  styleUrl: './detalle.scss',
})
export class PickingDetalle {
  readonly store = inject(PickingStore);
  readonly nf = nf;
  readonly pct = pct;
  readonly fechaHora = fechaHora;
  readonly estados = ESTADO_ORDEN;

  /** Orden con todos sus cartones del archivo (sin cancelados), aunque haya filtros. */
  readonly orden = computed(() => {
    const d = this.store.detalle();
    if (d?.tipo !== 'orden') {
      return null;
    }
    const cartones = this.store.datos()?.cartones.filter((r) => r.orden === d.id) ?? [];
    const validos = cartones.filter((r) => !r.cancel);
    const o = agregar(validos).ordenes.get(d.id);
    if (!o) {
      return null;
    }
    const skus = new Map<string, { sku: string; ean: string; producto: string; solic: number; asig: number; emp: number; cart: number }>();
    for (const r of validos) {
      if (r.multi) continue;
      let s = skus.get(r.sku);
      if (!s) skus.set(r.sku, (s = { sku: r.sku, ean: r.ean, producto: r.producto, solic: 0, asig: 0, emp: 0, cart: 0 }));
      s.solic += r.solic;
      s.asig += r.asig;
      s.emp += r.emp;
      s.cart++;
    }
    return {
      o,
      cluster: nomCluster(o.cluster),
      olas: lista([...o.olas].sort()),
      areas: [...o.areas].map(([k, v]) => `${k} (${v})`).join(', '),
      estAsig: resumen(o.estAsig),
      estCart: resumen(o.estCart),
      rutaP: lista(o.rutaP),
      rutaF: lista(o.rutaF),
      paradas: lista(o.paradas),
      cargas: lista(o.cargas),
      usuarios: lista(o.usuarios),
      skus: [...skus.values()].sort((a, b) => b.asig - a.asig),
      multi: validos.filter((r) => r.multi).length,
      cartones: validos.slice().sort((a, b) => Number(a.done) - Number(b.done) || a.carton.localeCompare(b.carton)),
      cancelados: cartones.length - validos.length,
    };
  });

  /** SKU con sus órdenes, con los filtros aplicados: primero lo que falta asignar o empacar. */
  readonly sku = computed(() => {
    const d = this.store.detalle();
    if (d?.tipo !== 'sku') {
      return null;
    }
    const filas = this.store.filas().filter((r) => !r.multi && r.sku === d.id);
    if (!filas.length) {
      return null;
    }
    const porOrden = new Map<string, { orden: string; tipo: string; suc: string; sucNom: string; cart: number; cartPend: number; solic: number; asig: number; emp: number; falt: number; pend: number }>();
    for (const r of filas) {
      let o = porOrden.get(r.orden);
      if (!o) {
        porOrden.set(r.orden, (o = { orden: r.orden, tipo: r.tipo, suc: r.suc, sucNom: r.sucNom, cart: 0, cartPend: 0, solic: 0, asig: 0, emp: 0, falt: 0, pend: 0 }));
      }
      o.cart++;
      if (!r.done) o.cartPend++;
      o.solic += r.solic;
      o.asig += r.asig;
      o.emp += r.emp;
      o.falt += r.falt;
    }
    const ordenes = [...porOrden.values()];
    for (const o of ordenes) o.pend = o.asig - o.emp;
    ordenes.sort((a, b) => b.falt + b.pend - (a.falt + a.pend) || a.orden.localeCompare(b.orden));
    const t = ordenes.reduce(
      (a, o) => ({ solic: a.solic + o.solic, asig: a.asig + o.asig, emp: a.emp + o.emp, falt: a.falt + o.falt, pend: a.pend + o.pend }),
      { solic: 0, asig: 0, emp: 0, falt: 0, pend: 0 },
    );
    return { sku: d.id, producto: filas[0].producto, ean: filas[0].ean, ordenes, t };
  });

  readonly titulo = computed(() => {
    const d = this.store.detalle();
    return d ? `${d.tipo === 'orden' ? 'Orden' : 'SKU'} ${d.id}` : '';
  });

  readonly subtitulo = computed(() => {
    const o = this.orden()?.o;
    const s = this.sku();
    return o ? `${o.tipo} · ${o.negocio}` : s ? `${s.producto} · EAN ${s.ean}` : '';
  });

  estadoDe(orden: string) {
    return this.store.agregado().ordenes.get(orden)?.estado ?? null;
  }

  abrirOrden(orden: string): void {
    const sku = this.store.detalle()?.id;
    this.store.detalle.set({ tipo: 'orden', id: orden, volverSku: sku });
  }

  volver(): void {
    const sku = this.store.detalle()?.volverSku;
    this.store.detalle.set(sku ? { tipo: 'sku', id: sku } : null);
  }

  trackCarton(_: number, r: Carton): string {
    return r.carton + r.sku;
  }
}
