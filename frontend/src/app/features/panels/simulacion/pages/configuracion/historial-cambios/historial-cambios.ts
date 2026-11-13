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
import {
  Balanceo,
  ETIQUETA_BALANCEO,
  HistorialRegistroSimulacion,
  HistorialSimulacion,
} from '../../../data/simulacion.model';
import { NumeroPipe } from '../../../numero.pipe';

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
  imports: [DatePipe, ActorLabelPipe, EmptyState, Icon, Modal, Paginador, NumeroPipe],
  templateUrl: './historial-cambios.html',
  styleUrl: './historial-cambios.scss',
})
export class SimulacionHistorialCambios {
  private readonly api = inject(SimulacionApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly registroId = input<number | null>(null);
  readonly abierto = input(false);
  /**
   * Pestaña con que se abre el modal (Diarias, L-X-V o M-J-S): la que mira
   * Configuración. Se toma cada vez que el modal se abre.
   */
  readonly balanceoInicial = input<Balanceo>('LXV');
  readonly cerrar = output<void>();

  private readonly historial = signal<HistorialRegistroSimulacion | null>(null);
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);

  /** Grupo abierto y la página de sus cambios. */
  readonly abiertoGrupo = signal<string | null>(null);
  readonly cambios = signal<Pagina<HistorialSimulacion> | null>(null);
  readonly cargandoCambios = signal(false);
  readonly pagina = signal(1);
  /**
   * Historial que se mira: Diarias solo lo suyo; cada día, lo suyo y además los
   * lanzamientos y cambios de Diarias (el backend antepone «Diarias · »).
   */
  readonly balanceo = signal<Balanceo>('LXV');
  readonly balanceos: readonly Balanceo[] = ['DIA', 'LXV', 'MJS'];
  readonly etiquetaBalanceo = ETIQUETA_BALANCEO;
  readonly porPagina = signal(25);
  /** Descarta respuestas viejas si se cambia de grupo o de página rápido. */
  private solicitud = 0;
  /** Descarta historiales viejos si se cambia de pestaña (o de registro) rápido. */
  private solicitudHistorial = 0;
  /** El modal ya estaba abierto en la corrida anterior del efecto. */
  private estabaAbierto = false;

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
        chutesOperarios: 0,
      });
    }
    return grupos;
  });

  constructor() {
    // Al abrir el modal (o cambiar de registro con él abierto) se recarga la lista.
    // Al abrirlo, además, parte de la pestaña que mira Configuración.
    effect(() => {
      const id = this.registroId();
      const abierto = this.abierto();
      const recienAbierto = abierto && !this.estabaAbierto;
      this.estabaAbierto = abierto;
      if (!abierto) {
        // Cerrado: cualquier respuesta en vuelo ya no se pinta.
        this.solicitudHistorial++;
        this.solicitud++;
        return;
      }
      if (id !== null) {
        untracked(() => {
          if (recienAbierto) {
            this.balanceo.set(this.balanceoInicial());
          }
          this.cargar(id);
        });
      }
    });
  }

  private cargar(id: number): void {
    const solicitud = ++this.solicitudHistorial;
    // Los cambios abiertos eran de otra pestaña: su respuesta en vuelo se descarta.
    this.solicitud++;
    this.cargandoCambios.set(false);
    this.historial.set(null);
    this.abiertoGrupo.set(null);
    this.cambios.set(null);
    this.error.set(null);
    this.cargando.set(true);
    this.api
      .historial(id, this.balanceo())
      .pipe(
        // Solo la última solicitud apaga el spinner: una vieja que termina antes no lo corta.
        finalize(() => {
          if (solicitud === this.solicitudHistorial) {
            this.cargando.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (historial) => {
          if (solicitud === this.solicitudHistorial) {
            this.historial.set(historial);
          }
        },
        error: (err: unknown) => {
          if (solicitud === this.solicitudHistorial) {
            this.error.set(mensajeDeError(err));
          }
        },
      });
  }

  elegirBalanceo(balanceo: Balanceo): void {
    const id = this.registroId();
    if (balanceo === this.balanceo() || id === null) {
      return;
    }
    this.balanceo.set(balanceo);
    this.cargar(id);
  }

  /** Vuelve a pedir el historial de la pestaña actual. */
  reintentar(): void {
    const id = this.registroId();
    if (id !== null) {
      this.cargar(id);
    }
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
      .historialCambios(id, grupo.lanzamientoId, this.pagina(), this.porPagina(), this.balanceo())
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
