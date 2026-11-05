import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  LOCALE_ID,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Subject, concatMap, debounceTime, finalize, from, map } from 'rxjs';
import { PERMISOS } from '../../../../../core/config/permisos';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import { exportarCsv } from '../../../../../core/utils/csv.util';
import { valorNumero, valorTexto } from '../../../../../core/utils/evento.util';
import { diaDeInstante, duracionHhmm, hoy } from '../../../../../core/utils/fecha.util';
import { ListadoRemoto } from '../../../../../core/utils/listado-remoto';
import { OpcionesDependientes } from '../../../../../core/utils/opciones-dependientes';
import {
  Incidencia,
  IncidenciaCrearRequest,
  IncidenciaFiltros,
  Responsable,
  TipoIncidencia,
} from '../../data/bitacora.model';
import { AreasApi } from '../../data/areas.api';
import { BitacoraCatalogos } from '../../state/bitacora-catalogos';
import { DirectorioUsuarios } from '../../../../../core/services/directorio-usuarios';
import { IncidenciasApi } from '../../data/incidencias.api';
import { Notificaciones } from '../../../../../core/services/notificaciones';
import { SistemasApi } from '../../data/sistemas.api';
import { ActorLabelPipe } from '../../../../../shared/pipes/actor-label.pipe';
import { ColumnasRedimensionables } from '../../../../../shared/directives/columnas-redimensionables.directive';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../shared/ui/modal/modal';
import { Paginador } from '../../../../../shared/ui/paginador/paginador';
import {
  EstadoFiltros,
  filtroCatalogo,
  filtroFecha,
} from '../../../../../shared/ui/panel-filtros/estado-filtros';
import { FiltrosActivos } from '../../../../../shared/ui/panel-filtros/filtros-activos';
import { PanelFiltros } from '../../../../../shared/ui/panel-filtros/panel-filtros';
import { RangoFechas, RangoRapido } from '../../../../../shared/ui/panel-filtros/rango-rapido';
import { CABECERAS_EXPORT, filaExportacion, hhmm, normalizarHora } from './incidencias.logica';

type CampoFormulario =
  | 'idDataCentro'
  | 'idDataSistema'
  | 'idDataIncidencia'
  | 'ticket'
  | 'idDataArea'
  | 'idDataResponsable'
  | 'descripcion'
  | 'horaInicio'
  | 'horaFin'
  | 'fecha';

