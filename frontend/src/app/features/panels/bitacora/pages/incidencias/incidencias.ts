import { formatDate } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Subject, concatMap, debounceTime, finalize, from, map, merge } from 'rxjs';
import { CONFIG } from '../../../../../core/config/configuracion';
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
import { PieCampo, textoRequerido } from '../../ui/pie-campo';
import { EntradaCompleta } from '../../ui/entrada-completa';
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
import {
  ETIQUETA_ESTADO,
  antiguedad,
  edad,
  estadoDeEvento,
  filtroEstado,
  relojPorMinuto,
  segundosDesde,
} from '../../data/estado-incidencia';
import {
  CABECERAS_EXPORT,
  fechaCorta,
  fechaFin,
  fechaInicio,
  filaExportacion,
  finCoherente,
  hhmm,
  normalizarHora,
  segundosEntre,
  sugerirFechaFin,
} from './incidencias.logica';

/** Largos de la base: ticket VARCHAR(59), descripción VARCHAR(255). */
const MAX_TICKET = 59;
const MAX_DESCRIPCION = 255;

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
  | 'fecha'
  | 'fechaFin';

@Component({
  selector: 'app-bitacora-incidencias',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
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
    PieCampo,
    EntradaCompleta,
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
  private readonly ruta = inject(ActivatedRoute);

  readonly permisos = PERMISOS;
  readonly maxTicket = MAX_TICKET;
  readonly maxDescripcion = MAX_DESCRIPCION;

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
    estado: filtroEstado(),
  });
  readonly filtroCentro = this.panelFiltros.valores.centro;
  readonly filtroSistema = this.panelFiltros.valores.sistema;
  readonly filtroArea = this.panelFiltros.valores.area;
  readonly filtroResponsable = this.panelFiltros.valores.responsable;
  readonly filtroDesde = this.panelFiltros.valores.desde;
  readonly filtroHasta = this.panelFiltros.valores.hasta;
  readonly filtroEstado = this.panelFiltros.valores.estado;
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

  readonly formulario = this.fb.group(
    {
      idDataCentro: this.fb.control<number | null>(null, Validators.required),
      idDataSistema: this.fb.control<number | null>(null, Validators.required),
      idDataIncidencia: this.fb.control<number | null>(null, Validators.required),
      ticket: this.fb.nonNullable.control('', textoRequerido(MAX_TICKET)),
      idDataArea: this.fb.control<number | null>(null, Validators.required),
      idDataResponsable: this.fb.control<number | null>(null, Validators.required),
      descripcion: this.fb.nonNullable.control('', textoRequerido(MAX_DESCRIPCION)),
      horaInicio: this.fb.nonNullable.control('', Validators.required),
      fecha: this.fb.nonNullable.control('', Validators.required),
      // Fin opcional (vacío = abierta); el grupo exige fecha y hora juntas y fin >= inicio.
      horaFin: this.fb.nonNullable.control(''),
      fechaFin: this.fb.nonNullable.control(''),
    },
    { validators: finCoherente },
  );

  readonly eliminarAbierto = signal(false);
  readonly aEliminar = signal<Incidencia | null>(null);
  readonly eliminando = signal(false);

  readonly verAbierto = signal(false);
  readonly aVer = signal<Incidencia | null>(null);
  readonly exportando = signal(false);

  /** Cierre rápido de una abierta: fecha y hora de fin (por defecto, ahora). */
  readonly cierreAbierto = signal(false);
  readonly aCerrar = signal<Incidencia | null>(null);
  readonly cerrando = signal(false);
  readonly cierreFecha = signal('');
  readonly cierreHora = signal('');

  /** Avanza cada minuto: la antigüedad de las abiertas se actualiza sola. */
  readonly ahora = relojPorMinuto();
  readonly etiquetaEstado = ETIQUETA_ESTADO;
  readonly fechaCorta = fechaCorta;
  readonly fechaInicio = fechaInicio;
  readonly fechaFin = fechaFin;

  constructor() {
    this.directorio.cargar();
    this.cargarCatalogos();
    // «Abiertas ahora» del dashboard abre el listado con ?estado=ABIERTA.
    const estado = this.ruta.snapshot.queryParamMap.get('estado');
    if (estado === 'ABIERTA' || estado === 'CERRADA') {
      this.filtroEstado.set(estado);
    }
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

    const { idDataSistema, idDataIncidencia, idDataArea, idDataResponsable, fecha, horaInicio, horaFin, fechaFin } =
      this.formulario.controls;
    // Fecha de fin sugerida (la del inicio, o el día siguiente si la hora de fin
    // es menor): se recalcula con CADA cambio de fecha u horas mientras la
    // persona no la elija a mano. Antes solo se sugería una vez y, si luego se
    // corregía el inicio, el fin quedaba en otro día (duraciones de días).
    merge(fecha.valueChanges, horaInicio.valueChanges, horaFin.valueChanges)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.sugerirFin());
    // Solo los cambios de la persona emiten aquí (la sugerencia usa emitEvent: false).
    fechaFin.valueChanges.pipe(takeUntilDestroyed()).subscribe((dia) => {
      this.fechaFinManual = !!dia;
      if (!dia) {
        this.sugerirFin();
      }
    });
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
      estado: this.filtroEstado(),
    };
  }

  setFiltroEstado(evento: Event): void {
    this.filtroEstado.set(estadoDeEvento(evento));
    this.cargarDesde(1);
  }

  /** Segundos que lleva abierta (avanza con el reloj por minuto). */
  segundosAbierta(incidencia: Incidencia): number {
    return segundosDesde(incidencia.creadoEn, this.ahora());
  }

  edadAbierta(incidencia: Incidencia): string {
    return edad(this.segundosAbierta(incidencia));
  }

  nivelAbierta(incidencia: Incidencia): string {
    return antiguedad(this.segundosAbierta(incidencia));
  }

  /** Mensaje del bloque de fin (fecha y hora juntas, fin no anterior al inicio). */
  /** true cuando la persona eligió la fecha de fin: desde ahí ya no se recalcula sola. */
  private fechaFinManual = false;

  /** Recalcula la fecha de fin sugerida (vacía si no hay hora de fin), salvo que sea manual. */
  private sugerirFin(): void {
    if (this.fechaFinManual) {
      return;
    }
    const v = this.formulario.getRawValue();
    const sugerida = v.horaFin ? sugerirFechaFin(v.fecha, v.horaInicio, v.horaFin) : '';
    if (sugerida !== v.fechaFin) {
      this.formulario.controls.fechaFin.setValue(sugerida, { emitEvent: false });
    }
  }

  /** Mensaje de fecha u hora de inicio: incompleta (a medio escribir) u obligatoria. */
  errorInicio(campo: 'fecha' | 'horaInicio'): string | null {
    const control = this.formulario.controls[campo];
    if (control.hasError('incompleta')) {
      return campo === 'fecha'
        ? 'La fecha de inicio está incompleta: completa día, mes y año.'
        : 'La hora de inicio está incompleta: completa hora, minutos y a. m./p. m.';
    }
    if (!control.touched || control.valid) {
      return null;
    }
    return campo === 'fecha' ? 'La fecha de inicio es obligatoria.' : 'La hora de inicio es obligatoria.';
  }

  errorFin(): string | null {
    const f = this.formulario;
    // A medio escribir: el navegador deja el valor vacío y parecería «abierta».
    if (f.controls.horaFin.hasError('incompleta')) {
      return 'La hora de fin está incompleta: completa hora, minutos y a. m./p. m.';
    }
    if (f.controls.fechaFin.hasError('incompleta')) {
      return 'La fecha de fin está incompleta: completa día, mes y año.';
    }
    const { horaFin, fechaFin } = f.controls;
    const tocado = horaFin.touched || fechaFin.touched || horaFin.dirty || fechaFin.dirty;
    if (!tocado) {
      return null;
    }
    if (f.hasError('finIncompleto')) {
      return 'Indica fecha y hora de fin, o deja ambas vacías para dejarla abierta.';
    }
    return f.hasError('finAnterior') ? 'El fin no puede ser anterior al inicio.' : null;
  }

  /** Resumen en vivo bajo las fechas: duración si está cerrada o aviso de abierta. */
  resumenFin(): { abierta: boolean; texto: string } | null {
    const v = this.formulario.getRawValue();
    const c = this.formulario.controls;
    if (c.horaFin.hasError('incompleta') || c.fechaFin.hasError('incompleta')) {
      return null;
    }
    if (!v.horaFin && !v.fechaFin) {
      return { abierta: true, texto: 'Sin fin: se registrará como abierta.' };
    }
    const segundos = segundosEntre(v.fecha, v.horaInicio, v.fechaFin, v.horaFin);
    return segundos === null || segundos < 0
      ? null
      : { abierta: false, texto: `Duración: ${edad(segundos)} · se registrará como cerrada.` };
  }

  abrirCierre(incidencia: Incidencia): void {
    this.aCerrar.set(incidencia);
    this.cierreFecha.set(hoy());
    this.cierreHora.set(formatDate(Date.now(), 'HH:mm', 'en-US', CONFIG.zonaHoraria));
    this.cierreAbierto.set(true);
  }

  confirmarCierre(): void {
    const incidencia = this.aCerrar();
    if (!incidencia || this.cerrando()) {
      return;
    }
    if (!this.cierreFecha() || !this.cierreHora()) {
      this.notificaciones.error('Falta el fin', 'Indica la fecha y la hora de fin.');
      return;
    }
    this.cerrando.set(true);
    this.incidenciasApi
      .cerrar(incidencia.id, { fechaFin: this.cierreFecha(), horaFin: normalizarHora(this.cierreHora()) })
      .pipe(finalize(() => this.cerrando.set(false)))
      .subscribe({
        next: (cerrada) => {
          this.notificaciones.exito('Cerrada', `Incidencia ${cerrada.ticket} cerrada.`);
          this.cierreAbierto.set(false);
          this.aCerrar.set(null);
          this.cargar();
        },
        error: (err: unknown) => this.notificaciones.error('No se pudo cerrar', mensajeDeError(err)),
      });
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
            this.directorio.resolver(new Set(lista.map((i) => i.creadoPor))),
          ).pipe(map(() => lista)),
        ),
        finalize(() => this.exportando.set(false)),
      )
      .subscribe({
        next: (lista) => {
          const filas = lista.map((incidencia) =>
            filaExportacion(incidencia, (id) => this.directorio.etiqueta(id)),
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
    this.fechaFinManual = false;
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
      fechaFin: '',
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
      horaFin: incidencia.horaFin ? hhmm(incidencia.horaFin) : '',
      fecha: diaDeInstante(incidencia.creadoEn),
      fechaFin: fechaFin(incidencia) ?? '',
    });
    // Un fin guardado en otro día que el sugerido es elección de la persona: se respeta.
    const v = this.formulario.getRawValue();
    this.fechaFinManual = !!v.fechaFin && v.fechaFin !== sugerirFechaFin(v.fecha, v.horaInicio, v.horaFin);
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
      ticket: valores.ticket.trim(),
      idDataArea: valores.idDataArea!,
      idDataResponsable: valores.idDataResponsable!,
      descripcion: valores.descripcion.trim(),
      horaInicio: normalizarHora(valores.horaInicio),
      horaFin: valores.horaFin ? normalizarHora(valores.horaFin) : null,
      fecha: valores.fecha,
      fechaFin: valores.fechaFin || null,
    };

    const editando = this.editando();
    this.guardando.set(true);
    const peticion$ = editando
      ? this.incidenciasApi.actualizar(editando.id, { ...peticion, version: editando.version })
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

  /** Color de la ficha del detalle: antigüedad si sigue abierta (reciente/media/antigua), si no «cerrada». */
  nivelFicha(incidencia: Incidencia): string {
    return incidencia.estado === 'ABIERTA' ? this.nivelAbierta(incidencia) : 'cerrada';
  }

  /** Tiempo en la ficha: cuánto lleva abierta (avanza con el reloj por minuto) o cuánto duró. */
  tiempoFicha(incidencia: Incidencia): string {
    if (incidencia.estado === 'ABIERTA') {
      return `Abierta hace ${this.edadAbierta(incidencia)}`;
    }
    return incidencia.tiempoSolucionSeg == null ? 'Cerrada' : `Duró ${edad(incidencia.tiempoSolucionSeg)}`;
  }

  /* Acciones del detalle: cierran el detalle y abren el mismo modal que la fila de la tabla. */
  verAEditar(incidencia: Incidencia): void {
    this.cerrarVer();
    this.abrirEditar(incidencia);
  }

  verACerrar(incidencia: Incidencia): void {
    this.cerrarVer();
    this.abrirCierre(incidencia);
  }

  verAEliminar(incidencia: Incidencia): void {
    this.cerrarVer();
    this.confirmarEliminar(incidencia);
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
