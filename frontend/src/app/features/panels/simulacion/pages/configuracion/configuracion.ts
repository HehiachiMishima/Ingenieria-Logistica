import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
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
  CargaSimulacion,
  ConfiguracionSimulacion,
  ConfiguracionSimulacionRequest,
  RutaSimulacion,
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
  compararNombre,
  datosCarga,
  etiquetaEstado,
  etiquetaRegistro,
  formatearNumero,
  nombreRegistroSugerido,
  problemaArchivoExcel,
  tamanoArchivo,
} from '../../simulacion-formato';
import {
  Paso,
  ResumenCluster,
  normalizarEquipo,
  normalizarLimite,
  normalizarPorChute,
  normalizarVelocidad,
  resumirClusters,
} from './configuracion.logica';
import { SimulacionGuiaBalanceo } from './guia-balanceo/guia-balanceo';
import { SimulacionHistorialCambios } from './historial-cambios/historial-cambios';
import { ArrastreRutas, DestinoRuta } from './arrastre-rutas.directive';
import { ScrollMatriz } from './scroll-matriz.directive';
import { HorasPipe, NumeroPipe } from '../../numero.pipe';

/** Ruta lista para pintar: el título y los permisos ya calculados (sin métodos en la plantilla). */
interface CeldaRuta {
  ruta: RutaSimulacion;
  titulo: string;
  /** Se puede arrastrar: hay permiso y su cluster no está cerrado. */
  movible: boolean;
  /** Ya no está en el chute o cluster del Excel. */
  movida: boolean;
  cambioCluster: boolean;
}

/** Un cluster de la matriz: una fila por nivel de apilado y una celda por chute. */
interface BloqueMatriz {
  cluster: ResumenCluster;
  /** filas[f][c]: ruta en la fila f del chute c (en el orden de `chutesMatriz`), o null. */
  filas: (CeldaRuta | null)[][];
}

/**
 * Filas mínimas de cada bloque: dejan sitio al panel del cluster y huecos
 * libres donde soltar una ruta aunque el chute ya esté lleno.
 */
