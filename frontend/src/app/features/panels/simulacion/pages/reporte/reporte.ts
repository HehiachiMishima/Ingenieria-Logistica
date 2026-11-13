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
import { NgTemplateOutlet } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { EMPTY, Observable, expand, forkJoin, map, reduce } from 'rxjs';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import { ComparativoChart, GrupoComparativo } from '../../../../../shared/ui/charts/comparativo-chart';
import { Kpi } from '../../../../../shared/ui/charts/kpi';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { SimulacionApi } from '../../data/simulacion.api';
import {
  CargaSimulacion,
  ConfiguracionSimulacion,
  DIAS_BALANCEO,
  DashboardSimulacion,
  DiaBalanceo,
  ETIQUETA_BALANCEO,
  RutaSimulacion,
  rutaEnBalanceo,
} from '../../data/simulacion.model';
import {
  coloresGrupos,
  compararChute,
  compararNombre,
  etiquetaEstado,
  formatearEntero,
  formatearHoras,
} from '../../simulacion-formato';
import { nivelesDeFila, resumirClusters, tonoSemaforo } from '../configuracion/configuracion.logica';
import { documentoHtml, nombreReporteHtml } from './reporte-html';

/** Chutes por tabla de un cluster: lo que cabe junto al panel en un A4 apaisado (~96 px cada uno). */
const CHUTES_POR_TABLA = 9;
/** Chutes por tabla del resumen total: solo cifras (~80 px cada uno, más la columna Total). */
const CHUTES_RESUMEN = 10;
/** Tiempo «sin operarios» del servidor (1E+30): no es un valor real. */
const SIN_OPERARIOS = 1e29;

/** Lo que muestra el reporte: un día o los dos juntos. */
export type VistaReporte = DiaBalanceo | 'AMBOS';

/** Datos de un día: su configuración, su dashboard y sus rutas (las suyas y las diarias). */
interface DatosDia {
  dia: DiaBalanceo;
  cfg: ConfiguracionSimulacion;
  dash: DashboardSimulacion;
  rutas: RutaSimulacion[];
}

interface Datos {
  /** Días con rutas, en orden (L-X-V, M-J-S). */
  dias: DatosDia[];
  /** Todas las rutas del registro, sin repetir: la cuadrícula de «Ambos». */
  todas: RutaSimulacion[];
}

/** Las cinco métricas del comparativo antes vs. después (como el cuadro del dashboard). */
const METRICAS = [
  { id: 'rutas', nombre: 'Rutas', antes: 'rutasOriginal', despues: 'rutasBalanceado' },
  { id: 'bultos', nombre: 'Bultos', antes: 'bultosOriginal', despues: 'bultosBalanceado' },
  { id: 'unidades', nombre: 'Unidades', antes: 'unidadesOriginal', despues: 'unidadesBalanceado' },
  { id: 'lineas', nombre: 'Líneas', antes: 'lineasOriginal', despues: 'lineasBalanceado' },
  { id: 'cod', nombre: 'COD', antes: 'codOriginal', despues: 'codBalanceado' },
] as const;

type ClaveMetrica = (typeof METRICAS)[number]['antes'] | (typeof METRICAS)[number]['despues'];

/** Fila del cuadro ejecutivo: una celda antes/después por métrica, con su variación. */
interface FilaComparativa {
  etiqueta: string;
  color?: string;
  celdas: { antes: number; despues: number; variacion: string; sube: boolean | null }[];
}

interface ColumnaChute {
  id: number;
  /** Nombre del chute (para saber en qué tramo de grupo cae). */
  nombre: string;
  etiqueta: string;
}

/** Frecuencia de la ruta en su pestaña: D (diaria), LXV o MJS. */
type MarcaFrecuencia = 'D' | 'LXV' | 'MJS';

/** Ruta en la cuadrícula (como en Configuración): pestaña del grupo, código, bultos y frecuencia. */
interface CeldaRutaReporte {
  codigo: string;
  bultos: string;
  grupo: string | null;
  color: string | null;
  marca: MarcaFrecuencia;
  movida: boolean;
  titulo: string;
}

/** Concepto de los totales por chute (con su ícono a color, como en Configuración). */
type ClaveConcepto = 'operarios' | 'bultos' | 'lineas' | 'unidades' | 'cod';

interface CeldaTotal {
  valor: number;
  tono: 'bajo' | 'medio' | 'alto' | null;
}

interface FilaTotal {
  clave: ClaveConcepto;
  etiqueta: string;
  celdas: CeldaTotal[];
}

interface Tramo {
  chutes: ColumnaChute[];
  /** filas[f][c]: la ruta en la fila f del chute c, o null. */
  filas: (CeldaRutaReporte | null)[][];
  totales: FilaTotal[];
}

/** Cuadrícula de un cluster en un día: sus chutes, en tramos que caben a lo ancho de la hoja. */
interface Cuadricula {
  id: number;
  tramos: Tramo[];
}

/** Un día de un cluster en la hoja de Configuración. */
interface SeccionConfiguracion {
  dia: DiaBalanceo;
  etiqueta: string;
  ola: OlaReporte | null;
  tramos: Tramo[];
}

interface ClusterConfiguracion {
  id: number;
  nombre: string;
  color: string;
  /** Solo los días en que el cluster tiene rutas (las suyas o las diarias). */
  secciones: SeccionConfiguracion[];
  /** Vistas del filtro en que aparece la tarjeta: sus días y «Ambos». */
  vistas: string;
}

