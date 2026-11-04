import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize } from 'rxjs';
import { PERMISOS } from '../../../../../core/config/permisos';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import {
  ArchivoSimulacion,
  ChuteComparativoSimulacion,
  DashboardSimulacion,
} from '../../data/simulacion.model';
import { SimulacionApi } from '../../data/simulacion.api';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import {
  ComparativoChart,
  GrupoComparativo,
} from '../../../../../shared/ui/charts/comparativo-chart';
import { porcentaje } from '../../../../../shared/ui/charts/formato';
import { Kpi } from '../../../../../shared/ui/charts/kpi';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../shared/ui/modal/modal';
import { ChipFiltro, FiltrosActivos } from '../../../../../shared/ui/panel-filtros/filtros-activos';
import { PanelFiltros } from '../../../../../shared/ui/panel-filtros/panel-filtros';
import { SelectBuscable } from '../../../../../shared/ui/select-buscable/select-buscable';
import { SelectMultiple } from '../../../../../shared/ui/select-multiple/select-multiple';
import {
  colorCluster,
  etiquetaEstadoCluster,
  etiquetaRegistro,
  formatearHoras,
  formatearNumero,
} from '../../simulacion-formato';
import { SimulacionDetalleChute } from './detalle-chute/detalle-chute';

type Metrica = 'rutas' | 'bultos' | 'unidades' | 'lineas' | 'cod';

/**
 * Cluster que se abre al entrar a un registro sin terminar: el primero sin
 * cerrar si ya empezó; si no, el último trabajado. Null si no hay ninguno.
 */
function olaPorDefecto(dashboard: DashboardSimulacion): number | null {
  const trabajadas = dashboard.olas.filter((ola) => ola.estado !== 'PENDIENTE');
  const enCurso = trabajadas.find((ola) => ola.estado !== 'CERRADO');
  return (enCurso ?? trabajadas[trabajadas.length - 1])?.clusterId ?? null;
}

/**
 * Diferencia de bultos entre chutes, igual que la mide el paso 1:
 * (chute más cargado − menos cargado) / promedio, en %. Null con menos de dos
 * chutes con carga.
 */
function dispersion(bultos: number[]): number | null {
  const cargas = bultos.filter((valor) => valor > 0);
  if (cargas.length < 2) {
    return null;
  }
  const promedio = cargas.reduce((total, valor) => total + valor, 0) / cargas.length;
  return Math.round(((Math.max(...cargas) - Math.min(...cargas)) / promedio) * 100);
}

/**
 * Métricas de carga por chute: qué campos comparar, cómo se llaman y con
 * cuántos decimales se muestran (bultos, unidades y líneas son promedios).
 */
const METRICAS: ReadonlyArray<{
  id: Metrica;
  nombre: string;
  decimales: number;
  antes: (c: ChuteComparativoSimulacion) => number;
  despues: (c: ChuteComparativoSimulacion) => number;
}> = [
  {
    id: 'rutas',
    nombre: 'Rutas',
    decimales: 0,
    antes: (c) => c.rutasOriginal,
    despues: (c) => c.rutasBalanceado,
  },
  {
    id: 'bultos',
    nombre: 'Bultos',
    decimales: 2,
    antes: (c) => c.bultosOriginal,
    despues: (c) => c.bultosBalanceado,
  },
  {
    id: 'unidades',
    nombre: 'Unidades',
    decimales: 2,
    antes: (c) => c.unidadesOriginal,
    despues: (c) => c.unidadesBalanceado,
  },
  {
    id: 'lineas',
    nombre: 'Líneas',
    decimales: 2,
    antes: (c) => c.lineasOriginal,
    despues: (c) => c.lineasBalanceado,
  },
  {
    id: 'cod',
    nombre: 'COD',
    decimales: 0,
    antes: (c) => c.codOriginal,
    despues: (c) => c.codBalanceado,
  },
];

/**
 * Dashboard de simulación. Cada cluster se ve por separado, en el orden en que
 * se trabaja: primero sus rutas (carga por chute antes y después, qué tan
 * parejos quedaron sus bultos) y luego sus operarios (lo que recibe de la ola
 * anterior frente a lo asignado, su cierre frente al límite y el paso de
 * gente). Al cerrar el último cluster se habilita el resumen de todas las olas.
 */
