import { DestroyRef, Directive, ElementRef, afterNextRender, inject, input, output } from '@angular/core';
import { Cluster } from '../../models/santa.model';

/** Tipo de ficha: rampa o pasillo (primera letra de su clave, «R15» / «P7»). */
export type TipoFicha = 'R' | 'P';

/** Columna donde se soltaron una o varias fichas. */
export interface DestinoFichas {
  claves: readonly string[];
  cluster: Cluster | null;
}

/** Columna sobre la que se está arrastrando (null al salir o terminar). */
export interface PasoFichas {
  claves: readonly string[];
  cluster: Cluster | null;
  motivo: string | null;
}

const OK = 'destino--ok';
const NO = 'destino--no';

/**
 * Arrastrar y soltar fichas de rampas y pasillos entre clusters (mismo enfoque
 * que el arrastre de rutas de Simulación Supply Chain): se escucha una sola vez
 * en el contenedor, el destino se resalta tocando clases en el DOM y Angular
 * solo se entera al cambiar de columna (`pasando`) y al soltar (`soltar`).
 *
 * Marcado que espera:
 * - cada ficha: `[attr.data-ficha]="'R15'"` con `draggable="true"`;
 * - cada columna destino: `[attr.data-cluster]` (1, 2, 3 o vacío = sin cluster).
 */
@Directive({ selector: '[appArrastreFichas]' })
export class ArrastreFichas {
  /** Motivo por el que esas fichas no pueden ir a ese cluster, o null si pueden. */
  readonly validar = input.required<(claves: readonly string[], cluster: Cluster | null) => string | null>();
  /** Fichas que viajan al tomar una: con selección, toda la selección. */
  readonly grupo = input<(clave: string) => readonly string[]>((clave) => [clave]);
  readonly soltar = output<DestinoFichas>();
  readonly pasando = output<PasoFichas | null>();

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private claves: readonly string[] = [];
  private destino: HTMLElement | null = null;
  private motivo: string | null = null;

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const host = this.host.nativeElement;
      const eventos: [string, (e: DragEvent) => void][] = [
        ['dragstart', (e) => this.alIniciar(e)],
        ['dragover', (e) => this.alPasar(e)],
        ['drop', (e) => this.alSoltar(e)],
        ['dragend', () => this.limpiar()],
      ];
      for (const [nombre, manejador] of eventos) {
        host.addEventListener(nombre, manejador as EventListener);
      }
      destroyRef.onDestroy(() => {
        for (const [nombre, manejador] of eventos) {
          host.removeEventListener(nombre, manejador as EventListener);
        }
      });
    });
  }

  private alIniciar(e: DragEvent): void {
    const ficha = (e.target as HTMLElement | null)?.closest<HTMLElement>('[data-ficha]');
    const clave = ficha?.dataset['ficha'];
    if (!ficha || !clave || !e.dataTransfer) {
      return;
    }
    this.claves = this.grupo()(clave);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', this.claves.join(','));
  }

  private alPasar(e: DragEvent): void {
    if (this.claves.length === 0) {
      return;
    }
    const destino = (e.target as HTMLElement | null)?.closest<HTMLElement>('[data-cluster]') ?? null;
    if (destino !== this.destino) {
      this.marcar(destino);
    }
    if (destino && this.motivo === null) {
      e.preventDefault();
      if (e.dataTransfer) {
        e.dataTransfer.dropEffect = 'move';
      }
    }
  }

  private alSoltar(e: DragEvent): void {
    const destino = this.destino;
    if (!destino || this.motivo !== null || this.claves.length === 0) {
      this.limpiar();
      return;
    }
    e.preventDefault();
    this.soltar.emit({ claves: this.claves, cluster: this.clusterDe(destino) });
    this.limpiar();
  }

  private marcar(destino: HTMLElement | null): void {
    this.destino?.classList.remove(OK, NO);
    this.destino = destino;
    if (!destino) {
      this.motivo = null;
      this.pasando.emit(null);
      return;
    }
    const cluster = this.clusterDe(destino);
    this.motivo = this.validar()(this.claves, cluster);
    destino.classList.add(this.motivo === null ? OK : NO);
    this.pasando.emit({ claves: this.claves, cluster, motivo: this.motivo });
  }

  private clusterDe(destino: HTMLElement): Cluster | null {
    const valor = Number(destino.dataset['cluster']);
    return valor === 1 || valor === 2 || valor === 3 ? valor : null;
  }

  private limpiar(): void {
    this.destino?.classList.remove(OK, NO);
    if (this.destino) {
      this.pasando.emit(null);
    }
    this.destino = null;
    this.claves = [];
    this.motivo = null;
  }
}