/** Hoja «Resumen total»: los días sumados (las diarias cuentan en cada día que salen). */
interface ResumenTotal {
  indicadores: { clave: ClaveConcepto; etiqueta: string; valor: number }[];
  /** Totales por chute; la columna Total va en el último tramo. */
  tramos: { chutes: ColumnaChute[]; ultimo: boolean; filas: (FilaTotal & { total: number })[] }[];
  /** Clusters con carga en algún día (los que no tienen rutas no se listan). */
  clusters: { id: number; nombre: string; color: string; valores: { valor: number; porcentaje: number }[] }[];
  totales: number[];
}

/** Indicador de la portada (tarjeta). */
interface Indicador {
  etiqueta: string;
  valor: string;
  detalle: string;
  icono: string;
  color: string;
}

/** Fila de «Resultado por ola» de un día. */
interface OlaReporte {
  id: number;
  ola: number;
  nombre: string;
  color: string;
  operarios: number | null;
  chutes: number;
  rutas: number;
  bultos: number;
  establecido: string;
  conseguido: string;
  uso: number | null;
  cumple: boolean | null;
  dispersion: number | null;
}

/** Todo lo que el reporte muestra de un día. */
interface VistaDia {
  dia: DiaBalanceo;
  etiqueta: string;
  rutas: number;
  indicadores: Indicador[];
  olas: OlaReporte[];
  conclusiones: string[];
  graficoCierres: GrupoComparativo[];
  hayCierres: boolean;
  graficoTiempos: GrupoComparativo[];
  hayTiemposAntes: boolean;
  graficoClusters: GrupoComparativo[];
  cuadroClusters: { filas: (FilaComparativa & { entraron: number; salieron: number })[]; total: FilaComparativa | null };
  graficosBultos: { grupos: GrupoComparativo[]; ancho: number }[];
  cuadriculas: Cuadricula[];
}

/** Los dos días lado a lado (sin sumar las diarias dos veces). */
interface VistaAmbos {
  etiquetas: string[];
  rutas: number;
  indicadores: { etiqueta: string; icono: string; color: string; valores: { valor: string; detalle: string }[] }[];
  olas: {
    id: number;
    ola: number;
    nombre: string;
    color: string;
    operarios: number | null;
    porDia: (OlaReporte | null)[];
  }[];
  conclusiones: { etiqueta: string; frases: string[] }[];
}

const CONCEPTOS: { clave: ClaveConcepto; etiqueta: string }[] = [
  { clave: 'operarios', etiqueta: 'Total operarios' },
  { clave: 'bultos', etiqueta: 'Total bultos' },
  { clave: 'lineas', etiqueta: 'Total líneas' },
  { clave: 'unidades', etiqueta: 'Total unidades' },
  { clave: 'cod', etiqueta: 'Total locales' },
];

/** El resumen total suma los días: los operarios no se suman (trabajan los dos días). */
const CONCEPTOS_RESUMEN = CONCEPTOS.filter((c) => c.clave !== 'operarios');

/** Secciones del índice: un ancla por hoja, siempre visible sea cual sea el filtro. */
const SECCIONES = [
  { id: 'resumen', titulo: 'Resumen' },
  { id: 'cierres', titulo: 'Cierre por día' },
  { id: 'comparativos', titulo: 'Comparativos' },
  { id: 'configuracion', titulo: 'Configuración' },
  { id: 'total', titulo: 'Resumen total' },
] as const;

/**
 * Reporte ejecutivo de la simulación (A4 apaisado): indicadores, resultado por ola, los gráficos
 * del dashboard y las cuadrículas de Configuración con sus totales en semáforo. Trae los dos días
 * y un filtro (L-X-V, M-J-S o Ambos) que solo cambia lo que se ve: todo está dibujado y cada
 * bloque dice en qué vistas aparece (data-vistas). Así el mismo DOM sirve para imprimir lo que
 * se filtra y para descargarlo como HTML interactivo (con ?formato=html).
 */
@Component({
  selector: 'app-simulacion-reporte',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [NgTemplateOutlet, Icon, Kpi, ComparativoChart],
  templateUrl: './reporte.html',
  styleUrls: ['./reporte.scss', './reporte-configuracion.scss'],
})
export class SimulacionReporteEjecutivo {
  private readonly api = inject(SimulacionApi);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  /** Para programar afterNextRender cuando llegan los datos (fuera del constructor). */
  private readonly injector = inject(Injector);