@Component({
  selector: 'app-simulacion-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgTemplateOutlet,
    HasPermiso,
    Kpi,
    ComparativoChart,
    EmptyState,
    Icon,
    Modal,
    SelectBuscable,
    SelectMultiple,
    PanelFiltros,
    FiltrosActivos,
    SimulacionDetalleChute,
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class SimulacionDashboard {
  private readonly api = inject(SimulacionApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly permisos = PERMISOS;
  readonly metricas = METRICAS;
  readonly formatearHoras = formatearHoras;
  readonly formatearNumero = formatearNumero;
  readonly estadoCluster = etiquetaEstadoCluster;
  /** Valores de carga en el gráfico: enteros con separador de miles. */
  readonly formatoCarga = (valor: number): string => formatearNumero(valor);

  readonly registros = signal<ArchivoSimulacion[]>([]);
  readonly seleccionado = signal<number | null>(null);
  readonly cargandoOpciones = signal(false);

  readonly dashboard = signal<DashboardSimulacion | null>(null);
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly errorOpciones = signal<string | null>(null);
  /** Descarta respuestas viejas si el usuario cambia de registro rápido. */
  private solicitud = 0;

  readonly metrica = signal<Metrica>('rutas');
  /** Cluster que se ve; null = resumen de todas las olas. */
  readonly vista = signal<number | null>(null);

  // ---------------- Filtros del panel (múltiples) ----------------
  readonly filtrosAbierto = signal(false);
  readonly filtroChutes = signal<string[]>([]);
  readonly filtroClusters = signal<string[]>([]);

  readonly opcionesChute = computed(() =>
    (this.dashboard()?.chutes ?? []).map((chute) => ({ id: chute.chute, etiqueta: chute.chute })),
  );

  readonly opcionesCluster = computed(() =>
    (this.dashboard()?.clusters ?? []).map((cluster) => ({
      id: cluster.cluster,
      etiqueta: cluster.cluster,
    })),
  );

  /**
   * Chutes elegidos y, si hay clusters elegidos, solo los que atienden alguno
   * de ellos: el filtro de cluster también acota la sección de chutes.
   */
  readonly chutesVisibles = computed(() => {
    const filtroChutes = this.filtroChutes();
    const filtroClusters = this.filtroClusters();
    return (this.dashboard()?.chutes ?? []).filter(
      (chute) =>
        (filtroChutes.length === 0 || filtroChutes.includes(chute.chute)) &&
        (filtroClusters.length === 0 ||
          chute.clusters.some((cluster) => filtroClusters.includes(cluster))),
    );
  });

  /** Chute cuyas rutas se abren al pulsar su nombre en el cuadro. */
  readonly detalleChute = signal<ChuteComparativoSimulacion | null>(null);
  /** Lista estable para el detalle: un arreglo nuevo en la plantilla se recrearía en cada ciclo. */
  readonly chutesModal = computed(() => {
    const chute = this.detalleChute();
    return chute ? [chute] : [];
  });

  readonly tituloDetalle = computed(() => {
    const chute = this.detalleChute();
    return chute ? `Rutas de ${chute.chute}: antes vs. después` : '';
  });

  readonly subtituloDetalle =
    'Las rutas que tenía al cargar el Excel y las que tiene tras el balanceo, agrupadas por cluster.';

  abrirDetalle(chute: ChuteComparativoSimulacion): void {
    this.detalleChute.set(chute);
  }

  cerrarDetalle(): void {
    this.detalleChute.set(null);
  }

  /** Clusters que atiende el chute en el resumen; si el balanceo los cambió, «antes → después». */
  clustersDeChute(chute: ChuteComparativoSimulacion): string {
    const antes = chute.clustersOriginal.join(', ') || '—';
    const despues = chute.clustersBalanceado.join(', ') || '—';
    return antes === despues ? despues : `${antes} → ${despues}`;
  }

  /** Texto del chute en el cuadro: al pulsarlo se abren sus rutas antes y después. */
  tituloDetalleChute(chute: ChuteComparativoSimulacion): string {
    return `Ver las rutas de ${chute.chute} antes y después del balanceo`;
  }

  readonly chips = computed<ChipFiltro[]>(() => [
    ...this.filtroChutes().map((chute) => ({
      clave: `chute:${chute}`,
      etiqueta: 'Chute',
      valor: chute,
      icono: 'layers',
    })),
    ...this.filtroClusters().map((cluster) => ({
      clave: `cluster:${cluster}`,
      etiqueta: 'Cluster',
      valor: cluster,
      icono: 'area',
    })),
  ]);

  readonly filtrosActivos = computed(() => this.chips().length);

  alternarFiltros(): void {
    this.filtrosAbierto.update((abierto) => !abierto);
  }

  setFiltroChutes(valores: (number | string)[]): void {
    this.filtroChutes.set(valores.map(String));
  }

  setFiltroClusters(valores: (number | string)[]): void {
    this.filtroClusters.set(valores.map(String));
  }

  quitarFiltro(clave: string): void {
    const [tipo, ...resto] = clave.split(':');
    const valor = resto.join(':');
    if (tipo === 'chute') {
      this.filtroChutes.update((chutes) => chutes.filter((chute) => chute !== valor));
    } else if (tipo === 'cluster') {
      this.filtroClusters.update((clusters) => clusters.filter((cluster) => cluster !== valor));
    }
  }

  limpiarFiltros(): void {
    this.filtroChutes.set([]);
    this.filtroClusters.set([]);
  }

  readonly metricaActual = computed(
    () => METRICAS.find((m) => m.id === this.metrica()) ?? METRICAS[0],
  );

  readonly opcionesRegistro = computed(() =>
    this.registros().map((registro) => ({ id: registro.id, etiqueta: etiquetaRegistro(registro) })),
  );

  readonly registroSeleccionado = computed(() => {
    const id = this.seleccionado();
    return this.registros().find((registro) => registro.id === id) ?? null;
  });

  /** Con algún cluster lanzado hay rutas repartidas que comparar; en proyección, nada. */
  readonly estaBalanceado = computed(() => {
    const estado = this.registroSeleccionado()?.estado;
    return estado === 'EN_PROCESO' || estado === 'FINALIZADO';
  });
  readonly finalizado = computed(() => this.registroSeleccionado()?.estado === 'FINALIZADO');

  readonly olas = computed(() => this.dashboard()?.olas ?? []);
  readonly olaActual = computed(
    () => this.olas().find((ola) => ola.clusterId === this.vista()) ?? null,
  );
  /** Ola anterior a la que se ve: de ella llega el equipo. */
  readonly olaPrevia = computed(() => {
    const indice = this.olas().findIndex((ola) => ola.clusterId === this.vista());
    return indice > 0 ? this.olas()[indice - 1].cluster : null;
  });
  /** La ola que se ve ya tiene sus operarios asignados. */
  readonly operariosListos = computed(() => {
    const estado = this.olaActual()?.estado;
    return estado === 'LISTO' || estado === 'CERRADO';
  });

  /** Operarios que cambian de chute al pasar de una ola a la siguiente (todas las transiciones). */
  readonly operariosEntreOlas = computed(() =>
    (this.dashboard()?.flujoOperarios ?? []).reduce(
      (total, paso) => total + Math.min(paso.salen, paso.llegan),
      0,
    ),
  );

  /**
   * Indicadores de las rutas del cluster que se ve: qué tan parejos quedaron
   * sus chutes, su chute más cargado y las rutas que movió.
   */
  readonly tarjetas = computed(() => {
    const datos = this.dashboard();
    if (!datos) {
      return [];
    }
    const kpis = datos.kpis;
    if (!this.olaActual()) {
      return [];
    }
    const chutes = datos.chutes;
    const antes = chutes.map((chute) => Number(chute.bultosOriginal));
    const despues = chutes.map((chute) => Number(chute.bultosBalanceado));
    const difAntes = dispersion(antes);
    const difDespues = dispersion(despues);
    const maxAntes = Math.max(0, ...antes);
    const maxDespues = Math.max(0, ...despues);
    const separados = chutes.filter((chute) => chute.clustersSeparados.length > 0).length;
    return [
      {
        etiqueta: 'Diferencia de bultos',
        valor: difDespues === null ? '—' : `${difDespues} %`,
        icono: 'paquete',
        color: 'var(--info)',
        detalle:
          difAntes === null
            ? 'Su ola tiene un solo chute'
            : `Entre sus chutes · antes ${difAntes} % · meta: 10 % o menos`,
      },
      {
        etiqueta: 'Chute más cargado',
        valor: `${formatearNumero(maxDespues)} bultos`,
        icono: 'almacen',
        color: 'var(--accent)',
        detalle:
          maxAntes > 0
            ? `Antes ${formatearNumero(maxAntes)} · ${this.variacion(maxAntes, maxDespues)}: es el que marca el cierre`
            : 'Sin carga',
      },
      {
        etiqueta: 'Rutas movidas',
        valor: `${formatearNumero(kpis.rutasMovidas)} rutas`,
        icono: 'flecha',
        color: 'var(--violet)',
        detalle:
          separados > 0
            ? `en otro chute · separa CL1 y CL2 en ${formatearNumero(separados)} chute(s)`
            : 'en otro chute para igualar sus bultos',
      },
    ];
  });

  /**
   * Indicadores de la sección de operarios del cluster: su cierre frente al
   * límite (y si nadie se moviera), cuántos operarios usa y cuántos cambian de
   * chute al llegar de la ola anterior.
   */
  readonly tarjetasOperarios = computed(() => {
    const datos = this.dashboard();
    const ola = this.olaActual();
    if (!datos || !ola) {
      return [];
    }
    const kpis = datos.kpis;
    const cluster = datos.clusters[0];
    const previa = this.olaPrevia();
    const asignados = datos.chutes.reduce((total, chute) => total + chute.operariosBalanceado, 0);
    const paso = datos.flujoOperarios[0];
    const limite = cluster && Number(cluster.limiteHoras) > 0 ? Number(cluster.limiteHoras) : null;
    return [
      {
        etiqueta: `Cierre de ${ola.cluster}`,
        valor: formatearHoras(kpis.tiempoBalanceado),
        icono: 'reloj',
        color: cluster?.excede ? 'var(--danger)' : 'var(--success)',
        detalle:
          (limite === null
            ? 'Sin límite'
            : `Límite ${formatearHoras(limite)} · ${cluster?.excede ? 'excede' : 'cumple'}`) +
          (kpis.tiempoOriginal !== null
            ? ` · sin mover gente: ${formatearHoras(kpis.tiempoOriginal)}`
            : ''),
      },
      {
        etiqueta: 'Operarios en la ola',
        valor: formatearNumero(asignados),
        icono: 'usuarios',
        color: 'var(--violet)',
        detalle:
          previa && paso
            ? `De los ${formatearNumero(paso.quedan + paso.salen)} que terminaron ${previa}, ${formatearNumero(paso.quedan)} siguen en su chute`
            : 'Primera ola: el equipo empieza aquí',
      },
      {
        etiqueta: 'Cambios de chute',
        valor: paso ? `${formatearNumero(Math.min(paso.salen, paso.llegan))}` : '—',
        icono: 'flecha',
        color: 'var(--accent)',
        detalle: paso
          ? `${formatearNumero(paso.quedan)} se quedan · ${formatearNumero(paso.salen)} salen · ${formatearNumero(paso.llegan)} llegan`
          : 'Nadie llega de otra ola',
      },
    ];
  });

  /**
   * Cuadro ejecutivo del resumen: carga, rutas movidas y cierre establecido
   * frente al conseguido de cada cluster. El total suma los cierres porque
   * las olas van una tras otra.
   */
  readonly cuadro = computed(() => {
    const olas = this.olas();
    const filas = olas.map((ola, indice) => {
      const limite = Number(ola.limiteHoras) > 0 ? Number(ola.limiteHoras) : null;
      const cierre = ola.tiempoCierre === null ? null : Number(ola.tiempoCierre);
      return {
        ola,
        color: colorCluster(indice),
        limite,
        cierre,
        excede: limite !== null && (cierre === null || cierre > limite + 0.01),
      };
    });
    const sumar = (valores: (number | null)[]) =>
      valores.includes(null) ? null : valores.reduce<number>((t, v) => t + Number(v), 0);
    const limite = sumar(filas.map((fila) => fila.limite));
    const cierre = sumar(filas.map((fila) => fila.cierre));
    return {
      filas,
      total: {
        bultos: olas.reduce((t, ola) => t + Number(ola.bultos), 0),
        lineas: olas.reduce((t, ola) => t + Number(ola.lineas), 0),
        unidades: olas.reduce((t, ola) => t + Number(ola.unidades), 0),
        cod: olas.reduce((t, ola) => t + ola.cod, 0),
        rutasMovidas: olas.reduce((t, ola) => t + ola.rutasMovidas, 0),
        limite,
        cierre,
      },
    };
  });

  /**
   * Dotación del equipo: operarios de cada chute en cada ola, con la
   * intensidad de cada celda respecto del chute con más gente.
   */
  readonly dotacion = computed(() => {
    const datos = this.dashboard();
    if (!datos) {
      return { filas: [], totales: [], cambios: [] };
    }
    const maximo = Math.max(1, ...datos.dotacion.flatMap((fila) => fila.operarios));
    const filas = datos.dotacion.map((fila) => ({
      chute: fila.chute,
      celdas: fila.operarios.map((operarios) => ({
        operarios,
        intensidad: Math.round((operarios / maximo) * 100),
      })),
    }));
    const totales = datos.olas.map((_, indice) =>
      datos.dotacion.reduce((total, fila) => total + (fila.operarios[indice] ?? 0), 0),
    );
    // Cambios de chute al llegar a cada ola desde la anterior (la primera no tiene).
    const cambios = datos.olas.map((ola) => {
      const paso = datos.flujoOperarios.find((p) => p.destinoId === ola.clusterId);
      return paso ? { cambian: Math.min(paso.salen, paso.llegan), quedan: paso.quedan } : null;
    });
    return { filas, totales, cambios };
  });

  /**
   * Dotación de la ola por chute: lo que recibe de la ola anterior frente a lo
   * asignado, y su tiempo frente al límite. Incluye los chutes que solo ceden
   * gente (sin carga en esta ola). La escala de las barras alcanza para el
   * límite y para el chute más lento.
   */
  readonly dotacionOla = computed(() => {
    const datos = this.dashboard();
    const paso = datos?.flujoOperarios[0] ?? null;
    if (!datos || !this.olaActual()) {
      return { filas: [], limite: 0, posLimite: 0, asignados: 0, paso };
    }
    const limite = Number(datos.clusters[0]?.limiteHoras ?? 0);
    const chutes = this.chutesVisibles();
    const tiempos = chutes.flatMap((chute) =>
      [chute.tiempoBalanceado, chute.tiempoOriginal].filter(
        (tiempo): tiempo is number => tiempo !== null,
      ),
    );
    const escala = Math.max(limite * 1.15, ...tiempos.map(Number), 0.0001);
    const conCarga = chutes.map((chute) => {
      const tiempo = chute.tiempoBalanceado === null ? null : Number(chute.tiempoBalanceado);
      const sinMover = chute.tiempoOriginal === null ? null : Number(chute.tiempoOriginal);
      return {
        id: chute.id,
        nombre: chute.chute,
        bultos: Number(chute.bultosBalanceado),
        recibidos: chute.operariosOriginal,
        asignados: chute.operariosBalanceado,
        tiempo,
        sinMover,
        barra: tiempo === null ? 0 : (tiempo / escala) * 100,
        barraSinMover: sinMover === null ? 0 : (sinMover / escala) * 100,
        excede: tiempo !== null && limite > 0 && tiempo > limite + 0.01,
      };
    });
    const filtro = this.filtroChutes();
    const ceden = (paso?.chutes ?? [])
      .filter(
        (chute) =>
          !chutes.some((visible) => visible.id === chute.chuteId) &&
          (filtro.length === 0 || filtro.includes(chute.chute)),
      )
      .map((chute) => ({
        id: chute.chuteId,
        nombre: chute.chute,
        bultos: 0,
        recibidos: chute.antes,
        asignados: chute.despues,
        tiempo: null,
        sinMover: null,
        barra: 0,
        barraSinMover: 0,
        excede: false,
      }));
    // Primero los chutes con carga en la ola; al final, los que solo ceden gente.
    const filas = [...conCarga, ...ceden]
      .sort((a, b) => Number(a.bultos === 0) - Number(b.bultos === 0))
      .map((fila) => ({ ...fila, movimiento: fila.asignados - fila.recibidos }));
    return {
      limite,
      posLimite: (limite / escala) * 100,
      asignados: filas.reduce((total, fila) => total + fila.asignados, 0),
      paso,
      filas,
    };
  });

  /** Carga por chute (métrica elegida): antes vs. después. */
  readonly gruposChute = computed<GrupoComparativo[]>(() => {
    const metrica = this.metricaActual();
    return this.chutesVisibles().map((chute) => ({
      etiqueta: chute.chute,
      antes: Number(metrica.antes(chute)),
      despues: Number(metrica.despues(chute)),
    }));
  });

  /** Totales de las cinco métricas (fila final del cuadro por chute). */
  readonly totalesChute = computed(() => {
    const chutes = this.chutesVisibles();
    return METRICAS.map((m) => ({
      antes: chutes.reduce((total, chute) => total + Number(m.antes(chute)), 0),
      despues: chutes.reduce((total, chute) => total + Number(m.despues(chute)), 0),
    }));
  });

  constructor() {
    this.cargarOpciones();
  }

  cargarOpciones(): void {
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
            this.solicitud++;
            this.seleccionado.set(null);
            this.dashboard.set(null);
            return;
          }
          const solicitado = Number(this.route.snapshot.queryParamMap.get('registro'));
          const actual = this.seleccionado();
          const elegido =
            lista.find((registro) => registro.id === actual)?.id ??
            lista.find((registro) => registro.id === solicitado)?.id ??
            lista[0].id;
          this.seleccionar(elegido);
        },
        error: (err: unknown) => this.errorOpciones.set(mensajeDeError(err)),
      });
  }

  /**
   * Recarga también la lista de registros: el estado puede haber cambiado en
   * otra pestaña y de él depende que se muestre el comparativo.
   */
  actualizar(): void {
    this.cargarOpciones();
  }

  seleccionar(id: number | null): void {
    // Los filtros y la vista se conservan al recargar el mismo registro («Actualizar»).
    if (id !== this.seleccionado()) {
      this.limpiarFiltros();
      this.vista.set(null);
    }
    this.seleccionado.set(id);
    this.dashboard.set(null);
    this.error.set(null);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { registro: id },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    this.cargar();
  }

  /** Solo pide el comparativo si hay ajustes: en proyección no hay nada que comparar. */
  cargar(): void {
    const id = this.seleccionado();
    const solicitud = ++this.solicitud;
    if (!id || !this.estaBalanceado()) {
      this.cargando.set(false);
      return;
    }
    this.cargando.set(true);
    this.error.set(null);
    this.api
      .dashboard(id, this.vista())
      .pipe(
        finalize(() => {
          if (solicitud === this.solicitud) {
            this.cargando.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (dashboard) => {
          if (solicitud !== this.solicitud) {
            return;
          }
          // Sin terminar, el resumen no está disponible: se abre el cluster en
          // curso (o el último trabajado).
          const ola = this.vista() === null && !this.finalizado() ? olaPorDefecto(dashboard) : null;
          if (ola !== null) {
            this.vista.set(ola);
            this.cargar();
            return;
          }
          this.dashboard.set(dashboard);
        },
        error: (err: unknown) => {
          if (solicitud === this.solicitud) {
            this.error.set(mensajeDeError(err));
          }
        },
      });
  }

  /** Variación relativa «+12 %» / «−8 %»; «—» si no había base o falta un tiempo. */
  variacion(antes: number | null | undefined, despues: number | null | undefined): string {
    if (antes == null || despues == null) {
      return '—';
    }
    if (antes <= 0) {
      return despues > 0 ? 'nuevo' : '—';
    }
    const relativa = (despues - antes) / antes;
    return Math.abs(relativa) < 0.0005
      ? '0 %'
      : `${relativa > 0 ? '+' : ''}${porcentaje(relativa)}`;
  }

  /**
   * Cambia entre la vista de un cluster y el resumen sin vaciar la pantalla:
   * los datos anteriores quedan atenuados hasta que llegan los nuevos. El
   * filtro de cluster no aplica dentro de la vista de un cluster.
   */
  elegirVista(clusterId: number | null): void {
    if (clusterId === this.vista()) {
      return;
    }
    this.vista.set(clusterId);
    this.filtroClusters.set([]);
    this.cargar();
  }

  /**
   * Color de la variación. En tiempo, bajar es bueno (verde) y subir es malo
   * (ámbar); en carga, mover bultos no es bueno ni malo: solo se marca el sentido.
   */
  claseVariacion(
    antes: number | null | undefined,
    despues: number | null | undefined,
    bajarEsBueno: boolean,
  ): string {
    if (antes == null || despues == null || Math.abs(despues - antes) < 0.005) {
      return '';
    }
    const baja = despues < antes;
    if (!bajarEsBueno) {
      return baja ? 'dash-delta--menos' : 'dash-delta--mas';
    }
    return baja ? 'dash-delta--baja' : 'dash-delta--sube';
  }

  irAConfiguracion(): void {
    const id = this.seleccionado();
    void this.router.navigate(['/simulacion/configuracion'], {
      queryParams: id ? { registro: id } : {},
    });
  }

  irARegistro(): void {
    void this.router.navigate(['/simulacion/registro']);
  }
}
