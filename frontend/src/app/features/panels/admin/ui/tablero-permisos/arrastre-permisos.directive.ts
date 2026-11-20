import { Directive, ElementRef, OnDestroy, OnInit, inject, input, output } from '@angular/core';

/** Lado del tablero: el catálogo (lo que el rol no tiene) o la columna del rol. */
export type ZonaPermisos = 'catalogo' | 'rol';

export interface SoltarPermisos {
  ids: readonly number[];
  origen: ZonaPermisos;
  destino: ZonaPermisos;
  /** Por qué no se puede soltar ahí (null = válido). */
  motivo: string | null;
}

/**
 * Arrastrar fichas de permiso entre el catálogo y el rol, como las rutas de Titan: se escucha
 * una sola vez en el tablero, el destino se resalta tocando clases en el DOM y Angular solo se
 * entera al soltar (`soltar`). Si la ficha tomada está elegida, viaja toda la selección.
 *
 * Marcado que espera: cada ficha con `[data-permiso]="id"` y `draggable="true"`; cada lado
 * con `[data-zona]="'catalogo' | 'rol'"`.
 */
@Directive({ selector: '[appArrastrePermisos]' })
export class ArrastrePermisos implements OnInit, OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly validar = input.required<(ids: readonly number[], destino: ZonaPermisos) => string | null>();
  readonly grupo = input<(id: number) => readonly number[]>((id) => [id]);
  readonly soltar = output<SoltarPermisos>();

  private ids: readonly number[] = [];
  private origen: ZonaPermisos | null = null;
  private destino: ZonaPermisos | null = null;
  private origenes: HTMLElement[] = [];
  private marcada: HTMLElement | null = null;
  private retirar: (() => void) | null = null;

  ngOnInit(): void {
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
    this.retirar = () => {
      for (const [nombre, manejador] of eventos) {
        host.removeEventListener(nombre, manejador as EventListener);
      }
    };
  }

  ngOnDestroy(): void {
    this.retirar?.();
    this.limpiar();
  }

  private alIniciar(evento: DragEvent): void {
    const ficha = (evento.target as HTMLElement | null)?.closest<HTMLElement>('[data-permiso]');
    const id = Number(ficha?.dataset['permiso']);
    const origen = this.zonaDe(ficha ?? null);
    if (!ficha || !id || !origen) {
      return;
    }
    const grupo = this.grupo()(id);
    this.ids = grupo.includes(id) ? grupo : [id];
    this.origen = origen;
    const host = this.host.nativeElement;
    this.origenes = this.ids.flatMap((permisoId) => {
      const elemento = host.querySelector<HTMLElement>(`[data-permiso="${permisoId}"]`);
      return elemento ? [elemento] : [];
    });
    // Chrome cancela el arrastre si el origen cambia de estilo dentro de `dragstart`.
    const origenes = this.origenes;
    requestAnimationFrame(() => {
      if (this.origen !== null) {
        for (const elemento of origenes) {
          elemento.classList.add('tp-ficha--arrastrada');
        }
        host.classList.add('tp--arrastrando');
      }
    });
    if (evento.dataTransfer) {
      evento.dataTransfer.effectAllowed = 'move';
      evento.dataTransfer.setData('text/plain', this.ids.join(','));
      if (this.ids.length > 1) {
        this.fantasma(evento.dataTransfer, this.ids.length);
      }
    }
  }

  /** Pastilla con cuántos permisos viajan (estilos en línea: vive en el body, fuera del componente). */
  private fantasma(datos: DataTransfer, cantidad: number): void {
    const pastilla = document.createElement('div');
    pastilla.textContent = `${cantidad} permisos`;
    Object.assign(pastilla.style, {
      position: 'fixed',
      top: '-200px',
      left: '0',
      padding: '6px 12px',
      borderRadius: '999px',
      background: 'var(--accent)',
      color: '#fff',
      font: '600 13px/1.2 system-ui, sans-serif',
      whiteSpace: 'nowrap',
      boxShadow: '0 4px 12px rgba(0, 0, 0, 0.25)',
    });
    document.body.appendChild(pastilla);
    datos.setDragImage(pastilla, 16, 16);
    setTimeout(() => pastilla.remove());
  }

  private alPasar(evento: DragEvent): void {
    if (this.origen === null) {
      return;
    }
    const zona = (evento.target as HTMLElement | null)?.closest<HTMLElement>('[data-zona]') ?? null;
    const destino = this.zonaDe(zona);
    if (!zona || !destino) {
      return;
    }
    // Se acepta siempre para que `drop` llegue y pueda explicar un rechazo.
    evento.preventDefault();
    if (evento.dataTransfer) {
      evento.dataTransfer.dropEffect = 'move';
    }
    if (destino === this.destino) {
      return;
    }
    this.destino = destino;
    this.desmarcar();
    if (destino !== this.origen) {
      const valido = this.validar()(this.ids, destino) === null;
      zona.classList.add(valido ? 'tp-zona--destino' : 'tp-zona--prohibido');
      this.marcada = zona;
    }
  }

  private alSoltar(evento: DragEvent): void {
    const zona = (evento.target as HTMLElement | null)?.closest<HTMLElement>('[data-zona]') ?? null;
    const destino = this.zonaDe(zona);
    const origen = this.origen;
    const ids = this.ids;
    this.limpiar();
    if (!destino || !origen) {
      return;
    }
    evento.preventDefault();
    if (destino !== origen) {
      this.soltar.emit({ ids, origen, destino, motivo: this.validar()(ids, destino) });
    }
  }

  private zonaDe(elemento: HTMLElement | null): ZonaPermisos | null {
    const zona = elemento?.closest<HTMLElement>('[data-zona]')?.dataset['zona'];
    return zona === 'catalogo' || zona === 'rol' ? zona : null;
  }

  private desmarcar(): void {
    this.marcada?.classList.remove('tp-zona--destino', 'tp-zona--prohibido');
    this.marcada = null;
  }

  private limpiar(): void {
    this.desmarcar();
    for (const elemento of this.origenes) {
      elemento.classList.remove('tp-ficha--arrastrada');
    }
    this.host.nativeElement.classList.remove('tp--arrastrando');
    this.ids = [];
    this.origen = null;
    this.destino = null;
    this.origenes = [];
  }
}
