import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { BarChart, BarraDato } from '../../../../../shared/ui/charts/bar-chart';
import { variacion } from '../../../../../shared/ui/charts/formato';
import { GaugeChart } from '../../../../../shared/ui/charts/gauge-chart';
import { Kpi } from '../../../../../shared/ui/charts/kpi';
import { LineChart } from '../../../../../shared/ui/charts/line-chart';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { PickingBarra } from '../../componentes/barra/barra';
import { PickingDetalle } from '../../componentes/detalle/detalle';
import {
  CONFIG,
  CampoFiltro,
  Grupo,
  OrdenGrupos,
  SEG_OPE,
  SEG_TRAS,
  cuadroMz,
  etiquetaHora,
  nf,
  nomCluster,
  ordenarGrupos,
  pct,
} from '../../data/picking.logica';
import { PickingStore } from '../../data/picking.store';

type MetricaArea = 'avance' | 'solic' | 'asig' | 'emp' | 'pend' | 'nOrdPend' | 'cartPend' | 'pct' | 'pctOrd';
const METRICAS_AREA: Record<MetricaArea, { l: string; v: (g: Grupo) => number; p?: boolean }> = {
  avance: { l: 'Unidades: empacadas y pendientes', v: (g) => g.asig },
  solic: { l: 'Unidades solicitadas', v: (g) => g.solic },
  asig: { l: 'Unidades asignadas', v: (g) => g.asig },
  emp: { l: 'Unidades empacadas', v: (g) => g.emp },
  pend: { l: 'Unidades pendientes de empacar', v: (g) => g.pend },
  nOrdPend: { l: 'Órdenes pendientes', v: (g) => g.nOrdPend },
  cartPend: { l: 'Cartones pendientes', v: (g) => g.cartPend },
  pct: { l: '% avance de unidades', v: (g) => g.pct, p: true },
  pctOrd: { l: '% avance de órdenes', v: (g) => g.pctOrd, p: true },
};

type MetricaEvo = 'cart' | 'und' | 'lin';
const METRICAS_EVO: Record<MetricaEvo, string> = { cart: 'Cartones', und: 'Unidades empacadas', lin: 'Líneas' };

/** Barra apilada (empacado / pendiente) o simple, escalada al mayor del grupo. */
interface FilaBarra {
  g: Grupo;
  etiqueta: string;
  hecho: number;
  pend: number;
  valor: string;
  extra: string;
  activa: boolean;
}

@Component({
  selector: 'app-picking-resumen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, PickingBarra, PickingDetalle, Kpi, GaugeChart, LineChart, BarChart, EmptyState, Icon],
  templateUrl: './resumen.html',
  styleUrl: './resumen.scss',
})
export class PickingResumen {
  readonly store = inject(PickingStore);
  private readonly router = inject(Router);
  readonly nf = nf;
  readonly pct = pct;
  readonly nomCluster = nomCluster;
  readonly mzClusters = CONFIG.mz.clusters;
  readonly metricasArea = Object.entries(METRICAS_AREA).map(([k, m]) => ({ k: k as MetricaArea, l: m.l }));
  readonly metricasEvo = Object.entries(METRICAS_EVO).map(([k, l]) => ({ k: k as MetricaEvo, l }));

  readonly metArea = signal<MetricaArea>('avance');
  readonly tablaArea = signal(false);
  readonly ordenClu = signal<OrdenGrupos>('pend');
  readonly cluAbiertos = signal<ReadonlySet<string>>(new Set());
  readonly rutaDim = signal<'rutaP' | 'rutaF'>('rutaP');
  readonly ordenRuta = signal<OrdenGrupos>('pend');
  readonly metEvo = signal<MetricaEvo>('cart');
  readonly tablaEvo = signal(false);

  constructor() {
    this.store.iniciar();
  }

  readonly kpis = computed(() => {
    const a = this.store.agregado();
    const p = this.store.agregadoPrevio();
    const n = a.ordenes.size;
    const t = a.total;
    return [
      {
        etiqueta: 'Órdenes completadas',
        valor: `${nf(a.estados.completa)} de ${nf(n)}`,
        icono: 'check',
        color: 'var(--success)',
        detalle: `${pct(n ? a.estados.completa / n : 0)} · ${nf(a.estados.en_proceso)} en proceso · ${nf(a.estados.sin_iniciar)} sin iniciar`,
        variacion: p ? variacion(a.estados.completa, p.estados.completa) : null,
      },
      {
        etiqueta: 'Avance de unidades',
        valor: pct(t.pct),
        icono: 'paquete',
        color: 'var(--accent)',
        detalle: `${nf(t.emp)} de ${nf(t.asig)} und. · ${nf(t.cartPend)} cartones pendientes`,
        variacion: p ? variacion(t.pct, p.total.pct) : null,
      },
      {
        etiqueta: 'Avance de líneas',
        valor: pct(t.lin ? t.linDone / t.lin : 0),
        icono: 'logistica',
        color: 'var(--info)',
        detalle: `${nf(t.linDone)} de ${nf(t.lin)} líneas · ${nf(t.lin - t.linDone)} pendientes`,
        variacion: p ? variacion(t.lin ? t.linDone / t.lin : 0, p.total.lin ? p.total.linDone / p.total.lin : 0) : null,
      },
    ];
  });