const MIN_FILAS = 8;

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
    NumeroPipe,
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

  readonly config = signal<ConfiguracionSimulacion | null>(null);
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly errorOpciones = signal<string | null>(null);
  /** Descarta respuestas viejas si el usuario cambia de registro rápido. */
  private solicitud = 0;

  // ---------------- Equipo y límites en edición ----------------
  readonly velocidad = signal(VELOCIDAD_INICIAL);
  /** Operarios del equipo: los mismos pasan de un cluster al siguiente. */
  readonly equipo = signal(1);
  /** Máximo de operarios en un chute; null = sin tope propio (hasta todo el equipo). */
  readonly porChute = signal<number | null>(null);
  readonly limites = signal<Record<number, number>>({});

  /** Hay cambios del equipo o de algún límite sin guardar. */
  readonly dirty = computed(() => {
    const cfg = this.config();
    if (!cfg) {
      return false;
    }
    const limites = this.limites();
    // Se compara con la configuración normalizada igual que al aplicarla: un 0 o
    // un nulo del servidor no dejan «Cambios sin guardar» fijo.
    const guardado = equipoNormalizado(cfg);
    return (
      guardado.velocidad !== this.velocidad() ||
      guardado.equipo !== this.equipo() ||
      guardado.porChute !== this.porChute() ||
      cfg.clusters.some((cluster) => Number(cluster.limiteHoras) !== (limites[cluster.id] ?? 0))
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

  readonly clustersCerrados = computed(
    () => (this.config()?.clusters ?? []).filter((cluster) => cluster.estado === 'CERRADO').length,
  );

  /**
   * El equipo ya trabajó en clusters cerrados: para cambiarlo hay que reabrir
   * el primero de ellos (los siguientes vuelven a la proyección).
   */
  readonly equipoBloqueado = computed(
    () => (this.config()?.clusters ?? []).find((cluster) => cluster.estado === 'CERRADO') ?? null,
  );

  readonly historialAbierto = signal(false);

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

  /** Chutes de la matriz, en orden natural (CHUTE 2 antes que CHUTE 10). */
  readonly chutesMatriz = computed(() =>
    [...(this.config()?.chutes ?? [])]
      .sort((a, b) => compararNombre(a.nombre, b.nombre))
      .map((chute) => ({ ...chute, etiqueta: etiquetaChute(chute.nombre) })),
  );

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
  private readonly celdasPorCluster = computed(() => {
    const chutes = this.chutesMatriz();
    const cerrados = new Set(
      (this.config()?.clusters ?? []).filter((c) => c.estado === 'CERRADO').map((c) => c.id),
    );
    const editable = this.puedeModificar();
    const porCluster = new Map<number, Map<number, RutaSimulacion[]>>();

    for (const ruta of this.rutasMatriz()) {
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
    return this.resumenClusters().map((cluster) => ({
      cluster,
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
   * Totales por chute de todo el registro. Salen de `carga`, que ya viene
   * agregada por cluster y chute: no se calculan desde las rutas de pantalla,
   * así el pie no cambia mientras la matriz termina de cargar.
   */
  readonly totalesMatriz = computed(() => this.totalesPorChute(null));

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

  /** Filas de las tablas de totales (por cluster y general), como en la planilla. */
  /**
   * Filas de las tablas de totales (por cluster y general). Cada una tiene su
   * color (token del sistema) y sus celdas se oscurecen cuanto mayor es el valor.
   */
  readonly filasTotales: { clave: CampoTotal; etiqueta: string; decimales: number; tono: string }[] = [
    { clave: 'operarios', etiqueta: 'Total operarios', decimales: 0, tono: 'var(--violet)' },
    { clave: 'bultos', etiqueta: 'Total bultos', decimales: 2, tono: 'var(--accent)' },
    { clave: 'lineas', etiqueta: 'Total líneas', decimales: 2, tono: 'var(--success)' },
    { clave: 'unidades', etiqueta: 'Total unidades', decimales: 2, tono: 'var(--warning)' },
    { clave: 'cod', etiqueta: 'Total códigos', decimales: 0, tono: 'var(--cyan)' },
  ];

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

  /** Totales por chute (en el orden de las columnas) de un cluster o, con null, de todos. */
  private totalesPorChute(clusterId: number | null): TotalesChute {
    const chutes = this.chutesMatriz();
    const carga = (this.config()?.carga ?? []).filter(
      (fila) => clusterId === null || fila.clusterId === clusterId,
    );
    const porChute = new Map<number, CargaSimulacion[]>();
    for (const fila of carga) {
      porChute.set(fila.chuteId, [...(porChute.get(fila.chuteId) ?? []), fila]);
    }
    // En el total general, los operarios de un chute son el máximo de sus olas:
    // el mismo equipo pasa de un cluster al siguiente, sumarlas lo contaría varias veces.
    const columna = (campo: CampoCarga) =>
      chutes.map((chute) =>
        (porChute.get(chute.id) ?? []).reduce((total, fila) => {
          const valor = Number(fila[campo] ?? 0);
          return clusterId === null && campo === 'operarios' ? Math.max(total, valor) : total + valor;
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

  readonly opcionesChute = computed(() =>
    [...(this.config()?.chutes ?? [])]
      .sort((a, b) => compararNombre(a.nombre, b.nombre))
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
    return cfg ? resumirClusters(cfg, this.velocidad() || VELOCIDAD_INICIAL, this.limites()) : [];
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
            this.config.set(null);
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

  /** Con el equipo o un límite sin guardar, avisa en lugar de descartarlos. */
  private avisarCambiosSinGuardar(accion: string): boolean {
    if (this.dirty()) {
      this.notificaciones.advertencia(
        'Cambios sin guardar',
        `Guarda el equipo y los límites antes de ${accion}.`,
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
    }
    this.seleccionado.set(id);
    this.config.set(null);
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
    this.api
      .configuracion(id)
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
        next: (config) => {
          if (solicitud === this.solicitud) {
            this.aplicarConfiguracion(config);
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

  /** Carga la configuración del servidor y descarta lo editado del equipo y los límites. */
  private aplicarConfiguracion(config: ConfiguracionSimulacion): void {
    this.config.set(config);
    const equipo = equipoNormalizado(config);
    this.velocidad.set(equipo.velocidad);
    this.equipo.set(equipo.equipo);
    this.porChute.set(equipo.porChute);
    this.limites.set(
      Object.fromEntries(
        config.clusters.map((cluster) => [cluster.id, Number(cluster.limiteHoras)]),
      ),
    );
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
    const n = (valor: number) => formatearNumero(valor, 2);
    const detalle =
      `${ruta.codigoRuta} · ${ruta.tipo}\n` +
      `Bultos: ${n(ruta.totalBultos)} · Unidades: ${n(ruta.totalUnidades)} · ` +
      `Líneas: ${n(ruta.totalLineas)} · COD: ${formatearNumero(ruta.conteoCod)}`;
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
   * clusters cerrados (antes o después de balancear se puede mover) y
   * CL1/CL2 sin compartir chute.
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
    if (this.clusterCerrado(ruta.clusterId)) {
      return `${ruta.cluster} está cerrado: reábrelo para mover sus rutas.`;
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
    // CL1 y CL2 no comparten chute: mira qué clusters quedan en el chute destino
    // (el servidor cambia cluster y chute en un solo paso y valida lo mismo).
    const presentes = cfg.carga
      .filter((fila) => fila.chuteId === chuteId && fila.rutas > 0)
      .filter((fila) => !(fila.clusterId === ruta.clusterId && fila.chuteId === ruta.chuteId && fila.rutas <= 1))
      .map((fila) => fila.clusterId);
    const choque = presentes.find((id) => destino.incompatibles.includes(id));
    if (choque !== undefined) {
      const chute = cfg.chutes.find((c) => c.id === chuteId)?.nombre ?? chuteId;
      const otro = cfg.clusters.find((cluster) => cluster.id === choque)?.nombre ?? choque;
      return `El chute ${chute} tiene rutas de ${otro}: no pueden mezclarse con ${destino.nombre}.`;
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

    this.rutaGuardando.set(ruta.id);
    // Cambio de cluster (con o sin chute) en un solo llamado: el servidor valida
    // el destino final, sin un estado intermedio que pueda rechazar.
    this.conCambiosGuardados(id, () =>
      cambiaCluster
        ? this.api.cambiarCluster(id, ruta.id, clusterId, cambiaChute ? chuteId : null)
        : this.api.moverRuta(id, ruta.id, chuteId),
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
          }
          const desde = `${ruta.cluster} · chute ${ruta.chute}`;
          const hasta = `${nombreCluster} · chute ${nombreChute}`;
          this.notificaciones.exito('Ruta movida', `${ruta.codigoRuta}: ${desde} → ${hasta}.`);
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

  setEquipo(evento: Event): void {
    const input = evento.target as HTMLInputElement;
    const equipo = normalizarEquipo(input.value);
    input.value = String(equipo);
    this.equipo.set(equipo);
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

  private peticion(): ConfiguracionSimulacionRequest | null {
    const config = this.config();
    if (!config) {
      return null;
    }
    return {
      velocidadBase: this.velocidad(),
      operariosMaximos: this.equipo(),
      operariosPorChute: this.porChute(),
      clusters: config.clusters.map((cluster) => ({
        clusterId: cluster.id,
        limiteHoras: this.limites()[cluster.id] ?? 0,
      })),
    };
  }

  /** Guarda el equipo y los límites en edición. */
  guardar(): void {
    const id = this.seleccionado();
    const peticion = this.peticion();
    if (!id || !peticion || this.ocupado()) {
      return;
    }
    this.guardando.set(true);
    this.api
      .guardarConfiguracion(id, peticion)
      .pipe(
        finalize(() => this.guardando.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (config) => {
          if (this.seleccionado() === id) {
            this.aplicarConfiguracion(config);
          }
          this.notificaciones.exito(
            'Cambios guardados',
            'El equipo y los límites quedaron al día.',
          );
        },
      });
  }

  /**
   * Guarda lo editado antes de un paso y aplica la respuesta: si el paso
   * falla, lo guardado ya no figura como pendiente.
   */
  private conCambiosGuardados<T>(id: number, paso: () => Observable<T>): Observable<T> {
    const peticion = this.peticion();
    if (!this.dirty() || !peticion) {
      return paso();
    }
    return this.api.guardarConfiguracion(id, peticion).pipe(
      tap((config) => {
        if (this.seleccionado() === id) {
          this.aplicarConfiguracion(config);
        }
      }),
      switchMap(() => paso()),
    );
  }

  /**
   * Paso 1 del cluster en curso: lo separa de su incompatible y reparte sus
   * rutas para que sus chutes queden parejos en bultos.
   */
  balancear(cluster: { id: number; nombre: string }): void {
    this.ejecutarPaso('RUTAS', cluster);
  }

  /**
   * Paso 2: reparte todo el equipo para que la ola cierre lo antes posible,
   * partiendo de donde quedó en la ola anterior. Necesita el límite de horas
   * del cluster para decir si cumple.
   */
  asignarOperarios(cluster: { id: number; nombre: string }): void {
    if ((this.limites()[cluster.id] ?? 0) <= 0) {
      this.notificaciones.advertencia(
        'Define el límite',
        `Ingresa el límite de horas de ${cluster.nombre} para asignar sus operarios.`,
      );
      return;
    }
    this.ejecutarPaso('OPERARIOS', cluster);
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
        `Cambiaste el límite de ${cluster.nombre} o el equipo sin guardar: pulsa «Recalcular operarios» (guarda los cambios) y luego ciérralo.`,
      );
      return;
    }
    this.ejecutarPaso('CERRAR', cluster);
  }

  /** Lo editado sin guardar (equipo o el límite de este cluster) cambia sus operarios. */
  private cambiosInvalidanOperarios(clusterId: number): boolean {
    const cfg = this.config();
    if (!cfg) {
      return false;
    }
    const guardado = equipoNormalizado(cfg);
    const limite = Number(cfg.clusters.find((cluster) => cluster.id === clusterId)?.limiteHoras ?? 0);
    return (
      guardado.velocidad !== this.velocidad() ||
      guardado.equipo !== this.equipo() ||
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

  private ejecutarPaso(paso: Paso, cluster: { id: number; nombre: string }): void {
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
              .cerrar(id, cluster.id)
              .pipe(map((config) => ({ mensaje: this.mensajeCierre(cluster.nombre), config })))
          : this.api.balancear(id, cluster.id, paso).pipe(
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
    this.restableciendo.set(true);
    this.api
      .restablecerCluster(id, cluster.id)
      .pipe(
        finalize(() => this.restableciendo.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (config) => {
          this.clusterARestablecer.set(null);
          if (this.seleccionado() === id) {
            this.aplicarConfiguracion(config);
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
    const clusters = this.config()?.clusters ?? [];
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
    this.restableciendo.set(true);
    this.api
      .reabrir(id, cluster.id)
      .pipe(
        finalize(() => this.restableciendo.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (config) => {
          this.clusterAReabrir.set(null);
          if (this.seleccionado() === id) {
            this.aplicarConfiguracion(config);
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

/**
 * Nivel de cada valor dentro de su fila para el mapa de color: el menor
 * (distinto de cero) vale 0 y el mayor 1. Los vacíos quedan sin color (null);
 * si todos son iguales, todos van al medio.
 */
function nivelesDeFila(valores: number[]): (number | null)[] {
  const conDato = valores.filter((valor) => valor > 0);
  if (conDato.length === 0) {
    return valores.map(() => null);
  }
  const menor = Math.min(...conDato);
  const rango = Math.max(...conDato) - menor;
  return valores.map((valor) =>
    valor > 0 ? (rango === 0 ? 0.5 : Math.round(((valor - menor) / rango) * 100) / 100) : null,
  );
}

/** Equipo tal como se edita: velocidad válida, al menos 1 operario y 0/nulo = sin tope por chute. */
function equipoNormalizado(config: ConfiguracionSimulacion): {
  velocidad: number;
  equipo: number;
  porChute: number | null;
} {
  const porChute = Number(config.operariosPorChute);
  return {
    velocidad: Number(config.velocidadBase) || VELOCIDAD_INICIAL,
    equipo: Math.max(1, Number(config.operariosMaximos) || 1),
    porChute: config.operariosPorChute === null || !(porChute > 0) ? null : porChute,
  };
}
