import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Injector,
  ViewEncapsulation,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { EMPTY, Observable, expand, forkJoin, map, reduce } from 'rxjs';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import { BarraDato, BarChart } from '../../../../../shared/ui/charts/bar-chart';
import { ComparativoChart, GrupoComparativo } from '../../../../../shared/ui/charts/comparativo-chart';
import { Kpi } from '../../../../../shared/ui/charts/kpi';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { SimulacionApi } from '../../data/simulacion.api';
import {
  CargaSimulacion,
  ConfiguracionSimulacion,
  DashboardSimulacion,
  RutaSimulacion,
} from '../../data/simulacion.model';
import {
  compararNombre,
  etiquetaEstado,
  etiquetaEstadoCluster,
  formatearEntero,
  formatearHoras,
} from '../../simulacion-formato';
import { nivelesDeFila, resumirClusters, tonoSemaforo } from '../configuracion/configuracion.logica';

/** Chutes por tabla: lo que cabe a lo ancho de un A4 apaisado sin achicar el texto. */
const CHUTES_POR_TABLA = 12;
/** Tiempo «sin operarios» del servidor (1E+30): no es un valor real. */
const SIN_OPERARIOS = 1e29;

interface Datos {
  cfg: ConfiguracionSimulacion;
  dash: DashboardSimulacion;
  rutas: RutaSimulacion[];
}

interface ColumnaChute {
  id: number;
  etiqueta: string;
}

interface CeldaTotal {
  valor: number;
  tono: 'bajo' | 'medio' | 'alto' | null;
}

interface Tramo {
  chutes: ColumnaChute[];
  /** filas[f][c]: «código · tipo» de la ruta en la fila f del chute c, o null. */
  filas: ({ texto: string; movida: boolean } | null)[][];
  totales: { etiqueta: string; celdas: CeldaTotal[] }[];
}

interface Cuadricula {
  id: number;
  nombre: string;
  color: string;
  resumen: string;
  tramos: Tramo[];
}

const CONCEPTOS: { clave: keyof Pick<CargaSimulacion, 'bultos' | 'lineas' | 'unidades' | 'cod'> | 'operarios'; etiqueta: string }[] = [
  { clave: 'operarios', etiqueta: 'Total operarios' },
  { clave: 'bultos', etiqueta: 'Total bultos' },
  { clave: 'lineas', etiqueta: 'Total líneas' },
  { clave: 'unidades', etiqueta: 'Total unidades' },
  { clave: 'cod', etiqueta: 'Total locales' },
];

/**
 * Reporte ejecutivo de la simulación para imprimir o guardar como PDF (A4
 * apaisado): indicadores, resultado por ola, los gráficos del dashboard y las
 * cuadrículas de Configuración con sus totales en semáforo. Se abre en su
 * propia pestaña y lanza el diálogo de impresión cuando todo está dibujado.
 */
@Component({
  selector: 'app-simulacion-reporte',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [Icon, Kpi, ComparativoChart, BarChart],
  templateUrl: './reporte.html',
  styleUrl: './reporte.scss',
})
export class SimulacionReporteEjecutivo {
  private readonly api = inject(SimulacionApi);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  /** Para programar afterNextRender cuando llegan los datos (fuera del constructor). */
  private readonly injector = inject(Injector);

  readonly datos = signal<Datos | null>(null);
  readonly error = signal<string | null>(null);
  readonly generado = new Date();
  readonly formatearEntero = formatearEntero;
  readonly formatoHoras = (valor: number): string => formatearHoras(valor);
  readonly formatoEntero = (valor: number): string => formatearEntero(valor);

  constructor() {
    const id = Number(this.route.snapshot.queryParamMap.get('registro'));
    const imprimir = this.route.snapshot.queryParamMap.get('imprimir') !== 'no';
    if (!Number.isInteger(id) || id <= 0) {
      this.error.set('Falta el registro a reportar.');
      return;
    }
    forkJoin({
      cfg: this.api.configuracion(id),
      dash: this.api.dashboard(id),
      rutas: this.todasLasRutas(id),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (datos) => {
          this.datos.set(datos);
          document.title = `Reporte · ${datos.cfg.archivo.nombreRegistro}`;
          if (imprimir) {
            // Tras dibujarse (y terminar la animación de los gráficos), abre el diálogo.
            afterNextRender(() => setTimeout(() => this.imprimir(), 1200), {
              injector: this.injector,
            });
          }
        },
        error: (err) => this.error.set(mensajeDeError(err)),
      });
  }

