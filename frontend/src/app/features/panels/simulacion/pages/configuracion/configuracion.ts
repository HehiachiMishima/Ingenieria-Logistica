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
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, Subject, debounceTime, finalize, map, of, switchMap, tap } from 'rxjs';
import { PERMISOS } from '../../../../../core/config/permisos';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import {
  ArchivoSimulacion,
  ConfiguracionSimulacion,
  ConfiguracionSimulacionRequest,
  RutaSimulacion,
} from '../../data/simulacion.model';
import { Pagina } from '../../../../../core/models';
import { Notificaciones } from '../../../../../core/services/notificaciones';
import { SesionStore } from '../../../../../core/services/sesion';
import { SimulacionApi } from '../../data/simulacion.api';
import { ListadoRemoto } from '../../../../../core/utils/listado-remoto';
import { DirectorioUsuarios } from '../../../../../core/services/directorio-usuarios';
import { ColumnasRedimensionables } from '../../../../../shared/directives/columnas-redimensionables.directive';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../shared/ui/modal/modal';
import { Paginador } from '../../../../../shared/ui/paginador/paginador';
import { ChipFiltro, FiltrosActivos } from '../../../../../shared/ui/panel-filtros/filtros-activos';
import { PanelFiltros } from '../../../../../shared/ui/panel-filtros/panel-filtros';
import { SelectBuscable } from '../../../../../shared/ui/select-buscable/select-buscable';
import { SelectMultiple } from '../../../../../shared/ui/select-multiple/select-multiple';
import {
  LIMITES,
  PALETA_REGISTROS,
  VELOCIDAD_INICIAL,
  colorCluster,
  compararNombre,
  datosCarga,
  etiquetaEstado,
  etiquetaRegistro,
  formatearNumero,
  nombreRegistroSugerido,
  problemaArchivoExcel,
  tamanoArchivo,
} from '../../simulacion-formato';
import { SimulacionClusterCard } from './cluster-card/cluster-card';
import {
  Paso,
  normalizarEquipo,
  normalizarPorChute,
  normalizarVelocidad,
  resumirClusters,
} from './configuracion.logica';
import { SimulacionGuiaBalanceo } from './guia-balanceo/guia-balanceo';
import { SimulacionHistorialCambios } from './historial-cambios/historial-cambios';

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
    ReactiveFormsModule,
    HasPermiso,
    ColumnasRedimensionables,
    EmptyState,
    Icon,
    Modal,
    Paginador,
    SelectBuscable,
    SelectMultiple,
    PanelFiltros,
    FiltrosActivos,
    SimulacionClusterCard,
    SimulacionHistorialCambios,
    SimulacionGuiaBalanceo,
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
  readonly formatearNumero = formatearNumero;
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
    return (
      Number(cfg.velocidadBase) !== this.velocidad() ||
      Number(cfg.operariosMaximos) !== this.equipo() ||
      (cfg.operariosPorChute ?? null) !== this.porChute() ||
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

  readonly pagina = signal(1);
  readonly porPagina = signal(25);

  readonly guardando = signal(false);
  /** Paso que se está ejecutando en el cluster en curso. */
  readonly trabajando = signal<Paso | null>(null);
  readonly restableciendo = signal(false);
  /** Recarga tras un paso: hasta que llegue, las acciones siguen bloqueadas. */
  readonly recargando = signal(false);
  /** Una sola escritura a la vez: los botones de acción se deshabilitan juntos. */
  readonly ocupado = computed(
    () =>
      this.guardando() || this.trabajando() !== null || this.restableciendo() || this.recargando(),
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

  /** Rutas paginadas en el servidor: un registro puede tener decenas de miles. */
  readonly rutas = new ListadoRemoto<RutaSimulacion>(
    () => this.fuenteRutas(),
    (ultima) => this.pagina.set(ultima),
  );

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
      this.recargarRutasDesde(1);
    });
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
      this.pagina.set(1);
      this.rutas.vaciar();
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
   * Carga la carga agregada por cluster y chute y la primera página de rutas.
   * Con `inicial` muestra el estado de carga a pantalla completa; si no (tras
   * balancear), recarga en segundo plano con las acciones bloqueadas.
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
            this.rutas.recargar();
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
    this.velocidad.set(Number(config.velocidadBase) || VELOCIDAD_INICIAL);
    this.equipo.set(Math.max(1, Number(config.operariosMaximos) || 1));
    this.porChute.set(
      config.operariosPorChute === null || Number(config.operariosPorChute) <= 0
        ? null
        : Number(config.operariosPorChute),
    );
    this.limites.set(
      Object.fromEntries(
        config.clusters.map((cluster) => [cluster.id, Number(cluster.limiteHoras)]),
      ),
    );
  }

  private fuenteRutas(): Observable<Pagina<RutaSimulacion>> {
    const id = this.seleccionado();
    if (id === null) {
      return of({ contenido: [], total: 0, pagina: 1, tamano: this.porPagina(), totalPaginas: 0 });
    }
    return this.api.rutas(id, {
      pagina: this.pagina(),
      tamano: this.porPagina(),
      texto: this.filtroTexto().trim(),
      clusterIds: this.filtroCluster(),
      chuteIds: this.filtroChute(),
    });
  }

  campoInvalido(campo: 'nombreRegistro'): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  colorDeCluster(clusterId: number): string {
    const indice = (this.config()?.clusters ?? []).findIndex((cluster) => cluster.id === clusterId);
    return colorCluster(Math.max(0, indice));
  }

  setFiltroChute(ids: (number | string)[]): void {
    this.filtroChute.set(ids.map(Number));
    this.recargarRutasDesde(1);
  }

  setFiltroCluster(ids: (number | string)[]): void {
    this.filtroCluster.set(ids.map(Number));
    this.recargarRutasDesde(1);
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
      this.recargarRutasDesde(1);
    }
  }

  buscarRuta(texto: string): void {
    this.textoBusqueda.set(texto);
    this.busqueda$.next(texto);
  }

  limpiarFiltros(): void {
    this.reiniciarFiltros();
    this.recargarRutasDesde(1);
  }

  /** Vacía los filtros y descarta una búsqueda pendiente del debounce. */
  private reiniciarFiltros(): void {
    this.busqueda$.next('');
    this.textoBusqueda.set('');
    this.filtroChute.set([]);
    this.filtroCluster.set([]);
    this.filtroTexto.set('');
  }

  cambiarPagina(pagina: number): void {
    this.recargarRutasDesde(pagina);
  }

  cambiarPorPagina(valor: number): void {
    this.porPagina.set(valor);
    this.recargarRutasDesde(1);
  }

  private recargarRutasDesde(pagina: number): void {
    this.pagina.set(pagina);
    this.rutas.recargar();
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
      .pipe(finalize(() => this.guardando.set(false)))
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

  /** Paso 3: cierra el cluster y el equipo pasa al siguiente con esta dotación. */
  cerrar(cluster: { id: number; nombre: string }): void {
    this.ejecutarPaso('CERRAR', cluster);
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
    operacion.pipe(finalize(() => this.trabajando.set(null))).subscribe({
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
          this.rutas.recargar();
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
    if (!this.ocupado()) {
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
      .pipe(finalize(() => this.restableciendo.set(false)))
      .subscribe({
        next: (config) => {
          this.clusterARestablecer.set(null);
          if (this.seleccionado() === id) {
            this.aplicarConfiguracion(config);
            this.rutas.recargar();
          }
          this.notificaciones.info(
            `${cluster.nombre} restablecido`,
            'Sus rutas volvieron a su chute original y su ola quedó sin operarios.',
          );
        },
      });
  }

  /** Reabrir un cluster devuelve a la proyección a los que se trabajaron después. */
  confirmarReabrir(cluster: { id: number; nombre: string }): void {
    if (this.ocupado()) {
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
      .pipe(finalize(() => this.restableciendo.set(false)))
      .subscribe({
        next: (config) => {
          this.clusterAReabrir.set(null);
          if (this.seleccionado() === id) {
            this.aplicarConfiguracion(config);
            this.rutas.recargar();
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
    if (!this.ocupado()) {
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
      .pipe(finalize(() => this.restableciendo.set(false)))
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
