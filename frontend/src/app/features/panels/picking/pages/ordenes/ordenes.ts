import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { PERMISOS } from '../../../../../core/config/permisos';
import { exportarCsv } from '../../../../../core/utils/csv.util';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Kpi } from '../../../../../shared/ui/charts/kpi';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Paginador } from '../../../../../shared/ui/paginador/paginador';
import { PickingBarra } from '../../componentes/barra/barra';
import { PickingDetalle } from '../../componentes/detalle/detalle';
import { ESTADO_ORDEN, Orden, fechaDia, fechaHora, nf, nomCluster, ordenarPor, pct } from '../../data/picking.logica';
import { PickingStore } from '../../data/picking.store';

type EstadoFiltro = 'todas' | 'pendientes' | 'en_proceso' | 'sin_iniciar' | 'completa' | 'faltante';
const ESTADOS: [EstadoFiltro, string][] = [
  ['todas', 'Todas'],
  ['pendientes', 'Pendientes'],
  ['en_proceso', 'En proceso'],
  ['sin_iniciar', 'Sin iniciar'],
  ['completa', 'Completadas'],
  ['faltante', 'Con faltante de asignación'],
];

const primero = (s: Set<string>) => [...s].sort()[0] ?? '';
const todos = (s: Iterable<string>) => [...s].join(' | ');

/** Columnas de la tabla: valor para ordenar y exportar (y texto si difiere). */
const COLUMNAS: { k: string; l: string; num?: boolean; v: (o: Orden) => number | string }[] = [
  { k: 'orden', l: 'Orden', v: (o) => o.orden },
  { k: 'estado', l: 'Estado', v: (o) => ESTADO_ORDEN[o.estado].orden },
  { k: 'tipo', l: 'Tipo de orden', v: (o) => o.tipo },
  { k: 'negocio', l: 'Negocio', v: (o) => o.negocio },
  { k: 'ola', l: 'Ola', v: (o) => primero(o.olas) },
  { k: 'cluster', l: 'Cluster', v: (o) => nomCluster(o.cluster) },
  { k: 'area', l: 'Área', v: (o) => [...o.areas.keys()][0] ?? '' },
  { k: 'cart', l: 'Cartones', num: true, v: (o) => o.cart },
  { k: 'solic', l: 'Und. solicitadas', num: true, v: (o) => o.solic },
  { k: 'falt', l: 'Und. no asignadas', num: true, v: (o) => o.falt },
  { k: 'asig', l: 'Und. asignadas', num: true, v: (o) => o.asig },
  { k: 'emp', l: 'Und. empacadas', num: true, v: (o) => o.emp },
  { k: 'pendU', l: 'Und. pendientes', num: true, v: (o) => o.asig - o.emp },
  { k: 'pct', l: 'Avance', num: true, v: (o) => (o.asig ? o.emp / o.asig : 0) },
  { k: 'ini', l: 'Primer inicio pick', v: (o) => o.ini ?? Infinity },
  { k: 'fin', l: 'Último inicio pick', v: (o) => o.fin ?? -Infinity },
  { k: 'usuarios', l: 'Usuario pick', v: (o) => o.usuarios.size },
  { k: 'suc', l: 'Sucursal destino', v: (o) => o.suc },
  { k: 'rutaP', l: 'Ruta principal', v: (o) => primero(o.rutaP) },
  { k: 'cargas', l: 'Nro. carga', v: (o) => primero(o.cargas) },
];

@Component({
  selector: 'app-picking-ordenes',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PickingBarra, PickingDetalle, Kpi, Paginador, EmptyState, Icon, HasPermiso],
  templateUrl: './ordenes.html',
  styleUrl: './ordenes.scss',
})
export class PickingOrdenes {
  readonly store = inject(PickingStore);
  readonly permisos = PERMISOS;
  readonly estados = ESTADOS;
  readonly estadoOrden = ESTADO_ORDEN;
  readonly columnas = COLUMNAS;
  readonly nf = nf;
  readonly pct = pct;
  readonly fechaHora = fechaHora;
  readonly nomCluster = nomCluster;
  readonly porPagina = 25;

  readonly estado = signal<EstadoFiltro>('todas');
  readonly texto = signal('');
  readonly orden = signal<{ k: string; dir: 1 | -1 }>({ k: 'pendU', dir: -1 });
  readonly pagina = signal(1);
  private espera?: ReturnType<typeof setTimeout>;

  constructor() {
    this.store.iniciar();
    const estado = inject(ActivatedRoute).snapshot.queryParamMap.get('estado');
    if (ESTADOS.some(([k]) => k === estado)) {
      this.estado.set(estado as EstadoFiltro);
    }
  }

