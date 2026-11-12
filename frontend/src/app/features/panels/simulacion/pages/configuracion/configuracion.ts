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
  ETIQUETA_BALANCEO,
  CargaSimulacion,
  ConfiguracionSimulacion,
  ConfiguracionSimulacionRequest,
  RutaSimulacion,
  TramoGrupo,
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
  etiquetaEstado,
  etiquetaRegistro,
  formatearEntero,
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

/**
 * Colores de las franjas de grupo: distintos del semáforo (rojo, amarillo y
 * verde) y del acento de la app, legibles en tema claro y oscuro.
 */
const PALETA_GRUPOS = [
  '#7c3aed',
  '#0891b2',
  '#db2777',
  '#4f46e5',
  '#0d9488',
  '#c2410c',
  '#9333ea',
  '#0369a1',
] as const;

/** Ruta lista para pintar: el título y los permisos ya calculados (sin métodos en la plantilla). */
interface CeldaRuta {
  ruta: RutaSimulacion;
  titulo: string;
  /** Se puede arrastrar: hay permiso y su cluster no está cerrado. */
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
  /** Ruta diaria ya fija para el balanceo automático (candado). */
  fija: boolean;
  /** Solo en «Ambos»: marca de su frecuencia (D, LXV o MJS). */
  marca: 'D' | 'LXV' | 'MJS' | null;
}

/** Lo que muestra un cluster: un balanceo o los dos apilados (solo mirar). */
export type VistaCluster = Balanceo | 'AMBOS';

