import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import {
  EMPTY,
  Observable,
  Subject,
  catchError,
  debounceTime,
  expand,
  finalize,
  forkJoin,
  map,
  of,
  scan,
  switchMap,
  tap,
} from 'rxjs';
import { PERMISOS } from '../../../../../core/config/permisos';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import {
  ArchivoSimulacion,
  Balanceo,
  BalanceoCuerpo,
  DIAS_BALANCEO,
  DiaBalanceo,
  ETIQUETA_BALANCEO,
  CargaSimulacion,
  ConfiguracionSimulacion,
  ConfiguracionSimulacionRequest,
  PredeterminadosSimulacion,
  RutaSimulacion,
  VistaBalanceo,
  rutaEnBalanceo,
} from '../../data/simulacion.model';
import { Notificaciones } from '../../../../../core/services/notificaciones';
import { SesionStore } from '../../../../../core/services/sesion';
import { SimulacionApi } from '../../data/simulacion.api';
import { DirectorioUsuarios } from '../../../../../core/services/directorio-usuarios';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { IconoSim } from '../../icono-sim/icono-sim';
import { FichaDetalle } from './ficha-detalle/ficha-detalle';
import { Modal } from '../../../../../shared/ui/modal/modal';
import { ChipFiltro, FiltrosActivos } from '../../../../../shared/ui/panel-filtros/filtros-activos';
import { PanelFiltros } from '../../../../../shared/ui/panel-filtros/panel-filtros';
import { SelectBuscable } from '../../../../../shared/ui/select-buscable/select-buscable';
import { SelectMultiple } from '../../../../../shared/ui/select-multiple/select-multiple';
import {
  LIMITES,
  PALETA_REGISTROS,
  VELOCIDAD_INICIAL,
  compararChute,
  numeroChute,
  compararNombre,
  datosCarga,
  etiquetaRegistro,
  coloresGrupos,
  formatearEntero,
  formatearNumero,
  formatearHoras,
  nombreRegistroSugerido,
  redondearArriba,
  problemaArchivoExcel,
  tamanoArchivo,
} from '../../simulacion-formato';
import {
  Paso,
  ResumenCluster,
  nivelesDeFila,
  normalizarLimite,
  normalizarPorChute,
  normalizarVelocidad,
  resumirClusters,
} from './configuracion.logica';
import { SimulacionGuiaBalanceo } from './guia-balanceo/guia-balanceo';
import { SimulacionHistorialCambios } from './historial-cambios/historial-cambios';
import { ArrastreRutas, DestinoRuta } from './arrastre-rutas.directive';
import { ScrollMatriz } from './scroll-matriz.directive';
import { SimulacionExportar } from '../../exportar/exportar';
import { EnteroPipe, HorasPipe, NumeroPipe } from '../../numero.pipe';

/** Ruta lista para pintar: el título y los permisos ya calculados (sin métodos en la plantilla). */
interface CeldaRuta {
  ruta: RutaSimulacion;
  titulo: string;
  /** Se puede arrastrar: hay permiso y su cluster no está cerrado (en cualquier vista). */
  movible: boolean;
  /** Ya no está en el chute o cluster del Excel. */
  movida: boolean;
  cambioCluster: boolean;
  /** Grupo de la ruta (null solo si su cluster aún no tiene grupos cargados). */
  grupo: string | null;
  /** Color de la pestaña de su grupo (null si el cluster no tiene grupos). */
  grupoColor: string | null;
  /** Bultos redondeados hacia arriba y con miles («1.250»), para la pastilla. */
  bultos: string;
  /** Pestaña de su frecuencia (al estilo de la del grupo, en el borde derecho). */
  frecuencia: PestanaFrecuencia;
}

/**
 * Lo que muestra un cluster: un balanceo (Diarias, L-X-V o M-J-S) o los dos días
 * apilados («Ambos»). Los filtros son solo de vista: las acciones van por el menú.
 */
export type VistaCluster = Balanceo | 'AMBOS';

/** Filtros del panel: cuáles están pulsados y cuáles bloqueados (frecuencia sin rutas). */
export interface FiltrosCluster {
  lxv: boolean;
  mjs: boolean;
  diaria: boolean;
  sinLxv: boolean;
  sinMjs: boolean;
  sinDiarias: boolean;
}

/** Avance de un cluster en sus tres balanceos y su lugar en el orden de las olas. */
export interface AvanceCluster {
  dia: string;
  lxv: string;
  mjs: string;
  /** Rutas propias por frecuencia (las diarias no cuentan en los días). */
  rutas: { dia: number; lxv: number; mjs: number };
  /** El día tiene rutas propias: se balancea. Sin ellas no hay nada que mover. */
  requiere: { lxv: boolean; mjs: boolean };
  /** El día tiene carga (rutas propias o diarias): lleva operarios y cierre. */
  participa: { lxv: boolean; mjs: boolean };
  cerrado: boolean;
  enCurso: boolean;
  esperaA: string | null;
  siguiente: string | null;
}

/** Un punto del avance del cluster (panel y ficha). */
export interface PasoAvance {
  clave: 'DIARIAS' | 'LXV' | 'MJS' | 'OPERARIOS' | 'CERRAR';
  etiqueta: string;
  /** Inicial para el riel minimizado. */
  corta: string;
  estado: 'hecho' | 'siguiente' | 'pendiente' | 'omitido';
  titulo: string;
}

/** Un paso del menú «Acciones» del cluster. */
export interface AccionCluster {
  clave: 'DIARIAS' | 'LXV' | 'MJS' | 'OPERARIOS' | 'CERRAR' | 'RESTABLECER' | 'REABRIR';
  titulo: string;
  detalle: string;
  /** hecho = ✓; siguiente = el que toca (resaltado); bloqueado = aún no; libre = se puede. */
  marca: 'hecho' | 'siguiente' | 'bloqueado' | 'libre';
  habilitada: boolean;
  peligro?: boolean;
}

/** Un cluster de la matriz: una fila por nivel de apilado y una celda por chute. */
interface BloqueMatriz {
  cluster: ResumenCluster;
  /** Lo que muestra el cluster. */
  vista: VistaCluster;
  /** Frecuencias que muestra (filtros del panel). */
  filtros: FiltrosCluster;
  /** El cluster está cerrado (en los tres balanceos). */
  cerrado: boolean;
  /** Estado para el título del cluster. */
  estado: string;
  /** filas[f][c]: ruta en la fila f del chute c (en el orden de `chutesMatriz`), o null. */
  filas: (CeldaRuta | null)[][];
}

/**
 * Filas de cada bloque: también su alto visible (el SCSS lo toma de
 * --mz-filas). Con más rutas, la tabla crece y se recorre con scroll vertical.
 */
const MIN_FILAS = 4;

/**
 * Dónde se dibuja la ficha de detalle: junto a su ancla (abajo o arriba,
 * según el espacio) o, en pantallas angostas o bajas, como hoja inferior.
 */
export interface PosicionDetalle {
  hoja: boolean;
  left: number | null;
  top: number | null;
  bottom: number | null;
  maxAlto: number | null;
}

interface DetalleAbierto {
  tipo: 'ruta' | 'cluster';
  id: number;
  /** Elemento que la abrió: tocarlo de nuevo la cierra y recibe el foco al salir. */
  ancla: HTMLElement;
  pos: PosicionDetalle;
}

/** Ancho de la ficha (igual que en el SCSS) y margen con el borde de la ventana. */
const ANCHO_DETALLE = 320;
const MARGEN_DETALLE = 12;

function posicionDetalle(ancla: HTMLElement, altoEstimado: number): PosicionDetalle {
  const caja = ancla.getBoundingClientRect();
  const ancho = window.innerWidth;
  const alto = window.innerHeight;
  const abajo = alto - caja.bottom - MARGEN_DETALLE - 6;
  const arriba = caja.top - MARGEN_DETALLE - 6;
  const hoja = { hoja: true, left: null, top: null, bottom: null, maxAlto: null };
  if (ancho < 640 || Math.max(abajo, arriba) < 220) {
    return hoja;
  }
  const left = Math.min(
    Math.max(MARGEN_DETALLE, caja.left),
    ancho - Math.min(ANCHO_DETALLE, ancho - MARGEN_DETALLE * 2) - MARGEN_DETALLE,
  );
  if (abajo >= altoEstimado || abajo >= arriba) {
    return { hoja: false, left, top: caja.bottom + 6, bottom: null, maxAlto: abajo };
  }
  return { hoja: false, left, top: null, bottom: alto - caja.top + 6, maxAlto: arriba };
}

/** Motivo interno: soltar la ruta en su misma celda no hace nada (y no avisa). */
const SIN_CAMBIO = 'sin-cambio';

/** Mac: Ctrl+clic es el clic derecho, así que la selección usa ⌘ (y Ctrl llega como menú). */
const ES_MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

type CampoCarga = 'rutas' | 'operarios' | 'bultos' | 'unidades' | 'lineas' | 'cod';

/** Un valor por chute, en el orden de las columnas de la matriz. */
type CampoTotal = 'operarios' | 'bultos' | 'unidades' | 'lineas' | 'cod';

/**
 * Totales por chute y, para colorearlos, el nivel de cada valor dentro de su
 * fila: 0 = el menor (color claro) … 1 = el mayor (color oscuro); null = vacío.
 */
type TotalesChute = Record<CampoTotal, number[]> & {
  niveles: Record<CampoTotal, (number | null)[]>;
};

interface MetricasCluster {
  rutas: number;
  bultos: number;
  lineas: number;
  unidades: number;
  cod: number;
  porChute: TotalesChute;
}

/** Progreso de la carga completa de rutas para la matriz. */
interface EstadoRutasMatriz {
  rutas: RutaSimulacion[];
  total: number;
}

/**
 * Simulación cluster por cluster, en el orden de las olas. El cluster en curso
 * se trabaja en pasos: balancear sus diarias (aquí se elige el rango de chutes),
 * balancear L-X-V y M-J-S (cada día solo sus rutas, en ese rango), asignar los
 * operarios (la misma cantidad para los dos días) y cerrarlo (los tres a la vez).
 * Al cerrarlo se habilita el siguiente; al cerrar el último, la simulación termina.
 */
