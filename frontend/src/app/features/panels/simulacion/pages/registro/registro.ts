import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Subject, debounceTime, finalize } from 'rxjs';
import { PERMISOS } from '../../../../../core/config/permisos';
import {
  ArchivoSimulacion,
  RegistroSimulacionActualizarRequest,
} from '../../data/simulacion.model';
import { DirectorioUsuarios } from '../../../../../core/services/directorio-usuarios';
import { Notificaciones } from '../../../../../core/services/notificaciones';
import { SimulacionApi } from '../../data/simulacion.api';
import { ListadoRemoto } from '../../../../../core/utils/listado-remoto';
import { ActorLabelPipe } from '../../../../../shared/pipes/actor-label.pipe';
import { ColumnasRedimensionables } from '../../../../../shared/directives/columnas-redimensionables.directive';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../shared/ui/modal/modal';
import { Paginador } from '../../../../../shared/ui/paginador/paginador';
import { ChipFiltro, FiltrosActivos } from '../../../../../shared/ui/panel-filtros/filtros-activos';
import { PanelFiltros } from '../../../../../shared/ui/panel-filtros/panel-filtros';
import {
  ESTADOS_REGISTRO,
  PALETA_REGISTROS,
  colorRegistro,
  datosCarga,
  etiquetaEstado,
  formatearNumero,
  nombreRegistroSugerido,
  problemaArchivoExcel,
  tamanoArchivo,
} from '../../simulacion-formato';

/** Al menos un carácter visible: un nombre solo con espacios no es válido. */
const CON_TEXTO = /\S/;

@Component({
  selector: 'app-simulacion-registro',
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
  ],
  templateUrl: './registro.html',
  styleUrl: './registro.scss',
})
export class SimulacionRegistro {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(SimulacionApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly router = inject(Router);
  private readonly directorio = inject(DirectorioUsuarios);
  private readonly inputArchivo = viewChild<ElementRef<HTMLInputElement>>('inputArchivo');

  readonly permisos = PERMISOS;
  readonly paleta = PALETA_REGISTROS;
  readonly tamanoArchivo = tamanoArchivo;
  readonly colorRegistro = colorRegistro;
  readonly formatearNumero = formatearNumero;
  readonly estado = etiquetaEstado;

  readonly busqueda = signal('');
  private readonly busqueda$ = new Subject<string>();

  /** Estado del registro: proyección, rutas balanceadas (paso 1) o balanceado (paso 2). */
  readonly estados = ESTADOS_REGISTRO;
  readonly filtroEstados = signal<string[]>([]);
  readonly filtrosAbierto = signal(false);

  readonly pagina = signal(1);
  readonly porPagina = signal(10);

  private readonly listado = new ListadoRemoto<ArchivoSimulacion>(
    () => this.api.listar(this.pagina(), this.porPagina(), this.busqueda(), this.filtroEstados()),
    (ultima) => this.pagina.set(ultima),
  );
  readonly items = this.listado.items;
  readonly total = this.listado.total;
  readonly cargando = this.listado.cargando;
  readonly cargaInicial = this.listado.cargaInicial;
  readonly refrescando = this.listado.refrescando;
  readonly error = this.listado.error;

  readonly totalPaginas = computed(() => Math.max(1, Math.ceil(this.total() / this.porPagina())));
  readonly paginaActual = computed(() => Math.min(Math.max(1, this.pagina()), this.totalPaginas()));
  readonly hayFiltros = computed(
    () => this.busqueda().trim() !== '' || this.filtroEstados().length > 0,
  );

  readonly chips = computed<ChipFiltro[]>(() =>
    this.filtroEstados().map((estado) => ({
      clave: `estado:${estado}`,
      etiqueta: 'Estado',
      valor: etiquetaEstado(estado),
      icono: 'power',
    })),
  );

  readonly filtrosActivos = computed(() => this.chips().length);

  alternarFiltros(): void {
    this.filtrosAbierto.update((abierto) => !abierto);
  }

  estaEstado(estado: string): boolean {
    return this.filtroEstados().includes(estado);
  }

  alternarEstado(estado: string): void {
    this.filtroEstados.update((estados) =>
      estados.includes(estado) ? estados.filter((item) => item !== estado) : [...estados, estado],
    );
    this.cargarDesde(1);
  }

  quitarFiltro(clave: string): void {
    const [, estado] = clave.split(':');
    this.filtroEstados.update((estados) => estados.filter((item) => item !== estado));
    this.cargarDesde(1);
  }

  limpiarFiltros(): void {
    this.filtroEstados.set([]);
    this.cargarDesde(1);
  }

  readonly archivoSeleccionado = signal<File | null>(null);
  readonly subirAbierto = signal(false);
  readonly guardando = signal(false);

  readonly formulario = this.fb.group({
    nombreRegistro: this.fb.nonNullable.control('', [
      Validators.required,
      Validators.pattern(CON_TEXTO),
      Validators.maxLength(150),
    ]),
    etiqueta: this.fb.nonNullable.control('', [Validators.maxLength(50)]),
    colorHex: this.fb.nonNullable.control(PALETA_REGISTROS[0].hex),
  });

  readonly editarAbierto = signal(false);
  readonly editando = signal<ArchivoSimulacion | null>(null);
  readonly guardandoEdicion = signal(false);

  readonly formEdicion = this.fb.group({
    nombreRegistro: this.fb.nonNullable.control('', [
      Validators.required,
      Validators.pattern(CON_TEXTO),
      Validators.maxLength(150),
    ]),
    etiqueta: this.fb.nonNullable.control('', [Validators.maxLength(50)]),
    colorHex: this.fb.nonNullable.control(PALETA_REGISTROS[0].hex),
  });

  readonly eliminarAbierto = signal(false);
  readonly aEliminar = signal<ArchivoSimulacion | null>(null);
  readonly eliminando = signal(false);

  constructor() {
    // Resuelve «Creado por» con el nombre de usuario en lugar de «Usuario #1».
    this.directorio.cargar();
    this.cargar();

    this.busqueda$.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => {
      this.cargarDesde(1);
    });
  }