  imprimir(): void {
    window.print();
  }

  cerrar(): void {
    window.close();
  }

  /** Todas las rutas del registro, por páginas de 1 000 (keyset). */
  private todasLasRutas(id: number): Observable<RutaSimulacion[]> {
    const pagina = (despues: number | null) =>
      this.api.rutas(id, { despues, tamano: 1_000, texto: '', clusterIds: [], chuteIds: [] });
    return pagina(null).pipe(
      expand((p) => (p.siguiente === null ? EMPTY : pagina(p.siguiente))),
      map((p) => p.contenido),
      reduce((todas, lote) => todas.concat(lote), [] as RutaSimulacion[]),
    );
  }

  // ---------------- Encabezado e indicadores ----------------

  readonly estadoRegistro = computed(() => {
    const d = this.datos();
    return d ? etiquetaEstado(d.cfg.archivo.estado) : '';
  });

  readonly finalizado = computed(() => this.datos()?.cfg.archivo.estado === 'FINALIZADO');

  /** Resumen por cluster de la configuración (color, operarios de la ola, estado). */
  private readonly resumen = computed(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    const limites: Record<number, number> = {};
    for (const cluster of d.cfg.clusters) {
      limites[cluster.id] = Number(cluster.limiteHoras) || 0;
    }
    return resumirClusters(d.cfg, Number(d.cfg.velocidadBase) || 0, limites);
  });

  readonly kpis = computed(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    const antes = tiempo(d.dash.kpis.tiempoOriginal);
    const despues = tiempo(d.dash.kpis.tiempoBalanceado);
    const mejora = antes !== null && despues !== null && antes > 0 ? Math.round(((antes - despues) / antes) * 100) : null;
    const medibles = d.dash.clusters.filter((c) => Number(c.limiteHoras) > 0 && tiempo(c.tiempoBalanceado) !== null);
    const cumplen = medibles.filter((c) => !c.excede).length;
    const archivo = d.cfg.archivo;
    return [
      {
        etiqueta: 'Tiempo total',
        valor: despues === null ? '—' : formatearHoras(despues),
        detalle:
          despues === null
            ? 'Se calcula cuando cada ola tiene operarios'
            : `Antes ${antes === null ? '—' : formatearHoras(antes)}`,
        icono: 'reloj',
        color: 'var(--accent)',
      },
      {
        etiqueta: 'Mejora del tiempo',
        valor: mejora === null ? '—' : `${mejora} %`,
        detalle: 'Respecto de la proyección del Excel',
        icono: 'tendencia',
        color: 'var(--success)',
      },
      {
        etiqueta: 'Clusters que cumplen',
        valor: medibles.length === 0 ? '—' : `${cumplen} de ${medibles.length}`,
        detalle: 'Contra su límite de horas',
        icono: 'check',
        color: cumplen === medibles.length ? 'var(--success)' : 'var(--danger)',
      },
      {
        etiqueta: 'Rutas movidas',
        valor: formatearEntero(d.dash.kpis.rutasMovidas),
        detalle: `De ${formatearEntero(archivo.rutas)} rutas`,
        icono: 'flecha',
        color: 'var(--violet)',
      },
      {
        etiqueta: 'Bultos',
        valor: formatearEntero(archivo.bultos),
        detalle: `${formatearEntero(archivo.unidades)} unidades · ${formatearEntero(archivo.lineas)} líneas`,
        icono: 'paquete',
        color: 'var(--warning)',
      },
      {
        etiqueta: 'Chutes',
        valor: formatearEntero(archivo.chutes),
        detalle: `${formatearEntero(archivo.cod)} locales (COD)`,
        icono: 'layers',
        color: 'var(--cyan)',
      },
    ];
  });

  /** Resultado por ola (tabla del resumen). */
  readonly olas = computed(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    const comparativo = new Map(d.dash.clusters.map((c) => [c.id, c]));
    const resumen = new Map(this.resumen().map((r) => [r.id, r]));
    return d.cfg.clusters.map((cluster, i) => {
      const c = comparativo.get(cluster.id);
      const r = resumen.get(cluster.id);
      const carga = d.cfg.carga.filter((fila) => fila.clusterId === cluster.id && fila.rutas > 0);
      const limite = Number(cluster.limiteHoras) || 0;
      const despues = tiempo(c?.tiempoBalanceado ?? null);
      const medible = limite > 0 && despues !== null;
      return {
        ola: i + 1,
        nombre: cluster.nombre,
        color: r?.color ?? 'var(--accent)',
        estado: etiquetaEstadoCluster(cluster.estado),
        operarios: cluster.operariosOla,
        chutes: carga.length,
        rutas: carga.reduce((t, f) => t + f.rutas, 0),
        bultos: carga.reduce((t, f) => t + Number(f.bultos), 0),
        antes: formatearHoras(tiempo(c?.tiempoOriginal ?? null)),
        despues: formatearHoras(despues),
        limite: limite > 0 ? formatearHoras(limite) : '—',
        uso: medible ? Math.ceil((despues / limite) * 100) : null,
        cumple: medible ? !c!.excede : null,
        dispersion: r?.dispersion ?? null,
      };
    });
  });

  readonly conclusiones = computed(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    const frases: string[] = [];
    const antes = tiempo(d.dash.kpis.tiempoOriginal);
    const despues = tiempo(d.dash.kpis.tiempoBalanceado);
    if (antes !== null && despues !== null && antes > 0) {
      frases.push(
        `El tiempo total pasa de ${formatearHoras(antes)} a ${formatearHoras(despues)} (${Math.round(((antes - despues) / antes) * 100)} % de mejora).`,
      );
    }
    for (const c of d.dash.clusters) {
      if (Number(c.limiteHoras) > 0 && c.excede && tiempo(c.tiempoBalanceado) !== null) {
        frases.push(`${c.cluster} excede su límite: ${formatearHoras(tiempo(c.tiempoBalanceado))} de ${formatearHoras(Number(c.limiteHoras))}.`);
      }
    }
    const larga = [...d.dash.olas]
      .filter((o) => tiempo(o.tiempoCierre) !== null)
      .sort((a, b) => (tiempo(b.tiempoCierre) ?? 0) - (tiempo(a.tiempoCierre) ?? 0))[0];
    if (larga) {
      frases.push(`La ola más larga es ${larga.cluster} (${formatearHoras(tiempo(larga.tiempoCierre))}).`);
    }
    frases.push(`${formatearEntero(d.dash.kpis.rutasMovidas)} ruta(s) cambiaron de chute o de cluster respecto del Excel.`);
    if (!this.finalizado()) {
      const cerrados = d.cfg.clusters.filter((c) => c.estado === 'CERRADO').length;
      frases.push(`La simulación aún no termina: ${cerrados} de ${d.cfg.clusters.length} clusters cerrados.`);
    }
    return frases;
  });

  // ---------------- Gráficos (los del dashboard) ----------------

  readonly graficoTiempos = computed<GrupoComparativo[]>(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    return d.dash.clusters.map((c) => {
      const antes = tiempo(c.tiempoOriginal);
      const despues = tiempo(c.tiempoBalanceado);
      return {
        etiqueta: c.cluster,
        antes: antes ?? 0,
        despues: despues ?? 0,
        ...(antes === null ? { textoAntes: 'Sin operarios' } : {}),
        ...(despues === null ? { textoDespues: 'Sin operarios' } : {}),
        ...(Number(c.limiteHoras) > 0 ? { limite: Number(c.limiteHoras) } : {}),
      };
    });
  });

  readonly graficoBultos = computed<GrupoComparativo[]>(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    return [...d.dash.chutes]
      .sort((a, b) => compararNombre(a.chute, b.chute))
      .map((c) => ({ etiqueta: c.chute, antes: Number(c.bultosOriginal), despues: Number(c.bultosBalanceado) }));
  });

  /** Hay al menos una ola con tiempo calculado (si no, el gráfico de tiempos no aporta). */
  readonly hayTiempos = computed(() =>
    this.graficoTiempos().some((g) => !g.textoAntes || !g.textoDespues),
  );

  /**
   * Bultos por chute en gráficos de hasta 15 chutes, parejos (29 → 15 y 14),
   * para que cada barra y su cifra se lean sin pisarse en el ancho de la hoja.
   */
  readonly graficosBultos = computed(() => {
    const grupos = this.graficoBultos();
    if (grupos.length === 0) {
      return [];
    }
    const porGrafico = Math.ceil(grupos.length / Math.ceil(grupos.length / 15));
    const tramos: { grupos: GrupoComparativo[]; ancho: number }[] = [];
    for (let i = 0; i < grupos.length; i += porGrafico) {
      const parte = grupos.slice(i, i + porGrafico);
      // 900 px de barras + el eje: cabe en el ancho útil de un A4 apaisado (≈1 047 px).
      tramos.push({ grupos: parte, ancho: Math.max(44, Math.min(90, Math.floor(900 / parte.length))) });
    }
    return tramos;
  });

  readonly graficoOperarios = computed<BarraDato[]>(() =>
    this.olas()
      .filter((o) => o.operarios !== null)
      .map((o) => ({
        etiqueta: o.nombre,
        valor: o.operarios ?? 0,
        color: o.color,
        detalle: `${o.operarios} operarios · ${o.despues}`,
      })),
  );

  // ---------------- Cuadrículas de Configuración ----------------

  /** Chutes con rutas, en orden natural: las columnas de todas las cuadrículas. */
  private readonly columnas = computed<ColumnaChute[]>(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    const conRutas = new Set(d.cfg.carga.filter((f) => f.rutas > 0).map((f) => f.chuteId));
    d.rutas.forEach((r) => conRutas.add(r.chuteId));
    return d.cfg.chutes
      .filter((chute) => conRutas.has(chute.id))
      .sort((a, b) => compararNombre(a.nombre, b.nombre))
      .map((chute) => ({ id: chute.id, etiqueta: etiquetaChute(chute.nombre) }));
  });

  readonly cuadriculas = computed<Cuadricula[]>(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    const columnas = this.columnas();
    const porCelda = new Map<string, RutaSimulacion[]>();
    for (const ruta of d.rutas) {
      const clave = `${ruta.clusterId}:${ruta.chuteId}`;
      const lista = porCelda.get(clave);
      if (lista) {
        lista.push(ruta);
      } else {
        porCelda.set(clave, [ruta]);
      }
    }
    porCelda.forEach((lista) => lista.sort((a, b) => compararNombre(a.codigoRuta, b.codigoRuta) || a.id - b.id));
    const resumen = new Map(this.resumen().map((r) => [r.id, r]));

    return d.cfg.clusters.map((cluster) => {
      const carga = new Map(
        d.cfg.carga.filter((f) => f.clusterId === cluster.id && f.rutas > 0).map((f) => [f.chuteId, f]),
      );
      const rutas = [...carga.values()].reduce((t, f) => t + f.rutas, 0);
      const bultos = [...carga.values()].reduce((t, f) => t + Number(f.bultos), 0);
      const partes = [
        etiquetaEstadoCluster(cluster.estado),
        `${formatearEntero(rutas)} rutas`,
        `${formatearEntero(bultos)} bultos`,
        ...(cluster.operariosOla ? [`${cluster.operariosOla} operarios`] : []),
        ...(Number(cluster.limiteHoras) > 0 ? [`límite ${formatearHoras(Number(cluster.limiteHoras))}`] : []),
      ];
      return {
        id: cluster.id,
        nombre: cluster.nombre,
        color: resumen.get(cluster.id)?.color ?? 'var(--accent)',
        resumen: partes.join(' · '),
        // En el reporte, cada cluster muestra solo los chutes que usa (sin columnas vacías).
        tramos: tramos(
          columnas.filter((c) => carga.has(c.id) || porCelda.has(`${cluster.id}:${c.id}`)),
          (chute) => porCelda.get(`${cluster.id}:${chute}`) ?? [],
          (chute) => carga.get(chute),
          false,
        ),
      };
    });
  });

  /** Total general: suma de los clusters; los operarios son el máximo de sus olas. */
  readonly totalGeneral = computed<Tramo[]>(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    const general = new Map<number, CargaSimulacion>();
    for (const fila of d.cfg.carga) {
      if (fila.rutas <= 0) {
        continue;
      }
      const previa = general.get(fila.chuteId);
      general.set(
        fila.chuteId,
        previa
          ? {
              ...previa,
              rutas: previa.rutas + fila.rutas,
              bultos: Number(previa.bultos) + Number(fila.bultos),
              unidades: Number(previa.unidades) + Number(fila.unidades),
              lineas: Number(previa.lineas) + Number(fila.lineas),
              cod: Number(previa.cod) + Number(fila.cod),
              operarios: Math.max(previa.operarios ?? 0, fila.operarios ?? 0) || null,
            }
          : { ...fila },
      );
    }
    return tramos(this.columnas(), () => [], (chute) => general.get(chute), true);
  });
}