@Component({
  selector: 'app-simulacion-configuracion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgTemplateOutlet,
    ReactiveFormsModule,
    HasPermiso,
    EmptyState,
    FichaDetalle,
    Icon,
    IconoSim,
    Modal,
    SelectBuscable,
    SelectMultiple,
    PanelFiltros,
    FiltrosActivos,
    SimulacionHistorialCambios,
    SimulacionGuiaBalanceo,
    ScrollMatriz,
    ArrastreRutas,
    SimulacionExportar,
    NumeroPipe,
    EnteroPipe,
    HorasPipe,
],
  templateUrl: './configuracion.html',
  styleUrls: ['./configuracion.scss', './configuracion-panel.scss'],
})
export class SimulacionConfiguracion {
  private readonly api = inject(SimulacionApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly sesion = inject(SesionStore);
  private readonly fb = inject(FormBuilder);
  private readonly directorio = inject(DirectorioUsuarios);
  private readonly inputArchivo = viewChild<ElementRef<HTMLInputElement>>('inputArchivo');

  readonly permisos = PERMISOS;
  readonly paleta = PALETA_REGISTROS;
  readonly tamanoArchivo = tamanoArchivo;
  readonly puedeModificar = computed(() =>
    this.sesion.permisos().includes(PERMISOS.MODIFICAR_CONFIGURACION_SIMULACION),
  );
  readonly limitesValidos = LIMITES;

  readonly registros = signal<ArchivoSimulacion[]>([]);
  readonly seleccionado = signal<number | null>(null);
  readonly cargandoOpciones = signal(false);

  /** Configuración de cada balanceo tal como la manda el servidor. */
  readonly configs = signal<Record<Balanceo, ConfiguracionSimulacion | null>>({
    LXV: null,
    MJS: null,
    DIA: null,
  });
  /** Vista de cada cluster (por id); sin elegir, L-X-V. */
  readonly vistas = signal<Readonly<Record<number, VistaCluster>>>({});
  /** Días con carga en el registro (algún cluster con rutas propias o diarias): el menú Exportar. */
  readonly diasConRutas = computed<readonly DiaBalanceo[]>(() => {
    const clusters = this.configs().LXV?.clusters ?? [];
    return DIAS_BALANCEO.filter((b) =>
      clusters.some((c) => {
        const rutas = rutasPorFrecuencia(c.avance);
        return rutas.dia > 0 || (b === 'LXV' ? rutas.lxv : rutas.mjs) > 0;
      }),
    );
  });

  /** Vista de cada cluster: la elegida si su frecuencia tiene rutas; si no, la primera con rutas. */
  readonly vistasEfectivas = computed<Readonly<Record<number, VistaCluster>>>(() => {
    const elegidas = this.vistas();
    const resultado: Record<number, VistaCluster> = {};
    for (const cluster of this.configs().LXV?.clusters ?? []) {
      const rutas = rutasPorFrecuencia(cluster.avance);
      const elegida = elegidas[cluster.id];
      resultado[cluster.id] = elegida && vistaValida(elegida, rutas) ? elegida : vistaInicial(rutas);
    }
    return resultado;
  });
  readonly etiquetaBalanceo = ETIQUETA_BALANCEO;

  /**
   * Lo que ve la página: lo del registro (velocidad, chutes, buff) es común y
   * sale de L-X-V; cada cluster trae su avance y su carga del balanceo que
   * muestra. «Ambos» suma los dos días sin contar dos veces las diarias.
   */
  readonly config = computed<ConfiguracionSimulacion | null>(() => {
    const configs = this.configs();
    const lxv = configs.LXV;
    if (!lxv) {
      return null;
    }
    const vistas = this.vistasEfectivas();
    const vistaDe = (clusterId: number): VistaCluster => vistas[clusterId] ?? 'LXV';
    // Un balanceo que aún no llegó se muestra con L-X-V hasta que llegue.
    const cfgDe = (vista: VistaCluster) => (vista === 'AMBOS' ? lxv : (configs[vista] ?? lxv));
    return {
      ...lxv,
      clusters: lxv.clusters.map(
        (cluster) => cfgDe(vistaDe(cluster.id)).clusters.find((c) => c.id === cluster.id) ?? cluster,
      ),
      carga: [
        ...(['LXV', 'MJS'] as const).flatMap((balanceo) =>
          (configs[balanceo] ?? (balanceo === 'LXV' ? lxv : null))?.carga.filter(
            (fila) => vistaDe(fila.clusterId) === balanceo,
          ) ?? [],
        ),
        ...this.cargaDiarias(),
        ...this.cargaAmbos(),
      ],
    };
  });

  /**
   * Carga de los clusters en «Diarias»: sus rutas diarias con los operarios de cada chute como
   * en «Ambos» (Diarias no es una ola: la gente es la de los días, así que va el mayor de los dos).
   */
  private readonly cargaDiarias = computed<CargaSimulacion[]>(() => {
    const vistas = this.vistasEfectivas();
    const { LXV: lxv, MJS: mjs, DIA: dia } = this.configs();
    const filas = (dia?.carga ?? []).filter((fila) => vistas[fila.clusterId] === 'DIA');
    if (filas.length === 0) {
      return [];
    }
    const operarios = new Map<string, number>();
    for (const fila of [...(lxv?.carga ?? []), ...(mjs?.carga ?? [])]) {
      if (fila.operarios !== null && vistas[fila.clusterId] === 'DIA') {
        const clave = `${fila.clusterId}:${fila.chuteId}`;
        operarios.set(clave, Math.max(operarios.get(clave) ?? 0, Number(fila.operarios)));
      }
    }
    return filas.map((fila) => {
      const valor = operarios.get(`${fila.clusterId}:${fila.chuteId}`);
      return valor === undefined ? fila : { ...fila, operarios: valor };
    });
  });

  /**
   * Carga de los clusters en «Ambos»: L-X-V + M-J-S − Diarias por (cluster, chute), así cada
   * diaria cuenta una vez. Sale de la carga agregada del servidor (no de las rutas cargadas:
   * no cambia con la búsqueda ni mientras llegan las páginas). Los operarios no se suman: son
   * la misma gente en días distintos, así que cada chute muestra el mayor de los dos días.
   */
  private readonly cargaAmbos = computed<CargaSimulacion[]>(() => {
    const vistas = this.vistasEfectivas();
    const ambos = new Set(
      Object.entries(vistas)
        .filter(([, vista]) => vista === 'AMBOS')
        .map(([id]) => Number(id)),
    );
    if (ambos.size === 0) {
      return [];
    }
    const { LXV: lxv, MJS: mjs, DIA: dia } = this.configs();
    const porPar = new Map<string, CargaSimulacion>();
    const sumar = (filas: CargaSimulacion[] | undefined, signo: 1 | -1, conOperarios: boolean) => {
      for (const fila of filas ?? []) {
        if (!ambos.has(fila.clusterId)) {
          continue;
        }
        const clave = `${fila.clusterId}:${fila.chuteId}`;
        let total = porPar.get(clave);
        if (!total) {
          total = {
            clusterId: fila.clusterId,
            chuteId: fila.chuteId,
            rutas: 0,
            bultos: 0,
            unidades: 0,
            lineas: 0,
            cod: 0,
            operarios: null,
          };
          porPar.set(clave, total);
        }
        total.rutas += signo * Number(fila.rutas ?? 0);
        total.bultos += signo * Number(fila.bultos ?? 0);
        total.unidades += signo * Number(fila.unidades ?? 0);
        total.lineas += signo * Number(fila.lineas ?? 0);
        total.cod += signo * Number(fila.cod ?? 0);
        if (conOperarios && fila.operarios !== null) {
          total.operarios = Math.max(total.operarios ?? 0, Number(fila.operarios));
        }
      }
    };
    sumar(lxv?.carga, 1, true);
    sumar(mjs?.carga, 1, true);
    sumar(dia?.carga, -1, false);
    return [...porPar.values()].filter((fila) => fila.rutas > 0);
  });

  /** Filtros del cluster: qué rutas muestra (siempre al menos uno pulsado). */
  filtrosDe(clusterId: number): FiltrosCluster {
    const vista = this.vistaDe(clusterId);
    const { rutas } = this.avanceCluster(clusterId);
    return {
      lxv: vista === 'LXV' || vista === 'AMBOS',
      mjs: vista === 'MJS' || vista === 'AMBOS',
      diaria: vista === 'DIA',
      // Cada frecuencia (también Diarias) se ve solo si el cluster tiene rutas propias de ella.
      sinLxv: rutas.lxv === 0,
      sinMjs: rutas.mjs === 0,
      sinDiarias: rutas.dia === 0,
    };
  }

  /**
   * Pulsa o suelta un filtro. L-X-V y M-J-S se combinan (los dos = Ambos); Diarias va sola:
   * pulsarla suelta las otras y pulsar otra la suelta. Nunca quedan todos sueltos.
   */
  alternarFiltro(clusterId: number, filtro: Balanceo): void {
    const filtros = this.filtrosDe(clusterId);
    const bloqueado = filtro === 'DIA' ? filtros.sinDiarias : filtro === 'LXV' ? filtros.sinLxv : filtros.sinMjs;
    if (bloqueado) {
      return;
    }
    if (filtro === 'DIA') {
      // Soltar Diarias vuelve al primer día con rutas; un cluster solo de diarias se queda en D.
      const vuelta = filtros.diaria ? (!filtros.sinLxv ? 'LXV' : !filtros.sinMjs ? 'MJS' : null) : 'DIA';
      if (vuelta) {
        this.cambiarVista(clusterId, vuelta);
      }
      return;
    }
    if (filtros.diaria) {
      this.cambiarVista(clusterId, filtro);
      return;
    }
    const lxv = filtro === 'LXV' ? !filtros.lxv : filtros.lxv;
    const mjs = filtro === 'MJS' ? !filtros.mjs : filtros.mjs;
    if (!lxv && !mjs) {
      return;
    }
    this.cambiarVista(clusterId, lxv && mjs ? 'AMBOS' : lxv ? 'LXV' : 'MJS');
  }

  // ---------------- Menú «Acciones» del cluster ----------------

  /**
   * Menú abierto: de qué cluster, dónde se dibuja (bajo su botón o, si no cabe, encima) y el
   * botón que lo abrió (recibe el foco al cerrarlo con Escape).
   */
  readonly menuAcciones = signal<{
    id: number;
    nombre: string;
    left: number;
    top: number | null;
    bottom: number | null;
    ancla: HTMLElement;
  } | null>(null);

  /**
   * Avance del cluster en Diarias, L-X-V y M-J-S (viene en cualquier configuración). Los
   * clusters se trabajan en orden: el en curso es el primero sin cerrar (se cierra en los tres).
   */
  avanceCluster(clusterId: number): AvanceCluster {
    const lista = this.configs().LXV?.clusters ?? [];
    const propio = lista.find((c) => c.id === clusterId);
    const avance = propio?.avance;
    const enCurso = lista.find((c) => c.estado !== 'CERRADO') ?? null;
    const indice = lista.findIndex((c) => c.id === clusterId);
    const cerrado = propio?.estado === 'CERRADO';
    const rutas = rutasPorFrecuencia(avance);
    return {
      dia: avance?.diarias ?? 'PENDIENTE',
      lxv: avance?.lxv ?? propio?.estado ?? 'PENDIENTE',
      mjs: avance?.mjs ?? 'PENDIENTE',
      rutas,
      requiere: { lxv: rutas.lxv > 0, mjs: rutas.mjs > 0 },
      participa: { lxv: rutas.lxv > 0 || rutas.dia > 0, mjs: rutas.mjs > 0 || rutas.dia > 0 },
      cerrado,
      enCurso: enCurso?.id === clusterId,
      esperaA: enCurso && enCurso.id !== clusterId ? enCurso.nombre : null,
      siguiente: lista[indice + 1]?.nombre ?? null,
    };
  }

  /** Cerrado y texto del estado del cluster (título de su nombre). */
  private estadoBloque(clusterId: number): { cerrado: boolean; estado: string } {
    const a = this.avanceCluster(clusterId);
    const marca = (estado: string, requiere = true) =>
      !requiere ? 'sin rutas' : estado === 'PENDIENTE' ? 'pendiente' : '✓';
    return {
      cerrado: a.cerrado,
      estado: a.cerrado
        ? 'Cerrado'
        : a.esperaA
          ? `En espera de ${a.esperaA}`
          : `En curso · Diarias ${marca(a.dia)} · L-X-V ${marca(a.lxv, a.requiere.lxv)} · M-J-S ${marca(a.mjs, a.requiere.mjs)}`,
    };
  }

  /**
   * Pasos del menú, en orden, cada uno habilitado solo cuando le toca: Diarias (elige el rango),
   * L-X-V y M-J-S (en cualquier orden), operarios (los días con carga) y cerrar. Un día sin rutas
   * propias no se balancea: queda bloqueado y no frena los operarios. El siguiente que toca va
   * resaltado. Lo usan el menú «Acciones» y el botón del paso en el panel.
   */
  accionesDe(clusterId: number): AccionCluster[] {
    const a = this.avanceCluster(clusterId);
    if (a.cerrado) {
      return [
        {
          clave: 'REABRIR',
          titulo: 'Reabrir cluster',
          detalle: 'Los clusters siguientes vuelven a la proyección',
          marca: 'libre',
          habilitada: this.puedeModificar(),
        },
      ];
    }
    const activo = a.enCurso && this.puedeModificar();
    const hecho = (estado: string) => estado !== 'PENDIENTE';
    const conDiarias = hecho(a.dia);
    const sinDiarias = a.rutas.dia === 0;
    const unDia = hecho(a.lxv) || hecho(a.mjs);
    // Listo para operarios: cada día con rutas propias, balanceado.
    const diasListos =
      (!a.requiere.lxv || hecho(a.lxv)) && (!a.requiere.mjs || hecho(a.mjs)) && (a.participa.lxv || a.participa.mjs);
    // Con operarios: cada día con carga, LISTO (uno sin carga no tiene operarios ni cierre).
    const conOperarios = (!a.participa.lxv || a.lxv === 'LISTO') && (!a.participa.mjs || a.mjs === 'LISTO');
    const conCarga = DIAS_BALANCEO.filter((b) => (b === 'LXV' ? a.participa.lxv : a.participa.mjs));
    const textoDias = conCarga.map((b) => ETIQUETA_BALANCEO[b]).join(' y ');
    const dia = (b: DiaBalanceo, estado: string): AccionCluster => {
      const etiqueta = ETIQUETA_BALANCEO[b];
      if (!(b === 'LXV' ? a.requiere.lxv : a.requiere.mjs)) {
        return {
          clave: b,
          titulo: `Balancear ${etiqueta}`,
          detalle: `Sin rutas ${etiqueta}: no hay nada que balancear`,
          marca: 'bloqueado',
          habilitada: false,
        };
      }
      return {
        clave: b,
        titulo: `${hecho(estado) ? 'Volver a balancear' : 'Balancear'} ${etiqueta}`,
        detalle: !conDiarias
          ? sinDiarias
            ? 'Después de definir el rango'
            : 'Después de las diarias'
          : hecho(estado)
            ? 'Hecho'
            : sinDiarias
              ? 'Solo sus rutas, en el rango definido'
              : 'Solo sus rutas, en el rango de las diarias',
        marca: hecho(estado) ? 'hecho' : conDiarias ? 'siguiente' : 'bloqueado',
        habilitada: activo && conDiarias,
      };
    };
    const acciones: AccionCluster[] = [
      {
        clave: 'DIARIAS',
        // Sin diarias el paso no mueve rutas, pero sigue: ahí se eligen el rango y los tramos.
        titulo: sinDiarias
          ? conDiarias
            ? 'Volver a definir rango y tramos'
            : 'Definir rango y tramos'
          : conDiarias
            ? 'Volver a balancear diarias'
            : 'Balancear diarias',
        detalle: unDia
          ? 'Restablece el cluster para rehacerlo'
          : conDiarias
            ? 'Hecho · aquí se elige el rango de chutes'
            : sinDiarias
              ? 'Sin rutas diarias: solo se elige el rango'
              : 'Primero: elige el rango de chutes',
        marca: conDiarias ? 'hecho' : 'siguiente',
        habilitada: activo && !unDia,
      },
      dia('LXV', a.lxv),
      dia('MJS', a.mjs),
      {
        clave: 'OPERARIOS',
        titulo: conOperarios ? 'Recalcular operarios' : 'Asignar operarios',
        detalle: conDiarias && diasListos
          ? conCarga.length > 1
            ? `Misma cantidad para ${textoDias}`
            : `Solo ${textoDias}: el otro día no tiene carga`
          : 'Después de balancear los días con rutas',
        marca: conOperarios ? 'hecho' : conDiarias && diasListos ? 'siguiente' : 'bloqueado',
        habilitada: activo && conDiarias && diasListos,
      },
      {
        clave: 'CERRAR',
        titulo: a.siguiente ? `Cerrar y pasar a ${a.siguiente}` : 'Cerrar y terminar',
        detalle: conOperarios ? `Cierra diarias, ${textoDias || 'L-X-V y M-J-S'}` : 'Después de los operarios',
        marca: conOperarios ? 'siguiente' : 'bloqueado',
        habilitada: activo && conDiarias && conOperarios,
      },
      {
        clave: 'RESTABLECER',
        titulo: 'Restablecer cluster',
        detalle: 'Deshace diarias, L-X-V y M-J-S',
        marca: 'libre',
        habilitada: activo && (conDiarias || unDia),
        peligro: true,
      },
    ];
    // Solo un paso resaltado: el primero que toca.
    let resaltado = false;
    return acciones.map((accion) => {
      if (accion.marca !== 'siguiente') {
        return accion;
      }
      if (resaltado || !accion.habilitada) {
        return { ...accion, marca: accion.habilitada ? 'libre' : 'bloqueado' };
      }
      resaltado = true;
      return accion;
    });
  }

  readonly accionesMenu = computed<AccionCluster[]>(() => {
    const menu = this.menuAcciones();
    if (!menu) {
      return [];
    }
    this.configs();
    return this.accionesDe(menu.id);
  });

  /**
   * Botón principal del panel: el paso que toca (un clic) o, cerrado, «Reabrir». En espera de
   * otro cluster o sin permiso, no hay paso: el botón lo dice y el menú sigue a mano.
   */
  readonly pasoPanel = computed(() => {
    this.configs();
    const resultado = new Map<number, AccionCluster | null>();
    for (const cluster of this.configs().LXV?.clusters ?? []) {
      const acciones = this.accionesDe(cluster.id);
      resultado.set(
        cluster.id,
        acciones.find((accion) => accion.marca === 'siguiente') ??
          acciones.find((accion) => accion.clave === 'REABRIR' && accion.habilitada) ??
          null,
      );
    }
    return resultado;
  });

  /**
   * Avance a la vista: Diarias, L-X-V, M-J-S, Operarios y Cerrado, cada uno hecho, siguiente,
   * pendiente u omitido (un día sin rutas propias no se balancea).
   */
  /** Avance de cada cluster para el panel (un cálculo por cambio, no por render). */
  readonly pasosPanel = computed(() => {
    const resultado = new Map<number, PasoAvance[]>();
    for (const cluster of this.configs().LXV?.clusters ?? []) {
      resultado.set(cluster.id, this.pasosDe(cluster.id));
    }
    return resultado;
  });

  pasosDe(clusterId: number): PasoAvance[] {
    const a = this.avanceCluster(clusterId);
    const siguiente = this.pasoPanel().get(clusterId)?.clave ?? null;
    const hecho = (estado: string) => estado !== 'PENDIENTE';
    const conOperarios = (!a.participa.lxv || a.lxv === 'LISTO') && (!a.participa.mjs || a.mjs === 'LISTO');
    const estado = (listo: boolean, clave: AccionCluster['clave']): PasoAvance['estado'] =>
      a.cerrado || listo ? 'hecho' : siguiente === clave ? 'siguiente' : 'pendiente';
    const dia = (b: DiaBalanceo, est: string, requiere: boolean): PasoAvance => ({
      clave: b,
      etiqueta: ETIQUETA_BALANCEO[b],
      corta: b === 'LXV' ? 'L' : 'M',
      estado: requiere ? estado(hecho(est), b) : 'omitido',
      titulo: requiere ? ETIQUETA_BALANCEO[b] : `${ETIQUETA_BALANCEO[b]}: sin rutas propias, no se balancea`,
    });
    return [
      {
        clave: 'DIARIAS',
        etiqueta: a.rutas.dia === 0 ? 'Rango' : 'Diarias',
        corta: a.rutas.dia === 0 ? 'R' : 'D',
        estado: estado(hecho(a.dia), 'DIARIAS'),
        titulo: a.rutas.dia === 0 ? 'Rango y tramos (sin rutas diarias)' : 'Diarias: rango y tramos',
      },
      dia('LXV', a.lxv, a.requiere.lxv),
      dia('MJS', a.mjs, a.requiere.mjs),
      {
        clave: 'OPERARIOS',
        etiqueta: 'Operarios',
        corta: 'O',
        estado: estado(conOperarios, 'OPERARIOS'),
        titulo: 'Operarios',
      },
      {
        clave: 'CERRAR',
        etiqueta: 'Cerrado',
        corta: 'C',
        estado: a.cerrado ? 'hecho' : siguiente === 'CERRAR' ? 'siguiente' : 'pendiente',
        titulo: 'Cluster cerrado',
      },
    ];
  }

  /** Aviso del menú: por qué está en espera. */
  readonly avisoMenu = computed(() => {
    const menu = this.menuAcciones();
    if (!menu) {
      return null;
    }
    this.configs();
    const a = this.avanceCluster(menu.id);
    return a.esperaA && !a.cerrado ? `Se habilita cuando cierres ${a.esperaA}.` : null;
  });

  abrirAcciones(evento: Event, cluster: { id: number; nombre: string }): void {
    const boton = evento.currentTarget;
    if (!(boton instanceof HTMLElement)) {
      return;
    }
    if (this.menuAcciones()?.id === cluster.id) {
      this.menuAcciones.set(null);
      return;
    }
    const caja = boton.getBoundingClientRect();
    const ancho = Math.min(300, window.innerWidth - 32);
    const left = Math.min(Math.max(16, caja.left), window.innerWidth - ancho - 16);
    // Alto aproximado del menú (6 pasos): si no cabe debajo y arriba hay más sitio, se abre encima.
    const alto = 380;
    const abajo = window.innerHeight - caja.bottom - 16;
    const arriba = caja.top - 16;
    const encima = abajo < alto && arriba > abajo;
    this.detalle.set(null);
    this.menuAcciones.set({
      id: cluster.id,
      nombre: cluster.nombre,
      left,
      top: encima ? null : caja.bottom + 6,
      bottom: encima ? window.innerHeight - caja.top + 6 : null,
      ancla: boton,
    });
    // El foco entra al menú (al primer paso habilitado) para usarlo con el teclado.
    setTimeout(() => this.enfocarMenu(0, 1));
  }

  /** Flechas, Inicio y Fin recorren los pasos habilitados; Escape cierra y devuelve el foco. */
  navegarMenu(evento: KeyboardEvent): void {
    const items = this.itemsMenu();
    const actual = items.indexOf(document.activeElement as HTMLElement);
    if (evento.key === 'ArrowDown' || evento.key === 'ArrowUp') {
      evento.preventDefault();
      const paso = evento.key === 'ArrowDown' ? 1 : -1;
      this.enfocarMenu(actual + paso, paso);
    } else if (evento.key === 'Home' || evento.key === 'End') {
      evento.preventDefault();
      this.enfocarMenu(evento.key === 'Home' ? 0 : items.length - 1, evento.key === 'Home' ? 1 : -1);
    } else if (evento.key === 'Tab') {
      this.cerrarMenu(false);
    }
  }

  private itemsMenu(): HTMLElement[] {
    return Array.from(document.querySelectorAll<HTMLElement>('.mz-menu [role="menuitem"]'));
  }

  private enfocarMenu(desde: number, paso: 1 | -1): void {
    const items = this.itemsMenu();
    if (items.length === 0) {
      return;
    }
    for (let i = 0; i < items.length; i++) {
      const indice = (((desde + i * paso) % items.length) + items.length) % items.length;
      if (!(items[indice] as HTMLButtonElement).disabled) {
        items[indice].focus();
        return;
      }
    }
  }

  /** Cierra el menú; con `devolverFoco`, el foco vuelve a su botón «Acciones». */
  cerrarMenu(devolverFoco = true): void {
    const menu = this.menuAcciones();
    this.menuAcciones.set(null);
    if (devolverFoco) {
      menu?.ancla.focus({ preventScroll: true });
    }
  }

  ejecutarAccion(clave: AccionCluster['clave']): void {
    const menu = this.menuAcciones();
    this.menuAcciones.set(null);
    if (!menu) {
      return;
    }
    this.ejecutarEn({ id: menu.id, nombre: menu.nombre }, clave);
  }

  /** Botón del paso en el panel: ejecuta el que toca sin abrir el menú. */
  pulsarPaso(cluster: { id: number; nombre: string }, clave: AccionCluster['clave']): void {
    this.menuAcciones.set(null);
    this.ejecutarEn(cluster, clave);
  }

  private ejecutarEn(cluster: { id: number; nombre: string }, clave: AccionCluster['clave']): void {
    switch (clave) {
      case 'DIARIAS':
        this.balancear(cluster, 'DIA');
        break;
      case 'LXV':
      case 'MJS':
        this.balancear(cluster, clave);
        break;
      case 'OPERARIOS':
        this.asignarOperarios(cluster);
        break;
      case 'CERRAR':
        this.cerrar(cluster);
        break;
      case 'RESTABLECER':
        this.confirmarRestablecerCluster(cluster);
        break;
      case 'REABRIR':
        this.confirmarReabrir(cluster);
        break;
    }
  }

  /** El menú se cierra al tocar fuera, con Escape, al desplazar o al cambiar el tamaño. */
  private readonly cierreAcciones = effect((alLimpiar) => {
    if (!this.menuAcciones()) {
      return;
    }
    const fuera = (objetivo: EventTarget | null) =>
      !(objetivo instanceof Element) ||
      (!objetivo.closest('.mz-menu') && !objetivo.closest('.mz-panel__acciones-boton'));
    const alTocar = (e: PointerEvent) => {
      if (fuera(e.target)) {
        this.menuAcciones.set(null);
      }
    };
    const alTeclear = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        this.cerrarMenu();
      }
    };
    const alMover = (e: Event) => {
      if (fuera(e.target)) {
        this.menuAcciones.set(null);
      }
    };
    const alRedimensionar = () => this.menuAcciones.set(null);
    document.addEventListener('pointerdown', alTocar, true);
    document.addEventListener('keydown', alTeclear);
    document.addEventListener('scroll', alMover, { capture: true, passive: true });
    window.addEventListener('resize', alRedimensionar);
    alLimpiar(() => {
      document.removeEventListener('pointerdown', alTocar, true);
      document.removeEventListener('keydown', alTeclear);
      document.removeEventListener('scroll', alMover, true);
      window.removeEventListener('resize', alRedimensionar);
    });
  });

  /** Vista de un cluster: Diarias, L-X-V, M-J-S o Ambos. */
  vistaDe(clusterId: number): VistaCluster {
    return this.vistasEfectivas()[clusterId] ?? 'LXV';
  }

  /** Cambia lo que muestra un cluster (solo la vista: el límite es uno para los tres). */
  cambiarVista(clusterId: number, vista: VistaCluster): void {
    if (vista === this.vistaDe(clusterId) || this.ocupado()) {
      return;
    }
    this.vistas.update((actual) => ({ ...actual, [clusterId]: vista }));
    this.detalle.set(null);
  }

  /** Rutas que se ven en la matriz: las del balanceo de cada cluster (en «Ambos», todas). */
  readonly rutasVisibles = computed(() => {
    const vistas = this.vistasEfectivas();
    return this.rutasMatriz().filter((ruta) => {
      const vista = vistas[ruta.clusterId] ?? 'LXV';
      return vista === 'AMBOS' || rutaEnBalanceo(ruta.frecuencia, vista);
    });
  });

  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly errorOpciones = signal<string | null>(null);
  /** Descarta respuestas viejas si el usuario cambia de registro rápido. */
  private solicitud = 0;

  // ---------------- Parámetros y límites en edición ----------------
  // Ya no hay «equipo por defecto»: cada ola ingresa su cantidad en el paso 2.
  readonly velocidad = signal(VELOCIDAD_INICIAL);
  /** Máximo de operarios en un chute; null = sin tope propio (hasta toda la ola). */
  readonly porChute = signal<number | null>(null);
  readonly limites = signal<Record<number, number>>({});

  /** Hay cambios de velocidad, máximo por chute o de algún límite sin guardar. */
  readonly dirty = computed(() => {
    const cfg = this.config();
    if (!cfg) {
      return false;
    }
    const limites = this.limites();
    // Se compara con la configuración normalizada igual que al aplicarla: un 0 o
    // un nulo del servidor no dejan «Cambios sin guardar» fijo.
    const guardado = equipoNormalizado(cfg);
    const buff = new Set(this.buffGuardado());
    const elegidos = this.buffElegidos();
    return (
      guardado.velocidad !== this.velocidad() ||
      guardado.porChute !== this.porChute() ||
      cfg.clusters.some((cluster) => Number(cluster.limiteHoras) !== (limites[cluster.id] ?? 0)) ||
      elegidos.length !== buff.size ||
      elegidos.some((id) => !buff.has(id))
    );
  });

  readonly filtroChute = signal<number[]>([]);
  readonly filtroCluster = signal<number[]>([]);
  readonly filtroTexto = signal('');
  /** Lo que muestra el buscador; el filtro se aplica tras la pausa de escritura. */
  readonly textoBusqueda = signal('');
  readonly filtrosAbierto = signal(false);
  private readonly busqueda$ = new Subject<string>();

  /**
   * La matriz necesita todas las rutas del registro, no una página suelta.
   * El servidor limita cada respuesta a 1 000 filas, así que se piden en cadena
   * (pocas peticiones aun en registros grandes) y se van acumulando; el scroll
   * de cada cluster reparte esa lista.
   */
  private static readonly TAMANO_PAGINA_RUTAS = 1_000;
  /** true = recarga suave (tras un paso): la planilla actual se mantiene hasta que llega la nueva. */
  private readonly recargarRutas$ = new Subject<boolean>();
  private generacionRutas = 0;
  readonly rutasMatriz = signal<RutaSimulacion[]>([]);
  readonly cargandoRutas = signal(false);
  readonly rutasCargadas = signal(0);
  readonly totalRutas = signal(0);
  readonly errorRutas = signal<string | null>(null);

  readonly guardando = signal(false);
  /** Paso que se está ejecutando en el cluster en curso. */
  readonly trabajando = signal<Paso | null>(null);
  readonly restableciendo = signal(false);
  /** Ruta cuyo movimiento (de chute o de cluster) se está guardando. */
  readonly rutaGuardando = signal<number | null>(null);
  /** Rutas de un movimiento en grupo mientras se guardan. */
  readonly rutasGuardando = signal<ReadonlySet<number>>(new Set());
  /** Recarga tras un paso: hasta que llegue, las acciones siguen bloqueadas. */
  readonly recargando = signal(false);
  /** Una sola escritura a la vez: los botones de acción se deshabilitan juntos. */
  readonly ocupado = computed(
    () =>
      this.guardando() ||
      this.trabajando() !== null ||
      this.restableciendo() ||
      this.recargando() ||
      this.rutaGuardando() !== null ||
      this.fijando() ||
      this.rutasGuardando().size > 0,
  );

  /** Todos los clusters cerrados: la simulación terminó. */
  readonly finalizado = computed(() => this.config()?.archivo.estado === 'FINALIZADO');

  /**
   * El equipo ya trabajó en clusters cerrados (se cierran en los tres balanceos a la
   * vez): para cambiarlo hay que reabrir el primero de ellos.
   */
  readonly equipoBloqueado = computed(
    () => (this.configs().LXV?.clusters ?? []).find((cluster) => cluster.estado === 'CERRADO') ?? null,
  );

  // ---------------- Velocidad y buff predeterminados (todos los registros) ----------------

  /** Velocidad y buff con que nace todo registro; null mientras llegan. */
  readonly predeterminados = signal<PredeterminadosSimulacion | null>(null);
  /** Bloqueados: ningún registro cambia su velocidad ni su buff hasta desbloquearlos. */
  readonly fijados = computed(() => this.predeterminados()?.bloqueado ?? false);
  /** Bloqueando o desbloqueando. */
  readonly fijando = signal(false);

  private cargarPredeterminados(): void {
    this.api
      .predeterminados()
      .pipe(takeUntilDestroyed(this.destroyRef))
      // Sin ellos el botón queda deshabilitado; el resto de la página funciona igual.
      .subscribe({ next: (p) => this.predeterminados.set(p), error: () => undefined });
  }

  /**
   * Bloquea la velocidad y el buff que se ven para todos los registros (los sin balancear los
   * reciben ya; los nuevos nacen con ellos) o los desbloquea para cambiarlos.
   */
  alternarFijado(): void {
    if (this.ocupado()) {
      return;
    }
    if (this.fijados()) {
      this.fijando.set(true);
      this.api
        .desbloquearPredeterminados()
        .pipe(
          finalize(() => this.fijando.set(false)),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe({
          next: (p) => {
            this.predeterminados.set(p);
            this.notificaciones.exito(
              'Velocidad y buff desbloqueados',
              'Cámbialos y vuelve a bloquearlos para todos los registros.',
            );
          },
          error: () => undefined,
        });
      return;
    }
    const id = this.seleccionado();
    const velocidad = this.velocidad();
    const buff = [...this.buffElegidos()];
    const bloquear = () => this.api.bloquearPredeterminados(velocidad, buff);
    this.fijando.set(true);
    // Lo editado del registro (p. ej. un límite) se guarda antes: la recarga no lo pisa.
    (id === null ? bloquear() : this.conCambiosGuardados(id, bloquear))
      .pipe(
        finalize(() => this.fijando.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (p) => {
          this.predeterminados.set(p);
          this.notificaciones.exito(
            'Velocidad y buff bloqueados',
            `${formatearNumero(velocidad, Number.isInteger(velocidad) ? 0 : 2)} bultos/h y ${buff.length} chute(s) con buff para todos los registros. Los ya balanceados conservan los suyos.`,
          );
          // Si este registro estaba sin balancear, el servidor ya le aplicó los valores.
          if (id !== null && this.seleccionado() === id) {
            this.api
              .configuracion(id, 'LXV')
              .pipe(takeUntilDestroyed(this.destroyRef))
              .subscribe({
                next: (config) => {
                  if (this.seleccionado() === id) {
                    this.aplicarConfiguracion(config);
                    this.refrescarDemas(id, 'LXV');
                  }
                },
                error: () => undefined,
              });
          }
        },
        // El interceptor ya muestra el motivo del servidor.
        error: () => undefined,
      });
  }

  // ---------------- Buff de los chutes ----------------

  /** Chutes con buff elegidos (aún sin guardar). */
  readonly buffElegidos = signal<number[]>([]);

  /** El buff ya no cambia: algún cluster ya se balanceó en algún balanceo. */
  readonly buffBloqueado = computed(() => this.configs().LXV?.buffBloqueado ?? false);

  /** Chutes con buff guardados. */
  private readonly buffGuardado = computed(() =>
    (this.configs().LXV?.chutes ?? []).filter((chute) => chute.buff).map((chute) => chute.id),
  );

  /** Ids de los chutes con buff guardados, para marcar sus cabeceras. */
  readonly chutesBuff = computed(() => new Set(this.buffGuardado()));

  setBuff(ids: (number | string)[]): void {
    this.buffElegidos.set(ids.map(Number));
  }

  // ---------------- Modal de operarios ----------------

  /** Cluster cuyo paso de operarios espera la cantidad (modal «Operarios · …»). */
  readonly operariosPendiente = signal<{ id: number; nombre: string } | null>(null);
  readonly operariosCantidad = signal<number | null>(null);

  /**
   * Vista previa del paso de operarios con la cantidad elegida, con las mismas reglas que el
   * servidor. La misma cantidad vale para L-X-V y M-J-S y cada día la reparte según su carga:
   * por día, cuántos chutes tienen carga (mínimo uno por chute) y cuántos harían falta para
   * cumplir el límite. El mínimo y lo sugerido son los del día que más pide.
   */
  readonly vistaOperarios = computed(() => {
    const cluster = this.operariosPendiente();
    const configs = this.configs();
    if (!cluster || !configs.LXV) {
      return null;
    }
    const velocidad = this.velocidad();
    const limite = this.limites()[cluster.id] ?? 0;
    // Solo los días con carga: uno sin rutas propias ni diarias no lleva operarios.
    const { participa } = this.avanceCluster(cluster.id);
    const conCarga = DIAS_BALANCEO.filter((b) => (b === 'LXV' ? participa.lxv : participa.mjs));
    const dias = conCarga.map((balanceo) => {
      const porChute = new Map<number, number>();
      for (const fila of configs[balanceo]?.carga ?? []) {
        if (fila.clusterId === cluster.id && fila.rutas > 0) {
          porChute.set(fila.chuteId, (porChute.get(fila.chuteId) ?? 0) + Number(fila.bultos));
        }
      }
      let necesarios = 0;
      for (const bultos of porChute.values()) {
        if (bultos > 0 && velocidad > 0 && limite > 0) {
          necesarios += Math.max(1, Math.ceil(bultos / (velocidad * limite)));
        }
      }
      return { balanceo, etiqueta: ETIQUETA_BALANCEO[balanceo], chutes: porChute.size, necesarios };
    });
    const necesarios = Math.max(...dias.map((d) => d.necesarios));
    const minimo = Math.max(1, ...dias.map((d) => d.chutes));
    const cantidad = this.operariosCantidad();
    const valida =
      cantidad !== null && Number.isInteger(cantidad) && cantidad >= minimo && cantidad <= LIMITES.equipo;
    // Sin máximo por chute, el tope es la ola entera (con el tope técnico por chute).
    const tope = Math.min(LIMITES.operariosPorChute, this.porChute() ?? Math.max(1, cantidad ?? 1));
    const sugerido = Math.min(LIMITES.equipo, Math.max(minimo, necesarios));
    return {
      dias,
      minimo,
      necesarios,
      sugerido,
      limite,
      cantidad,
      valida,
      /** La cantidad cumple el límite en los dos días. */
      cumple: valida && limite > 0 && cantidad >= necesarios,
      sinChute: valida ? Math.max(0, cantidad - minimo * tope) : 0,
      tope,
    };
  });

  /** − / + del modal de operarios (desde el mínimo, sin pasar del tope). */
  ajustarOperarios(paso: 1 | -1): void {
    const vista = this.vistaOperarios();
    if (!vista) {
      return;
    }
    const actual = this.operariosCantidad() ?? vista.sugerido;
    this.operariosCantidad.set(Math.min(LIMITES.equipo, Math.max(vista.minimo, Math.trunc(actual) + paso)));
  }

  readonly historialAbierto = signal(false);

  // ---------------- Modal de rango (paso Diarias) ----------------

  /** Cluster cuyas diarias esperan el rango de chutes (modal «Diarias · …»). */
  readonly rangoPendiente = signal<{ id: number; nombre: string } | null>(null);
  /** El cluster del modal no tiene diarias: el paso solo define el rango y los tramos. */
  readonly rangoSinDiarias = computed(() => {
    const cluster = this.rangoPendiente();
    return cluster !== null && this.avanceCluster(cluster.id).rutas.dia === 0;
  });
  readonly rangoDesde = signal<number | null>(null);
  readonly rangoHasta = signal<number | null>(null);

  /** Grupos del cluster del modal en orden natural: con dos o más, cada uno recibe su tramo. */
  readonly gruposRango = signal<string[]>([]);
  /** Vista previa del servidor: el tramo de cada grupo con el rango elegido. */
  readonly vistaGrupos = signal<VistaBalanceo | null>(null);
  readonly cargandoGrupos = signal(false);
  private readonly pedidosVistaGrupos = new Subject<{
    id: number;
    clusterId: number;
    desde: number | null;
    hasta: number | null;
  } | null>();

  /** Cada cambio de rango pide la vista previa de los tramos (la última gana). */
  private readonly vistaGruposSuscripcion = this.pedidosVistaGrupos
    .pipe(
      debounceTime(150),
      switchMap((pedido) =>
        pedido === null
          ? of(null)
          : this.api
              .vistaBalanceo(pedido.id, pedido.clusterId, pedido.desde, pedido.hasta, 'DIA')
              .pipe(catchError(() => of(null))),
      ),
      takeUntilDestroyed(this.destroyRef),
    )
    .subscribe((vista) => {
      this.vistaGrupos.set(vista);
      this.cargandoGrupos.set(false);
    });

  private readonly pedirVistaGrupos = effect(() => {
    const cluster = this.rangoPendiente();
    const id = this.seleccionado();
    const grupos = this.gruposRango();
    const desde = this.rangoDesde();
    const hasta = this.rangoHasta();
    if (!cluster || id === null || grupos.length < 2) {
      this.cargandoGrupos.set(false);
      this.vistaGrupos.set(null);
      this.pedidosVistaGrupos.next(null);
      return;
    }
    this.cargandoGrupos.set(true);
    this.pedidosVistaGrupos.next({ id, clusterId: cluster.id, desde, hasta });
  });

  /**
   * Se puede balancear: con un solo grupo, basta el rango; con varios, la vista previa del
   * servidor llegó para este cluster y no tiene aviso.
   */
  readonly rangoListo = computed(() => {
    if (this.gruposRango().length < 2) {
      return true;
    }
    const vista = this.vistaGrupos();
    return (
      !this.cargandoGrupos() &&
      vista !== null &&
      vista.aviso === null &&
      vista.clusterId === this.rangoPendiente()?.id
    );
  });

  /**
   * Resumen del rango de las diarias, para decidir de un vistazo: chutes del rango, las
   * diarias del cluster (rutas y bultos), cuántas entrarán y el mínimo de operarios (uno por
   * chute con carga). Con varios grupos, la barra de tramos sale de la vista del servidor.
   */
  readonly vistaRango = computed(() => {
    const cluster = this.rangoPendiente();
    const cfg = this.configs().DIA;
    const desde = this.rangoDesde();
    const hasta = this.rangoHasta();
    if (!cluster || !cfg || desde === null || hasta === null) {
      return null;
    }
    const orden = this.opcionesChute();
    const a = orden.findIndex((chute) => chute.id === desde);
    const b = orden.findIndex((chute) => chute.id === hasta);
    if (a < 0 || b < 0) {
      return null;
    }
    const inicio = Math.min(a, b);
    const fin = Math.max(a, b);
    const incluidos = new Set(orden.slice(inicio, fin + 1).map((chute) => chute.id));
    // Carga de las diarias del cluster (la configuración de Diarias solo trae diarias).
    const filas = cfg.carga.filter((fila) => fila.clusterId === cluster.id && fila.rutas > 0);
    const rutas = filas.reduce((total, fila) => total + fila.rutas, 0);
    // Bloqueados que quedan entre «desde» y «hasta»: el rango se los salta.
    const numero = (nombre: string) => numeroChute(nombre) ?? Number.NaN;
    const n1 = numero(orden[inicio].etiqueta);
    const n2 = numero(orden[fin].etiqueta);
    const saltados = (cfg.chutesBloqueados ?? []).filter((nombre) => {
      const n = numero(nombre);
      return n > n1 && n < n2;
    });
    const tramos = this.gruposRango().length > 1 ? this.vistaGrupos() : null;
    const conTramos = tramos !== null && tramos.aviso === null && tramos.clusterId === cluster.id;
    const colores = this.coloresGrupo().get(cluster.id);
    // L-X-V y M-J-S usan este mismo rango: el día con menos rutas (las suyas y las diarias) que
    // chutes deja libres los del final. Solo informa (con grupos es por tramo: es una estimación).
    const chutesUtiles = conTramos ? tramos.grupos.reduce((total, g) => total + g.chutes, 0) : incluidos.size;
    const { rutas: porFrecuencia } = this.avanceCluster(cluster.id);
    const cortos = DIAS_BALANCEO
      .map((dia) => ({ dia, propias: dia === 'LXV' ? porFrecuencia.lxv : porFrecuencia.mjs }))
      .filter((d) => d.propias > 0)
      .map((d) => ({ etiqueta: ETIQUETA_BALANCEO[d.dia], rutas: d.propias + porFrecuencia.dia }))
      .filter((d) => d.rutas < chutesUtiles)
      .map((d) => ({ ...d, libres: chutesUtiles - d.rutas }));
    return {
      chutesUtiles,
      cortos,
      primero: orden[inicio].etiqueta,
      ultimo: orden[fin].etiqueta,
      saltados,
      chutes: incluidos.size,
      rutas,
      bultos: filas.reduce((total, fila) => total + Number(fila.bultos), 0),
      fuera: conTramos
        ? tramos.grupos.reduce((total, t) => total + t.rutasFuera, 0)
        : filas.filter((fila) => !incluidos.has(fila.chuteId)).reduce((total, fila) => total + fila.rutas, 0),
      // Con menos rutas que chutes, algunos quedan vacíos: no necesitan gente.
      minimo: conTramos ? tramos.grupos.reduce((total, t) => total + t.chutes, 0) : Math.min(incluidos.size, rutas),
      /** Barra de tramos: un segmento por grupo, del ancho de sus chutes. */
      tramos: conTramos
        ? tramos.grupos.map((t) => ({
            grupo: t.grupo,
            chutes: t.chutes,
            desde: t.chuteDesde,
            hasta: t.chuteHasta,
            color: colores?.get(t.grupo) ?? null,
          }))
        : [],
      libres: conTramos ? tramos.chutesSinUsar : 0,
    };
  });

  readonly restablecerAbierto = signal(false);
  /** Cluster cuyo restablecimiento se está confirmando. */
  readonly clusterARestablecer = signal<{ id: number; nombre: string } | null>(null);
  /** Cluster cerrado que se va a reabrir y los siguientes que vuelven a la proyección. */
  readonly clusterAReabrir = signal<{ id: number; nombre: string; siguientes: string[] } | null>(
    null,
  );

  // ---------------- Carga de Excel desde configuración ----------------
  readonly subirAbierto = signal(false);
  readonly archivoSeleccionado = signal<File | null>(null);
  readonly subiendo = signal(false);
  readonly explicacionAbierta = signal(false);
  readonly formulario = this.fb.nonNullable.group({
    nombreRegistro: [
      '',
      [Validators.required, Validators.pattern(/\S/), Validators.maxLength(150)],
    ],
    etiqueta: ['', [Validators.maxLength(50)]],
    colorHex: [PALETA_REGISTROS[0].hex],
  });

  /**
   * Columnas de la matriz: los chutes del filtro (sin filtro, todos), en orden
   * natural (CHUTE 2 antes que CHUTE 10). Celdas y totales usan este mismo
   * orden, así que filtrar columnas no desalinea nada.
   */
  readonly chutesMatriz = computed(() => {
    const elegidos = new Set(this.filtroChute());
    return (this.config()?.chutes ?? [])
      .filter((chute) => elegidos.size === 0 || elegidos.has(chute.id))
      .sort((a, b) => compararChute(a.nombre, b.nombre))
      .map((chute) => ({ ...chute, etiqueta: etiquetaChute(chute.nombre) }));
  });

  /**
   * Rutas agrupadas por cluster y chute. Cada bloque ocupa tantas filas como
   * la celda más cargada, para que las rutas se apilen en su columna como en
   * el Excel de referencia.
   */
  /**
   * Celdas de cada cluster (filas × chutes). Solo dependen de las rutas, los
   * chutes y qué clusters están cerrados: cambiar un límite o la velocidad
   * (que sí cambian el panel) no vuelve a armar miles de celdas.
   */
  /**
   * Color de cada grupo, por cluster (también con un solo grupo). Se
   * asigna por nombre en orden natural, no por prioridad: reordenar la prioridad
   * no le cambia el color a ningún grupo.
   */
  readonly coloresGrupo = computed(() => {
    const resultado = new Map<number, Map<string, string>>();
    for (const cluster of this.config()?.clusters ?? []) {
      const grupos = cluster.grupos ?? [];
      if (grupos.length === 0) {
        continue;
      }
      resultado.set(cluster.id, coloresGrupos(grupos.map((g) => g.nombre)));
    }
    return resultado;
  });

  private readonly celdasPorCluster = computed(() => {
    const chutes = this.chutesMatriz();
    const cerrados = new Set(
      (this.config()?.clusters ?? []).filter((c) => c.estado === 'CERRADO').map((c) => c.id),
    );
    const editable = this.puedeModificar();
    // Toda ruta muestra su grupo, aunque el cluster tenga uno solo.
    const colores = this.coloresGrupo();
    const porCluster = new Map<number, Map<number, RutaSimulacion[]>>();

    for (const ruta of this.rutasVisibles()) {
      let celdas = porCluster.get(ruta.clusterId);
      if (!celdas) {
        celdas = new Map<number, RutaSimulacion[]>();
        porCluster.set(ruta.clusterId, celdas);
      }
      const lista = celdas.get(ruta.chuteId);
      if (lista) {
        lista.push(ruta);
      } else {
        celdas.set(ruta.chuteId, [ruta]);
      }
    }

    const vista = (ruta: RutaSimulacion): CeldaRuta => ({
      ruta,
      titulo: this.tituloEnCache(ruta),
      movible: editable && !cerrados.has(ruta.clusterId),
      movida: ruta.chuteId !== ruta.chuteOriginalId || ruta.clusterId !== ruta.clusterOriginalId,
      cambioCluster: ruta.clusterId !== ruta.clusterOriginalId,
      grupo: colores.get(ruta.clusterId)?.has(ruta.grupo) ? ruta.grupo : null,
      grupoColor: colores.get(ruta.clusterId)?.get(ruta.grupo) ?? null,
      bultos: formatearEntero(ruta.totalBultos),
      frecuencia: pestanaFrecuencia(ruta),
    });

    const resultado = new Map<number, (CeldaRuta | null)[][]>();
    for (const [clusterId, celdas] of porCluster) {
      // Las rutas llegan por id (keyset): dentro de cada chute se apilan por código.
      const columnas = chutes.map((chute) =>
        (celdas.get(chute.id) ?? [])
          .sort((a, b) => (a.codigoRuta < b.codigoRuta ? -1 : a.codigoRuta > b.codigoRuta ? 1 : a.id - b.id))
          .map(vista),
      );
      const alto = Math.max(MIN_FILAS, ...columnas.map((columna) => columna.length));
      resultado.set(
        clusterId,
        Array.from({ length: alto }, (_, f) => columnas.map((columna) => columna[f] ?? null)),
      );
    }
    return resultado;
  });

  readonly bloquesMatriz = computed<BloqueMatriz[]>(() => {
    const celdas = this.celdasPorCluster();
    const vacias = (): (CeldaRuta | null)[][] => {
      const columnas = this.chutesMatriz().length;
      return Array.from({ length: MIN_FILAS }, () => Array<CeldaRuta | null>(columnas).fill(null));
    };
    // Solo los clusters del filtro. Con búsqueda por código, además, solo los que
    // tienen alguna ruta que coincide (cuando ya terminó de cargar, para que los
    // bloques no aparezcan y desaparezcan mientras llegan las páginas).
    const elegidos = new Set(this.filtroCluster());
    const conBusqueda = this.filtroTexto().trim() !== '' && !this.cargandoRutas();
    return this.resumenClusters()
      .filter((cluster) => elegidos.size === 0 || elegidos.has(cluster.id))
      .filter((cluster) => !conBusqueda || celdas.has(cluster.id))
      .map((cluster) => ({
        cluster,
        vista: this.vistaDe(cluster.id),
        filtros: this.filtrosDe(cluster.id),
        ...this.estadoBloque(cluster.id),
        filas: celdas.get(cluster.id) ?? vacias(),
      }));
  });

  /** Títulos por ruta: cada objeto ruta es inmutable (moverla crea otro), así que se calculan una vez. */
  private readonly titulos = new WeakMap<RutaSimulacion, string>();

  private tituloEnCache(ruta: RutaSimulacion): string {
    let titulo = this.titulos.get(ruta);
    if (titulo === undefined) {
      titulo = this.tituloRuta(ruta);
      this.titulos.set(ruta, titulo);
    }
    return titulo;
  }

  /**
   * Total general por chute con los filtros vigentes: solo los clusters y
   * chutes elegidos (sin filtro, todos). Sale de `carga`, ya agregada por
   * cluster y chute, así que no depende de que la matriz termine de cargar.
   * Con búsqueda por código, la carga agregada no sirve: se suman las rutas
   * que trajo el servidor con todos los filtros, y los operarios siguen siendo
   * los de la ola de cada (cluster, chute).
   */
  readonly totalesMatriz = computed(() => {
    const clusters = new Set(this.filtroCluster());
    const chutes = new Set(this.filtroChute());
    const carga = (this.config()?.carga ?? []).filter(
      (fila) =>
        (clusters.size === 0 || clusters.has(fila.clusterId)) &&
        (chutes.size === 0 || chutes.has(fila.chuteId)),
    );
    if (!this.filtroTexto().trim()) {
      return this.totalesDeCarga(carga, true);
    }
    const operarios = new Map(carga.map((fila) => [`${fila.clusterId}:${fila.chuteId}`, fila.operarios]));
    const porPar = new Map<string, CargaSimulacion>();
    for (const ruta of this.rutasVisibles()) {
      const clave = `${ruta.clusterId}:${ruta.chuteId}`;
      let fila = porPar.get(clave);
      if (!fila) {
        fila = {
          clusterId: ruta.clusterId,
          chuteId: ruta.chuteId,
          rutas: 0,
          bultos: 0,
          unidades: 0,
          lineas: 0,
          cod: 0,
          operarios: operarios.get(clave) ?? null,
        };
        porPar.set(clave, fila);
      }
      fila.rutas += 1;
      fila.bultos += Number(ruta.totalBultos ?? 0);
      fila.unidades += Number(ruta.totalUnidades ?? 0);
      fila.lineas += Number(ruta.totalLineas ?? 0);
      fila.cod += Number(ruta.conteoCod ?? 0);
    }
    return this.totalesDeCarga([...porPar.values()], true);
  });

  /**
   * Panel de cada cluster (como la columna izquierda de la planilla): rutas,
   * bultos, líneas, unidades y códigos, más sus totales por chute para el
   * desplegable «Totales». También salen de `carga`, no de las rutas filtradas.
   */
  readonly metricasCluster = computed(() => {
    const carga = this.config()?.carga ?? [];
    const metricas = new Map<number, MetricasCluster>();
    for (const cluster of this.resumenClusters()) {
      const filas = carga.filter((fila) => fila.clusterId === cluster.id);
      const suma = (campo: CampoCarga) => filas.reduce((total, fila) => total + Number(fila[campo] ?? 0), 0);
      metricas.set(cluster.id, {
        rutas: suma('rutas'),
        bultos: suma('bultos'),
        lineas: suma('lineas'),
        unidades: suma('unidades'),
        cod: suma('cod'),
        porChute: this.totalesPorChute(cluster.id),
      });
    }
    return metricas;
  });

  /**
   * Filas de las tablas de totales (por cluster y general). Cada una se
   * identifica con su ícono a color y sus celdas llevan el semáforo por línea.
   * Los valores se muestran enteros, redondeados hacia arriba (pipe `entero`).
   */
  readonly filasTotales: { clave: CampoTotal; etiqueta: string; corta: string }[] = [
    { clave: 'operarios', etiqueta: 'Total operarios', corta: 'Ope.' },
    { clave: 'bultos', etiqueta: 'Total bultos', corta: 'Bul.' },
    { clave: 'lineas', etiqueta: 'Total líneas', corta: 'Lín.' },
    { clave: 'unidades', etiqueta: 'Total unidades', corta: 'Uni.' },
    { clave: 'cod', etiqueta: 'Total locales', corta: 'Loc.' },
  ];

  /** Filas visibles de cada cluster antes del scroll vertical (las fija MIN_FILAS). */
  readonly filasVisibles = MIN_FILAS;

  // ---------------- Minimizar clusters ----------------

  /**
   * Un solo interruptor para toda la matriz: minimizado, el panel de cada
   * cluster y la columna de etiquetas de todos los totales (también el
   * general) pasan a un riel delgado y los chutes ganan ese ancho.
   */
  readonly minimizado = signal(false);

  alternarMinimizado(): void {
    this.minimizado.update((valor) => !valor);
  }

  // ---------------- Detalle flotante (ruta o cluster) ----------------

  /** Ficha abierta: qué muestra y dónde se dibuja, junto al elemento que la abrió. */
  readonly detalle = signal<DetalleAbierto | null>(null);

  /** Ruta de la ficha, siempre la versión vigente (si se mueve, se actualiza). */
  readonly rutaDetalle = computed(() => {
    const abierto = this.detalle();
    if (abierto?.tipo !== 'ruta') {
      return null;
    }
    const ruta = this.rutasPorId().get(abierto.id);
    if (!ruta) {
      return null;
    }
    // En «Ambos», la parte se mide en el día de la ruta (una diaria, en L-X-V): sumar los
    // dos días la haría parecer más chica.
    const vista = this.vistaDe(ruta.clusterId);
    const balanceo: Balanceo =
      vista !== 'AMBOS' ? vista : ruta.frecuencia === 'MJS' ? 'MJS' : 'LXV';
    const chute = (this.configs()[balanceo]?.carga ?? []).find(
      (fila) => fila.clusterId === ruta.clusterId && fila.chuteId === ruta.chuteId,
    );
    const bultosChute = Number(chute?.bultos ?? 0);
    const cerrado = this.clusterCerrado(ruta.clusterId);
    return {
      ruta,
      color: this.resumenClusters().find((cluster) => cluster.id === ruta.clusterId)?.color ?? null,
      /** El mismo color de la pestaña del grupo en la cuadrícula y en el PDF. */
      colorGrupo: ruta.grupo ? (this.coloresGrupo().get(ruta.clusterId)?.get(ruta.grupo) ?? null) : null,
      frecuencia: pestanaFrecuencia(ruta),
      cerrado,
      movida: ruta.chuteId !== ruta.chuteOriginalId || ruta.clusterId !== ruta.clusterOriginalId,
      /** Parte de los bultos de su chute (en este cluster) que lleva la ruta. */
      parte: bultosChute > 0 ? Math.min(100, redondearArriba((Number(ruta.totalBultos ?? 0) / bultosChute) * 100)) : null,
      operarios: chute?.operarios ?? null,
      aviso: !this.puedeModificar()
        ? null
        : cerrado
          ? `${ruta.cluster} está cerrado: reábrelo para mover sus rutas.`
          : 'Arrástrala a otro chute o cluster para moverla.',
    };
  });

  /** Cluster de la ficha, con sus métricas vigentes. */
  readonly clusterDetalle = computed(() => {
    const abierto = this.detalle();
    if (abierto?.tipo !== 'cluster') {
      return null;
    }
    const cluster = this.resumenClusters().find((c) => c.id === abierto.id);
    if (!cluster) {
      return null;
    }
    const grupos = this.config()?.clusters.find((c) => c.id === cluster.id)?.grupos ?? [];
    const a = this.avanceCluster(cluster.id);
    // Cierre de cada día con sus operarios (las diarias no son una ola: no tienen cierre propio).
    const cierres = this.resumenPorDia().flatMap(({ etiqueta, porCluster }) => {
      const dia = porCluster.get(cluster.id);
      return dia?.conOperarios
        ? [{ etiqueta, ...dia, exceso: dia.limite > 0 && Number.isFinite(dia.tiempo) ? Math.max(0, dia.tiempo - dia.limite) : 0 }]
        : [];
    });
    // Franja de cada tramo dentro del rango que ocupan todos los grupos (orden de los chutes).
    const indice = new Map(this.opcionesChute().map((chute, i) => [chute.id, i]));
    const tramos = grupos.map((g) => ({
      desde: g.chuteDesdeId == null ? undefined : indice.get(g.chuteDesdeId),
      hasta: g.chuteHastaId == null ? undefined : indice.get(g.chuteHastaId),
    }));
    const conTramo = tramos.filter((t) => t.desde !== undefined && t.hasta !== undefined) as { desde: number; hasta: number }[];
    const inicio = conTramo.length > 0 ? Math.min(...conTramo.map((t) => Math.min(t.desde, t.hasta))) : 0;
    const fin = conTramo.length > 0 ? Math.max(...conTramo.map((t) => Math.max(t.desde, t.hasta))) : 0;
    const total = fin - inicio + 1;
    return {
      c: cluster,
      m: this.metricasCluster().get(cluster.id) ?? null,
      /** En «Ambos» la carga es de los dos días: la dispersión de un día no aplica. */
      ambos: this.vistaDe(cluster.id) === 'AMBOS',
      /** La misma cantidad trabaja los dos días: la mayor que se repartió. */
      operarios: cierres.length > 0 ? Math.max(...cierres.map((dia) => dia.operarios)) : null,
      cierres,
      etiquetaVista: this.etiquetaVista(cluster.id),
      estado: a.cerrado ? 'Cerrado' : a.enCurso ? 'En curso' : 'En espera',
      iconoEstado: a.cerrado ? 'lock' : a.enCurso ? 'activity' : 'reloj',
      iniciales: inicialesCluster(cluster.nombre),
      pasos: this.pasosDe(cluster.id),
      // Todos sus grupos (aunque sea uno), con el color de su pestaña, su tramo y su franja.
      grupos: grupos.map((g, i) => {
        const t = tramos[i];
        const franja =
          conTramo.length > 1 && t.desde !== undefined && t.hasta !== undefined
            ? {
                inicio: ((Math.min(t.desde, t.hasta) - inicio) / total) * 100,
                ancho: ((Math.abs(t.hasta - t.desde) + 1) / total) * 100,
              }
            : null;
        return { ...g, color: this.coloresGrupo().get(cluster.id)?.get(g.nombre) ?? null, franja };
      }),
    };
  });

  /** Abre (o cierra, si ya estaba abierta) la ficha de una ruta junto a ella. */
  abrirDetalleRuta(evento: Event, rutaId: number): void {
    this.abrirDetalle(evento, 'ruta', rutaId, 300);
  }

  /** Abre (o cierra) la ficha con el detalle de un cluster junto a su botón. */
  abrirDetalleCluster(evento: Event, clusterId: number): void {
    this.abrirDetalle(evento, 'cluster', clusterId, 380);
  }

  cerrarDetalle(): void {
    this.detalle.set(null);
  }

  private abrirDetalle(evento: Event, tipo: DetalleAbierto['tipo'], id: number, alto: number): void {
    const ancla = evento.currentTarget;
    if (!(ancla instanceof HTMLElement)) {
      return;
    }
    if (evento instanceof KeyboardEvent) {
      evento.preventDefault();
    }
    const actual = this.detalle();
    if (actual?.tipo === tipo && actual.id === id) {
      this.detalle.set(null);
      return;
    }
    this.detalle.set({ tipo, id, ancla, pos: posicionDetalle(ancla, alto) });
  }

  /**
   * Mientras hay una ficha abierta: se cierra con Escape, al tocar fuera, al
   * desplazar la página o la matriz (quedaría lejos de su ruta) y al cambiar
   * el tamaño de la ventana. Listeners nativos (sin zone.js): solo tocan la señal.
   */
  private readonly cierreDetalle = effect((alLimpiar) => {
    const abierto = this.detalle();
    if (!abierto) {
      return;
    }
    const dentro = (objetivo: EventTarget | null): boolean => {
      if (!(objetivo instanceof Node)) {
        return false;
      }
      const elemento = objetivo instanceof Element ? objetivo : objetivo.parentElement;
      return abierto.ancla.contains(objetivo) || !!elemento?.closest('.detalle-pop');
    };
    const alTocar = (e: PointerEvent) => {
      if (!dentro(e.target)) {
        this.detalle.set(null);
      }
    };
    const alTeclear = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        this.detalle.set(null);
        abierto.ancla.focus({ preventScroll: true });
      }
    };
    const alDesplazar = (e: Event) => {
      if (!dentro(e.target)) {
        this.detalle.set(null);
      }
    };
    const alRedimensionar = () => this.detalle.set(null);
    document.addEventListener('pointerdown', alTocar, true);
    document.addEventListener('keydown', alTeclear);
    document.addEventListener('scroll', alDesplazar, { capture: true, passive: true });
    window.addEventListener('resize', alRedimensionar);
    alLimpiar(() => {
      document.removeEventListener('pointerdown', alTocar, true);
      document.removeEventListener('keydown', alTeclear);
      document.removeEventListener('scroll', alDesplazar, true);
      window.removeEventListener('resize', alRedimensionar);
    });
  });

  /** Clusters con el desplegable de totales por chute abierto. */
  readonly totalesAbiertos = signal<ReadonlySet<number>>(new Set());

  alternarTotales(clusterId: number): void {
    this.totalesAbiertos.update((abiertos) => {
      const siguiente = new Set(abiertos);
      if (!siguiente.delete(clusterId)) {
        siguiente.add(clusterId);
      }
      return siguiente;
    });
  }

  /** Totales por chute (en el orden de las columnas) de un cluster. */
  private totalesPorChute(clusterId: number): TotalesChute {
    return this.totalesDeCarga(
      (this.config()?.carga ?? []).filter((fila) => fila.clusterId === clusterId),
      false,
    );
  }

  /**
   * Suma por chute de unas filas de carga. En el total general (`general`), los
   * operarios de un chute son el máximo de sus olas: el mismo equipo pasa de un
   * cluster al siguiente, sumarlas lo contaría varias veces.
   */
  private totalesDeCarga(carga: CargaSimulacion[], general: boolean): TotalesChute {
    const chutes = this.chutesMatriz();
    const porChute = new Map<number, CargaSimulacion[]>();
    for (const fila of carga) {
      const lista = porChute.get(fila.chuteId);
      if (lista) {
        lista.push(fila);
      } else {
        porChute.set(fila.chuteId, [fila]);
      }
    }
    const columna = (campo: CampoCarga) =>
      chutes.map((chute) =>
        (porChute.get(chute.id) ?? []).reduce((total, fila) => {
          const valor = Number(fila[campo] ?? 0);
          return general && campo === 'operarios' ? Math.max(total, valor) : total + valor;
        }, 0),
      );
    const valores = {
      operarios: columna('operarios'),
      bultos: columna('bultos'),
      unidades: columna('unidades'),
      lineas: columna('lineas'),
      cod: columna('cod'),
    };
    return {
      ...valores,
      niveles: {
        operarios: nivelesDeFila(valores.operarios),
        bultos: nivelesDeFila(valores.bultos),
        unidades: nivelesDeFila(valores.unidades),
        lineas: nivelesDeFila(valores.lineas),
        cod: nivelesDeFila(valores.cod),
      },
    };
  }

  readonly opcionesRegistro = computed(() =>
    this.registros().map((registro) => ({ id: registro.id, etiqueta: etiquetaRegistro(registro) })),
  );

  /** Chutes que se pueden elegir (filtro y rango): sin los bloqueados. */
  readonly opcionesChute = computed(() =>
    [...(this.config()?.chutes ?? [])]
      .filter((chute) => !chute.bloqueado)
      .sort((a, b) => compararChute(a.nombre, b.nombre))
      .map((chute) => ({ id: chute.id, etiqueta: chute.nombre })),
  );

  readonly opcionesCluster = computed(() =>
    [...(this.config()?.clusters ?? [])]
      .sort((a, b) => compararNombre(a.nombre, b.nombre))
      .map((cluster) => ({ id: cluster.id, etiqueta: cluster.nombre })),
  );

  readonly filtrosActivos = computed(
    () =>
      this.filtroChute().length + this.filtroCluster().length + (this.filtroTexto().trim() ? 1 : 0),
  );

  readonly chips = computed<ChipFiltro[]>(() => {
    const chips: ChipFiltro[] = [];
    for (const chute of this.opcionesChute()) {
      if (this.filtroChute().includes(Number(chute.id))) {
        chips.push({
          clave: `chute:${chute.id}`,
          etiqueta: 'Chute',
          valor: chute.etiqueta,
          icono: 'layers',
        });
      }
    }
    for (const cluster of this.opcionesCluster()) {
      if (this.filtroCluster().includes(Number(cluster.id))) {
        chips.push({
          clave: `cluster:${cluster.id}`,
          etiqueta: 'Cluster',
          valor: cluster.etiqueta,
          icono: 'area',
        });
      }
    }
    const texto = this.filtroTexto().trim();
    if (texto) {
      chips.push({ clave: 'texto:', etiqueta: 'Ruta', valor: texto, icono: 'search' });
    }
    return chips;
  });

  alternarFiltros(): void {
    this.filtrosAbierto.update((abierto) => !abierto);
  }

  /** Cada cluster en el orden de las olas, tal como lo muestra su tarjeta. */
  readonly resumenClusters = computed(() => {
    const cfg = this.config();
    if (!cfg) {
      return [];
    }
    // La configuración compuesta ya trae, por cluster, el avance y la carga de lo que
    // muestra; el límite es uno solo para los tres balanceos.
    return resumirClusters(cfg, this.velocidad() || VELOCIDAD_INICIAL, this.limites());
  });

  /**
   * Resumen de cada día (L-X-V y M-J-S) con su propia carga y operarios, sea cual sea la
   * vista: el cierre de un cluster es el de cada día, no el de la vista que se mira.
   */
  private readonly resumenPorDia = computed(() => {
    const configs = this.configs();
    const velocidad = this.velocidad() || VELOCIDAD_INICIAL;
    const limites = this.limites();
    return DIAS_BALANCEO.map((balanceo) => {
      const cfg = configs[balanceo];
      return {
        etiqueta: ETIQUETA_BALANCEO[balanceo],
        porCluster: new Map(
          (cfg ? resumirClusters(cfg, velocidad, limites) : []).map((resumen) => [resumen.id, resumen]),
        ),
        balanceo,
      };
    });
  });

  /**
   * Hora conseguida junto al límite de cada cluster (la establecida): la del día que se mira;
   * en «Ambos» o «Diarias», la del día que más tarda, que es la que decide si cumple. El
   * title trae los dos días. Solo clusters con operarios asignados en algún día.
   */
  readonly cierrePanel = computed(() => {
    const dias = this.resumenPorDia();
    const mapa = new Map<number, { tiempo: number; fuera: boolean; conLimite: boolean; titulo: string }>();
    for (const cluster of this.configs().LXV?.clusters ?? []) {
      const vista = this.vistaDe(cluster.id);
      const conTiempo = dias.flatMap(({ etiqueta, balanceo, porCluster }) => {
        const dia = porCluster.get(cluster.id);
        return dia?.conOperarios ? [{ etiqueta, balanceo, ...dia }] : [];
      });
      const mirados = vista === 'LXV' || vista === 'MJS' ? conTiempo.filter((d) => d.balanceo === vista) : conTiempo;
      if (mirados.length === 0) {
        continue;
      }
      const peor = mirados.reduce((a, b) => (b.tiempo > a.tiempo ? b : a));
      mapa.set(cluster.id, {
        tiempo: peor.tiempo,
        fuera: mirados.some((d) => d.limite > 0 && !d.cumple),
        conLimite: mirados.some((d) => d.limite > 0),
        titulo:
          'Hora conseguida · ' + conTiempo.map((d) => `${d.etiqueta} ${formatearHoras(d.tiempo)}`).join(' · '),
      });
    }
    return mapa;
  });

  /** «Diarias», «L-X-V», «M-J-S» o «L-X-V + M-J-S», para títulos y fichas. */
  etiquetaVista(clusterId: number): string {
    const vista = this.vistaDe(clusterId);
    return vista === 'AMBOS' ? 'L-X-V + M-J-S' : ETIQUETA_BALANCEO[vista];
  }

  constructor() {
    // Se compara con el filtro vigente (no con el último texto emitido): tras
    // «Limpiar», volver a escribir el mismo código sí vuelve a filtrar.
    this.busqueda$.pipe(debounceTime(300), takeUntilDestroyed()).subscribe((texto) => {
      if (texto.trim() === this.filtroTexto().trim()) {
        return;
      }
      this.filtroTexto.set(texto);
      this.recargarRutas();
    });

    this.recargarRutas$
      .pipe(
        tap((suave) => {
          this.generacionRutas++;
          this.cargandoRutas.set(true);
          this.errorRutas.set(null);
          this.rutasCargadas.set(0);
          if (!suave) {
            this.rutasMatriz.set([]);
            this.totalRutas.set(0);
          }
        }),
        switchMap((suave) => {
          const generacion = this.generacionRutas;
          let ultimo: EstadoRutasMatriz | null = null;
          return this.cargarTodasLasRutas().pipe(
            tap({
              next: (estado) => {
                if (generacion !== this.generacionRutas) {
                  return;
                }
                ultimo = estado;
                this.rutasCargadas.set(estado.rutas.length);
                this.totalRutas.set(estado.total);
                // Carga nueva: se pinta mientras llega. Recarga suave: se cambia al final,
                // así la planilla no desaparece ni pierde su desplazamiento.
                if (!suave) {
                  this.rutasMatriz.set(estado.rutas);
                }
              },
              error: (err: unknown) => {
                if (generacion !== this.generacionRutas) {
                  return;
                }
                this.errorRutas.set(mensajeDeError(err));
                this.cargandoRutas.set(false);
              },
              complete: () => {
                if (generacion === this.generacionRutas) {
                  if (suave && ultimo) {
                    this.rutasMatriz.set(ultimo.rutas);
                  }
                  this.cargandoRutas.set(false);
                }
              },
            }),
            // Un fallo de carga no debe romper el flujo que escucha los filtros.
            // El mensaje ya se guardó en `errorRutas` para mostrarlo en la vista.
            catchError(() => EMPTY),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();

    this.cargarOpciones();
    this.cargarPredeterminados();
  }

  cargarOpciones(preferirId?: number): void {
    this.cargandoOpciones.set(true);
    this.errorOpciones.set(null);
    this.api
      .opciones()
      .pipe(
        finalize(() => this.cargandoOpciones.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (lista) => {
          this.registros.set(lista);
          if (lista.length === 0) {
            this.seleccionado.set(null);
            this.configs.set({ LXV: null, MJS: null, DIA: null });
            return;
          }
          const solicitado = Number(this.route.snapshot.queryParamMap.get('registro'));
          const actual = this.seleccionado();
          const elegido =
            lista.find((registro) => registro.id === preferirId)?.id ??
            lista.find((registro) => registro.id === actual)?.id ??
            lista.find((registro) => registro.id === solicitado)?.id ??
            lista[0].id;
          this.seleccionar(elegido);
        },
        error: (err: unknown) => this.errorOpciones.set(mensajeDeError(err)),
      });
  }

  seleccionarDesdeSelector(id: number | null): void {
    if (this.ocupado()) {
      this.notificaciones.advertencia('Operación en curso', 'Espera a que termine la operación.');
      return;
    }
    if (this.avisarCambiosSinGuardar('cambiar de registro')) {
      return;
    }
    this.seleccionar(id);
  }

  actualizar(): void {
    if (!this.ocupado() && !this.avisarCambiosSinGuardar('actualizar')) {
      this.cargarOpciones();
    }
  }

  /** Con un parámetro o un límite sin guardar, avisa en lugar de descartarlos. */
  private avisarCambiosSinGuardar(accion: string): boolean {
    if (this.dirty()) {
      this.notificaciones.advertencia(
        'Cambios sin guardar',
        `Guarda los parámetros y los límites antes de ${accion}.`,
      );
    }
    return this.dirty();
  }

  seleccionar(id: number | null): void {
    // Los filtros pertenecen al registro: solo se limpian al cambiar de registro,
    // no al recargar el mismo con «Actualizar».
    if (id !== this.seleccionado()) {
      this.reiniciarFiltros();
      this.rutasMatriz.set([]);
      this.rutasCargadas.set(0);
      this.totalRutas.set(0);
      this.errorRutas.set(null);
      this.vistas.set({});
    }
    this.seleccionado.set(id);
    this.configs.set({ LXV: null, MJS: null, DIA: null });
    this.error.set(null);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { registro: id },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    if (id === null) {
      this.solicitud++;
      this.cargando.set(false);
      return;
    }
    this.cargarConfiguracion(id, true);
  }

  /**
   * Carga la carga agregada por cluster y chute y, después, todas las rutas
   * que alimentan la matriz. Con `inicial` muestra el estado de carga a
   * pantalla completa; si no (tras balancear), recarga en segundo plano.
   */
  private cargarConfiguracion(id: number, inicial = false): void {
    const solicitud = ++this.solicitud;
    if (inicial) {
      this.cargando.set(true);
    } else {
      this.recargando.set(true);
    }
    forkJoin([
      this.api.configuracion(id, 'LXV'),
      this.api.configuracion(id, 'MJS'),
      this.api.configuracion(id, 'DIA'),
    ])
      .pipe(
        finalize(() => {
          if (solicitud === this.solicitud) {
            this.cargando.set(false);
            this.recargando.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ([lxv, mjs, dia]) => {
          if (solicitud === this.solicitud) {
            this.aplicarConfiguracion(dia);
            this.aplicarConfiguracion(mjs);
            this.aplicarConfiguracion(lxv);
            // Tras un paso, la planilla se mantiene hasta tener la nueva.
            this.recargarRutas(!inicial);
          }
        },
        error: (err: unknown) => {
          if (solicitud !== this.solicitud) {
            return;
          }
          if (inicial) {
            this.error.set(mensajeDeError(err));
          } else {
            this.notificaciones.error(
              'No se pudo actualizar la vista',
              `Los cambios se guardaron, pero la configuración no se recargó: ${mensajeDeError(err)} Pulsa «Actualizar».`,
            );
          }
        },
      });
  }

  /**
   * Carga la configuración de un balanceo y descarta lo editado de los parámetros y los
   * límites (velocidad, máximo por chute, buff y límite son uno solo para los tres).
   */
  private aplicarConfiguracion(config: ConfiguracionSimulacion): void {
    const balanceo: Balanceo = config.balanceo === 'MJS' || config.balanceo === 'DIA' ? config.balanceo : 'LXV';
    this.configs.update((actual) => ({ ...actual, [balanceo]: config }));
    const equipo = equipoNormalizado(config);
    this.velocidad.set(equipo.velocidad);
    this.porChute.set(equipo.porChute);
    this.buffElegidos.set(config.chutes.filter((chute) => chute.buff).map((chute) => chute.id));
    this.limites.set(
      Object.fromEntries(config.clusters.map((cluster) => [cluster.id, Number(cluster.limiteHoras)])),
    );
  }

  /**
   * Tras escribir en un balanceo, los otros dos también pueden haber cambiado (una diaria
   * movida, operarios invalidados, el avance): se piden juntos. Comparte el contador de
   * solicitudes con la carga completa, así una respuesta vieja nunca pisa una nueva, y
   * mientras llegan las acciones siguen bloqueadas (`recargando`).
   */
  private refrescarDemas(id: number, aplicado: Balanceo, rutas: 'suave' | 'no' = 'no'): void {
    const solicitud = ++this.solicitud;
    const otros = (['LXV', 'MJS', 'DIA'] as const).filter((b) => b !== aplicado);
    this.recargando.set(true);
    forkJoin(otros.map((balanceo) => this.api.configuracion(id, balanceo)))
      .pipe(
        finalize(() => {
          if (solicitud === this.solicitud) {
            this.recargando.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (configs) => {
          if (solicitud !== this.solicitud || this.seleccionado() !== id) {
            return;
          }
          configs.forEach((config) => this.aplicarConfiguracion(config));
          // Lo editado vuelve a ser lo del balanceo que respondió primero (todos iguales).
          const propio = this.configs()[aplicado];
          if (propio) {
            this.aplicarConfiguracion(propio);
          }
          if (rutas === 'suave') {
            this.recargarRutas(true);
          }
        },
        error: (err: unknown) => {
          if (solicitud === this.solicitud) {
            this.notificaciones.error(
              'No se pudo actualizar la vista',
              `Los cambios se guardaron, pero la vista no se recargó: ${mensajeDeError(err)} Pulsa «Actualizar».`,
            );
          }
        },
      });
  }

  /**
   * Pide todas las rutas del registro (con los filtros vigentes) encadenando
   * páginas de 1 000, el máximo que acepta el servidor. Es keyset: cada página
   * pide las rutas después del último id recibido (sin OFFSET), y solo la
   * primera trae el total. Cada página se acumula sobre la anterior.
   */
  private cargarTodasLasRutas(): Observable<EstadoRutasMatriz> {
    const id = this.seleccionado();
    if (id === null) {
      return of({ rutas: [], total: 0 });
    }
    const filtros = {
      texto: this.filtroTexto().trim(),
      clusterIds: this.filtroCluster(),
      chuteIds: this.filtroChute(),
    };
    const pedir = (despues: number | null) =>
      this.api.rutas(id, {
        despues,
        tamano: SimulacionConfiguracion.TAMANO_PAGINA_RUTAS,
        ...filtros,
      });

    return pedir(null).pipe(
      expand((pagina) => (pagina.siguiente !== null ? pedir(pagina.siguiente) : EMPTY)),
      scan(
        (estado, pagina) => ({
          rutas: estado.rutas.concat(pagina.contenido),
          total: pagina.total ?? estado.total,
        }),
        { rutas: [] as RutaSimulacion[], total: 0 },
      ),
    );
  }

  /** Vuelve a pedir la matriz completa; cada llamada cancela la carga anterior. */
  private recargarRutas(suave = false): void {
    this.recargarRutas$.next(suave);
  }

  /** Reintento manual desde el aviso de error. */
  reintentarRutas(): void {
    this.recargarRutas();
  }

  campoInvalido(campo: 'nombreRegistro'): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  /** Por qué el cluster de una ruta no se puede cambiar (título del chip). */
  motivoClusterFijo(ruta: RutaSimulacion): string {
    const propio = this.config()?.clusters.find((cluster) => cluster.id === ruta.clusterId);
    if (propio?.estado === 'CERRADO') {
      return `${ruta.cluster} está cerrado: reábrelo para cambiar sus rutas de cluster.`;
    }
    return 'No hay otro cluster abierto al que pasar la ruta.';
  }

  /** Detalle de la ruta al pasar el mouse y, si no se puede mover, el motivo. */
  tituloRuta(ruta: RutaSimulacion): string {
    const n = (valor: number) => formatearEntero(valor);
    const detalle =
      `${ruta.codigoRuta} · ${ruta.tipo}${ruta.grupo ? ` · Grupo ${ruta.grupo}` : ''} · ${textoFrecuencia(ruta.frecuencia, ruta.dias)}${ruta.diariaFija ? ' (fija)' : ''}\n` +
      `Bultos: ${n(ruta.totalBultos)} · Unidades: ${n(ruta.totalUnidades)} · ` +
      `Líneas: ${n(ruta.totalLineas)} · COD: ${formatearEntero(ruta.conteoCod)}`;
    const origen =
      ruta.chuteId !== ruta.chuteOriginalId || ruta.clusterId !== ruta.clusterOriginalId
        ? `\nEn el Excel: ${ruta.clusterOriginal} · chute ${ruta.chuteOriginal}`
        : '';
    if (!this.puedeModificar()) {
      return detalle + origen;
    }
    const fijo = this.clusterCerrado(ruta.clusterId)
      ? `\n${ruta.cluster} está cerrado: reábrelo para mover sus rutas.`
      : '\nArrástrala a otro chute o cluster para moverla.';
    return detalle + origen + fijo;
  }

  // ---------------- Mover rutas a mano (arrastrar y soltar) ----------------

  private readonly rutasPorId = computed(
    () => new Map(this.rutasMatriz().map((ruta) => [ruta.id, ruta])),
  );

  private clusterCerrado(clusterId: number): boolean {
    return this.config()?.clusters.find((cluster) => cluster.id === clusterId)?.estado === 'CERRADO';
  }

  /**
   * Mismas reglas que el servidor, para marcar en rojo un destino imposible
   * mientras se arrastra (el servidor vuelve a validar al guardar):
   * clusters cerrados (antes o después de balancear se puede mover) y un
   * cluster no puede quedarse sin rutas.
   */
  readonly motivoNoMover = (rutaId: number, clusterId: number, chuteId: number): string | null => {
    const ruta = this.rutasPorId().get(rutaId);
    const cfg = this.config();
    if (!ruta || !cfg) {
      return 'La ruta ya no está en la matriz.';
    }
    if (this.ocupado()) {
      return 'Espera a que termine la operación en curso.';
    }
    // Una página que llega después pisaría el movimiento con datos de antes.
    if (this.cargandoRutas()) {
      return 'Espera a que terminen de cargar las rutas.';
    }
    if (ruta.clusterId === clusterId && ruta.chuteId === chuteId) {
      return SIN_CAMBIO;
    }
    const destino = cfg.clusters.find((cluster) => cluster.id === clusterId);
    // Chute bloqueado (solo visible si un registro antiguo aún tiene rutas ahí):
    // sus rutas pueden salir, pero ninguna puede entrar.
    const chuteDestino = cfg.chutes.find((chute) => chute.id === chuteId);
    if (chuteDestino?.bloqueado && chuteId !== ruta.chuteId) {
      return `${etiquetaChute(chuteDestino.nombre)} está bloqueado: no se pueden mover rutas ahí.`;
    }
    if (this.clusterCerrado(ruta.clusterId)) {
      return `${ruta.cluster} está cerrado: reábrelo para mover sus rutas.`;
    }
    if (!destino || destino.estado === 'CERRADO') {
      return `${destino?.nombre ?? 'Ese cluster'} está cerrado: reábrelo para recibir rutas.`;
    }
    if (clusterId !== ruta.clusterId) {
      // Soltarla donde no se vería (p. ej. una ruta L-X-V en un cluster que muestra Diarias)
      // la haría desaparecer de la pantalla.
      const vistaDestino = this.vistaDe(clusterId);
      if (vistaDestino !== 'AMBOS' && !rutaEnBalanceo(ruta.frecuencia, vistaDestino)) {
        return `${destino.nombre} muestra ${ETIQUETA_BALANCEO[vistaDestino]}: cambia su filtro para recibir esta ruta.`;
      }
      if (!ruta.clusterEditable) {
        return this.motivoClusterFijo(ruta);
      }
      // Como el servidor: cuentan todas sus rutas (de los dos días y las diarias).
      if (this.rutasDelCluster(ruta.clusterId) <= 1) {
        return `${ruta.codigoRuta} es la única ruta de ${ruta.cluster}: un cluster no puede quedarse sin rutas.`;
      }
    }
    return null;
  };

  /** Rutas del cluster en el registro: L-X-V + M-J-S − Diarias (cada diaria una vez). */
  private rutasDelCluster(clusterId: number): number {
    const configs = this.configs();
    const suma = (cfg: ConfiguracionSimulacion | null) =>
      (cfg?.carga ?? []).filter((fila) => fila.clusterId === clusterId).reduce((t, fila) => t + fila.rutas, 0);
    return suma(configs.LXV) + suma(configs.MJS) - suma(configs.DIA);
  }

  // ---------------- Selección de varias rutas ----------------

  /** Rutas elegidas con Ctrl/⌘+clic o Mayús+clic para moverlas juntas. */
  private readonly seleccionElegida = signal<ReadonlySet<number>>(new Set());
  /** Última ruta pulsada: Mayús+clic elige desde ella hasta otra de la misma celda. */
  private anclaSeleccion: number | null = null;

  /**
   * Cada ruta que se puede mover, con la columna de su celda (cluster y chute) en el orden en
   * que se ve: Mayús+clic elige un tramo de esa columna.
   */
  private readonly columnasMovibles = computed(() => {
    const chutes = this.chutesMatriz();
    const columnaDe = new Map<number, readonly number[]>();
    for (const bloque of this.bloquesMatriz()) {
      chutes.forEach((_, c) => {
        const columna: number[] = [];
        for (const fila of bloque.filas) {
          const celda = fila[c];
          if (celda?.movible) {
            columna.push(celda.ruta.id);
          }
        }
        for (const id of columna) {
          columnaDe.set(id, columna);
        }
      });
    }
    return columnaDe;
  });

  /** La selección vigente: sin las rutas que ya no se ven o ya no se pueden mover. */
  readonly seleccion = computed<ReadonlySet<number>>(() => {
    const elegidas = this.seleccionElegida();
    const movibles = this.columnasMovibles();
    const vigentes = [...elegidas].filter((id) => movibles.has(id));
    return vigentes.length === elegidas.size ? elegidas : new Set(vigentes);
  });

  /** Lo que muestra la barra flotante (solo informa: se mueven arrastrando): rutas, bultos y diarias. */
  readonly resumenSeleccion = computed(() => {
    const ids = this.seleccion();
    if (ids.size === 0) {
      return null;
    }
    const porId = this.rutasPorId();
    let bultos = 0;
    let diarias = 0;
    for (const id of ids) {
      const ruta = porId.get(id);
      if (ruta) {
        bultos += Number(ruta.totalBultos ?? 0);
        diarias += esDiaria(ruta) ? 1 : 0;
      }
    }
    return { rutas: ids.size, bultos, diarias };
  });

  /**
   * Clic en una ruta: solo, abre su ficha; con Ctrl/⌘ la suma o la quita de la selección; con
   * Mayús elige el tramo desde la última pulsada (si están en la misma celda).
   */
  clicRuta(evento: Event, rutaId: number): void {
    const teclas = evento as MouseEvent;
    // En Mac, Ctrl+clic es el clic derecho: lo atiende `menuRuta` (aquí ni abre la ficha).
    if (ES_MAC && teclas.ctrlKey && !teclas.metaKey) {
      evento.preventDefault();
      return;
    }
    if (!teclas.ctrlKey && !teclas.metaKey && !teclas.shiftKey) {
      this.abrirDetalleRuta(evento, rutaId);
      return;
    }
    evento.preventDefault();
    this.alternarSeleccion(rutaId, teclas.shiftKey);
  }

  /**
   * Mayús+clic no debe seleccionar el texto de la matriz. Sin Mayús no se toca: cancelar el
   * mousedown impediría arrastrar la ruta. (Un manejador que devuelve false lo cancela.)
   */
  presionarRuta(evento: MouseEvent): void {
    if (evento.shiftKey) {
      evento.preventDefault();
    }
  }

  /** Ctrl+clic en Mac llega como menú contextual: también suma o quita la ruta de la selección. */
  menuRuta(evento: MouseEvent, rutaId: number): void {
    if (ES_MAC && evento.ctrlKey && evento.button === 0) {
      evento.preventDefault();
      this.alternarSeleccion(rutaId, evento.shiftKey);
    }
  }

  private alternarSeleccion(rutaId: number, tramo: boolean): void {
    const columnas = this.columnasMovibles();
    const columna = columnas.get(rutaId);
    if (!columna) {
      this.notificaciones.advertencia(
        'No se puede seleccionar',
        'Esa ruta no se puede mover: su cluster está cerrado o no tienes permiso.',
      );
      return;
    }
    this.detalle.set(null);
    const nueva = new Set(this.seleccion());
    const ancla = this.anclaSeleccion;
    if (tramo && ancla !== null && columnas.get(ancla) === columna) {
      const desde = columna.indexOf(ancla);
      const hasta = columna.indexOf(rutaId);
      for (const id of columna.slice(Math.min(desde, hasta), Math.max(desde, hasta) + 1)) {
        nueva.add(id);
      }
    } else if (nueva.has(rutaId)) {
      nueva.delete(rutaId);
    } else {
      nueva.add(rutaId);
    }
    this.anclaSeleccion = rutaId;
    this.seleccionElegida.set(nueva);
  }

  limpiarSeleccion(): void {
    this.seleccionElegida.set(new Set());
    this.anclaSeleccion = null;
  }

  /** Al tomar una ruta seleccionada viaja toda la selección; si no, solo ella. */
  readonly grupoArrastre = (rutaId: number): readonly number[] => {
    const seleccion = this.seleccion();
    return seleccion.size > 1 && seleccion.has(rutaId) ? [...seleccion] : [rutaId];
  };

  /**
   * Las reglas de mover una ruta, para varias: basta una que no pueda para que no se mueva
   * ninguna (como el servidor), y ningún cluster puede quedarse sin rutas.
   */
  readonly motivoNoMoverRutas = (rutaIds: readonly number[], clusterId: number, chuteId: number): string | null => {
    if (rutaIds.length === 1) {
      return this.motivoNoMover(rutaIds[0], clusterId, chuteId);
    }
    const porId = this.rutasPorId();
    const salenDe = new Map<number, number>();
    let cambia = false;
    for (const id of rutaIds) {
      const motivo = this.motivoNoMover(id, clusterId, chuteId);
      if (motivo === SIN_CAMBIO) {
        continue;
      }
      // Que el grupo sí pueda dejar su cluster se mira abajo, con todas las que salen.
      const ruta = porId.get(id);
      const soloQuedaUna = ruta && motivo?.endsWith('un cluster no puede quedarse sin rutas.');
      if (motivo !== null && !soloQuedaUna) {
        return ruta ? `Ruta ${ruta.codigoRuta}: ${motivo}` : motivo;
      }
      if (ruta && ruta.clusterId !== clusterId) {
        salenDe.set(ruta.clusterId, (salenDe.get(ruta.clusterId) ?? 0) + 1);
      }
      cambia = true;
    }
    for (const [origen, salen] of salenDe) {
      if (this.rutasDelCluster(origen) <= salen) {
        const nombre = this.config()?.clusters.find((c) => c.id === origen)?.nombre ?? 'su cluster';
        return `Se llevarían todas las rutas de ${nombre}: un cluster no puede quedarse sin rutas.`;
      }
    }
    return cambia ? null : SIN_CAMBIO;
  };

  /**
   * Varias rutas a una celda en un solo llamado: el servidor las mueve todas o ninguna (y dice
   * cuál falló). La matriz se corrige en el sitio y los otros balanceos se refrescan.
   */
  private moverVarias(rutaIds: readonly number[], clusterId: number, chuteId: number): void {
    const id = this.seleccionado();
    const cfg = this.config();
    if (id === null || !cfg) {
      return;
    }
    const porId = this.rutasPorId();
    const rutas = rutaIds
      .map((rutaId) => porId.get(rutaId))
      .filter((ruta): ruta is RutaSimulacion => !!ruta && (ruta.clusterId !== clusterId || ruta.chuteId !== chuteId));
    if (rutas.length === 0) {
      return;
    }
    const nombreCluster = cfg.clusters.find((c) => c.id === clusterId)?.nombre ?? '';
    const nombreChute = cfg.chutes.find((c) => c.id === chuteId)?.nombre ?? '';
    // La respuesta trae la configuración de lo que muestra el destino («Ambos»: L-X-V).
    const vista = this.vistaDe(clusterId);
    const balanceo: Balanceo = vista === 'AMBOS' ? 'LXV' : vista;
    const diarias = rutas.filter(esDiaria).length;
    const ids = new Set(rutas.map((ruta) => ruta.id));

    this.rutasGuardando.set(ids);
    this.conCambiosGuardados(id, () => this.api.moverRutas(id, [...ids], clusterId, chuteId, balanceo))
      .pipe(
        finalize(() => this.rutasGuardando.set(new Set())),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (config) => {
          if (this.seleccionado() === id) {
            this.aplicarConfiguracion(config);
            this.actualizarRutas(ids, { clusterId, cluster: nombreCluster, chuteId, chute: nombreChute });
            this.refrescarDemas(id, balanceo);
            this.limpiarSeleccion();
          }
          const texto = `${rutas.length} rutas → ${nombreCluster} · chute ${nombreChute}.`;
          if (diarias > 0) {
            this.notificaciones.advertencia(
              'Rutas movidas',
              `${texto} ${diarias === 1 ? 'Una es diaria: cambia' : `${diarias} son diarias: cambian`} en L-X-V y en M-J-S.`,
            );
          } else {
            this.notificaciones.exito('Rutas movidas', texto);
          }
        },
        // El interceptor ya muestra el motivo del servidor; el cambio es todo o nada.
        error: () => undefined,
      });
  }

  /**
   * Suelta una ruta en otra celda. Mismo cluster → cambia de chute; otra fila
   * de cluster → cambia de cluster (y de chute si también cambió de columna).
   * La matriz se corrige en el sitio, sin volver a pedir todas las rutas.
   */
  soltarRuta({ rutaId, rutaIds, clusterId, chuteId, motivo }: DestinoRuta): void {
    if (motivo !== null) {
      if (motivo !== SIN_CAMBIO) {
        this.notificaciones.advertencia(
          rutaIds.length > 1 ? 'No se pueden mover las rutas' : 'No se puede mover la ruta',
          motivo,
        );
      }
      return;
    }
    if (rutaIds.length > 1) {
      this.moverVarias(rutaIds, clusterId, chuteId);
      return;
    }
    const ruta = this.rutasPorId().get(rutaId);
    const id = this.seleccionado();
    const cfg = this.config();
    if (!ruta || id === null || !cfg) {
      return;
    }
    const nombreCluster = cfg.clusters.find((c) => c.id === clusterId)?.nombre ?? ruta.cluster;
    const nombreChute = cfg.chutes.find((c) => c.id === chuteId)?.nombre ?? ruta.chute;
    const cambiaCluster = clusterId !== ruta.clusterId;
    const cambiaChute = chuteId !== ruta.chuteId;
    const diaria = esDiaria(ruta);
    // Se mueve en su dueño: una diaria en Diarias (cambia en los dos días), las demás en su día.
    const balanceo: Balanceo = diaria ? 'DIA' : ruta.frecuencia === 'MJS' ? 'MJS' : 'LXV';

    this.rutaGuardando.set(ruta.id);
    // Cambio de cluster (con o sin chute) en un solo llamado: el servidor valida
    // el destino final, sin un estado intermedio que pueda rechazar.
    this.conCambiosGuardados(id, () =>
      cambiaCluster
        ? this.api.cambiarCluster(id, ruta.id, clusterId, cambiaChute ? chuteId : null, balanceo)
        : this.api.moverRuta(id, ruta.id, chuteId, balanceo),
    )
      .pipe(
        finalize(() => this.rutaGuardando.set(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (config) => {
          if (this.seleccionado() === id) {
            this.aplicarConfiguracion(config);
            this.actualizarRutas(new Set([ruta.id]), { clusterId, cluster: nombreCluster, chuteId, chute: nombreChute });
            // Los otros balanceos también cambiaron (carga, operarios invalidados).
            this.refrescarDemas(id, balanceo);
          }
          const desde = `${ruta.cluster} · chute ${ruta.chute}`;
          const hasta = `${nombreCluster} · chute ${nombreChute}`;
          if (diaria) {
            this.notificaciones.advertencia(
              'Ruta diaria movida',
              `${ruta.codigoRuta}: ${desde} → ${hasta}. Cambia en L-X-V y en M-J-S.`,
            );
          } else {
            this.notificaciones.exito('Ruta movida', `${ruta.codigoRuta}: ${desde} → ${hasta}.`);
          }
        },
        // El interceptor ya muestra el motivo del servidor; el cambio es todo o nada.
        error: () => undefined,
      });
  }

  /**
   * Corrige rutas en la matriz tras guardarlas. Si con los filtros vigentes
   * ya no debería verse (otro cluster o chute filtrado), sale de la lista.
   */
  private actualizarRutas(rutaIds: ReadonlySet<number>, cambios: Partial<RutaSimulacion>): void {
    const clusters = this.filtroCluster();
    const chutes = this.filtroChute();
    this.rutasMatriz.update((rutas) =>
      rutas.flatMap((ruta) => {
        if (!rutaIds.has(ruta.id)) {
          return [ruta];
        }
        const nueva = { ...ruta, ...cambios };
        const visible =
          (!clusters.length || clusters.includes(nueva.clusterId)) &&
          (!chutes.length || chutes.includes(nueva.chuteId));
        return visible ? [nueva] : [];
      }),
    );
  }

  setFiltroChute(ids: (number | string)[]): void {
    this.filtroChute.set(ids.map(Number));
    this.recargarRutas();
  }

  setFiltroCluster(ids: (number | string)[]): void {
    this.filtroCluster.set(ids.map(Number));
    this.recargarRutas();
  }

  quitarFiltro(clave: string): void {
    const [tipo, valor] = clave.split(':');
    const id = Number(valor);
    if (tipo === 'chute') {
      this.setFiltroChute(this.filtroChute().filter((item) => item !== id));
    } else if (tipo === 'cluster') {
      this.setFiltroCluster(this.filtroCluster().filter((item) => item !== id));
    } else if (tipo === 'texto') {
      this.busqueda$.next('');
      this.textoBusqueda.set('');
      this.filtroTexto.set('');
      this.recargarRutas();
    }
  }

  buscarRuta(texto: string): void {
    this.textoBusqueda.set(texto);
    this.busqueda$.next(texto);
  }

  limpiarFiltros(): void {
    this.reiniciarFiltros();
    this.recargarRutas();
  }

  /** Vacía los filtros y descarta una búsqueda pendiente del debounce. */
  private reiniciarFiltros(): void {
    this.busqueda$.next('');
    this.textoBusqueda.set('');
    this.filtroChute.set([]);
    this.filtroCluster.set([]);
    this.filtroTexto.set('');
  }

  setVelocidad(evento: Event): void {
    const input = evento.target as HTMLInputElement;
    const valor = normalizarVelocidad(input.value) ?? this.velocidad();
    input.value = String(valor);
    this.velocidad.set(valor);
  }

  setPorChute(evento: Event): void {
    const input = evento.target as HTMLInputElement;
    const tope = normalizarPorChute(input.value);
    input.value = tope === null ? '' : String(tope);
    this.porChute.set(tope);
  }

  setLimite(clusterId: number, limite: number): void {
    this.limites.set({ ...this.limites(), [clusterId]: limite });
  }

  /** Límite escrito en el panel del cluster: se normaliza (0 a LIMITES.horas) antes de guardarlo. */
  cambiarLimite(clusterId: number, evento: Event): void {
    const input = evento.target as HTMLInputElement;
    const limite = normalizarLimite(input.value);
    input.value = limite > 0 ? String(limite) : '';
    this.setLimite(clusterId, limite);
  }

  /**
   * Lo editado en una sola petición: velocidad, máximo por chute, buff y el límite de cada
   * cluster (el servidor lo aplica a los tres balanceos).
   */
  private peticion(): ConfiguracionSimulacionRequest | null {
    const config = this.configs().LXV;
    if (!config) {
      return null;
    }
    const buff = new Set(this.buffElegidos());
    const buffGuardado = new Set(this.buffGuardado());
    const cambiaBuff = buff.size !== buffGuardado.size || [...buff].some((id) => !buffGuardado.has(id));
    return {
      velocidadBase: this.velocidad(),
      operariosPorChute: this.porChute(),
      // Un solo límite por cluster: el servidor lo aplica a los tres balanceos.
      clusters: config.clusters
        .map((cluster) => ({
          clusterId: cluster.id,
          limiteHoras: this.limites()[cluster.id] ?? 0,
        })),
      ...(cambiaBuff
        ? {
            chutes: config.chutes
              .filter((chute) => buff.has(chute.id) !== buffGuardado.has(chute.id))
              .map((chute) => ({ chuteId: chute.id, operarios: chute.operarios, buff: buff.has(chute.id) })),
          }
        : {}),
    };
  }

  /**
   * Guarda lo editado. Con `refrescar`, pide además los otros balanceos (la velocidad o un
   * límite también invalidan operarios allí); antes de un paso no hace falta: el paso los
   * vuelve a pedir al terminar.
   */
  private guardarTodo(id: number, refrescar = true): Observable<void> {
    const peticion = this.peticion();
    if (!peticion) {
      return of(undefined);
    }
    return this.api.guardarConfiguracion(id, peticion, 'LXV').pipe(
      tap((config) => {
        if (this.seleccionado() === id) {
          this.aplicarConfiguracion(config);
          if (refrescar) {
            this.refrescarDemas(id, 'LXV');
          }
        }
      }),
      map(() => undefined),
    );
  }

  /** Guarda la velocidad, el máximo por chute, el buff y los límites en edición. */
  guardar(): void {
    const id = this.seleccionado();
    if (!id || this.ocupado()) {
      return;
    }
    this.guardando.set(true);
    this.guardarTodo(id)
      .pipe(
        finalize(() => this.guardando.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () =>
          this.notificaciones.exito(
            'Cambios guardados',
            'Los parámetros y los límites quedaron al día.',
          ),
      });
  }

  /**
   * Guarda lo editado antes de un paso y aplica la respuesta: si el paso
   * falla, lo guardado ya no figura como pendiente.
   */
  private conCambiosGuardados<T>(id: number, paso: () => Observable<T>): Observable<T> {
    if (!this.dirty()) {
      return paso();
    }
    return this.guardarTodo(id, false).pipe(switchMap(() => paso()));
  }

  /**
   * Balancear rutas. Diarias abre el modal del rango (propone el último o, si no hay, del
   * primer al último chute que el cluster usa hoy). Un día no elige nada: usa el rango y
   * los tramos de las diarias, así que se lanza directo.
   */
  balancear(cluster: { id: number; nombre: string }, balanceo: Balanceo): void {
    if (this.ocupado()) {
      return;
    }
    if (balanceo !== 'DIA') {
      this.ejecutarPaso('RUTAS', cluster, {}, balanceo);
      return;
    }
    const cfg = this.configs().DIA ?? this.configs().LXV;
    if (!cfg) {
      return;
    }
    const orden = this.opcionesChute();
    const existe = (id: number | null) => id !== null && orden.some((chute) => chute.id === id);
    const guardado = cfg.clusters.find((item) => item.id === cluster.id);
    let desde = guardado?.chuteDesdeId ?? null;
    let hasta = guardado?.chuteHastaId ?? null;
    if (!existe(desde) || !existe(hasta)) {
      // Sin rango guardado: los chutes que el cluster usa hoy (todas sus rutas).
      const usados = new Set(
        (this.configs().LXV?.carga ?? [])
          .concat(this.configs().MJS?.carga ?? [])
          .filter((fila) => fila.clusterId === cluster.id && fila.rutas > 0)
          .map((fila) => fila.chuteId),
      );
      const indices = orden.flatMap((chute, indice) => (usados.has(chute.id) ? [indice] : []));
      desde = orden[indices[0] ?? 0]?.id ?? null;
      hasta = orden[indices.at(-1) ?? orden.length - 1]?.id ?? null;
    }
    this.rangoDesde.set(desde);
    this.rangoHasta.set(hasta);
    // Grupos del cluster en orden natural: cada uno recibe su tramo.
    this.gruposRango.set((guardado?.grupos ?? []).map((g) => g.nombre).sort(compararNombre));
    this.vistaGrupos.set(null);
    this.rangoPendiente.set({ id: cluster.id, nombre: cluster.nombre });
  }

  /** Confirma el modal de rango y balancea las diarias con esos chutes. */
  confirmarRango(): void {
    const cluster = this.rangoPendiente();
    const desde = this.rangoDesde();
    const hasta = this.rangoHasta();
    if (!cluster || desde === null || hasta === null || !this.rangoListo()) {
      return;
    }
    this.rangoPendiente.set(null);
    this.ejecutarPaso('RUTAS', cluster, { chuteDesdeId: desde, chuteHastaId: hasta }, 'DIA');
  }

  /**
   * Paso 2: pide la cantidad de operarios de la ola y los reparte para que
   * cierre lo antes posible, partiendo de donde quedó la ola anterior. Necesita
   * el límite de horas del cluster para decir si cumple.
   */
  asignarOperarios(cluster: { id: number; nombre: string }): void {
    if ((this.limites()[cluster.id] ?? 0) <= 0) {
      this.notificaciones.advertencia(
        'Define el límite',
        `Ingresa el límite de horas de ${cluster.nombre} para asignar sus operarios.`,
      );
      return;
    }
    // Propone la cantidad que usó la ola la última vez (cualquiera de los dos días); si
    // nunca la eligió, el campo queda vacío y el modal sugiere una.
    const guardada = DIAS_BALANCEO.map(
      (b) => this.configs()[b]?.clusters.find((item) => item.id === cluster.id)?.operariosOla,
    ).find((valor) => valor !== null && valor !== undefined);
    this.operariosCantidad.set(guardada ?? null);
    this.operariosPendiente.set(cluster);
  }

  /** Cantidad escrita en el modal de operarios (vacío o no numérico = sin cantidad). */
  setCantidadOperarios(valor: string): void {
    const numero = Number(valor);
    this.operariosCantidad.set(valor.trim() === '' || !Number.isFinite(numero) ? null : numero);
  }

  /** Confirma el modal de operarios y lanza el paso 2 con esa cantidad. */
  confirmarOperarios(): void {
    const cluster = this.operariosPendiente();
    const vista = this.vistaOperarios();
    if (!cluster || !vista?.valida || vista.cantidad === null) {
      return;
    }
    this.operariosPendiente.set(null);
    this.ejecutarPaso('OPERARIOS', cluster, { operarios: vista.cantidad });
  }

  /**
   * Paso 3: cierra el cluster y el equipo pasa al siguiente con esta dotación.
   * Si hay un límite o un equipo sin guardar que cambia sus operarios, no se
   * cierra: guardarlos los invalidaría y el cierre fallaría a mitad de camino.
   */
  cerrar(cluster: { id: number; nombre: string }): void {
    if (this.cambiosInvalidanOperarios(cluster.id)) {
      this.notificaciones.advertencia(
        'Recalcula los operarios',
        `Cambiaste el límite de ${cluster.nombre}, la velocidad o el máximo por chute sin guardar: pulsa «Recalcular operarios» (guarda los cambios) y luego ciérralo.`,
      );
      return;
    }
    this.ejecutarPaso('CERRAR', cluster);
  }

  /** Lo editado sin guardar (velocidad, máximo por chute o su límite) cambia sus operarios. */
  private cambiosInvalidanOperarios(clusterId: number): boolean {
    const cfg = this.configs().LXV;
    if (!cfg) {
      return false;
    }
    const guardado = equipoNormalizado(cfg);
    const cluster = cfg.clusters.find((item) => item.id === clusterId);
    const limite = Number(cluster?.limiteHoras ?? 0);
    return (
      guardado.velocidad !== this.velocidad() ||
      guardado.porChute !== this.porChute() ||
      limite !== (this.limites()[clusterId] ?? 0)
    );
  }

  /**
   * Paso de un cluster. Las rutas son de un balanceo (Diarias, L-X-V o M-J-S); operarios y
   * cerrar valen para todos (el servidor los hace a la vez).
   */
  private ejecutarPaso(
    paso: Paso,
    cluster: { id: number; nombre: string },
    cuerpo: BalanceoCuerpo | null = null,
    balanceo: Balanceo = 'LXV',
  ): void {
    const id = this.seleccionado();
    if (!id || this.ocupado()) {
      return;
    }
    this.trabajando.set(paso);
    // Cada paso devuelve la configuración ya actualizada: no hace falta pedirla.
    const operacion: Observable<{ mensaje: string; config: ConfiguracionSimulacion }> =
      this.conCambiosGuardados(id, () =>
        paso === 'CERRAR'
          ? this.api
              .cerrar(id, cluster.id, balanceo)
              .pipe(map((config) => ({ mensaje: this.mensajeCierre(cluster.nombre), config })))
          : this.api.balancear(id, cluster.id, paso, cuerpo, balanceo).pipe(
              map((resultado) => ({
                mensaje: resultado.mensaje,
                config: resultado.configuracion,
              })),
            ),
      );
    operacion
      .pipe(
        finalize(() => this.trabajando.set(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
      next: ({ mensaje, config }) => {
        this.notificaciones.exito(
          paso === 'RUTAS'
            ? `${cluster.nombre}: ${balanceo === 'DIA' ? 'diarias' : ETIQUETA_BALANCEO[balanceo]} balanceadas`
            : paso === 'OPERARIOS'
              ? `${cluster.nombre}: operarios asignados`
              : `${cluster.nombre} cerrado`,
          mensaje,
        );
        this.actualizarEstadoEnLista(id, config.archivo.estado);
        if (this.seleccionado() === id) {
          this.aplicarConfiguracion(config);
          // Las rutas se recargan cuando llegan los demás balanceos (todo al día a la vez).
          this.refrescarDemas(id, balanceo, 'suave');
        }
      },
    });
  }

  private mensajeCierre(nombre: string): string {
    const indice = (this.config()?.clusters ?? []).findIndex(
      (cluster) => cluster.nombre === nombre,
    );
    const siguiente = this.config()?.clusters[indice + 1]?.nombre;
    return siguiente
      ? `El equipo pasa a ${siguiente} con la dotación con la que terminó ${nombre}.`
      : 'Era el último cluster: la simulación terminó. Revisa el resumen en el dashboard.';
  }

  confirmarRestablecerCluster(cluster: { id: number; nombre: string }): void {
    if (!this.ocupado() && !this.avisarCambiosSinGuardar('restablecer el cluster')) {
      this.clusterARestablecer.set({ id: cluster.id, nombre: cluster.nombre });
    }
  }

  restablecerCluster(): void {
    const id = this.seleccionado();
    const cluster = this.clusterARestablecer();
    if (!id || !cluster || this.ocupado()) {
      return;
    }
    const balanceo = this.balanceoDeAccion(cluster.id);
    this.restableciendo.set(true);
    this.api
      .restablecerCluster(id, cluster.id, balanceo)
      .pipe(
        finalize(() => this.restableciendo.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (config) => {
          this.clusterARestablecer.set(null);
          if (this.seleccionado() === id) {
            this.aplicarConfiguracion(config);
            this.refrescarDemas(id, balanceo, 'suave');
          }
          this.notificaciones.info(
            `${cluster.nombre} restablecido`,
            'Se deshizo lo hecho mientras estuvo en curso (diarias, L-X-V, M-J-S y los cambios a mano). Su ola quedó sin operarios.',
          );
        },
      });
  }

  /** Reabrir un cluster devuelve a la proyección a los que se trabajaron después. */
  confirmarReabrir(cluster: { id: number; nombre: string }): void {
    if (this.ocupado() || this.avisarCambiosSinGuardar('reabrir el cluster')) {
      return;
    }
    const clusters = this.configs().LXV?.clusters ?? [];
    const indice = clusters.findIndex((otro) => otro.id === cluster.id);
    // Como el servidor: vuelven a empezar los siguientes trabajados en cualquier balanceo.
    this.clusterAReabrir.set({
      id: cluster.id,
      nombre: cluster.nombre,
      siguientes: clusters
        .slice(indice + 1)
        .filter((otro) => {
          const a = this.avanceCluster(otro.id);
          return [a.dia, a.lxv, a.mjs].some((estado) => estado !== 'PENDIENTE');
        })
        .map((otro) => otro.nombre),
    });
  }

  reabrir(): void {
    const id = this.seleccionado();
    const cluster = this.clusterAReabrir();
    if (!id || !cluster || this.ocupado()) {
      return;
    }
    const balanceo = this.balanceoDeAccion(cluster.id);
    this.restableciendo.set(true);
    this.api
      .reabrir(id, cluster.id, balanceo)
      .pipe(
        finalize(() => this.restableciendo.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (config) => {
          this.clusterAReabrir.set(null);
          if (this.seleccionado() === id) {
            this.aplicarConfiguracion(config);
            this.refrescarDemas(id, balanceo, 'suave');
          }
          this.actualizarEstadoEnLista(id, config.archivo.estado);
          this.notificaciones.info(
            `${cluster.nombre} reabierto`,
            'Conserva sus rutas y operarios; ya puedes cambiarlos.',
          );
        },
      });
  }

  /** Mantiene al día el estado del registro en el selector sin recargar la lista. */
  private actualizarEstadoEnLista(id: number, estado: string): void {
    this.registros.update((lista) =>
      lista.map((registro) => (registro.id === id ? { ...registro, estado } : registro)),
    );
  }

  /** El modal carga el historial del registro elegido al abrirse. */
  verCambios(): void {
    if (this.seleccionado()) {
      this.directorio.cargar();
      this.historialAbierto.set(true);
    }
  }

  confirmarRestablecer(): void {
    if (!this.ocupado() && !this.avisarCambiosSinGuardar('restablecer todo')) {
      this.restablecerAbierto.set(true);
    }
  }

  restablecer(): void {
    const id = this.seleccionado();
    if (!id || this.ocupado()) {
      return;
    }
    this.restableciendo.set(true);
    // Una sola operación en el servidor: los tres balanceos vuelven a la proyección juntos.
    this.api
      .restablecer(id)
      .pipe(
        finalize(() => this.restableciendo.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.restablecerAbierto.set(false);
          this.notificaciones.info(
            'Datos restablecidos',
            'Todo volvió a la proyección: la simulación empieza de nuevo por el primer cluster.',
          );
          if (this.seleccionado() === id) {
            this.cargarConfiguracion(id);
          }
        },
        // El interceptor ya avisa el motivo; la vista se recarga por si algo cambió.
        error: () => {
          if (this.seleccionado() === id) {
            this.cargarConfiguracion(id);
          }
        },
      });
  }

  /** Balanceo con que se piden las acciones del cluster (el servidor las hace en los tres). */
  private balanceoDeAccion(clusterId: number): Balanceo {
    const vista = this.vistaDe(clusterId);
    return vista === 'AMBOS' ? 'LXV' : vista;
  }

  irADashboard(): void {
    const id = this.seleccionado();
    void this.router.navigate(['/simulacion/dashboard'], {
      queryParams: id ? { registro: id } : {},
    });
  }

  // ------------------------------------------------------------------
  // Carga de Excel desde la configuración
  // ------------------------------------------------------------------

  abrirSelectorArchivo(): void {
    if (!this.ocupado() && !this.avisarCambiosSinGuardar('cargar otro Excel')) {
      this.inputArchivo()?.nativeElement.click();
    }
  }

  seleccionarArchivo(evento: Event): void {
    const input = evento.target as HTMLInputElement;
    const archivo = input.files?.[0];
    input.value = '';
    if (!archivo) {
      return;
    }
    const problema = problemaArchivoExcel(archivo);
    if (problema) {
      this.notificaciones.advertencia(problema.titulo, problema.mensaje);
      return;
    }
    this.archivoSeleccionado.set(archivo);
    this.formulario.reset({
      nombreRegistro: nombreRegistroSugerido(archivo),
      etiqueta: '',
      colorHex: PALETA_REGISTROS[0].hex,
    });
    this.subirAbierto.set(true);
  }

  cerrarCarga(): void {
    this.subirAbierto.set(false);
    this.archivoSeleccionado.set(null);
  }

  fijarColor(color: string): void {
    this.formulario.controls.colorHex.setValue(color);
  }

  confirmarCarga(): void {
    if (this.subiendo()) {
      return;
    }
    const archivo = this.archivoSeleccionado();
    if (!archivo) {
      return;
    }
    if (this.formulario.invalid) {
      this.formulario.markAllAsTouched();
      return;
    }
    this.subiendo.set(true);
    this.api
      .subir(datosCarga(archivo, this.formulario.getRawValue()))
      .pipe(
        finalize(() => this.subiendo.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (registro) => {
          this.notificaciones.exito(
            'Registro creado',
            `${registro.rutas} rutas y ${registro.chutes} chutes cargados.`,
          );
          this.cerrarCarga();
          this.cargarOpciones(registro.id);
        },
        error: (err: unknown) => {
          // 503/504: la respuesta expiró pero el servidor pudo terminar de
          // guardar. Se refresca la lista para no duplicar el registro.
          if (err instanceof HttpErrorResponse && (err.status === 503 || err.status === 504)) {
            this.notificaciones.info(
              'Revisa la lista antes de reintentar',
              'La carga tardó más de lo esperado y pudo haberse completado.',
            );
            this.cerrarCarga();
            this.cargarOpciones();
          }
        },
      });
  }

  irARegistro(): void {
    void this.router.navigate(['/simulacion/registro']);
  }
}

/** Etiqueta de la columna: el nombre del chute con el prefijo del Excel. */
function etiquetaChute(nombre: string): string {
  return /^chute\b/i.test(nombre.trim()) ? nombre : `CHUTE ${nombre}`;
}

/** Parámetros tal como se editan: velocidad válida y 0/nulo = sin tope por chute. */
function equipoNormalizado(config: ConfiguracionSimulacion): {
  velocidad: number;
  porChute: number | null;
} {
  const porChute = Number(config.operariosPorChute);
  return {
    velocidad: Number(config.velocidadBase) || VELOCIDAD_INICIAL,
    porChute: config.operariosPorChute === null || !(porChute > 0) ? null : porChute,
  };
}

/** Días de la ruta: los del Excel («L-X») o, en rutas antiguas, los de su frecuencia. */
export function textoFrecuencia(frecuencia: string | null | undefined, dias?: string | null): string {
  if (dias) {
    return dias;
  }
  return frecuencia === 'LXV' ? 'L-X-V' : frecuencia === 'MJS' ? 'M-J-S' : 'L-M-X-J-V-S';
}

/** Pestaña de frecuencia de una ruta: texto corto, color de su balanceo y título completo. */
export interface PestanaFrecuencia {
  /** D (toda la semana), LXV, MJS o sus días sin guiones si son parciales («LX», «V»). */
  texto: string;
  tipo: 'D' | 'LXV' | 'MJS';
  titulo: string;
  /** Diaria ya balanceada (paso Diarias): pestaña más oscura. */
  fija: boolean;
}

/** La ruta es diaria (aparece en Diarias, L-X-V y M-J-S). */
function esDiaria(ruta: RutaSimulacion): boolean {
  return !ruta.frecuencia || ruta.frecuencia === 'DIARIA';
}

function pestanaFrecuencia(ruta: RutaSimulacion): PestanaFrecuencia {
  const dias = textoFrecuencia(ruta.frecuencia, ruta.dias);
  const diaria = esDiaria(ruta);
  // La pestaña apila una letra por línea y caben 3: toda diaria (también la de
  // días mezclados, p. ej. L-M) es «D»; sus días van en el título de la ruta.
  const corto = diaria ? 'D' : dias.replaceAll('-', '');
  const fija = diaria && !!ruta.diariaFija;
  return {
    texto: corto,
    tipo: diaria ? 'D' : ruta.frecuencia === 'MJS' ? 'MJS' : 'LXV',
    titulo: `Frecuencia ${dias}${diaria ? ' (diaria' + (fija ? ', ya balanceada)' : ')') : ''}`,
    fija,
  };
}

/**
 * Rutas propias del cluster por frecuencia. Un cluster nunca queda sin rutas: sin el dato
 * (respuesta anterior al cambio) no se omite nada, igual que el servidor.
 */
function rutasPorFrecuencia(
  avance: { rutasDiarias?: number; rutasLxv?: number; rutasMjs?: number } | undefined,
): { dia: number; lxv: number; mjs: number } {
  const dia = avance?.rutasDiarias ?? 0;
  const lxv = avance?.rutasLxv ?? 0;
  const mjs = avance?.rutasMjs ?? 0;
  return dia + lxv + mjs === 0 ? { dia: 1, lxv: 1, mjs: 1 } : { dia, lxv, mjs };
}

/**
 * Vista que se puede mostrar: cada frecuencia (L-X-V, M-J-S o Diarias) solo si el cluster tiene
 * rutas propias de ella; Ambos, solo si tiene rutas de L-X-V y de M-J-S.
 */
export function vistaValida(vista: VistaCluster, rutas: { dia: number; lxv: number; mjs: number }): boolean {
  const lxv = rutas.lxv > 0;
  const mjs = rutas.mjs > 0;
  switch (vista) {
    case 'LXV':
      return lxv;
    case 'MJS':
      return mjs;
    case 'DIA':
      return rutas.dia > 0;
    default:
      return lxv && mjs;
  }
}

/**
 * Vista con que abre un cluster: la primera frecuencia con rutas propias (L-X-V, M-J-S, Diarias).
 * Un cluster con solo diarias abre en D, que muestra los operarios del día que más tiene.
 */
export function vistaInicial(rutas: { dia: number; lxv: number; mjs: number }): VistaCluster {
  return rutas.lxv > 0 ? 'LXV' : rutas.mjs > 0 ? 'MJS' : 'DIA';
}

/** Iniciales del cluster para su avatar: «CLUSTER 12» → «C12», «Norte» → «NO». */
function inicialesCluster(nombre: string): string {
  const palabras = nombre.trim().split(/\s+/).filter(Boolean);
  if (palabras.length > 1) {
    return (palabras[0][0] + palabras[palabras.length - 1].slice(0, 2)).toUpperCase();
  }
  return (palabras[0] ?? '?').slice(0, 2).toUpperCase();
}
