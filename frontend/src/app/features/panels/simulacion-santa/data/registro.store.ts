import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, debounceTime, finalize } from 'rxjs';
import { Notificaciones } from '../../../../core/services/notificaciones';
import { ListadoRemoto } from '../../../../core/utils/listado-remoto';
import { mensajeDeError } from '../../../../core/errors/error.util';
import { EstadoRegistroSanta, RegistroSanta, ValidacionExcel } from '../models/santa.model';
import { SantaApi } from './santa.api';
import { entero, nombreSugerido, problemaArchivo } from '../santa-formato';

/** Fase de la carga de un Excel: se revisa primero y se guarda después. */
export type FaseCarga = 'cerrada' | 'revisando' | 'revisado' | 'fallo' | 'guardando';

/**
 * Estado y reglas del módulo Registro: listado con filtros, carga en dos pasos
 * (revisión sin guardar → guardado de las filas válidas), renombrar y eliminar.
 * Se provee en la página: vive y muere con ella.
 */
@Injectable()
export class RegistroSantaStore {
  private readonly api = inject(SantaApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly destroyRef = inject(DestroyRef);

  /* ---------- listado ---------- */
  readonly texto = signal('');
  readonly estados = signal<EstadoRegistroSanta[]>([]);
  readonly pagina = signal(1);
  readonly porPagina = signal(10);
  private readonly texto$ = new Subject<void>();

  private readonly listado = new ListadoRemoto<RegistroSanta>(
    () => this.api.listar(this.pagina(), this.porPagina(), this.texto(), this.estados()),
    (ultima) => this.pagina.set(ultima),
  );
  readonly items = this.listado.items;
  readonly total = this.listado.total;
  readonly cargando = this.listado.cargando;
  readonly cargaInicial = this.listado.cargaInicial;
  readonly refrescando = this.listado.refrescando;
  readonly error = this.listado.error;
  readonly hayFiltros = computed(() => this.texto().trim() !== '' || this.estados().length > 0);

  constructor() {
    this.texto$
      .pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.irAPagina(1));
    this.listado.recargar();
  }

  recargar(): void {
    this.listado.recargar();
  }

  buscar(valor: string): void {
    this.texto.set(valor);
    this.texto$.next();
  }

  alternarEstado(estado: EstadoRegistroSanta): void {
    this.estados.update((lista) =>
      lista.includes(estado) ? lista.filter((e) => e !== estado) : [...lista, estado],
    );
    this.irAPagina(1);
  }

  limpiarFiltros(): void {
    this.texto.set('');
    this.estados.set([]);
    this.irAPagina(1);
  }

  irAPagina(pagina: number): void {
    this.pagina.set(pagina);
    this.listado.recargar();
  }

  cambiarPorPagina(valor: number): void {
    this.porPagina.set(valor);
    this.irAPagina(1);
  }

  /* ---------- carga de un Excel ---------- */
  readonly fase = signal<FaseCarga>('cerrada');
  readonly archivo = signal<File | null>(null);
  readonly revision = signal<ValidacionExcel | null>(null);
  readonly falloRevision = signal<string | null>(null);
  readonly nombre = signal('');
  readonly nombreValido = computed(() => {
    const limpio = this.nombre().trim();
    return limpio.length > 0 && limpio.length <= 150;
  });
  readonly puedeGuardar = computed(
    () =>
      this.fase() === 'revisado' &&
      this.nombreValido() &&
      (this.revision()?.filasValidas ?? 0) > 0,
  );

  /** Revisa el archivo elegido sin guardarlo. */
  elegirArchivo(archivo: File): void {
    const problema = problemaArchivo(archivo);
    if (problema) {
      this.notificaciones.advertencia(problema.titulo, problema.mensaje);
      return;
    }
    this.archivo.set(archivo);
    this.nombre.set(nombreSugerido(archivo));
    this.revision.set(null);
    this.falloRevision.set(null);
    this.fase.set('revisando');
    this.api
      .validar(archivo)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (revision) => {
          if (this.archivo() !== archivo) {
            return;
          }
          this.revision.set(revision);
          this.fase.set('revisado');
        },
        error: (err: unknown) => {
          if (this.archivo() !== archivo) {
            return;
          }
          this.falloRevision.set(mensajeDeError(err));
          this.fase.set('fallo');
        },
      });
  }

  cerrarCarga(): void {
    this.fase.set('cerrada');
    this.archivo.set(null);
    this.revision.set(null);
    this.falloRevision.set(null);
  }

  guardarCarga(): void {
    const archivo = this.archivo();
    if (!archivo || !this.puedeGuardar()) {
      return;
    }
    this.fase.set('guardando');
    this.api
      .subir(archivo, this.nombre())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (registro) => {
          const omitidas = registro.filasOmitidas
            ? ` · ${entero(registro.filasOmitidas)} filas omitidas`
            : '';
          this.notificaciones.exito(
            'Registro creado',
            `${entero(registro.paletas)} paletas de ${entero(registro.rutas)} rutas${omitidas}.`,
          );
          this.cerrarCarga();
          this.irAPagina(1);
        },
        error: (err: unknown) => {
          // 503/504: el servidor pudo terminar de guardar; se refresca antes de reintentar.
          if (err instanceof HttpErrorResponse && (err.status === 503 || err.status === 504)) {
            this.notificaciones.info(
              'Revisa la lista antes de reintentar',
              'La carga tardó más de lo esperado y pudo haberse completado.',
            );
            this.cerrarCarga();
            this.irAPagina(1);
            return;
          }
          this.fase.set('revisado');
        },
      });
  }

  /* ---------- renombrar y eliminar ---------- */
  readonly guardandoNombre = signal(false);
  readonly eliminando = signal(false);

  renombrar(registro: RegistroSanta, nombre: string, alTerminar: () => void): void {
    if (this.guardandoNombre()) {
      return;
    }
    this.guardandoNombre.set(true);
    this.api
      .renombrar(registro.id, nombre)
      .pipe(
        finalize(() => this.guardandoNombre.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.notificaciones.exito('Registro actualizado', 'El nombre se guardó.');
          alTerminar();
          this.recargar();
        },
      });
  }

  eliminar(registro: RegistroSanta, alTerminar: () => void): void {
    if (this.eliminando()) {
      return;
    }
    this.eliminando.set(true);
    this.api
      .eliminar(registro.id)
      .pipe(
        finalize(() => this.eliminando.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.notificaciones.exito('Registro eliminado', registro.nombre);
          alTerminar();
          this.recargar();
        },
      });
  }
}