  /**
   * Bloque principal: avance de unidades (medidor) y cómo se reparte lo
   * solicitado entre empacado, pendiente de empacar y sin asignar. Usa los
   * totales del agregado; solo los presenta.
   */
  readonly hero = computed(() => {
    const t = this.store.agregado().total;
    const base = t.emp + t.pend + t.falt;
    const parte = (valor: number) => (base ? (valor / base) * 100 : 0);
    return {
      porcentaje: Math.round(t.pct * 100),
      color: t.pct >= 0.9 ? 'var(--success)' : t.pct >= 0.5 ? 'var(--accent)' : 'var(--warning)',
      emp: t.emp,
      asig: t.asig,
      pend: t.pend,
      falt: t.falt,
      partes: { emp: parte(t.emp), pend: parte(t.pend), falt: parte(t.falt) },
    };
  });

  /**
   * Lectura ejecutiva: tres frases con lo que ya muestran los indicadores y
   * tablas (avance, dónde está el mayor pendiente y lo que falta asignar). No
   * calcula datos nuevos: solo los resume.
   */
  readonly lecturas = computed(() => {
    const a = this.store.agregado();
    const t = a.total;
    const avance = t.pct;
    const areas = a.dims.area.filter((g) => g.pend > 0).sort((x, y) => y.pend - x.pend);
    const foco = areas[0] ?? null;
    const segmentos = a.dims.segmento;
    const sinAsignar = segmentos.reduce((s, g) => s + g.falt, 0);
    const ope = segmentos.find((g) => g.key === SEG_OPE)?.falt ?? 0;
    const tras = segmentos.find((g) => g.key === SEG_TRAS)?.falt ?? 0;
    return [
      {
        kicker: 'Avance del día',
        icono: avance >= 0.9 ? 'check' : 'tendencia',
        color: avance >= 0.9 ? 'var(--success)' : 'var(--accent)',
        texto: `Va <strong>${pct(avance)}</strong> de lo asignado: ${nf(t.emp)} de ${nf(t.asig)} und. empacadas; quedan ${nf(t.cartPend)} cartones.`,
      },
      {
        kicker: 'Foco',
        icono: foco ? 'area' : 'check',
        color: foco ? 'var(--warning)' : 'var(--success)',
        texto: foco
          ? `Mayor pendiente en <strong>${foco.key || '(sin área)'}</strong>: ${nf(foco.pend)} und. (${pct(t.pend ? foco.pend / t.pend : 0)} de lo pendiente).`
          : 'Sin unidades pendientes de empacar con los filtros actuales.',
      },
      {
        kicker: 'Sin asignar',
        icono: sinAsignar > 0.5 ? 'alertTriangle' : 'check',
        color: sinAsignar > 0.5 ? 'var(--danger)' : 'var(--success)',
        texto:
          sinAsignar > 0.5
            ? `<strong>${nf(sinAsignar)} und.</strong> solicitadas sin asignar: operación ${nf(ope)} · traslados ${nf(tras)}.`
            : 'Todo lo solicitado está asignado.',
      },
    ];
  });

  /** Operación y traslados por separado: solicitado, asignado, empacado y lo que falta. */
  readonly cantidades = computed(() =>
    [SEG_OPE, SEG_TRAS].map((seg) => {
      const g = this.store.agregado().dims.segmento.find((x) => x.key === seg) ?? null;
      const total = g ? g.emp + g.pend + g.falt : 0;
      return {
        seg,
        titulo: seg === SEG_OPE ? 'Operación' : 'Traslados a otros CD (TSC, TLI)',
        g,
        partes: g && total ? { hecho: (g.emp / total) * 100, pend: (g.pend / total) * 100, falta: (g.falt / total) * 100 } : null,
      };
    }),
  );

  /** Cuadro MZ TITAN; el medidor recibe el avance ya redondeado (sin decimales largos). */
  readonly mz = computed(() =>
    cuadroMz(this.store.filas()).map((sec) => ({ ...sec, porcentaje: Math.round(sec.avance * 100) })),
  );

  readonly area = computed(() => {
    const met = METRICAS_AREA[this.metArea()];
    const gs = this.store.agregado().dims.area.slice();
    gs.sort((a, b) => (met.p ? met.v(a) - met.v(b) : met.v(b) - met.v(a)) || a.key.localeCompare(b.key));
    return {
      grupos: gs,
      apilada: this.metArea() === 'avance',
      filas: this.barras('area', gs, this.metArea() === 'avance', met.v, met.p ? pct : nf, !!met.p),
    };
  });

  readonly negocio = computed(() => this.barrasApiladas('negocio'));
  readonly tipo = computed(() => this.barrasApiladas('tipo'));