/** Un cluster de la matriz: una fila por nivel de apilado y una celda por chute. */
interface BloqueMatriz {
  cluster: ResumenCluster;
  /** Lo que muestra el cluster (L-X-V, M-J-S o Ambos). */
  vista: VistaCluster;
  /** «Ambos» se habilita cuando el cluster ya tiene sus rutas balanceadas en los dos. */
  ambosListo: boolean;
  /** Sus rutas diarias ya están fijas para el automático. */
  diariasFijas: boolean;
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
interface PosicionDetalle {
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
 * Simulación cluster por cluster, en el orden de las olas. El cluster en
 * curso se trabaja en tres pasos: balancear sus rutas por bultos, asignar sus
 * operarios con el equipo que llega de la ola anterior y cerrarlo. Al cerrarlo
 * se habilita el siguiente; al cerrar el último, la simulación termina.
 */
@Component({
  selector: 'app-simulacion-configuracion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgTemplateOutlet,
    ReactiveFormsModule,
    HasPermiso,
    EmptyState,
    Icon,
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
  styleUrl: './configuracion.scss',
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
  readonly estadoRegistro = etiquetaEstado;
  readonly limitesValidos = LIMITES;

  readonly registros = signal<ArchivoSimulacion[]>([]);
  readonly seleccionado = signal<number | null>(null);
  readonly cargandoOpciones = signal(false);

  /** Configuración de cada balanceo tal como la manda el servidor. */
  readonly configs = signal<Record<Balanceo, ConfiguracionSimulacion | null>>({
    LXV: null,
    MJS: null,
  });
  /** Vista de cada cluster (por id); sin elegir, L-X-V. */
  readonly vistas = signal<Readonly<Record<number, VistaCluster>>>({});
  readonly etiquetaBalanceo = ETIQUETA_BALANCEO;
  readonly opcionesVista: VistaCluster[] = ['LXV', 'MJS', 'AMBOS'];

  /**
   * Lo que ve la página: lo del registro (velocidad, chutes, buff) es común y
   * sale de L-X-V; cada cluster trae su avance y su carga del balanceo que
   * muestra («Ambos» usa los de L-X-V, solo para mirar).
   */
  readonly config = computed<ConfiguracionSimulacion | null>(() => {
    const { LXV: lxv, MJS: mjs } = this.configs();
    if (!lxv) {
      return null;
    }
    if (!mjs) {
      return lxv;
    }
    const vistas = this.vistas();
    const enMjs = (clusterId: number) => vistas[clusterId] === 'MJS';
    return {
      ...lxv,
      clusters: lxv.clusters.map((cluster) =>
        enMjs(cluster.id) ? (mjs.clusters.find((c) => c.id === cluster.id) ?? cluster) : cluster,
      ),
      carga: [
        ...lxv.carga.filter((fila) => !enMjs(fila.clusterId)),
        ...mjs.carga.filter((fila) => enMjs(fila.clusterId)),
      ],
    };
  });

  /** Vista de un cluster: L-X-V, M-J-S o Ambos. */
  vistaDe(clusterId: number): VistaCluster {
    return this.vistas()[clusterId] ?? 'LXV';
  }

  /** Balanceo en que trabajan las acciones del cluster («Ambos» no tiene acciones). */
  balanceoDe(clusterId: number): Balanceo {
    return this.vistaDe(clusterId) === 'MJS' ? 'MJS' : 'LXV';
  }

  /** El cluster ya tiene sus rutas balanceadas en L-X-V y en M-J-S. */
  ambosListo(clusterId: number): boolean {
    const { LXV: lxv, MJS: mjs } = this.configs();
    const balanceado = (cfg: ConfiguracionSimulacion | null) => {
      const estado = cfg?.clusters.find((c) => c.id === clusterId)?.estado;
      return estado !== undefined && estado !== 'PENDIENTE';
    };
    return balanceado(lxv) && balanceado(mjs);
  }

  /** Cambia lo que muestra un cluster y propone su límite de ese balanceo. */
  cambiarVista(clusterId: number, vista: VistaCluster): void {
    if (vista === this.vistaDe(clusterId) || this.ocupado()) {
      return;
    }
    if (vista === 'AMBOS' && !this.ambosListo(clusterId)) {
      return;
    }
    this.vistas.update((actual) => ({ ...actual, [clusterId]: vista }));
    this.detalle.set(null);
    const cfg = this.configs()[vista === 'MJS' ? 'MJS' : 'LXV'];
    const limite = cfg?.clusters.find((c) => c.id === clusterId)?.limiteHoras;
    this.limites.update((actual) => ({ ...actual, [clusterId]: Number(limite ?? 0) }));
  }

  /** Rutas que se ven en la matriz: las del balanceo de cada cluster (en «Ambos», todas). */
  readonly rutasVisibles = computed(() => {
    const vistas = this.vistas();
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
  /** Recarga tras un paso: hasta que llegue, las acciones siguen bloqueadas. */
  readonly recargando = signal(false);
  /** Una sola escritura a la vez: los botones de acción se deshabilitan juntos. */
  readonly ocupado = computed(
    () =>
      this.guardando() ||
      this.trabajando() !== null ||
      this.restableciendo() ||
      this.recargando() ||
      this.rutaGuardando() !== null,
  );

  /** Todos los clusters cerrados: la simulación terminó. */
  readonly finalizado = computed(() => this.config()?.archivo.estado === 'FINALIZADO');

  /** Primer cluster sin cerrar (en el orden de las olas): el único que se trabaja. */
  readonly enCurso = computed(
    () => (this.config()?.clusters ?? []).find((cluster) => cluster.estado !== 'CERRADO') ?? null,
  );

  /** «L-X-V 2/5 · M-J-S 0/5»: clusters cerrados en cada balanceo. */
  readonly clustersCerrados = computed(() => {
    const { LXV: lxv, MJS: mjs } = this.configs();
    const cerrados = (cfg: ConfiguracionSimulacion | null) =>
      `${(cfg?.clusters ?? []).filter((c) => c.estado === 'CERRADO').length}/${cfg?.clusters.length ?? 0}`;
    return `L-X-V ${cerrados(lxv)} · M-J-S ${cerrados(mjs)}`;
  });

  /**
   * El equipo ya trabajó en clusters cerrados (de cualquiera de los dos
   * balanceos): para cambiarlo hay que reabrir el primero de ellos.
   */
  readonly equipoBloqueado = computed(() => {
    const { LXV: lxv, MJS: mjs } = this.configs();
    return (
      (lxv?.clusters ?? []).find((cluster) => cluster.estado === 'CERRADO') ??
      (mjs?.clusters ?? []).find((cluster) => cluster.estado === 'CERRADO') ??
      null
    );
  });

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

  /** Cluster cuyo paso 2 espera la cantidad de operarios (modal «Operarios para …»). */
  readonly operariosPendiente = signal<{ id: number; nombre: string } | null>(null);
  readonly operariosCantidad = signal<number | null>(null);

  /**
   * Vista previa del paso 2 con la cantidad elegida, con las mismas reglas que
   * el servidor: mínimo uno por chute con carga, cuántos harían falta para
   * cumplir el límite (cada chute por su cuenta) y cuántos no cabrían por el
   * máximo por chute.
   */
  readonly vistaOperarios = computed(() => {
    const cluster = this.operariosPendiente();
    const cfg = this.config();
    if (!cluster || !cfg) {
      return null;
    }
    const filas = cfg.carga.filter((fila) => fila.clusterId === cluster.id && fila.rutas > 0);
    const porChute = new Map<number, number>();
    for (const fila of filas) {
      porChute.set(fila.chuteId, (porChute.get(fila.chuteId) ?? 0) + Number(fila.bultos));
    }
    const velocidad = this.velocidad();
    const limite = this.limites()[cluster.id] ?? 0;
    let necesarios = 0;
    for (const bultos of porChute.values()) {
      if (bultos > 0 && velocidad > 0 && limite > 0) {
        necesarios += Math.max(1, Math.ceil(bultos / (velocidad * limite)));
      }
    }
    const cantidad = this.operariosCantidad();
    const minimo = porChute.size;
    const valida =
      cantidad !== null &&
      Number.isInteger(cantidad) &&
      cantidad >= Math.max(1, minimo) &&
      cantidad <= LIMITES.equipo;
    // Sin máximo por chute, el tope es la ola entera (con el tope técnico por chute).
    const tope = Math.min(LIMITES.operariosPorChute, this.porChute() ?? Math.max(1, cantidad ?? 1));
    return {
      chutes: minimo,
      necesarios,
      limite,
      cantidad,
      valida,
      sinChute: valida ? Math.max(0, cantidad - minimo * tope) : 0,
      tope,
    };
  });

  readonly historialAbierto = signal(false);

  /** Cluster cuyo paso 1 espera el rango de chutes (modal «Chutes para balancear»). */
  readonly rangoPendiente = signal<{ id: number; nombre: string } | null>(null);
  readonly rangoDesde = signal<number | null>(null);
  readonly rangoHasta = signal<number | null>(null);

  /**
   * El otro balanceo ya trabajó el cluster del modal: su rango y los tramos de
   * sus grupos valen aquí (no se eligen). Devuelve el nombre de ese balanceo.
   */
  readonly rangoHeredado = computed<string | null>(() => {
    const cluster = this.rangoPendiente();
    if (!cluster) {
      return null;
    }
    const balanceo = this.balanceoDe(cluster.id);
    const propio = this.configs()[balanceo]?.clusters.find((c) => c.id === cluster.id);
    return propio?.rangoDelOtro ? ETIQUETA_BALANCEO[balanceo === 'LXV' ? 'MJS' : 'LXV'] : null;
  });

  /** Cuántas rutas diarias ya fijas tiene el cluster del modal (no se moverán). */
  readonly diariasDelModal = computed(() => {
    const cluster = this.rangoPendiente();
    if (!cluster) {
      return null;
    }
    const rutas = this.rutasMatriz().filter(
      (ruta) => ruta.clusterId === cluster.id && (!ruta.frecuencia || ruta.frecuencia === 'DIARIA'),
    );
    const propias = this.rutasMatriz().filter(
      (ruta) => ruta.clusterId === cluster.id && ruta.frecuencia === this.balanceoDe(cluster.id),
    );
    return {
      diarias: rutas.length,
      fijas: rutas.filter((ruta) => ruta.diariaFija).length,
      propias: propias.length,
    };
  });

  /**
   * Grupos del cluster del modal, del de mayor prioridad al de menor: se
   * reordenan con las flechas. Con dos o más, cada uno recibe un tramo de chutes
   * seguidos del rango en este orden.
   */
  readonly prioridadGrupos = signal<string[]>([]);
  /** Vista previa del servidor: el tramo de cada grupo con el rango y la prioridad elegidos. */
  readonly vistaGrupos = signal<VistaBalanceo | null>(null);
  readonly cargandoGrupos = signal(false);
  private readonly pedidosVistaGrupos = new Subject<{
    id: number;
    clusterId: number;
    desde: number | null;
    hasta: number | null;
    prioridad: string[];
    balanceo: Balanceo;
  } | null>();

  /** Cada cambio de rango o de prioridad pide la vista previa (la última gana). */
  private readonly vistaGruposSuscripcion = this.pedidosVistaGrupos
    .pipe(
      debounceTime(150),
      switchMap((pedido) =>
        pedido === null
          ? of(null)
          : this.api
              .vistaBalanceo(
                pedido.id,
                pedido.clusterId,
                pedido.desde,
                pedido.hasta,
                pedido.prioridad,
                pedido.balanceo,
              )
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
    const prioridad = this.prioridadGrupos();
    const desde = this.rangoDesde();
    const hasta = this.rangoHasta();
    if (!cluster || id === null || prioridad.length < 2) {
      this.cargandoGrupos.set(false);
      this.vistaGrupos.set(null);
      this.pedidosVistaGrupos.next(null);
      return;
    }
    this.cargandoGrupos.set(true);
    this.pedidosVistaGrupos.next({
      id,
      clusterId: cluster.id,
      desde,
      hasta,
      prioridad,
      balanceo: this.balanceoDe(cluster.id),
    });
  });

  /**
   * Se puede balancear: con un solo grupo, basta el rango; con varios, la vista
   * previa del servidor llegó para este cluster y no tiene aviso.
   */
  readonly rangoListo = computed(() => {
    if (this.prioridadGrupos().length < 2 || this.rangoHeredado() !== null) {
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

  /** Sube (−1) o baja (+1) un grupo en la prioridad. */
  moverGrupo(indice: number, paso: -1 | 1): void {
    const destino = indice + paso;
    this.prioridadGrupos.update((lista) => {
      if (destino < 0 || destino >= lista.length) {
        return lista;
      }
      const nueva = [...lista];
      [nueva[indice], nueva[destino]] = [nueva[destino], nueva[indice]];
      return nueva;
    });
  }

  /** Tramo de un grupo en la vista previa (null mientras no llega o sin rango). */
  tramoDe(grupo: string): TramoGrupo | null {
    return this.vistaGrupos()?.grupos.find((t) => t.grupo === grupo) ?? null;
  }

  /** Carga de cada grupo del cluster del modal (rutas y bultos), por nombre. */
  readonly gruposDelModal = computed(() => {
    const cluster = this.rangoPendiente();
    const grupos = this.config()?.clusters.find((c) => c.id === cluster?.id)?.grupos ?? [];
    return new Map(grupos.map((g) => [g.nombre, g]));
  });

  /**
   * Vista previa del rango: chutes incluidos (orden natural, «desde» y «hasta»
   * incluidos aunque estén invertidos), la carga del cluster, cuántas rutas
   * están hoy fuera del rango y cuántos operarios necesitará como mínimo (uno
   * por chute que puede quedar con carga: el paso 2 lo exige). Si la ola ya
   * eligió su cantidad, avisa cuando no alcanza para ese mínimo.
   */
  readonly vistaRango = computed(() => {
    const cluster = this.rangoPendiente();
    const cfg = this.config();
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
    const filas = cfg.carga.filter((fila) => fila.clusterId === cluster.id && fila.rutas > 0);
    const rutas = filas.reduce((total, fila) => total + fila.rutas, 0);
    const equipo = cfg.clusters.find((c) => c.id === cluster.id)?.operariosOla ?? null;
    // Con menos rutas que chutes, algunos quedan vacíos: no necesitan gente.
    const conCarga = Math.min(incluidos.size, rutas);
    // Bloqueados que quedan entre «desde» y «hasta»: el rango se los salta.
    const numero = (nombre: string) => numeroChute(nombre) ?? Number.NaN;
    const n1 = numero(orden[inicio].etiqueta);
    const n2 = numero(orden[fin].etiqueta);
    const saltados = (cfg.chutesBloqueados ?? []).filter((nombre) => {
      const n = numero(nombre);
      return n > n1 && n < n2;
    });
    // Con grupos, cada uno entra a su propio tramo: «fuera» cuenta las rutas fuera
    // de su tramo y el mínimo, los chutes que reciben grupo (la vista del servidor).
    const tramos = this.prioridadGrupos().length > 1 ? this.vistaGrupos() : null;
    const conTramos = tramos !== null && tramos.aviso === null && tramos.clusterId === cluster.id;
    const minimo = conTramos ? tramos.grupos.reduce((total, t) => total + t.chutes, 0) : conCarga;
    return {
      primero: orden[inicio].etiqueta,
      ultimo: orden[fin].etiqueta,
      saltados,
      chutes: incluidos.size,
      rutas,
      bultos: filas.reduce((total, fila) => total + Number(fila.bultos), 0),
      fuera: conTramos
        ? tramos.grupos.reduce((total, t) => total + t.rutasFuera, 0)
        : filas
            .filter((fila) => !incluidos.has(fila.chuteId))
            .reduce((total, fila) => total + fila.rutas, 0),
      conTramos,
      minimo,
      equipo,
      excede: equipo !== null && minimo > equipo,
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
      const nombres = grupos.map((g) => g.nombre).sort(compararNombre);
      resultado.set(
        cluster.id,
        new Map<string, string>(
          nombres.map((nombre, i) => [nombre, PALETA_GRUPOS[i % PALETA_GRUPOS.length]]),
        ),
      );
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
    const vistas = this.vistas();
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
      // En «Ambos» solo se mira: nada se arrastra.
      movible: editable && !cerrados.has(ruta.clusterId) && vistas[ruta.clusterId] !== 'AMBOS',
      movida: ruta.chuteId !== ruta.chuteOriginalId || ruta.clusterId !== ruta.clusterOriginalId,
      cambioCluster: ruta.clusterId !== ruta.clusterOriginalId,
      grupo: colores.get(ruta.clusterId)?.has(ruta.grupo) ? ruta.grupo : null,
      grupoColor: colores.get(ruta.clusterId)?.get(ruta.grupo) ?? null,
      bultos: formatearEntero(ruta.totalBultos),
      fija: !!ruta.diariaFija,
      marca:
        vistas[ruta.clusterId] !== 'AMBOS'
          ? null
          : !ruta.frecuencia || ruta.frecuencia === 'DIARIA'
            ? 'D'
            : ruta.frecuencia,
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
        ambosListo: this.ambosListo(cluster.id),
        diariasFijas: !!this.config()?.clusters.find((c) => c.id === cluster.id)?.diariasFijas,
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
    // Un cluster en «Ambos» mezcla días distintos: no suma al total general.
    const vistas = this.vistas();
    const enAmbos = (clusterId: number) => vistas[clusterId] === 'AMBOS';
    const carga = (this.config()?.carga ?? []).filter(
      (fila) =>
        !enAmbos(fila.clusterId) &&
        (clusters.size === 0 || clusters.has(fila.clusterId)) &&
        (chutes.size === 0 || chutes.has(fila.chuteId)),
    );
    if (!this.filtroTexto().trim()) {
      return this.totalesDeCarga(carga, true);
    }
    const operarios = new Map(carga.map((fila) => [`${fila.clusterId}:${fila.chuteId}`, fila.operarios]));
    const porPar = new Map<string, CargaSimulacion>();
    for (const ruta of this.rutasVisibles()) {
      if (enAmbos(ruta.clusterId)) {
        continue;
      }
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
    const chute = (this.config()?.carga ?? []).find(
      (fila) => fila.clusterId === ruta.clusterId && fila.chuteId === ruta.chuteId,
    );
    const bultosChute = Number(chute?.bultos ?? 0);
    const cerrado = this.clusterCerrado(ruta.clusterId);
    return {
      ruta,
      color: this.resumenClusters().find((cluster) => cluster.id === ruta.clusterId)?.color ?? null,
      movida: ruta.chuteId !== ruta.chuteOriginalId || ruta.clusterId !== ruta.clusterOriginalId,
      /** Parte de los bultos de su chute (en este cluster) que lleva la ruta. */
      parte: bultosChute > 0 ? Math.min(100, redondearArriba((Number(ruta.totalBultos ?? 0) / bultosChute) * 100)) : null,
      operarios: chute?.operarios ?? null,
      aviso: !this.puedeModificar()
        ? null
        : cerrado
          ? `${ruta.cluster} está cerrado: reábrelo para mover sus rutas.`
          : 'Arrastra ⋮⋮ a otro chute o cluster para moverla.',
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
    return {
      c: cluster,
      m: this.metricasCluster().get(cluster.id) ?? null,
      // Todos sus grupos (aunque sea uno), con el color de su pestaña y su tramo.
      grupos: grupos.map((g) => ({
        ...g,
        color: this.coloresGrupo().get(cluster.id)?.get(g.nombre) ?? null,
      })),
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
    const { LXV: lxv, MJS: mjs } = this.configs();
    if (!lxv) {
      return [];
    }
    const velocidad = this.velocidad() || VELOCIDAD_INICIAL;
    // Cada balanceo tiene su propio orden de olas (en curso, anterior, siguiente):
    // se resume por separado y cada cluster toma el del balanceo que muestra. El
    // límite en edición es el del balanceo que muestra cada cluster.
    const vistas = this.vistas();
    const limites = this.limites();
    const limitesDe = (cfg: ConfiguracionSimulacion, balanceo: Balanceo) =>
      Object.fromEntries(
        cfg.clusters.map((c) => [
          c.id,
          (vistas[c.id] === 'MJS') === (balanceo === 'MJS') ? (limites[c.id] ?? 0) : Number(c.limiteHoras),
        ]),
      );
    const deLxv = resumirClusters(lxv, velocidad, limitesDe(lxv, 'LXV'));
    if (!mjs) {
      return deLxv;
    }
    const deMjs = new Map(
      resumirClusters(mjs, velocidad, limitesDe(mjs, 'MJS')).map((c) => [c.id, c]),
    );
    return deLxv.map((cluster) =>
      vistas[cluster.id] === 'MJS' ? { ...(deMjs.get(cluster.id) ?? cluster), color: cluster.color } : cluster,
    );
  });

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
            this.configs.set({ LXV: null, MJS: null });
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
    this.configs.set({ LXV: null, MJS: null });
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
    forkJoin([this.api.configuracion(id, 'LXV'), this.api.configuracion(id, 'MJS')])
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
        next: ([lxv, mjs]) => {
          if (solicitud === this.solicitud) {
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

  /** Carga la configuración del servidor y descarta lo editado de los parámetros y los límites. */
  private aplicarConfiguracion(config: ConfiguracionSimulacion): void {
    const balanceo: Balanceo = config.balanceo === 'MJS' ? 'MJS' : 'LXV';
    this.configs.update((actual) => ({ ...actual, [balanceo]: config }));
    // Lo del registro (velocidad, máximo por chute, buff) es común a los dos.
    const equipo = equipoNormalizado(config);
    this.velocidad.set(equipo.velocidad);
    this.porChute.set(equipo.porChute);
    this.buffElegidos.set(config.chutes.filter((chute) => chute.buff).map((chute) => chute.id));
    // Cada cluster muestra el límite del balanceo que tiene a la vista.
    const vistas = this.vistas();
    const limites = { ...this.limites() };
    for (const cluster of config.clusters) {
      if ((vistas[cluster.id] === 'MJS') === (balanceo === 'MJS')) {
        limites[cluster.id] = Number(cluster.limiteHoras);
      }
    }
    this.limites.set(limites);
    // Un cluster que deja de tener los dos balanceos no puede seguir en «Ambos».
    const sinAmbos = Object.entries(vistas).filter(
      ([id, vista]) => vista === 'AMBOS' && !this.ambosListo(Number(id)),
    );
    if (sinAmbos.length > 0) {
      this.vistas.update((actual) => {
        const siguiente = { ...actual };
        sinAmbos.forEach(([id]) => (siguiente[Number(id)] = 'LXV'));
        return siguiente;
      });
    }
  }

  /**
   * Tras escribir en un balanceo, el otro también puede haber cambiado (una
   * ruta diaria movida, operarios invalidados, diarias fijas): se vuelve a pedir.
   */
  private refrescarOtro(id: number, balanceo: Balanceo): void {
    const otro: Balanceo = balanceo === 'LXV' ? 'MJS' : 'LXV';
    this.api
      .configuracion(id, otro)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (config) => {
          if (this.seleccionado() === id) {
            this.aplicarConfiguracion(config);
          }
        },
        error: () => undefined,
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
      `${ruta.codigoRuta} · ${ruta.tipo}${ruta.grupo ? ` · Grupo ${ruta.grupo}` : ''} · ${textoFrecuencia(ruta.frecuencia)}${ruta.diariaFija ? ' (fija)' : ''}\n` +
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
      : '\nArrastra ⋮⋮ a otro chute o cluster para moverla.';
    return detalle + origen + fijo;
  }

  // ---------------- Mover rutas a mano (arrastrar y soltar) ----------------

  private readonly rutasPorId = computed(
    () => new Map(this.rutasMatriz().map((ruta) => [ruta.id, ruta])),
  );

  private clusterCerrado(clusterId: number): boolean {
    return this.config()?.clusters.find((cluster) => cluster.id === clusterId)?.estado === 'CERRADO';
  }

  /** Se puede tomar la ruta: hay permiso y su cluster no está cerrado. */
  puedeArrastrar(ruta: RutaSimulacion): boolean {
    return this.puedeModificar() && !this.clusterCerrado(ruta.clusterId);
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
    if (this.vistaDe(ruta.clusterId) === 'AMBOS') {
      return 'En «Ambos» solo se mira: elige L-X-V o M-J-S para mover rutas.';
    }
    // Una diaria también cambia en el otro balanceo: allí su cluster no puede estar cerrado.
    if (!ruta.frecuencia || ruta.frecuencia === 'DIARIA') {
      const otro: Balanceo = this.balanceoDe(ruta.clusterId) === 'LXV' ? 'MJS' : 'LXV';
      const cerradoAlla = (clusterIdOtro: number) =>
        this.configs()[otro]?.clusters.find((c) => c.id === clusterIdOtro)?.estado === 'CERRADO';
      if (cerradoAlla(ruta.clusterId) || cerradoAlla(clusterId)) {
        return `La ruta es diaria y su cluster está cerrado en ${ETIQUETA_BALANCEO[otro]}: reábrelo allí para moverla.`;
      }
    }
    if (!destino || destino.estado === 'CERRADO') {
      return `${destino?.nombre ?? 'Ese cluster'} está cerrado: reábrelo para recibir rutas.`;
    }
    if (clusterId !== ruta.clusterId) {
      if (!ruta.clusterEditable) {
        return this.motivoClusterFijo(ruta);
      }
      const rutasDelCluster = cfg.carga
        .filter((fila) => fila.clusterId === ruta.clusterId)
        .reduce((total, fila) => total + fila.rutas, 0);
      if (rutasDelCluster <= 1) {
        return `${ruta.codigoRuta} es la única ruta de ${ruta.cluster}: un cluster no puede quedarse sin rutas.`;
      }
    }
    return null;
  };

  /**
   * Suelta una ruta en otra celda. Mismo cluster → cambia de chute; otra fila
   * de cluster → cambia de cluster (y de chute si también cambió de columna).
   * La matriz se corrige en el sitio, sin volver a pedir todas las rutas.
   */
  soltarRuta({ rutaId, clusterId, chuteId, motivo }: DestinoRuta): void {
    if (motivo !== null) {
      if (motivo !== SIN_CAMBIO) {
        this.notificaciones.advertencia('No se puede mover la ruta', motivo);
      }
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
    const balanceo = this.balanceoDe(ruta.clusterId);
    const diaria = !ruta.frecuencia || ruta.frecuencia === 'DIARIA';

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
            this.actualizarRuta(ruta.id, { clusterId, cluster: nombreCluster, chuteId, chute: nombreChute });
            if (diaria) {
              // Una diaria tiene un solo chute: el otro balanceo también cambió.
              this.refrescarOtro(id, balanceo);
            }
          }
          const desde = `${ruta.cluster} · chute ${ruta.chute}`;
          const hasta = `${nombreCluster} · chute ${nombreChute}`;
          if (diaria) {
            const otro = ETIQUETA_BALANCEO[balanceo === 'LXV' ? 'MJS' : 'LXV'];
            this.notificaciones.advertencia(
              'Ruta diaria movida',
              `${ruta.codigoRuta}: ${desde} → ${hasta}. También cambia en ${otro}.`,
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
   * Corrige una ruta en la matriz tras guardarla. Si con los filtros vigentes
   * ya no debería verse (otro cluster o chute filtrado), sale de la lista.
   */
  private actualizarRuta(rutaId: number, cambios: Partial<RutaSimulacion>): void {
    const clusters = this.filtroCluster();
    const chutes = this.filtroChute();
    this.rutasMatriz.update((rutas) =>
      rutas.flatMap((ruta) => {
        if (ruta.id !== rutaId) {
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
   * Petición de un balanceo: la velocidad, el máximo por chute y el buff son del
   * registro; los límites, solo de los clusters que muestran ese balanceo.
   */
  private peticion(balanceo: Balanceo = 'LXV'): ConfiguracionSimulacionRequest | null {
    const config = this.configs()[balanceo];
    if (!config) {
      return null;
    }
    const enEste = (clusterId: number) => (this.vistaDe(clusterId) === 'MJS') === (balanceo === 'MJS');
    const buff = new Set(this.buffElegidos());
    const buffGuardado = new Set(this.buffGuardado());
    const cambiaBuff = buff.size !== buffGuardado.size || [...buff].some((id) => !buffGuardado.has(id));
    return {
      velocidadBase: this.velocidad(),
      operariosPorChute: this.porChute(),
      clusters: config.clusters
        .filter((cluster) => enEste(cluster.id))
        .map((cluster) => ({
          clusterId: cluster.id,
          limiteHoras: this.limites()[cluster.id] ?? 0,
        })),
      ...(balanceo === 'LXV' && cambiaBuff
        ? {
            chutes: config.chutes
              .filter((chute) => buff.has(chute.id) !== buffGuardado.has(chute.id))
              .map((chute) => ({ chuteId: chute.id, operarios: chute.operarios, buff: buff.has(chute.id) })),
          }
        : {}),
    };
  }

  /** Algún cluster que muestra M-J-S tiene su límite cambiado sin guardar. */
  private limitesMjsCambiados(): boolean {
    const mjs = this.configs().MJS;
    return (mjs?.clusters ?? []).some(
      (cluster) =>
        this.vistaDe(cluster.id) === 'MJS' &&
        Number(cluster.limiteHoras) !== (this.limites()[cluster.id] ?? 0),
    );
  }

  /**
   * Guarda lo editado: lo del registro y los límites de L-X-V en una petición y,
   * si cambió algún límite de M-J-S, otra para ese balanceo.
   */
  private guardarTodo(id: number): Observable<void> {
    const lxv = this.peticion('LXV');
    const mjs = this.limitesMjsCambiados() ? this.peticion('MJS') : null;
    if (!lxv) {
      return of(undefined);
    }
    return this.api.guardarConfiguracion(id, lxv, 'LXV').pipe(
      switchMap((config) =>
        mjs ? this.api.guardarConfiguracion(id, mjs, 'MJS').pipe(map((otra) => [config, otra])) : of([config]),
      ),
      tap((configs) => {
        if (this.seleccionado() === id) {
          configs.forEach((config) => this.aplicarConfiguracion(config));
        }
      }),
      // La velocidad o el máximo por chute también invalidan operarios de M-J-S.
      tap(() => this.refrescarOtro(id, 'LXV')),
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
    return this.guardarTodo(id).pipe(switchMap(() => paso()));
  }

  /**
   * Paso 1 del cluster en curso: primero pide entre qué chutes repartir sus
   * rutas. Propone el último rango usado o, si no hay, del primer al último
   * chute que el cluster usa hoy.
   */
  balancear(cluster: { id: number; nombre: string }): void {
    const cfg = this.config();
    if (!cfg || this.ocupado()) {
      return;
    }
    const orden = this.opcionesChute();
    const existe = (id: number | null) => id !== null && orden.some((chute) => chute.id === id);
    const guardado = cfg.clusters.find((item) => item.id === cluster.id);
    // Si el otro balanceo ya definió el rango, se usa el suyo (aquí no se elige).
    const otro: Balanceo = this.balanceoDe(cluster.id) === 'LXV' ? 'MJS' : 'LXV';
    const delOtro = guardado?.rangoDelOtro
      ? this.configs()[otro]?.clusters.find((item) => item.id === cluster.id)
      : null;
    let desde = (delOtro ?? guardado)?.chuteDesdeId ?? null;
    let hasta = (delOtro ?? guardado)?.chuteHastaId ?? null;
    if (!existe(desde) || !existe(hasta)) {
      const usados = new Set(
        cfg.carga
          .filter((fila) => fila.clusterId === cluster.id && fila.rutas > 0)
          .map((fila) => fila.chuteId),
      );
      const indices = orden.flatMap((chute, indice) => (usados.has(chute.id) ? [indice] : []));
      desde = orden[indices[0] ?? 0]?.id ?? null;
      hasta = orden[indices.at(-1) ?? orden.length - 1]?.id ?? null;
    }
    this.rangoDesde.set(desde);
    this.rangoHasta.set(hasta);
    // Propone la prioridad guardada (los grupos ya vienen en ese orden, y los nuevos al final).
    this.prioridadGrupos.set((guardado?.grupos ?? []).map((g) => g.nombre));
    this.vistaGrupos.set(null);
    this.rangoPendiente.set(cluster);
  }

  /** Confirma el modal de rango y lanza el paso 1 con esos chutes. */
  confirmarRango(): void {
    const cluster = this.rangoPendiente();
    const desde = this.rangoDesde();
    const hasta = this.rangoHasta();
    if (!cluster || desde === null || hasta === null || !this.rangoListo()) {
      return;
    }
    const prioridad = this.prioridadGrupos();
    const heredado = this.rangoHeredado() !== null;
    this.rangoPendiente.set(null);
    // Con el rango del otro balanceo, el servidor usa el suyo: no se manda.
    this.ejecutarPaso(
      'RUTAS',
      cluster,
      heredado
        ? {}
        : {
            chuteDesdeId: desde,
            chuteHastaId: hasta,
            ...(prioridad.length > 1 ? { prioridad } : {}),
          },
    );
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
    // Propone la cantidad que usó la ola la última vez; si nunca la eligió, el campo
    // queda vacío y hay que ingresarla (ya no hay equipo por defecto).
    const guardada = this.config()?.clusters.find((item) => item.id === cluster.id)?.operariosOla;
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
    const cfg = this.config();
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

  /** Botón ▶ del panel: ejecuta el paso que le toca al cluster en curso (1 rutas, 2 operarios, 3 cerrar). */
  ejecutarPasoActual(cluster: ResumenCluster): void {
    if (cluster.paso === 1) {
      this.balancear(cluster);
    } else if (cluster.paso === 2) {
      this.asignarOperarios(cluster);
    } else {
      this.cerrar(cluster);
    }
  }

  private ejecutarPaso(
    paso: Paso,
    cluster: { id: number; nombre: string },
    cuerpo: BalanceoCuerpo | null = null,
  ): void {
    const id = this.seleccionado();
    if (!id || this.ocupado()) {
      return;
    }
    const balanceo = this.balanceoDe(cluster.id);
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
            ? `${cluster.nombre}: rutas balanceadas`
            : paso === 'OPERARIOS'
              ? `${cluster.nombre}: operarios asignados`
              : `${cluster.nombre} cerrado`,
          mensaje,
        );
        this.actualizarEstadoEnLista(id, config.archivo.estado);
        if (this.seleccionado() === id) {
          this.aplicarConfiguracion(config);
          this.refrescarOtro(id, balanceo);
          this.recargarRutas(true);
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
      : 'Era el último cluster de este balanceo. Revisa el resumen en el dashboard.';
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
    const balanceo = this.balanceoDe(cluster.id);
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
            this.refrescarOtro(id, balanceo);
            this.recargarRutas(true);
          }
          this.notificaciones.info(
            `${cluster.nombre} restablecido`,
            'Se deshizo lo hecho mientras estuvo en curso: su balanceo y los cambios a mano. Su ola quedó sin operarios.',
          );
        },
      });
  }

  /** Reabrir un cluster devuelve a la proyección a los que se trabajaron después. */
  confirmarReabrir(cluster: { id: number; nombre: string }): void {
    if (this.ocupado() || this.avisarCambiosSinGuardar('reabrir el cluster')) {
      return;
    }
    const clusters = this.configs()[this.balanceoDe(cluster.id)]?.clusters ?? [];
    const indice = clusters.findIndex((otro) => otro.id === cluster.id);
    this.clusterAReabrir.set({
      id: cluster.id,
      nombre: cluster.nombre,
      siguientes: clusters
        .slice(indice + 1)
        .filter((otro) => otro.estado !== 'PENDIENTE')
        .map((otro) => otro.nombre),
    });
  }

  reabrir(): void {
    const id = this.seleccionado();
    const cluster = this.clusterAReabrir();
    if (!id || !cluster || this.ocupado()) {
      return;
    }
    const balanceo = this.balanceoDe(cluster.id);
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
            this.refrescarOtro(id, balanceo);
            this.recargarRutas(true);
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
    // Los dos balanceos vuelven a la proyección: primero L-X-V y luego M-J-S (al
    // quedar L-X-V pendiente, las diarias también vuelven a su chute del Excel).
    this.api
      .restablecer(id, 'LXV')
      .pipe(
        switchMap(() => this.api.restablecer(id, 'MJS')),
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
      });
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

/** «L-M-X-J-V-S», «L-X-V» o «M-J-S». */
export function textoFrecuencia(frecuencia: string | null | undefined): string {
  return frecuencia === 'LXV' ? 'L-X-V' : frecuencia === 'MJS' ? 'M-J-S' : 'L-M-X-J-V-S';
}