/** Tiempo del servidor como número de horas, o null si no se puede calcular. */
function tiempo(valor: number | null | undefined): number | null {
  const n = Number(valor);
  return valor === null || valor === undefined || !Number.isFinite(n) || n >= SIN_OPERARIOS ? null : n;
}

function etiquetaChute(nombre: string): string {
  return /^chute\b/i.test(nombre.trim()) ? nombre : `CHUTE ${nombre}`;
}

/** Parte las columnas en tramos que caben a lo ancho de la hoja. */
function tramos(
  columnas: ColumnaChute[],
  rutasDe: (chute: number) => RutaSimulacion[],
  cargaDe: (chute: number) => CargaSimulacion | undefined,
  general: boolean,
): Tramo[] {
  const resultado: Tramo[] = [];
  // Tramos parejos: 29 chutes van en 3 tablas de 10, 10 y 9 (no 12, 12 y 5).
  const porTabla = Math.ceil(columnas.length / Math.max(1, Math.ceil(columnas.length / CHUTES_POR_TABLA)));
  for (let inicio = 0; inicio < columnas.length; inicio += porTabla) {
    const chutes = columnas.slice(inicio, inicio + porTabla);
    const listas = chutes.map((c) => rutasDe(c.id));
    const alto = Math.max(0, ...listas.map((l) => l.length));
    const filas = Array.from({ length: alto }, (_, f) =>
      listas.map((lista) => {
        const ruta = lista[f];
        return ruta
          ? {
              texto: `${ruta.codigoRuta} · ${ruta.tipo}`,
              movida: ruta.chuteId !== ruta.chuteOriginalId || ruta.clusterId !== ruta.clusterOriginalId,
            }
          : null;
      }),
    );
    const totales = CONCEPTOS.map((concepto) => {
      const valores = chutes.map((c) => {
        const carga = cargaDe(c.id);
        if (!carga) {
          return 0;
        }
        const crudo = concepto.clave === 'operarios' ? (carga.operarios ?? 0) : Number(carga[concepto.clave]);
        return Math.ceil(Math.round(crudo * 1e6) / 1e6);
      });
      // El semáforo compara la fila entera (todas las columnas), no solo el tramo.
      return { concepto, valores };
    });
    resultado.push({
      chutes,
      filas,
      totales: totales.map(({ concepto, valores }) => ({
        etiqueta: general && concepto.clave === 'operarios' ? 'Máx. operarios por ola' : concepto.etiqueta,
        celdas: valores.map((valor) => ({ valor, tono: null })),
      })),
    });
  }
  // Semáforo por línea con todos los chutes: se calcula sobre la fila completa.
  CONCEPTOS.forEach((_, fila) => {
    const todos = resultado.flatMap((t) => t.totales[fila].celdas.map((c) => c.valor));
    const niveles = nivelesDeFila(todos);
    let i = 0;
    for (const tramo of resultado) {
      for (const celda of tramo.totales[fila].celdas) {
        celda.tono = tonoSemaforo(niveles[i++]);
      }
    }
  });
  return resultado;
}
