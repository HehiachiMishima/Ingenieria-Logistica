import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { finalize } from 'rxjs';
import { mensajeDeError } from '../../../../../../core/errors/error.util';
import { Pagina } from '../../../../../../core/models';
import { ActorLabelPipe } from '../../../../../../shared/pipes/actor-label.pipe';
import { EmptyState } from '../../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { Paginador } from '../../../../../../shared/ui/paginador/paginador';
import { SimulacionApi } from '../../../data/simulacion.api';
import { HistorialRegistroSimulacion, HistorialSimulacion } from '../../../data/simulacion.model';
import { formatearNumero } from '../../../simulacion-formato';

/** Un lanzamiento (o los cambios sin lanzamiento) tal como se lista. */
interface GrupoHistorial {
  clave: string;
  /** null = cambios sin lanzamiento (a mano o de versiones anteriores). */
  lanzamientoId: number | null;
  titulo: string;
  mensaje: string | null;
  fecha: string | null;
  creadoPor: number | null;
  rutas: number;
  separaciones: number;
  chutesOperarios: number;
}

/**
 * Modal con los cambios del registro: cada lanzamiento con sus totales, del
 * más reciente al más antiguo. Los cambios de uno se piden paginados al
 * abrirlo: un balanceo grande mueve miles de rutas.
 */
@Component({
  selector: 'app-simulacion-historial-cambios',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, ActorLabelPipe, EmptyState, Icon, Modal, Paginador],
  templateUrl: './historial-cambios.html',
  styleUrl: './historial-cambios.scss',
})
export class SimulacionHistorialCambios {
  private readonly api = inject(SimulacionApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly registroId = input<number | null>(null);
  readonly abierto = input(false);
  readonly cerrar = output<void>();

  readonly formatearNumero = formatearNumero;

  private readonly historial = signal<HistorialRegistroSimulacion | null>(null);
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);

  /** Grupo abierto y la página de sus cambios. */
  readonly abiertoGrupo = signal<string | null>(null);
  readonly cambios = signal<Pagina<HistorialSimulacion> | null>(null);
  readonly cargandoCambios = signal(false);
  readonly pagina = signal(1);
  readonly porPagina = signal(25);
  /** Descarta respuestas viejas si se cambia de grupo o de página rápido. */
  private solicitud = 0;

  readonly grupos = computed<GrupoHistorial[]>(() => {
    const historial = this.historial();
    if (!historial) {
      return [];
    }
    const grupos: GrupoHistorial[] = historial.lanzamientos.map((lanzamiento) => ({
      clave: `l${lanzamiento.id}`,
      lanzamientoId: lanzamiento.id,
      titulo: `${lanzamiento.cluster} · ${
        lanzamiento.fase === 'RUTAS' ? 'Balanceo de rutas' : 'Asignación de operarios'
      }`,
      mensaje: lanzamiento.mensaje,
      fecha: lanzamiento.creadoEn,
      creadoPor: lanzamiento.creadoPor,
      rutas: lanzamiento.rutas,
      separaciones: lanzamiento.separaciones,
      chutesOperarios: lanzamiento.chutesOperarios,
    }));
    if (historial.sinLanzamiento > 0) {
      grupos.push({
        clave: 'sin',
        lanzamientoId: null,
        titulo: 'Movimientos sin lanzamiento',
        mensaje: 'Hechos a mano o con una versión anterior del balanceo.',
        fecha: null,
        creadoPor: null,
        rutas: historial.sinLanzamiento,
        separaciones: 0,
        chutesOperarios: 0,
      });
    }
    return grupos;
  });

  constructor() {
    // Al abrir el modal (o cambiar de registro con él abierto) se recarga la lista.
    effect(() => {
      const id = this.registroId();
      if (this.abierto() && id !== null) {
        untracked(() => this.cargar(id));
      }
    });
  }

  private cargar(id: number): void {
    this.historial.set(null);
    this.abiertoGrupo.set(null);
    this.cambios.set(null);
    this.error.set(null);
    this.cargando.set(true);
    this.api
      .historial(id)
      .pipe(
        finalize(() => this.cargando.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (historial) => this.historial.set(historial),
        error: (err: unknown) => this.error.set(mensajeDeError(err)),
      });
  }

  alternar(grupo: GrupoHistorial): void {
    if (this.abiertoGrupo() === grupo.clave) {
      this.abiertoGrupo.set(null);
      return;
    }
    this.abiertoGrupo.set(grupo.clave);
    this.cambios.set(null);
    this.pagina.set(1);
    this.cargarCambios(grupo);
  }

  cambiarPagina(grupo: GrupoHistorial, pagina: number): void {
    this.pagina.set(pagina);
    this.cargarCambios(grupo);
  }

  cambiarPorPagina(grupo: GrupoHistorial, porPagina: number): void {
    this.porPagina.set(porPagina);
    this.pagina.set(1);
    this.cargarCambios(grupo);
  }

  private cargarCambios(grupo: GrupoHistorial): void {
    const id = this.registroId();
    if (id === null) {
      return;
    }
    const solicitud = ++this.solicitud;
    this.cargandoCambios.set(true);
    this.api
      .historialCambios(id, grupo.lanzamientoId, this.pagina(), this.porPagina())
      .pipe(
        finalize(() => {
          if (solicitud === this.solicitud) {
            this.cargandoCambios.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (pagina) => {
          if (solicitud === this.solicitud) {
            this.cambios.set(pagina);
          }
        },
      });
  }
}
