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
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize, map } from 'rxjs';
import { CONFIG } from '../../../../../core/config/configuracion';
import { PERMISOS } from '../../../../../core/config/permisos';
import { cuerpoDeError, mensajeDeError } from '../../../../../core/errors/error.util';
import { DirectorioUsuarios } from '../../../../../core/services/directorio-usuarios';
import { Notificaciones } from '../../../../../core/services/notificaciones';
import { SesionStore } from '../../../../../core/services/sesion';
import { descargarArchivo } from '../../../../../core/utils/archivo.util';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { Hora24 } from '../../../../../shared/directives/hora-24.directive';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../shared/ui/modal/modal';
import { SelectBuscable } from '../../../../../shared/ui/select-buscable/select-buscable';
import { MallaApi } from '../../data/malla.api';
import {
  CambioMalla,
  CentroMalla,
  CentroMallaDatos,
  CentroMallaResumen,
  EvaluacionMalla,
  Proceso,
  ReglaEvaluada,
} from '../../data/malla.model';
import { PROCESOS, RESPONSABLES, mallaVacia } from '../../malla-formato';
import { FormularioMalla } from './formulario-malla';

/**
 * Configuración de la malla: todos los datos de un centro en un formulario
 * (centro, capacidad, dotación, turnos, flujos, olas y zonas), con la opción
 * de importarlos de un Excel con la plantilla. Al guardar, el servidor vuelve
 * a evaluar las reglas de la malla.
 */
@Component({
  selector: 'app-malla-configuracion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    DatePipe,
    HasPermiso,
    Hora24,
    EmptyState,
    Icon,
    Modal,
    SelectBuscable,
  ],
  host: { '(window:beforeunload)': 'avisarAlCerrar($event)' },
  templateUrl: './configuracion.html',
  styleUrl: './configuracion.scss',
})
export class MallaConfiguracion {
  private readonly api = inject(MallaApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly sesion = inject(SesionStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly directorio = inject(DirectorioUsuarios);
  private readonly inputArchivo = viewChild<ElementRef<HTMLInputElement>>('inputArchivo');

  readonly permisos = PERMISOS;
  readonly procesos = PROCESOS;
  readonly responsables = RESPONSABLES;
  readonly puedeModificar = computed(() =>
    this.sesion.permisos().includes(PERMISOS.MODIFICAR_MALLA),
  );
  /** Activar reglas o cambiar umbrales cambia qué se considera correcto: permiso propio. */
  readonly puedeConfigurarReglas = computed(() =>
    this.sesion.permisos().includes(PERMISOS.CONFIGURAR_REGLAS_MALLA),
  );
  readonly nombreAccion: Record<string, string> = {
    CREAR: 'Creado',
    GUARDAR: 'Guardado',
    ELIMINAR: 'Eliminado',
  };

  readonly formulario = new FormularioMalla();
  readonly form = this.formulario.form;

  readonly centros = signal<CentroMallaResumen[]>([]);
  /** Centro que se edita; null = centro nuevo sin guardar. */
  readonly seleccionado = signal<number | null>(null);
  readonly actualizadoEn = signal<string | null>(null);
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly guardando = signal(false);
  readonly importando = signal(false);
  /** Archivo del que salieron los datos que se ven (hasta guardar). */
  readonly importado = signal<string | null>(null);
  readonly eliminarAbierto = signal(false);
  readonly proceso = signal<Proceso>('REC');

  readonly evaluacion = signal<EvaluacionMalla | null>(null);
  readonly reglaAbierta = signal<number | null>(null);
  readonly historial = signal<CambioMalla[]>([]);

  /** Cambios sin guardar (o datos importados que aún no se guardan). */
  readonly pendiente = toSignal(this.form.events.pipe(map(() => this.form.dirty)), {
    initialValue: false,
  });
  /** Se recalcula con cada cambio del formulario (los pasos se filtran por proceso). */
  private readonly cambios = toSignal(this.form.valueChanges, { initialValue: null });

  /** Inbound + Outbound + Despacho deberían dar el total declarado. */
  readonly descuadreDotacionReal = computed(() => {
    this.cambios();
    const real = this.form.controls.dotacionReal.getRawValue();
    const partes = [real.inbound, real.outbound, real.despacho];
    if (real.total === null || partes.some((valor) => valor === null)) {
      return null;
    }
    const suma = partes.reduce<number>((total, valor) => total + (valor ?? 0), 0);
    return suma === real.total ? null : { suma, total: real.total };
  });

  readonly opcionesCentro = computed(() =>
    this.centros().map((c) => ({ id: c.id, etiqueta: `${c.codigo} · ${c.nombre}` })),
  );

  /** Pasos del proceso elegido, con su posición en la lista completa. */
  readonly pasos = computed(() => {
    this.cambios();
    return this.formulario.indicesDe(this.proceso()).map((indice, posicion, todos) => ({
      indice,
      paso: this.formulario.flujos.at(indice),
      primero: posicion === 0,
      ultimo: posicion === todos.length - 1,
    }));
  });

  /** Cuántos pasos tiene cada proceso (para las pestañas). */
  readonly pasosPorProceso = computed(() => {
    this.cambios();
    return Object.fromEntries(
      PROCESOS.map((p) => [p.id, this.formulario.indicesDe(p.id).length]),
    ) as Record<Proceso, number>;
  });

  constructor() {
    this.cargarCentros(Number(this.route.snapshot.queryParamMap.get('centro')) || null);
    this.directorio.cargar();
  }

  /**
   * Al salir con cambios sin guardar se pide confirmación. Cerrar sesión o una
   * sesión vencida (destino login) nunca se bloquea.
   */
  puedeSalir(destino: string): boolean {
    if (
      !this.form.dirty ||
      this.sesion.sesion() === null ||
      destino.startsWith(CONFIG.rutas.login)
    ) {
      return true;
    }
    return window.confirm('Hay cambios sin guardar en la malla. ¿Salir sin guardarlos?');
  }

  /** Cerrar o recargar la pestaña con cambios pendientes también avisa. */
  avisarAlCerrar(evento: BeforeUnloadEvent): void {
    if (this.form.dirty) {
      evento.preventDefault();
    }
  }

  quien(id: number): string {
    return this.directorio.etiqueta(id);
  }

  private cargarCentros(preferido: number | null = null): void {
    this.api
      .centros()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (lista) => {
          this.centros.set(lista);
          const elegido =
            lista.find((c) => c.id === preferido)?.id ??
            lista.find((c) => c.id === this.seleccionado())?.id ??
            lista[0]?.id ??
            null;
          if (elegido === null) {
            this.nuevo();
          } else if (elegido !== this.seleccionado() || preferido !== null) {
            this.abrir(elegido);
          }
        },
        error: (err: unknown) => this.error.set(mensajeDeError(err)),
      });
  }