@Component({
  selector: 'app-bitacora-incidencias',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    DatePipe,
    ActorLabelPipe,
    Icon,
    Modal,
    EmptyState,
    HasPermiso,
    ColumnasRedimensionables,
    Paginador,
    PanelFiltros,
    FiltrosActivos,
    RangoRapido,
  ],
  templateUrl: './incidencias.html',
  styleUrl: './incidencias.scss',
})
export class Incidencias {
  private readonly fb = inject(FormBuilder);
  private readonly incidenciasApi = inject(IncidenciasApi);
  private readonly catalogos = inject(BitacoraCatalogos);
  private readonly sistemasApi = inject(SistemasApi);
  private readonly areasApi = inject(AreasApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly directorio = inject(DirectorioUsuarios);
  private readonly locale = inject(LOCALE_ID);

  readonly permisos = PERMISOS;

  readonly pagina = signal(1);
  readonly porPagina = signal(10);

  private readonly listado = new ListadoRemoto<Incidencia>(
    () => this.incidenciasApi.listar(this.filtros(), this.pagina(), this.porPagina()),
    (ultima) => this.pagina.set(ultima),
  );
  readonly items = this.listado.items;
  readonly total = this.listado.total;
  readonly cargando = this.listado.cargando;
  readonly cargaInicial = this.listado.cargaInicial;
  readonly refrescando = this.listado.refrescando;
  readonly error = this.listado.error;

  readonly centros = this.catalogos.centros;
  readonly sistemas = this.catalogos.sistemas;
  readonly areas = this.catalogos.areas;

  readonly filtroBusqueda = signal('');
  private readonly responsablesDelFiltro = new OpcionesDependientes(
    (areaId) => this.areasApi.responsablesDe(areaId),
    'No se pudieron cargar los responsables',
  );
  readonly responsablesFiltro = this.responsablesDelFiltro.opciones;
  readonly cargandoResponsablesFiltro = this.responsablesDelFiltro.cargando;

  readonly panelFiltros = new EstadoFiltros({
    centro: filtroCatalogo('Centro', 'centro', this.centros, (c) => c.centro),
    sistema: filtroCatalogo('Sistema', 'sistema', this.sistemas, (s) => s.sistema),
    area: filtroCatalogo('Área', 'area', this.areas, (a) => a.area),
    responsable: filtroCatalogo(
      'Responsable',
      'responsable',
      this.responsablesFiltro,
      (r) => r.responsable,
    ),
    desde: filtroFecha('Desde'),
    hasta: filtroFecha('Hasta'),
  });
  readonly filtroCentro = this.panelFiltros.valores.centro;
  readonly filtroSistema = this.panelFiltros.valores.sistema;
  readonly filtroArea = this.panelFiltros.valores.area;
  readonly filtroResponsable = this.panelFiltros.valores.responsable;
  readonly filtroDesde = this.panelFiltros.valores.desde;
  readonly filtroHasta = this.panelFiltros.valores.hasta;
  readonly filtrosAbierto = this.panelFiltros.abierto;
  /** Filtros del panel aplicados, como chips removibles. */
  readonly chips = this.panelFiltros.chips;
  readonly filtrosActivos = this.panelFiltros.activos;

  private readonly busqueda$ = new Subject<string>();

  readonly hayFiltros = computed(
    () => this.filtrosActivos() > 0 || this.filtroBusqueda().trim() !== '',
  );

  readonly totalPaginas = computed(() => Math.max(1, Math.ceil(this.total() / this.porPagina())));
  readonly paginaActual = computed(() => Math.min(Math.max(1, this.pagina()), this.totalPaginas()));

  readonly formAbierto = signal(false);
  readonly editando = signal<Incidencia | null>(null);
  readonly guardando = signal(false);
  private readonly tiposDelForm = new OpcionesDependientes<TipoIncidencia>(
    (sistemaId) => this.sistemasApi.tiposDe(sistemaId),
    'No se pudieron cargar los tipos',
  );
  private readonly responsablesDelForm = new OpcionesDependientes<Responsable>(
    (areaId) => this.areasApi.responsablesDe(areaId),
    'No se pudieron cargar los responsables',
  );
  readonly formTipos = this.tiposDelForm.opciones;
  readonly formResponsables = this.responsablesDelForm.opciones;
  readonly cargandoFormTipos = this.tiposDelForm.cargando;
  readonly cargandoFormResponsables = this.responsablesDelForm.cargando;

  private tipoPendiente: number | null = null;
  private responsablePendiente: number | null = null;

  readonly formulario = this.fb.group({
    idDataCentro: this.fb.control<number | null>(null, Validators.required),
    idDataSistema: this.fb.control<number | null>(null, Validators.required),
    idDataIncidencia: this.fb.control<number | null>(null, Validators.required),
    ticket: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(59)]),
    idDataArea: this.fb.control<number | null>(null, Validators.required),
    idDataResponsable: this.fb.control<number | null>(null, Validators.required),
    descripcion: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(255)]),
    horaInicio: this.fb.nonNullable.control('', Validators.required),
    horaFin: this.fb.nonNullable.control('', Validators.required),
    fecha: this.fb.nonNullable.control('', Validators.required),
  });

  readonly eliminarAbierto = signal(false);
  readonly aEliminar = signal<Incidencia | null>(null);
  readonly eliminando = signal(false);

  readonly verAbierto = signal(false);
  readonly aVer = signal<Incidencia | null>(null);
  readonly exportando = signal(false);

  constructor() {
    this.directorio.cargar();
    this.cargarCatalogos();
    this.cargar();

    this.busqueda$.pipe(debounceTime(300), takeUntilDestroyed()).subscribe((valor) => {
      // Se compara contra el filtro vigente (no contra la última emisión):
      // así «Limpiar filtros» + volver a escribir lo mismo sí recarga.
      if (valor.trim() === this.filtroBusqueda().trim()) {
        return;
      }
      this.filtroBusqueda.set(valor);
      this.cargarDesde(1);
    });

    const { idDataSistema, idDataIncidencia, idDataArea, idDataResponsable } =
      this.formulario.controls;
    idDataSistema.valueChanges.pipe(takeUntilDestroyed()).subscribe((sistemaId) => {
      const seleccion = this.tipoPendiente;
      this.tipoPendiente = null;
      idDataIncidencia.setValue(null, { emitEvent: false });
      this.tiposDelForm.elegir(sistemaId, () => {
        if (seleccion !== null) {
          idDataIncidencia.setValue(seleccion, { emitEvent: false });
        }
      });
    });
    idDataArea.valueChanges.pipe(takeUntilDestroyed()).subscribe((areaId) => {
      const seleccion = this.responsablePendiente;
      this.responsablePendiente = null;
      idDataResponsable.setValue(null, { emitEvent: false });
      this.responsablesDelForm.elegir(areaId, () => {
        if (seleccion !== null) {
          idDataResponsable.setValue(seleccion, { emitEvent: false });
        }
      });
    });
  }

  cargarCatalogos(): void {
    this.catalogos.cargar();
  }

  cargar(): void {
    this.listado.recargar();
  }

  private cargarDesde(pagina: number): void {
    this.pagina.set(pagina);
    this.cargar();
  }

  private filtros(): IncidenciaFiltros {
    return {
      centro: this.filtroCentro(),
      sistema: this.filtroSistema(),
      area: this.filtroArea(),
      responsable: this.filtroResponsable(),
      desde: this.filtroDesde(),
      hasta: this.filtroHasta(),
      texto: this.filtroBusqueda(),
    };
  }

  /** Formateadores de la plantilla: duración en segundos y hora → «HH:MM». */
  readonly duracionHhmm = duracionHhmm;
  readonly hhmm = hhmm;

  exportar(): void {
    if (this.exportando() || this.total() === 0) {
      return;
    }
    this.exportando.set(true);
    this.incidenciasApi
      .exportar(this.filtros())
      .pipe(
        // Nombres de quien creó o editó: se piden los que falten antes de armar el CSV.
        concatMap((lista) =>
          from(
            this.directorio.resolver(new Set(lista.flatMap((i) => [i.creadoPor, i.actualizadoPor]))),
          ).pipe(map(() => lista)),
        ),
        finalize(() => this.exportando.set(false)),
      )
      .subscribe({
        next: (lista) => {
          const filas = lista.map((incidencia) =>
            filaExportacion(incidencia, this.locale, (id) => this.directorio.etiqueta(id)),
          );
          exportarCsv(`incidencias_${hoy()}.csv`, CABECERAS_EXPORT, filas);
          this.notificaciones.exito(
            'Exportado',
            `Se exportaron ${filas.length} ${filas.length === 1 ? 'incidencia' : 'incidencias'} a Excel (CSV).`,
          );
        },
        error: (err: unknown) =>
          this.notificaciones.error('No se pudo exportar', mensajeDeError(err)),
      });
  }

  buscar(valor: string): void {
    this.busqueda$.next(valor);
  }

  alternarFiltros(): void {
    this.panelFiltros.alternar();
  }

  setFiltroCentro(evento: Event): void {
    this.filtroCentro.set(valorNumero(evento));
    this.cargarDesde(1);
  }

  setFiltroSistema(evento: Event): void {
    this.filtroSistema.set(valorNumero(evento));
    this.cargarDesde(1);
  }

  setFiltroArea(evento: Event): void {
    const areaId = valorNumero(evento);
    this.filtroArea.set(areaId);
    this.filtroResponsable.set(null);
    this.responsablesDelFiltro.elegir(areaId);
    this.cargarDesde(1);
  }

  setFiltroResponsable(evento: Event): void {
    this.filtroResponsable.set(valorNumero(evento));
    this.cargarDesde(1);
  }

  setFiltroDesde(evento: Event): void {
    this.filtroDesde.set(valorTexto(evento));
    this.cargarDesde(1);
  }

  setFiltroHasta(evento: Event): void {
    this.filtroHasta.set(valorTexto(evento));
    this.cargarDesde(1);
  }

  quitarFiltro(clave: string): void {
    this.panelFiltros.quitar(clave);
    if (clave === 'area') {
      this.filtroResponsable.set(null);
      this.responsablesDelFiltro.elegir(null);
    }
    this.cargarDesde(1);
  }

  setRango(rango: RangoFechas): void {
    this.filtroDesde.set(rango.desde);
    this.filtroHasta.set(rango.hasta);
    this.cargarDesde(1);
  }

  limpiarFiltros(): void {
    this.filtroBusqueda.set('');
    this.panelFiltros.limpiar();
    this.responsablesDelFiltro.elegir(null);
    this.cargarDesde(1);
  }

  cambiarPorPagina(valor: number): void {
    this.porPagina.set(valor);
    this.cargarDesde(1);
  }

  cambiarPagina(pagina: number): void {
    this.cargarDesde(pagina);
  }

  campoInvalido(campo: CampoFormulario): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  abrirCrear(): void {
    this.editando.set(null);
    this.tipoPendiente = null;
    this.responsablePendiente = null;
    this.formTipos.set([]);
    this.formResponsables.set([]);
    this.formulario.reset({
      idDataCentro: null,
      idDataSistema: null,
      idDataIncidencia: null,
      ticket: '',
      idDataArea: null,
      idDataResponsable: null,
      descripcion: '',
      horaInicio: '',
      horaFin: '',
      fecha: hoy(),
    });
    this.formAbierto.set(true);
  }

  abrirEditar(incidencia: Incidencia): void {
    this.editando.set(incidencia);
    this.tipoPendiente = incidencia.idDataIncidencia;
    this.responsablePendiente = incidencia.idDataResponsable;
    this.formTipos.set([]);
    this.formResponsables.set([]);
    this.formulario.reset({
      idDataCentro: incidencia.idDataCentro,
      idDataSistema: incidencia.idDataSistema,
      idDataIncidencia: incidencia.idDataIncidencia,
      ticket: incidencia.ticket,
      idDataArea: incidencia.idDataArea,
      idDataResponsable: incidencia.idDataResponsable,
      descripcion: incidencia.descripcion,
      horaInicio: hhmm(incidencia.horaInicio),
      horaFin: hhmm(incidencia.horaFin),
      fecha: diaDeInstante(incidencia.creadoEn),
    });
    this.formAbierto.set(true);
  }

  cerrarForm(): void {
    this.formAbierto.set(false);
    this.editando.set(null);
    this.tipoPendiente = null;
    this.responsablePendiente = null;
  }

  guardar(): void {
    if (this.guardando()) {
      return;
    }
    if (this.formulario.invalid) {
      this.formulario.markAllAsTouched();
      return;
    }

    const valores = this.formulario.getRawValue();
    const peticion: IncidenciaCrearRequest = {
      idDataCentro: valores.idDataCentro!,
      idDataSistema: valores.idDataSistema!,
      idDataIncidencia: valores.idDataIncidencia!,
      ticket: valores.ticket,
      idDataArea: valores.idDataArea!,
      idDataResponsable: valores.idDataResponsable!,
      descripcion: valores.descripcion,
      horaInicio: normalizarHora(valores.horaInicio),
      horaFin: normalizarHora(valores.horaFin),
      fecha: valores.fecha,
    };

    const editando = this.editando();
    this.guardando.set(true);
    const peticion$ = editando
      ? this.incidenciasApi.actualizar(editando.id, peticion)
      : this.incidenciasApi.crear(peticion);

    peticion$.pipe(finalize(() => this.guardando.set(false))).subscribe({
      next: (incidencia) => {
        this.notificaciones.exito(
          'Guardado',
          `Incidencia ${incidencia.ticket} ${editando ? 'actualizada' : 'registrada'}.`,
        );
        this.cerrarForm();
        this.cargar();
      },
      error: (err: unknown) => this.notificaciones.error('No se pudo guardar', mensajeDeError(err)),
    });
  }

  confirmarEliminar(incidencia: Incidencia): void {
    this.aEliminar.set(incidencia);
    this.eliminarAbierto.set(true);
  }

  abrirVer(incidencia: Incidencia): void {
    this.aVer.set(incidencia);
    this.verAbierto.set(true);
  }

  cerrarVer(): void {
    this.verAbierto.set(false);
    this.aVer.set(null);
  }

  eliminar(): void {
    const incidencia = this.aEliminar();
    if (!incidencia || this.eliminando()) {
      return;
    }
    this.eliminando.set(true);
    this.incidenciasApi
      .eliminar(incidencia.id)
      .pipe(finalize(() => this.eliminando.set(false)))
      .subscribe({
        next: () => {
          this.notificaciones.exito('Eliminado', `Incidencia ${incidencia.ticket} eliminada.`);
          this.eliminarAbierto.set(false);
          this.aEliminar.set(null);
          this.cargar();
        },
        error: (err: unknown) =>
          this.notificaciones.error('No se pudo eliminar', mensajeDeError(err)),
      });
  }
}