  cargar(): void {
    this.listado.recargar();
  }

  actualizar(): void {
    this.cargar();
  }

  buscar(valor: string): void {
    this.busqueda.set(valor);
    this.busqueda$.next(valor);
  }

  private cargarDesde(pagina: number): void {
    this.pagina.set(pagina);
    this.cargar();
  }

  limpiarBusqueda(): void {
    this.busqueda.set('');
    this.filtroEstados.set([]);
    this.cargarDesde(1);
  }

  cambiarPagina(pagina: number): void {
    this.cargarDesde(pagina);
  }

  cambiarPorPagina(valor: number): void {
    this.porPagina.set(valor);
    this.cargarDesde(1);
  }

  campoInvalido(campo: 'nombreRegistro'): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  campoEdicionInvalido(campo: 'nombreRegistro'): boolean {
    const control = this.formEdicion.controls[campo];
    return control.touched && control.invalid;
  }

  abrirSelectorArchivo(): void {
    this.inputArchivo()?.nativeElement.click();
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

  cerrarSubida(): void {
    this.subirAbierto.set(false);
    this.archivoSeleccionado.set(null);
  }

  fijarColor(color: string): void {
    this.formulario.controls.colorHex.setValue(color);
  }

  confirmarSubida(): void {
    if (this.guardando()) {
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
    this.guardando.set(true);
    this.api
      .subir(datosCarga(archivo, this.formulario.getRawValue()))
      .pipe(finalize(() => this.guardando.set(false)))
      .subscribe({
        next: (registro) => {
          this.notificaciones.exito(
            'Registro creado',
            `${registro.rutas} rutas y ${registro.chutes} chutes cargados.`,
          );
          this.cerrarSubida();
          this.cargarDesde(1);
        },
        error: (err: unknown) => {
          // 503/504: la respuesta expiró pero el servidor pudo terminar de
          // guardar. Se refresca la lista para no duplicar el registro al reintentar.
          if (err instanceof HttpErrorResponse && (err.status === 503 || err.status === 504)) {
            this.notificaciones.info(
              'Revisa la lista antes de reintentar',
              'La carga tardó más de lo esperado y pudo haberse completado.',
            );
            // Igual que en Configuración: se cierra para no reenviar el mismo archivo.
            this.cerrarSubida();
            this.cargarDesde(1);
          }
        },
      });
  }

  abrirEditar(registro: ArchivoSimulacion): void {
    this.editando.set(registro);
    this.formEdicion.reset({
      nombreRegistro: registro.nombreRegistro,
      etiqueta: registro.etiqueta ?? '',
      colorHex: colorRegistro(registro.colorHex),
    });
    this.editarAbierto.set(true);
  }

  cerrarEdicion(): void {
    this.editarAbierto.set(false);
    this.editando.set(null);
  }

  fijarColorEdicion(color: string): void {
    this.formEdicion.controls.colorHex.setValue(color);
  }

  guardarEdicion(): void {
    const registro = this.editando();
    if (!registro || this.guardandoEdicion()) {
      return;
    }
    if (this.formEdicion.invalid) {
      this.formEdicion.markAllAsTouched();
      return;
    }
    const valores = this.formEdicion.getRawValue();
    const peticion: RegistroSimulacionActualizarRequest = {
      nombreRegistro: valores.nombreRegistro.trim(),
      etiqueta: valores.etiqueta.trim(),
      colorHex: valores.colorHex,
    };
    this.guardandoEdicion.set(true);
    this.api
      .actualizar(registro.id, peticion)
      .pipe(finalize(() => this.guardandoEdicion.set(false)))
      .subscribe({
        next: () => {
          this.notificaciones.exito('Registro actualizado', 'Los datos del registro se guardaron.');
          this.cerrarEdicion();
          this.cargar();
        },
      });
  }

  confirmarEliminar(registro: ArchivoSimulacion): void {
    this.aEliminar.set(registro);
    this.eliminarAbierto.set(true);
  }

  eliminar(): void {
    const registro = this.aEliminar();
    if (!registro || this.eliminando()) {
      return;
    }
    this.eliminando.set(true);
    this.api
      .eliminar(registro.id)
      .pipe(finalize(() => this.eliminando.set(false)))
      .subscribe({
        next: () => {
          this.notificaciones.exito('Registro eliminado', registro.nombreRegistro);
          this.eliminarAbierto.set(false);
          this.aEliminar.set(null);
          this.cargar();
        },
      });
  }

  verConfiguracion(registro: ArchivoSimulacion): void {
    void this.router.navigate(['/simulacion/configuracion'], {
      queryParams: { registro: registro.id },
    });
  }
}