  readonly datos = signal<Datos | null>(null);
  /** Vista elegida en el filtro. */
  readonly vista = signal<VistaReporte>('AMBOS');
  readonly etiquetaBalanceo = ETIQUETA_BALANCEO;
  readonly secciones = SECCIONES;
  readonly error = signal<string | null>(null);
  /** Aviso tras descargar el HTML (en esta pestaña ya no queda nada por hacer). */
  readonly descargado = signal(false);
  readonly generado = new Date();
  readonly formatearEntero = formatearEntero;
  readonly metricas = METRICAS;
  /**
   * Horas cortas para las cifras de los gráficos («2h31», «8h», «45m»): caben
   * sobre la barra (34 px). En tablas y textos se usa el formato completo.
   */
  readonly formatoHoras = (valor: number): string => {
    const minutos = Math.round(valor * 60);
    const h = Math.floor(minutos / 60);
    const m = minutos % 60;
    if (h === 0) {
      return `${m}m`;
    }
    return m === 0 ? `${h}h` : `${h}h${String(m).padStart(2, '0')}`;
  };
  readonly formatoEntero = (valor: number): string => formatearEntero(valor);

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    const id = Number(params.get('registro'));
    const formato = params.get('formato') === 'html' ? 'html' : 'pdf';
    const imprimir = formato === 'pdf' && params.get('imprimir') !== 'no';
    // Días con rutas («LXV,MJS»); sin dato, los dos. ?balanceo=LXV (enlaces anteriores) sigue valiendo.
    const dias = DIAS_BALANCEO.filter((dia) =>
      (params.get('dias') ?? DIAS_BALANCEO.join(','))
        .split(',')
        .map(normalizarDia)
        .includes(dia),
    );
    const pedida = normalizarVista(params.get('vista') ?? params.get('balanceo'));
    if (!Number.isInteger(id) || id <= 0) {
      this.error.set('Falta el registro a reportar.');
      return;
    }
    const cargar = dias.length > 0 ? dias : [...DIAS_BALANCEO];
    forkJoin({
      todas: this.todasLasRutas(id),
      porDia: forkJoin(
        cargar.map((dia) =>
          forkJoin({ cfg: this.api.configuracion(id, dia), dash: this.api.dashboard(id, null, dia) }),
        ),
      ),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ todas, porDia }) => {
          const conRutas = porDia
            .map((d, i) => ({ dia: cargar[i], ...d, rutas: todas.filter((r) => rutaEnBalanceo(r.frecuencia, cargar[i])) }))
            .filter((d) => d.rutas.length > 0);
          // Un registro sin rutas en ningún día igual muestra el primero (vacío).
          const diasDatos = conRutas.length > 0 ? conRutas : [{ dia: cargar[0], ...porDia[0], rutas: [] }];
          this.datos.set({ dias: diasDatos, todas });
          this.vista.set(vistaValida(pedida, diasDatos.map((d) => d.dia)));
          document.title = `Reporte · ${diasDatos[0].cfg.archivo.nombreRegistro}`;
          if (formato === 'html' || imprimir) {
            // Tras dibujarse (y terminar la animación de los gráficos), imprime o descarga.
            afterNextRender(
              () => setTimeout(() => (formato === 'html' ? this.descargarHtml() : this.imprimir()), 1200),
              { injector: this.injector },
            );
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

  cambiarVista(vista: VistaReporte): void {
    if (this.vistasDisponibles().includes(vista)) {
      this.vista.set(vista);
    }
  }

  /**
   * Enlace del índice: la URL de esta misma página con el ancla. Así Chrome lo guarda como enlace
   * interno del PDF (un «#id» suelto se resolvería contra la base de la app). En pantalla el clic
   * solo desplaza, sin pasar por el router.
   */
  enlace(id: string): string {
    return `${location.pathname}${location.search}#${id}`;
  }

  irA(evento: Event, id: string): void {
    evento.preventDefault();
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  /**
   * Descarga el reporte como un HTML autocontenido: el DOM ya dibujado (los dos días), los
   * estilos de la página y un script mínimo para el filtro y la impresión.
   */
  descargarHtml(): void {
    const raiz = document.querySelector<HTMLElement>('.rep');
    const d = this.datos();
    if (!raiz || !d) {
      return;
    }
    const nombre = d.dias[0].cfg.archivo.nombreRegistro;
    const html = documentoHtml(raiz, {
      titulo: `Reporte · ${nombre}`,
      vista: this.vistasDisponibles().includes('AMBOS') ? 'AMBOS' : this.vista(),
    });
    const url = URL.createObjectURL(new Blob([html], { type: 'text/html;charset=utf-8' }));
    const enlace = document.createElement('a');
    enlace.href = url;
    enlace.download = nombreReporteHtml(nombre);
    document.body.appendChild(enlace);
    enlace.click();
    enlace.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    this.descargado.set(true);
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

  // ---------------- Filtro ----------------

  /** Días con rutas y, si son dos, «Ambos». */
  readonly vistasDisponibles = computed<VistaReporte[]>(() => {
    const dias = (this.datos()?.dias ?? []).map((d) => d.dia);
    return dias.length > 1 ? [...dias, 'AMBOS'] : dias;
  });

  readonly opciones = computed(() => {
    const disponibles = this.vistasDisponibles();
    return (['LXV', 'MJS', 'AMBOS'] as const).map((v) => ({
      vista: v,
      etiqueta: v === 'AMBOS' ? 'Ambos' : ETIQUETA_BALANCEO[v],
      habilitada: disponibles.includes(v),
      titulo:
        v === 'AMBOS'
          ? disponibles.includes('AMBOS')
            ? 'L-X-V y M-J-S juntos'
            : 'Hace falta que los dos días tengan rutas'
          : disponibles.includes(v)
            ? `Solo ${ETIQUETA_BALANCEO[v]} (sus rutas y las diarias)`
            : `Sin rutas ${ETIQUETA_BALANCEO[v]}`,
    }));
  });

  // ---------------- Encabezado ----------------

  readonly archivo = computed(() => this.datos()?.dias[0].cfg.archivo ?? null);
  readonly base = computed(() => this.datos()?.dias[0].cfg ?? null);

  readonly estadoRegistro = computed(() => {
    const archivo = this.archivo();
    return archivo ? etiquetaEstado(archivo.estado) : '';
  });

  readonly finalizado = computed(() => this.archivo()?.estado === 'FINALIZADO');

  // ---------------- Cada día ----------------

  readonly vistasDia = computed<VistaDia[]>(() =>
    (this.datos()?.dias ?? []).map((d) => armarDia(d, this.finalizado())),
  );

  // ---------------- Ambos ----------------

  readonly ambos = computed<VistaAmbos | null>(() => {
    const d = this.datos();
    const dias = this.vistasDia();
    if (!d || dias.length < 2) {
      return null;
    }
    const indicadores = dias[0].indicadores.map((k, i) => ({
      etiqueta: k.etiqueta,
      icono: k.icono,
      color: k.color,
      valores: dias.map((v) => ({ valor: v.indicadores[i].valor, detalle: v.indicadores[i].detalle })),
    }));
    const olas = dias[0].olas.map((o) => {
      const porDia = dias.map((v) => v.olas.find((x) => x.id === o.id) ?? null);
      const operarios = porDia.map((x) => x?.operarios ?? null).filter((n): n is number => n !== null);
      return {
        id: o.id,
        ola: o.ola,
        nombre: o.nombre,
        color: o.color,
        // La misma cantidad trabaja los dos días: la mayor que se repartió.
        operarios: operarios.length > 0 ? Math.max(...operarios) : null,
        porDia,
      };
    });
    return {
      etiquetas: dias.map((v) => v.etiqueta),
      rutas: d.todas.length,
      indicadores,
      olas,
      conclusiones: dias.map((v) => ({ etiqueta: v.etiqueta, frases: v.conclusiones })),
    };
  });

  // ---------------- Configuración: cada cluster con sus días, uno debajo del otro ----------------

  readonly configuracion = computed<ClusterConfiguracion[]>(() => {
    const d = this.datos();
    const dias = this.vistasDia();
    if (!d || dias.length === 0) {
      return [];
    }
    // Un día sin rutas del cluster no se muestra; un cluster sin rutas en ningún día, tampoco.
    return d.dias[0].cfg.clusters
      .map((cluster) => {
        const secciones = dias
          .map((v) => ({
            dia: v.dia,
            etiqueta: v.etiqueta,
            ola: v.olas.find((o) => o.id === cluster.id && o.rutas > 0) ?? null,
            tramos: v.cuadriculas.find((c) => c.id === cluster.id)?.tramos ?? [],
          }))
          .filter((s) => s.tramos.length > 0);
        return {
          id: cluster.id,
          nombre: cluster.nombre,
          color: dias[0].olas.find((o) => o.id === cluster.id)?.color ?? 'var(--accent)',
          secciones,
          vistas: [...secciones.map((s) => s.dia), 'AMBOS'].join(' '),
        };
      })
      .filter((c) => c.secciones.length > 0);
  });

  // ---------------- Resumen total ----------------

  /** «L-X-V + M-J-S» (o el único día con rutas). */
  readonly etiquetaDias = computed(() => this.vistasDia().map((v) => v.etiqueta).join(' + '));

  readonly resumenTotal = computed<ResumenTotal | null>(() => {
    const d = this.datos();
    return d ? armarResumenTotal(d) : null;
  });
}

// ====================================================================
// Armado de cada día
// ====================================================================

function armarDia(d: DatosDia, finalizado: boolean): VistaDia {
  const etiqueta = ETIQUETA_BALANCEO[d.dia];
  const limites: Record<number, number> = {};
  for (const cluster of d.cfg.clusters) {
    limites[cluster.id] = Number(cluster.limiteHoras) || 0;
  }
  const resumen = resumirClusters(d.cfg, Number(d.cfg.velocidadBase) || 0, limites);
  const colores = new Map(resumen.map((r) => [r.id, r.color]));
  const totales = totalesRutas(d.rutas);

  // Cierre establecido (límite) frente al conseguido de las olas que tienen ambos.
  let establecido = 0;
  let conseguido = 0;
  let olasMedibles = 0;
  for (const c of d.dash.clusters) {
    const limite = Number(c.limiteHoras) || 0;
    const real = tiempo(c.tiempoBalanceado);
    if (limite > 0 && real !== null) {
      establecido += limite;
      conseguido += real;
      olasMedibles++;
    }
  }
  // Positivo: se cerró antes de lo establecido; negativo: se pasó.
  const mejora =
    olasMedibles > 0 && establecido > 0 ? Math.round(((establecido - conseguido) / establecido) * 100) : null;
  const medibles = d.dash.clusters.filter((c) => Number(c.limiteHoras) > 0 && tiempo(c.tiempoBalanceado) !== null);
  const cumplen = medibles.filter((c) => !c.excede).length;

  const indicadores: Indicador[] = [
    {
      etiqueta: 'Cierre conseguido',
      valor: olasMedibles === 0 ? '—' : formatearHoras(conseguido),
      detalle:
        olasMedibles === 0
          ? 'Se calcula cuando cada ola tiene operarios'
          : `Establecido ${formatearHoras(establecido)} · ${olasMedibles} ola(s)`,
      icono: 'reloj',
      color: 'var(--accent)',
    },
    {
      etiqueta: 'Mejora del tiempo',
      valor: mejora === null ? '—' : `${mejora > 0 ? '+' : ''}${mejora} %`,
      detalle:
        mejora === null
          ? 'Cierre conseguido frente al establecido'
          : mejora >= 0
            ? 'Cierre conseguido por debajo del establecido'
            : 'Cierre conseguido por encima del establecido',
      icono: 'tendencia',
      color: mejora !== null && mejora < 0 ? 'var(--danger)' : 'var(--success)',
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
      detalle: `De ${formatearEntero(totales.rutas)} rutas de ${etiqueta}`,
      icono: 'flecha',
      color: 'var(--violet)',
    },
    {
      etiqueta: 'Bultos',
      valor: formatearEntero(totales.bultos),
      detalle: `${formatearEntero(totales.unidades)} unidades · ${formatearEntero(totales.lineas)} líneas`,
      icono: 'paquete',
      color: 'var(--warning)',
    },
    {
      etiqueta: 'Chutes',
      valor: formatearEntero(d.cfg.archivo.chutes),
      detalle: `${formatearEntero(totales.cod)} locales (COD)`,
      icono: 'layers',
      color: 'var(--cyan)',
    },
  ];

  const comparativo = new Map(d.dash.clusters.map((c) => [c.id, c]));
  const porId = new Map(resumen.map((r) => [r.id, r]));
  const olas: OlaReporte[] = d.cfg.clusters.map((cluster, i) => {
    const c = comparativo.get(cluster.id);
    const carga = d.cfg.carga.filter((fila) => fila.clusterId === cluster.id && fila.rutas > 0);
    const limite = Number(cluster.limiteHoras) || 0;
    const despues = tiempo(c?.tiempoBalanceado ?? null);
    const medible = limite > 0 && despues !== null;
    return {
      id: cluster.id,
      ola: i + 1,
      nombre: cluster.nombre,
      color: colores.get(cluster.id) ?? 'var(--accent)',
      operarios: cluster.operariosOla,
      chutes: carga.length,
      rutas: carga.reduce((t, f) => t + f.rutas, 0),
      bultos: carga.reduce((t, f) => t + Number(f.bultos), 0),
      /** Cierre establecido (límite de horas) y conseguido (tiempo de la ola balanceada). */
      establecido: limite > 0 ? formatearHoras(limite) : '—',
      conseguido: despues === null ? '—' : formatearHoras(despues),
      uso: medible ? Math.ceil((despues / limite) * 100) : null,
      cumple: medible ? !c!.excede : null,
      dispersion: porId.get(cluster.id)?.dispersion ?? null,
    };
  });

  const conclusiones: string[] = [];
  if (mejora !== null) {
    conclusiones.push(
      `Cierre conseguido de ${formatearHoras(conseguido)} frente a ${formatearHoras(establecido)} establecido` +
        ` (${mejora >= 0 ? mejora + ' % por debajo' : -mejora + ' % por encima'}; ${olasMedibles} ola(s) con límite y operarios).`,
    );
  }
  const antes = tiempo(d.dash.kpis.tiempoOriginal);
  const despues = tiempo(d.dash.kpis.tiempoBalanceado);
  if (antes !== null && despues !== null && antes > 0) {
    conclusiones.push(`Frente a la proyección del Excel, el tiempo pasa de ${formatearHoras(antes)} a ${formatearHoras(despues)}.`);
  }
  for (const c of d.dash.clusters) {
    if (Number(c.limiteHoras) > 0 && c.excede && tiempo(c.tiempoBalanceado) !== null) {
      conclusiones.push(`${c.cluster} excede su límite: ${formatearHoras(tiempo(c.tiempoBalanceado))} de ${formatearHoras(Number(c.limiteHoras))}.`);
    }
  }
  const larga = [...d.dash.olas]
    .filter((o) => tiempo(o.tiempoCierre) !== null)
    .sort((a, b) => (tiempo(b.tiempoCierre) ?? 0) - (tiempo(a.tiempoCierre) ?? 0))[0];
  if (larga) {
    conclusiones.push(`La ola más larga es ${larga.cluster} (${formatearHoras(tiempo(larga.tiempoCierre))}).`);
  }
  conclusiones.push(`${formatearEntero(d.dash.kpis.rutasMovidas)} ruta(s) cambiaron de chute o de cluster respecto del Excel.`);
  if (!finalizado) {
    const cerrados = d.cfg.clusters.filter((c) => c.estado === 'CERRADO').length;
    conclusiones.push(`La simulación aún no termina: ${cerrados} de ${d.cfg.clusters.length} clusters cerrados.`);
  }

  const graficoTiempos = d.dash.clusters.map((c) => {
    const a = tiempo(c.tiempoOriginal);
    const b = tiempo(c.tiempoBalanceado);
    return {
      etiqueta: c.cluster,
      antes: a ?? 0,
      despues: b ?? 0,
      ...(a === null ? { textoAntes: 'Sin operarios' } : {}),
      ...(b === null ? { textoDespues: 'Sin operarios' } : {}),
      ...(Number(c.limiteHoras) > 0 ? { limite: Number(c.limiteHoras) } : {}),
    };
  });
  const graficoCierres = d.dash.clusters.map((c) => {
    const limite = Number(c.limiteHoras) || 0;
    const real = tiempo(c.tiempoBalanceado);
    return {
      etiqueta: c.cluster,
      antes: limite,
      despues: real ?? 0,
      ...(limite > 0 ? {} : { textoAntes: 'Sin límite' }),
      ...(real === null ? { textoDespues: 'Sin operarios' } : {}),
    };
  });
  const filasClusters = d.dash.clusters.map((c) => ({
    etiqueta: c.cluster,
    color: colores.get(c.id),
    celdas: celdas(c as unknown as Record<ClaveMetrica, number>),
    entraron: c.rutasEntraron,
    salieron: c.rutasSalieron,
  }));

  return {
    dia: d.dia,
    etiqueta,
    rutas: totales.rutas,
    indicadores,
    olas,
    conclusiones,
    graficoCierres,
    // Hay al menos una ola con límite o con cierre conseguido.
    hayCierres: graficoCierres.some((g) => !g.textoAntes || !g.textoDespues),
    graficoTiempos,
    // Sin dotación base en el Excel, el «antes» es el cierre establecido (ya graficado).
    hayTiemposAntes: graficoTiempos.some((g) => !g.textoAntes),
    graficoClusters: d.dash.clusters.map((c) => ({
      etiqueta: c.cluster,
      antes: Number(c.bultosOriginal),
      despues: Number(c.bultosBalanceado),
    })),
    cuadroClusters: { filas: filasClusters, total: totalDe(filasClusters) },
    graficosBultos: partirGrafico(
      [...d.dash.chutes]
        .sort((a, b) => compararChute(a.chute, b.chute))
        .map((c) => ({ etiqueta: c.chute, antes: Number(c.bultosOriginal), despues: Number(c.bultosBalanceado) })),
    ),
    cuadriculas: cuadriculasDia(d),
  };
}

/** Totales de las rutas (de un día: las suyas y las diarias). */
function totalesRutas(rutas: RutaSimulacion[]) {
  return rutas.reduce(
    (t, ruta) => ({
      rutas: t.rutas + 1,
      bultos: t.bultos + Number(ruta.totalBultos),
      unidades: t.unidades + Number(ruta.totalUnidades),
      lineas: t.lineas + Number(ruta.totalLineas),
      cod: t.cod + Number(ruta.conteoCod),
    }),
    { rutas: 0, bultos: 0, unidades: 0, lineas: 0, cod: 0 },
  );
}

// ====================================================================
// Cuadrículas de Configuración
// ====================================================================

/** Rutas agrupadas por (cluster, chute), ordenadas por código. */
function rutasPorCelda(rutas: RutaSimulacion[]): Map<string, RutaSimulacion[]> {
  const porCelda = new Map<string, RutaSimulacion[]>();
  for (const ruta of rutas) {
    const clave = `${ruta.clusterId}:${ruta.chuteId}`;
    const lista = porCelda.get(clave);
    if (lista) {
      lista.push(ruta);
    } else {
      porCelda.set(clave, [ruta]);
    }
  }
  porCelda.forEach((lista) => lista.sort((a, b) => compararNombre(a.codigoRuta, b.codigoRuta) || a.id - b.id));
  return porCelda;
}

/** Chutes con rutas (o carga), en orden natural: las columnas de todas las cuadrículas. */
function columnasDe(cfg: ConfiguracionSimulacion, cargas: CargaSimulacion[][], rutas: RutaSimulacion[]): ColumnaChute[] {
  const conRutas = new Set<number>();
  cargas.forEach((carga) => carga.filter((f) => f.rutas > 0).forEach((f) => conRutas.add(f.chuteId)));
  rutas.forEach((r) => conRutas.add(r.chuteId));
  return cfg.chutes
    .filter((chute) => conRutas.has(chute.id))
    .sort((a, b) => compararChute(a.nombre, b.nombre))
    .map((chute) => ({ id: chute.id, nombre: chute.nombre, etiqueta: etiquetaChute(chute.nombre) }));
}

/** Carga de un cluster por chute (filas con rutas). */
function cargaCluster(cfg: ConfiguracionSimulacion, clusterId: number): Map<number, CargaSimulacion> {
  return new Map(cfg.carga.filter((f) => f.clusterId === clusterId && f.rutas > 0).map((f) => [f.chuteId, f]));
}

/** Total general: suma de los clusters; los operarios son el máximo de sus olas. */
function cargaGeneral(cfg: ConfiguracionSimulacion): Map<number, CargaSimulacion> {
  const general = new Map<number, CargaSimulacion>();
  for (const fila of cfg.carga) {
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
  return general;
}

/** Cuadrículas de un día: cada cluster con sus rutas (las suyas y las diarias) y su carga por chute. */
function cuadriculasDia(d: DatosDia): Cuadricula[] {
  const columnas = columnasDe(d.cfg, [d.cfg.carga], d.rutas);
  const porCelda = rutasPorCelda(d.rutas);
  return d.cfg.clusters.map((cluster) => {
    const carga = cargaCluster(d.cfg, cluster.id);
    const grupos = cluster.grupos ?? [];
    return {
      id: cluster.id,
      // Solo los chutes que el cluster usa ese día (sin columnas vacías).
      tramos: tramos(
        columnas.filter((c) => carga.has(c.id) || porCelda.has(`${cluster.id}:${c.id}`)),
        (chute) => porCelda.get(`${cluster.id}:${chute}`) ?? [],
        (chute) => carga.get(chute),
        grupos.length > 0 ? coloresGrupos(grupos.map((g) => g.nombre)) : null,
      ),
    };
  });
}

/**
 * Resumen total: los días sumados, por chute y por cluster. Cada día suma sus rutas y las
 * diarias, así que una diaria cuenta en los dos días (es el volumen que se trabaja).
 */
function armarResumenTotal(d: Datos): ResumenTotal {
  const base = d.dias[0].cfg;
  const limites: Record<number, number> = {};
  base.clusters.forEach((c) => (limites[c.id] = Number(c.limiteHoras) || 0));
  const colores = new Map(resumirClusters(base, Number(base.velocidadBase) || 0, limites).map((r) => [r.id, r.color]));
  const generales = d.dias.map((x) => cargaGeneral(x.cfg));
  const columnas = columnasDe(base, d.dias.map((x) => x.cfg.carga), []);
  const sumaChute = (chute: number, clave: ClaveConcepto) =>
    generales.reduce((t, g) => t + Number(g.get(chute)?.[clave] ?? 0), 0);

  const filas = CONCEPTOS_RESUMEN.map((concepto) => {
    const crudos = columnas.map((c) => sumaChute(c.id, concepto.clave));
    const valores = crudos.map(redondear);
    const niveles = nivelesDeFila(valores);
    return { ...concepto, valores, tonos: niveles.map(tonoSemaforo), total: redondear(crudos.reduce((a, b) => a + b, 0)) };
  });
  const partes = partir(columnas, CHUTES_RESUMEN);
  let inicio = 0;
  const tramosResumen = partes.map((chutes, i) => {
    const desde = inicio;
    inicio += chutes.length;
    return {
      chutes,
      ultimo: i === partes.length - 1,
      filas: filas.map((f) => ({
        clave: f.clave,
        etiqueta: f.etiqueta,
        total: f.total,
        celdas: chutes.map((_, j) => ({ valor: f.valores[desde + j], tono: f.tonos[desde + j] })),
      })),
    };
  });

  const totales = filas.map((f) => f.total);
  const clusters = base.clusters.map((cluster) => {
    const valores = CONCEPTOS_RESUMEN.map((concepto, i) => {
      const crudo = d.dias.reduce(
        (t, x) =>
          t + x.cfg.carga.filter((f) => f.clusterId === cluster.id && f.rutas > 0).reduce((s, f) => s + Number(f[concepto.clave] ?? 0), 0),
        0,
      );
      const valor = redondear(crudo);
      return { valor, porcentaje: totales[i] > 0 ? Math.round((valor / totales[i]) * 100) : 0 };
    });
    return { id: cluster.id, nombre: cluster.nombre, color: colores.get(cluster.id) ?? 'var(--accent)', valores };
  }).filter((c) => c.valores.some((v) => v.valor > 0));

  return {
    indicadores: filas.map((f) => ({ clave: f.clave, etiqueta: f.etiqueta, valor: f.total })),
    tramos: tramosResumen,
    clusters,
    totales,
  };
}

// ====================================================================
// Utilidades
// ====================================================================

/** «LXV», «l-x-v», «MJS»… → el día, o null. */
function normalizarDia(valor: string | null | undefined): DiaBalanceo | null {
  const limpio = (valor ?? '').toUpperCase().replace(/-/g, '').trim();
  return limpio === 'LXV' || limpio === 'MJS' ? limpio : null;
}

function normalizarVista(valor: string | null | undefined): VistaReporte | null {
  return (valor ?? '').toUpperCase() === 'AMBOS' ? 'AMBOS' : normalizarDia(valor);
}

/** La vista pedida si hay datos para ella; si no, Ambos (con los dos días) o el único día. */
function vistaValida(pedida: VistaReporte | null, dias: DiaBalanceo[]): VistaReporte {
  if (pedida === 'AMBOS') {
    return dias.length > 1 ? 'AMBOS' : dias[0];
  }
  if (pedida && dias.includes(pedida)) {
    return pedida;
  }
  return dias.length > 1 ? 'AMBOS' : dias[0];
}

/** Tiempo del servidor como número de horas, o null si no se puede calcular. */
function tiempo(valor: number | null | undefined): number | null {
  const n = Number(valor);
  return valor === null || valor === undefined || !Number.isFinite(n) || n >= SIN_OPERARIOS ? null : n;
}

/**
 * Parte un gráfico de chutes en gráficos de hasta 15, parejos (29 → 15 y 14).
 * Cada chute tiene entre 56 y 110 px: sus dos cifras se leen sin pisarse y,
 * con 900 px de barras más el eje, cabe en el ancho útil de un A4 apaisado.
 */
function partirGrafico(grupos: GrupoComparativo[]): { grupos: GrupoComparativo[]; ancho: number }[] {
  if (grupos.length === 0) {
    return [];
  }
  const porGrafico = Math.ceil(grupos.length / Math.ceil(grupos.length / 15));
  const partes: { grupos: GrupoComparativo[]; ancho: number }[] = [];
  for (let i = 0; i < grupos.length; i += porGrafico) {
    const parte = grupos.slice(i, i + porGrafico);
    partes.push({ grupos: parte, ancho: Math.max(56, Math.min(110, Math.floor(900 / parte.length))) });
  }
  return partes;
}

/** «+12 %», «−5 %», «0 %», «nuevo» o «—» (como el cuadro del dashboard). */
function variacion(antes: number, despues: number): { texto: string; sube: boolean | null } {
  if (antes <= 0) {
    return despues > 0 ? { texto: 'nuevo', sube: true } : { texto: '—', sube: null };
  }
  const relativa = Math.round(((despues - antes) / antes) * 100);
  if (relativa === 0) {
    return { texto: '0 %', sube: null };
  }
  return { texto: `${relativa > 0 ? '+' : '−'}${Math.abs(relativa)} %`, sube: relativa > 0 };
}

/** Celdas antes/después de las cinco métricas de un cluster o chute. */
function celdas(fila: Record<ClaveMetrica, number>): FilaComparativa['celdas'] {
  return METRICAS.map((m) => {
    const antes = Number(fila[m.antes]) || 0;
    const despues = Number(fila[m.despues]) || 0;
    const v = variacion(antes, despues);
    return { antes, despues, variacion: v.texto, sube: v.sube };
  });
}

/** Fila «Total» del cuadro: suma de cada métrica. */
function totalDe(filas: FilaComparativa[]): FilaComparativa | null {
  if (filas.length === 0) {
    return null;
  }
  return {
    etiqueta: 'Total',
    celdas: METRICAS.map((_, i) => {
      const antes = filas.reduce((t, f) => t + f.celdas[i].antes, 0);
      const despues = filas.reduce((t, f) => t + f.celdas[i].despues, 0);
      const v = variacion(antes, despues);
      return { antes, despues, variacion: v.texto, sube: v.sube };
    }),
  };
}

function etiquetaChute(nombre: string): string {
  return /^chute\b/i.test(nombre.trim()) ? nombre : `CHUTE ${nombre}`;
}

/** Frecuencia de la ruta: D si es diaria (o no tiene), si no L-X-V o M-J-S. */
function marcaDe(frecuencia: string | null | undefined): MarcaFrecuencia {
  if (!frecuencia || frecuencia === 'DIARIA') {
    return 'D';
  }
  return frecuencia === 'MJS' ? 'MJS' : 'LXV';
}

/** Cantidad del servidor redondeada hacia arriba, sin arrastrar el error de los decimales. */
function redondear(valor: number): number {
  return Math.ceil(Math.round(valor * 1e6) / 1e6);
}

/** Parte la lista en tramos parejos de hasta `maximo` (29 con 12 → 10, 10 y 9; no 12, 12 y 5). */
function partir<T>(lista: T[], maximo: number): T[][] {
  const porTramo = Math.ceil(lista.length / Math.max(1, Math.ceil(lista.length / maximo)));
  const partes: T[][] = [];
  for (let i = 0; i < lista.length; i += porTramo) {
    partes.push(lista.slice(i, i + porTramo));
  }
  return partes;
}

/**
 * Parte las columnas de un cluster en tramos que caben junto a su panel. Cada tramo lleva sus
 * rutas y los totales por chute; el semáforo se calcula con la fila completa.
 */
function tramos(
  columnas: ColumnaChute[],
  rutasDe: (chute: number) => RutaSimulacion[],
  cargaDe: (chute: number) => CargaSimulacion | undefined,
  colores: Map<string, string> | null,
): Tramo[] {
  const resultado: Tramo[] = partir(columnas, CHUTES_POR_TABLA).map((chutes) => {
    const listas = chutes.map((c) => rutasDe(c.id));
    const alto = Math.max(0, ...listas.map((l) => l.length));
    const filas = Array.from({ length: alto }, (_, f) =>
      listas.map((lista) => {
        const ruta = lista[f];
        if (!ruta) {
          return null;
        }
        // Código y bultos redondeados hacia arriba, como en la pantalla (el tipo va en el Excel).
        const bultos = formatearEntero(ruta.totalBultos);
        const grupo = colores && ruta.grupo ? ruta.grupo : null;
        const marca = marcaDe(ruta.frecuencia);
        const movida = ruta.chuteId !== ruta.chuteOriginalId || ruta.clusterId !== ruta.clusterOriginalId;
        return {
          codigo: ruta.codigoRuta,
          bultos,
          grupo,
          color: grupo ? (colores?.get(grupo) ?? null) : null,
          marca,
          movida,
          titulo:
            `${ruta.codigoRuta} · ${bultos} bultos` +
            (grupo ? ` · grupo ${grupo}` : '') +
            (marca === 'D' ? ' · diaria' : ` · ${ETIQUETA_BALANCEO[marca]}`) +
            (movida ? ' · movida' : ''),
        };
      }),
    );
    return {
      chutes,
      filas,
      totales: CONCEPTOS.map((concepto) => ({
        clave: concepto.clave,
        etiqueta: concepto.etiqueta,
        celdas: chutes.map((c) => {
          const carga = cargaDe(c.id);
          const crudo = !carga ? 0 : concepto.clave === 'operarios' ? (carga.operarios ?? 0) : Number(carga[concepto.clave]);
          return { valor: redondear(crudo), tono: null };
        }),
      })),
    };
  });
  // Semáforo por línea con todos los chutes del cluster.
  CONCEPTOS.forEach((_, fila) => {
    const niveles = nivelesDeFila(resultado.flatMap((t) => t.totales[fila].celdas.map((c) => c.valor)));
    let i = 0;
    for (const tramo of resultado) {
      for (const celda of tramo.totales[fila].celdas) {
        celda.tono = tonoSemaforo(niveles[i++]);
      }
    }
  });
  return resultado;
}
