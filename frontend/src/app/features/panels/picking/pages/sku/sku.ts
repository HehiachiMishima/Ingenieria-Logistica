import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { PERMISOS } from '../../../../../core/config/permisos';
import { exportarCsv } from '../../../../../core/utils/csv.util';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { Kpi } from '../../../../../shared/ui/charts/kpi';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Paginador } from '../../../../../shared/ui/paginador/paginador';
import { PickingBarra } from '../../componentes/barra/barra';
import { PickingDetalle } from '../../componentes/detalle/detalle';
import { SkuResumen, fechaDia, nf, ordenarPor, pct } from '../../data/picking.logica';
import { PickingStore } from '../../data/picking.store';

type EstadoSku = 'todas' | 'faltante' | 'pendiente' | 'completos';
const ESTADOS: [EstadoSku, string][] = [
  ['todas', 'Todos'],
  ['faltante', 'Con faltante de asignación'],
  ['pendiente', 'Con pendiente de empacar'],
  ['completos', 'Completos'],
];

const COLUMNAS: { k: string; l: string; num?: boolean; v: (s: SkuResumen) => number | string }[] = [
  { k: 'sku', l: 'SKU', v: (s) => s.sku },
  { k: 'producto', l: 'Producto', v: (s) => s.producto },
  { k: 'ean', l: 'EAN', v: (s) => s.ean },
  { k: 'ordenes', l: 'Órdenes', num: true, v: (s) => s.ordenes.size },
  { k: 'cart', l: 'Cartones', num: true, v: (s) => s.cart },
  { k: 'solic', l: 'Und. solicitadas', num: true, v: (s) => s.solic },
  { k: 'asig', l: 'Und. asignadas', num: true, v: (s) => s.asig },
  { k: 'emp', l: 'Und. empacadas', num: true, v: (s) => s.emp },
  { k: 'falt', l: 'No asignadas', num: true, v: (s) => s.falt },
  { k: 'pend', l: 'Pendientes de empacar', num: true, v: (s) => s.pend },
  { k: 'pctA', l: '% asignado', num: true, v: (s) => (s.solic ? s.asig / s.solic : 0) },
  { k: 'pct', l: 'Avance de empaque', num: true, v: (s) => (s.asig ? s.emp / s.asig : 0) },
];

@Component({
  selector: 'app-picking-sku',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PickingBarra, PickingDetalle, Kpi, Paginador, EmptyState, Icon, HasPermiso],
  templateUrl: './sku.html',
  styleUrl: './sku.scss',
})
export class PickingSku {
  readonly store = inject(PickingStore);
  readonly permisos = PERMISOS;
  readonly estados = ESTADOS;
  readonly columnas = COLUMNAS;
  readonly nf = nf;
  readonly pct = pct;
  readonly porPagina = 25;

  readonly estado = signal<EstadoSku>('todas');
  readonly texto = signal('');
  readonly orden = signal<{ k: string; dir: 1 | -1 }>({ k: 'pend', dir: -1 });
  readonly pagina = signal(1);
  private espera?: ReturnType<typeof setTimeout>;

  constructor() {
    this.store.iniciar();
    const estado = inject(ActivatedRoute).snapshot.queryParamMap.get('estado');
    if (ESTADOS.some(([k]) => k === estado)) {
      this.estado.set(estado as EstadoSku);
    }
  }

  readonly cuentas = computed(() => {
    const L = this.store.skus().lista;
    return {
      todas: L.length,
      faltante: L.filter((s) => s.conFalt).length,
      pendiente: L.filter((s) => s.conPend).length,
      completos: L.filter((s) => !s.conFalt && !s.conPend).length,
    } satisfies Record<EstadoSku, number>;
  });

  readonly kpis = computed(() => {
    const { lista, multi } = this.store.skus();
    const a = this.store.agregado();
    const t = a.total;
    const ordenesFalt = [...a.ordenes.values()].filter((o) => o.falt > 0.5).length;
    return [
      { etiqueta: 'SKU del día', valor: nf(lista.length), icono: 'almacen', color: 'var(--accent)',
        detalle: multi.cart ? `más ${nf(multi.cart)} cartones multi-SKU` : 'con los filtros actuales' },
      { etiqueta: 'Unidades solicitadas', valor: nf(t.solic), icono: 'paquete', color: 'var(--info)',
        detalle: 'UND. ORIG., una vez por orden y SKU' },
      { etiqueta: 'Unidades asignadas', valor: nf(t.asig), icono: 'check', color: 'var(--accent)',
        detalle: `${pct(t.solic ? t.asig / t.solic : 0)} de lo solicitado` },
      { etiqueta: 'Unidades empacadas', valor: nf(t.emp), icono: 'check', color: 'var(--success)',
        detalle: `${pct(t.asig ? t.emp / t.asig : 0)} de lo asignado` },
      { etiqueta: 'No asignadas', valor: nf(t.falt), icono: 'alerta', color: 'var(--warning)',
        detalle: `${nf(this.cuentas().faltante)} SKU · ${nf(ordenesFalt)} órdenes` },
      { etiqueta: 'Pendientes de empacar', valor: nf(t.pend), icono: 'reloj', color: 'var(--info)',
        detalle: `${nf(this.cuentas().pendiente)} SKU · ${nf(a.estados.en_proceso + a.estados.sin_iniciar)} órdenes` },
    ];
  });

  readonly lista = computed(() => {
    const e = this.estado();
    const q = this.texto().trim().toLowerCase();
    const col = COLUMNAS.find((c) => c.k === this.orden().k) ?? COLUMNAS[9];
    const filtrados = this.store.skus().lista.filter(
      (s) =>
        (e === 'todas' || (e === 'faltante' ? s.conFalt : e === 'pendiente' ? s.conPend : !s.conFalt && !s.conPend)) &&
        (!q || `${s.sku} ${s.ean} ${s.producto}`.toLowerCase().includes(q)),
    );
    return ordenarPor(filtrados, col.v, this.orden().dir, (s) => s.sku);
  });

  readonly visibles = computed(() => {
    const desde = (this.pagina() - 1) * this.porPagina;
    return this.lista().slice(desde, desde + this.porPagina);
  });

  setEstado(estado: EstadoSku): void {
    this.estado.set(estado);
    this.pagina.set(1);
  }

  buscar(texto: string): void {
    clearTimeout(this.espera);
    this.espera = setTimeout(() => {
      this.texto.set(texto);
      this.pagina.set(1);
    }, 200);
  }

  ordenar(k: string): void {
    const col = COLUMNAS.find((c) => c.k === k)!;
    this.orden.update((o) => (o.k === k ? { k, dir: o.dir === 1 ? -1 : 1 } : { k, dir: col.num ? -1 : 1 }));
    this.pagina.set(1);
  }

  abrir(s: SkuResumen): void {
    this.store.detalle.set({ tipo: 'sku', id: s.sku });
  }

  exportar(): void {
    const d = this.store.datos();
    exportarCsv(
      `skus_picking_${d ? fechaDia(d.opKey).split('/').reverse().join('') : ''}`,
      COLUMNAS.map((c) => c.l),
      this.lista().map((s) => [
        s.sku, s.producto, s.ean, s.ordenes.size, s.cart, Math.round(s.solic), s.asig, s.emp, Math.round(s.falt), s.pend,
        (s.solic ? (s.asig / s.solic) * 100 : 0).toFixed(1), (s.asig ? (s.emp / s.asig) * 100 : 0).toFixed(1),
      ]),
    );
  }
}