  readonly clusters = computed(() => {
    const a = this.store.agregado();
    const abiertos = this.cluAbiertos();
    return ordenarGrupos(a.dims.cluster, this.ordenClu(), true).map((g) => ({
      g,
      abierto: abiertos.has(g.key),
      areas: ordenarGrupos(a.cluArea.get(g.key) ?? [], this.ordenClu()),
    }));
  });

  readonly rutas = computed(() => ordenarGrupos(this.store.agregado().dims[this.rutaDim()], this.ordenRuta()));

  /** Por hora del día operativo: acumulado (línea) y por hora (barras). */
  readonly evolucion = computed(() => {
    const a = this.store.agregado();
    const serie = a.evo[this.metEvo()];
    const total = this.metEvo() === 'cart' ? a.total.cart : this.metEvo() === 'und' ? a.total.asig : a.total.lin;
    const corte = this.store.corte();
    const d = this.store.datos();
    const inicio = d ? d.opKey * 864e5 + CONFIG.inicioDiaOperativo * 36e5 : 0;
    const ultima = corte == null ? 23 : Math.max(0, Math.min(23, Math.floor((corte - inicio) / 36e5)));
    const horas = serie.slice(0, ultima + 1);
    const acumulado: number[] = [];
    horas.reduce((s, v) => (acumulado.push(s + v), s + v), 0);
    const etiquetas = horas.map((_, i) => etiquetaHora(i));
    return {
      etiquetas,
      total,
      lograda: acumulado.at(-1) ?? 0,
      linea: [{ nombre: `${METRICAS_EVO[this.metEvo()]} acumulados`, color: 'var(--accent)', valores: acumulado }],
      barras: horas.map<BarraDato>((v, i) => ({ etiqueta: etiquetas[i].slice(0, 2), valor: v, color: 'var(--accent)' })),
      filas: horas.map((_, i) => ({
        hora: `${etiquetas[i]}–${etiquetas[i].slice(0, 2)}:59`,
        cart: a.evo.cart[i],
        und: a.evo.und[i],
        lin: a.evo.lin[i],
        acum: acumulado[i],
        pct: total ? acumulado[i] / total : 0,
      })),
    };
  });

  // ---------------- Acciones ----------------

  filtrado(campo: CampoFiltro, valor: string): boolean {
    return this.store.filtros().campos[campo]?.includes(valor) ?? false;
  }

  /** Clic en un grupo del MZ: filtra por sus áreas (o quita el filtro). */
  filtrarAreas(areas: string[]): void {
    const actual = this.store.filtros().campos.area ?? [];
    const igual = actual.length === areas.length && areas.every((a) => actual.includes(a));
    this.store.setCampo('area', igual ? [] : areas);
  }

  mzSeleccionado(areas: string[]): boolean {
    const actual = this.store.filtros().campos.area ?? [];
    return actual.length === areas.length && areas.every((a) => actual.includes(a));
  }

  alternarCluster(key: string): void {
    this.cluAbiertos.update((s) => {
      const n = new Set(s);
      if (n.has(key)) n.delete(key);
      else n.add(key);
      return n;
    });
  }

  abrirTodos(): void {
    const todos = this.clusters().every((c) => c.abierto);
    this.cluAbiertos.set(todos ? new Set() : new Set(this.clusters().map((c) => c.g.key)));
  }

  filtrarClusterArea(cluster: string, area: string): void {
    const sel = this.filtrado('cluster', cluster) && this.filtrado('area', area);
    this.store.setCampo('cluster', sel ? [] : [cluster]);
    this.store.setCampo('area', sel ? [] : [area]);
  }

  /** «Ver órdenes» / «Ver SKU» desde cantidades: filtra el segmento y abre el módulo con el estado. */
  ver(seg: string, modulo: 'ordenes' | 'sku', estado: string): void {
    this.store.setCampo('segmento', [seg]);
    void this.router.navigate(['/picking', modulo], { queryParams: { estado } });
  }

  // ---------------- Barras ----------------

  private barrasApiladas(dim: 'negocio' | 'tipo'): FilaBarra[] {
    const gs = this.store.agregado().dims[dim].slice().sort((a, b) => b.asig - a.asig);
    return this.barras(dim, gs, true, (g) => g.asig, nf, false);
  }

  private barras(
    dim: CampoFiltro,
    gs: Grupo[],
    apilada: boolean,
    v: (g: Grupo) => number,
    f: (x: number) => string,
    porcentaje: boolean,
  ): FilaBarra[] {
    const escala = porcentaje ? 1 : Math.max(1, ...gs.map(v));
    return gs.map((g) => ({
      g,
      etiqueta: (dim === 'cluster' ? nomCluster(g.key) : g.key) || '(vacío)',
      hecho: ((apilada ? g.emp : v(g)) / escala) * 100,
      pend: apilada ? (g.pend / escala) * 100 : 0,
      valor: apilada ? nf(g.asig) : f(v(g)),
      extra: apilada ? pct(g.pct) : '',
      activa: this.filtrado(dim, g.key),
    }));
  }
}