  readonly cuentas = computed(() => {
    const a = this.store.agregado();
    const ordenes = [...a.ordenes.values()];
    return {
      todas: ordenes.length,
      pendientes: a.estados.en_proceso + a.estados.sin_iniciar,
      en_proceso: a.estados.en_proceso,
      sin_iniciar: a.estados.sin_iniciar,
      completa: a.estados.completa,
      faltante: ordenes.filter((o) => o.falt > 0.5).length,
    } satisfies Record<EstadoFiltro, number>;
  });

  readonly lista = computed(() => {
    const e = this.estado();
    const q = this.texto().trim().toLowerCase();
    const col = COLUMNAS.find((c) => c.k === this.orden().k) ?? COLUMNAS[12];
    const filtradas = [...this.store.agregado().ordenes.values()].filter(
      (o) =>
        (e === 'todas' ||
          (e === 'pendientes' ? o.estado !== 'completa' : e === 'faltante' ? o.falt > 0.5 : o.estado === e)) &&
        (!q || `${o.orden} ${o.suc} ${o.sucNom} ${todos(o.cargas)}`.toLowerCase().includes(q)),
    );
    return ordenarPor(filtradas, col.v, this.orden().dir, (o) => o.orden);
  });

  /**
   * Indicadores de las órdenes en la vista (filtros, estado y búsqueda): suma lo
   * que ya muestra la tabla, sin calcular datos nuevos.
   */
  readonly kpis = computed(() => {
    const lista = this.lista();
    let asig = 0;
    let emp = 0;
    let falt = 0;
    let cart = 0;
    let cartPend = 0;
    let completas = 0;
    for (const o of lista) {
      asig += o.asig;
      emp += o.emp;
      falt += o.falt;
      cart += o.cart;
      cartPend += o.pend;
      if (o.estado === 'completa') {
        completas++;
      }
    }
    return [
      {
        etiqueta: 'Órdenes en la vista',
        valor: nf(lista.length),
        icono: 'logistica',
        color: 'var(--accent)',
        detalle: `${nf(completas)} completadas · ${nf(lista.length - completas)} abiertas`,
      },
      {
        etiqueta: 'Avance de unidades',
        valor: pct(asig ? emp / asig : 0),
        icono: 'paquete',
        color: 'var(--success)',
        detalle: `${nf(emp)} de ${nf(asig)} und. empacadas`,
      },
      {
        etiqueta: 'Pendiente de empacar',
        valor: `${nf(asig - emp)} und.`,
        icono: 'reloj',
        color: 'var(--warning)',
        detalle: `${nf(cartPend)} de ${nf(cart)} cartones pendientes`,
      },
      {
        etiqueta: 'Sin asignar',
        valor: `${nf(falt)} und.`,
        icono: 'alerta',
        color: falt > 0.5 ? 'var(--danger)' : 'var(--success)',
        detalle: falt > 0.5 ? 'Solicitado que el WMS no asignó' : 'Todo lo solicitado está asignado',
      },
    ];
  });

  readonly visibles = computed(() => {
    const desde = (this.pagina() - 1) * this.porPagina;
    return this.lista().slice(desde, desde + this.porPagina);
  });

  setEstado(estado: EstadoFiltro): void {
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

  abrir(o: Orden): void {
    this.store.detalle.set({ tipo: 'orden', id: o.orden });
  }

  /** Primer valor y cuántos más hay: «OPPN36287 +2». */
  resumen(valores: Set<string>): string {
    const lista = [...valores].sort();
    return lista.length ? lista[0] + (lista.length > 1 ? ` +${lista.length - 1}` : '') : '—';
  }

  /** Área con más cartones de la orden y cuántas más tiene. */
  areaPrincipal(o: Orden): string {
    const areas = [...o.areas].sort((a, b) => b[1] - a[1]);
    return areas.length ? areas[0][0] + (areas.length > 1 ? ` +${areas.length - 1}` : '') : '—';
  }

  exportar(): void {
    const d = this.store.datos();
    exportarCsv(
      `ordenes_picking_${d ? fechaDia(d.opKey).split('/').reverse().join('') : ''}`,
      [...COLUMNAS.map((c) => c.l), 'Datos'],
      this.lista().map((o) => [
        o.orden, ESTADO_ORDEN[o.estado].texto, o.tipo, o.negocio, todos(o.olas), nomCluster(o.cluster),
        todos(o.areas.keys()), o.cart, Math.round(o.solic), Math.round(o.falt), o.asig, o.emp, o.asig - o.emp,
        (o.asig ? (o.emp / o.asig) * 100 : 0).toFixed(1), fechaHora(o.ini), fechaHora(o.fin), todos(o.usuarios),
        `${o.suc} ${o.sucNom}`, todos(o.rutaP), todos(o.cargas), o.problemas.join('; '),
      ]),
    );
  }
}