  elegir(id: number | null): void {
    if (id === null || id === this.seleccionado()) {
      return;
    }
    if (this.avisarPendiente('cambiar de centro')) {
      return;
    }
    this.abrir(id);
  }

  private abrir(id: number): void {
    this.seleccionado.set(id);
    this.cargando.set(true);
    this.error.set(null);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { centro: id },
      replaceUrl: true,
    });
    this.api
      .centro(id)
      .pipe(
        finalize(() => this.cargando.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (centro) => this.aplicar(centro),
        error: (err: unknown) => this.error.set(mensajeDeError(err)),
      });
  }

  private aplicar(centro: CentroMalla): void {
    this.formulario.cargar(centro);
    this.actualizadoEn.set(centro.actualizadoEn);
    this.importado.set(null);
    this.habilitar();
    this.cargarEvaluacion();
    this.cargarHistorial();
  }

  nuevo(): void {
    if (this.avisarPendiente('crear un centro')) {
      return;
    }
    this.seleccionado.set(null);
    this.actualizadoEn.set(null);
    this.importado.set(null);
    this.evaluacion.set(null);
    this.historial.set([]);
    this.formulario.cargar(mallaVacia());
    this.habilitar();
  }

  descartar(): void {
    const id = this.seleccionado();
    this.form.markAsPristine();
    if (id === null) {
      this.nuevo();
    } else {
      this.abrir(id);
    }
  }

  private habilitar(): void {
    if (this.puedeModificar()) {
      this.form.enable({ emitEvent: false });
    } else {
      this.form.disable({ emitEvent: false });
    }
  }

  private avisarPendiente(accion: string): boolean {
    if (this.form.dirty) {
      this.notificaciones.advertencia(
        'Cambios sin guardar',
        `Guarda o descarta los cambios antes de ${accion}.`,
      );
      return true;
    }
    return false;
  }

  guardar(): void {
    if (this.guardando()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.notificaciones.advertencia(
        'Revisa el formulario',
        'Hay campos obligatorios vacíos o con un formato no válido (marcados en rojo).',
      );
      return;
    }
    const id = this.seleccionado();
    const datos = this.formulario.datos();
    this.guardando.set(true);
    (id === null ? this.api.crear(datos) : this.api.guardar(id, datos, this.actualizadoEn()))
      .pipe(finalize(() => this.guardando.set(false)))
      .subscribe({
        next: (centro) => {
          this.notificaciones.exito('Malla guardada', `${centro.codigo} · ${centro.nombre}`);
          this.seleccionado.set(centro.id);
          this.aplicar(centro);
          this.cargarCentros();
        },
        // El interceptor ya avisó con el mensaje; aquí se marcan los campos que señaló.
        error: (err: unknown) => {
          const campos = cuerpoDeError(err)?.campos;
          if (campos) {
            this.formulario.marcarErroresServidor(campos);
          }
        },
      });
  }

  // ---------------- Excel ----------------

  abrirSelectorArchivo(): void {
    if (!this.importando() && !this.avisarPendiente('importar un Excel')) {
      this.inputArchivo()?.nativeElement.click();
    }
  }

  /** Los datos del Excel llenan el formulario; se guardan solo al pulsar «Guardar». */
  importar(evento: Event): void {
    const input = evento.target as HTMLInputElement;
    const archivo = input.files?.[0];
    input.value = '';
    if (!archivo) {
      return;
    }
    this.importando.set(true);
    this.api
      .importar(archivo)
      .pipe(finalize(() => this.importando.set(false)))
      .subscribe({
        next: (datos: CentroMallaDatos) => {
          // Si el código ya existe, se actualiza ese centro; si no, se crea uno nuevo.
          const existente = this.centros().find(
            (c) => c.codigo.toUpperCase() === datos.codigo?.trim().toUpperCase(),
          );
          this.seleccionado.set(existente?.id ?? null);
          // Versión del centro que se reemplaza: si alguien lo guarda antes, el servidor avisa.
          this.actualizadoEn.set(existente?.actualizadoEn ?? null);
          this.formulario.cargar(datos);
          this.habilitar();
          this.form.markAsDirty();
          this.form.updateValueAndValidity();
          this.importado.set(archivo.name);
          this.notificaciones.info(
            'Excel leído',
            existente
              ? `Reemplazará la malla de ${existente.codigo} al guardar. Revisa los datos.`
              : 'Es un centro nuevo: revisa los datos y guarda.',
          );
        },
      });
  }

  descargarPlantilla(): void {
    this.api.plantilla().subscribe((archivo) => descargarArchivo(archivo, 'plantilla-malla.xlsx'));
  }

  exportar(): void {
    const id = this.seleccionado();
    if (id !== null) {
      const codigo = this.centros().find((c) => c.id === id)?.codigo ?? id;
      this.api
        .exportar(id)
        .subscribe((archivo) => descargarArchivo(archivo, `malla-${codigo}.xlsx`));
    }
  }

  eliminar(): void {
    const id = this.seleccionado();
    if (id === null) {
      return;
    }
    this.api.eliminar(id).subscribe({
      next: () => {
        this.eliminarAbierto.set(false);
        this.notificaciones.info('Centro eliminado', 'Su malla ya no aparece en el panel.');
        this.form.markAsPristine();
        this.seleccionado.set(null);
        this.cargarCentros();
      },
    });
  }

  // ---------------- Validación (reglas del servidor) ----------------

  /** Reglas evaluadas sobre la malla guardada del centro elegido. */
  cargarEvaluacion(): void {
    const id = this.seleccionado();
    if (id === null) {
      this.evaluacion.set(null);
      return;
    }
    this.api
      .evaluacion(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (evaluacion) => {
          if (id === this.seleccionado()) {
            this.evaluacion.set(evaluacion);
          }
        },
        error: () => {},
      });
  }

  private cargarHistorial(): void {
    const id = this.seleccionado();
    if (id === null) {
      return;
    }
    this.api
      .cambios(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (cambios) => {
          if (id === this.seleccionado()) {
            this.historial.set(cambios);
          }
        },
        error: () => this.historial.set([]),
      });
  }

  alternarRegla(regla: ReglaEvaluada): void {
    this.reglaAbierta.update((abierta) => (abierta === regla.id ? null : regla.id));
  }

  activarRegla(regla: ReglaEvaluada, activa: boolean): void {
    this.api.actualizarRegla(regla.id, { activa }).subscribe(() => this.cargarEvaluacion());
  }

  cambiarParametro(regla: ReglaEvaluada, clave: string, valor: string): void {
    this.api
      .actualizarRegla(regla.id, { parametros: { [clave]: valor } })
      .subscribe({ next: () => this.cargarEvaluacion(), error: () => this.cargarEvaluacion() });
  }

  irAMalla(): void {
    const id = this.seleccionado();
    void this.router.navigate(['/malla/operativa'], {
      queryParams: id ? { centro: id } : {},
    });
  }
}
