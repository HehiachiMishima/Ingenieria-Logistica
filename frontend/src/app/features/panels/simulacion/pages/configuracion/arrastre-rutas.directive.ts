import { Directive, ElementRef, OnDestroy, OnInit, inject, input, output } from '@angular/core';

/** Celda de la matriz donde se soltaron una o varias rutas. */
export interface DestinoRuta {
  /** La ruta que se tomó con el mouse. */
  rutaId: number;
  /** Todas las que se mueven: la tomada y, si estaba seleccionada, toda la selección. */
  rutaIds: readonly number[];
  clusterId: number;
  chuteId: number;
  /** Por qué no se puede mover ahí (null = movimiento válido). */
  motivo: string | null;
}

/**
 * Arrastrar y soltar rutas en la matriz (como el ⋮⋮ de la planilla Excel).
 *
 * Con miles de celdas, enlazar `dragover` en cada una dispararía la detección
 * de cambios decenas de veces por segundo. Aquí se escucha una sola vez en el
 * contenedor, el destino se resalta tocando clases en el DOM y Angular solo
 * se entera al soltar (`soltar`).
 *
 * Marcado que espera:
 * - el asa: `[data-arrastre-ruta]="ruta.id"` con `draggable="true"`;
 * - cada celda: `[data-cluster]` y `[data-chute]`.
 */
@Directive({ selector: '[appArrastreRutas]' })
export class ArrastreRutas implements OnInit, OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Motivo por el que esas rutas no pueden ir a esa celda, o null si pueden. */
  readonly validar = input.required<(rutaIds: readonly number[], clusterId: number, chuteId: number) => string | null>();
  /** Rutas que viajan al tomar una: por defecto solo ella; con selección, toda la selección. */
  readonly grupo = input<(rutaId: number) => readonly number[]>((rutaId) => [rutaId]);
  readonly soltar = output<DestinoRuta>();

  private rutaId: number | null = null;
  private rutaIds: readonly number[] = [];
  private origenes: HTMLElement[] = [];
  private clave: string | null = null;
  private motivo: string | null = null;
  private marcadas: HTMLElement[] = [];
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
    const asa = (evento.target as HTMLElement | null)?.closest<HTMLElement>('[data-arrastre-ruta]');
    const id = Number(asa?.dataset['arrastreRuta']);
    if (!asa || !id) {
      return;
    }
    const ids = this.grupo()(id);
    this.rutaId = id;
    this.rutaIds = ids.includes(id) ? ids : [id];
    const host = this.host.nativeElement;
    this.origenes = this.rutaIds.flatMap((rutaId) => {
      const elemento = host.querySelector<HTMLElement>(`[data-arrastre-ruta="${rutaId}"]`);
      return elemento ? [elemento.closest<HTMLElement>('.matriz-ruta') ?? elemento] : [];
    });
    // Chrome cancela el arrastre si el origen cambia de estilo dentro de
    // `dragstart` (p. ej. pointer-events): las clases van en el cuadro siguiente.
    const origenes = this.origenes;
    requestAnimationFrame(() => {
      if (this.rutaId === id) {
        for (const origen of origenes) {
          origen.classList.add('matriz-ruta--arrastrada');
        }
        host.classList.add('matriz--arrastrando');
      }
    });
    if (evento.dataTransfer) {
      evento.dataTransfer.effectAllowed = 'move';
      // Firefox no arrastra sin datos.
      evento.dataTransfer.setData('text/plain', this.rutaIds.join(','));
      if (this.rutaIds.length > 1) {
        this.fantasma(evento.dataTransfer, this.rutaIds.length);
      }
    }
  }

  /**
   * Imagen del arrastre en grupo: una pastilla con cuántas rutas viajan. Va fuera de la pantalla
   * solo mientras el navegador la captura (estilos en línea: vive en el body, fuera del componente).
   */
  private fantasma(datos: DataTransfer, cantidad: number): void {
    const pastilla = document.createElement('div');
    pastilla.textContent = `${cantidad} rutas`;
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
    if (this.rutaId === null) {
      return;
    }
    const celda = this.celdaDe(evento);
    if (!celda) {
      return;
    }
    // Se acepta siempre para que `drop` llegue y pueda explicar un rechazo.
    evento.preventDefault();
    const clusterId = Number(celda.dataset['cluster']);
    const chuteId = Number(celda.dataset['chute']);
    const clave = `${clusterId}:${chuteId}`;
    if (clave !== this.clave) {
      this.clave = clave;
      this.motivo = this.validar()(this.rutaIds, clusterId, chuteId);
      this.marcar(clusterId, chuteId, this.motivo === null);
    }
    // Siempre «move»: con «none» el navegador no dispara `drop` y no se podría
    // explicar el rechazo. El destino inválido ya se ve en rojo.
    if (evento.dataTransfer) {
      evento.dataTransfer.dropEffect = 'move';
    }
  }

  private alSoltar(evento: DragEvent): void {
    const celda = this.celdaDe(evento);
    const rutaId = this.rutaId;
    const rutaIds = this.rutaIds;
    this.limpiar();
    if (!celda || rutaId === null) {
      return;
    }
    evento.preventDefault();
    const clusterId = Number(celda.dataset['cluster']);
    const chuteId = Number(celda.dataset['chute']);
    this.soltar.emit({
      rutaId,
      rutaIds,
      clusterId,
      chuteId,
      motivo: this.validar()(rutaIds, clusterId, chuteId),
    });
  }

  private celdaDe(evento: DragEvent): HTMLElement | null {
    return (evento.target as HTMLElement | null)?.closest<HTMLElement>('[data-cluster][data-chute]') ?? null;
  }

  /** Resalta toda la columna del chute dentro del bloque del cluster destino. */
  private marcar(clusterId: number, chuteId: number, valido: boolean): void {
    this.desmarcar();
    this.marcadas = Array.from(
      this.host.nativeElement.querySelectorAll<HTMLElement>(
        `[data-cluster="${clusterId}"][data-chute="${chuteId}"]`,
      ),
    );
    const clase = valido ? 'matriz__celda--destino' : 'matriz__celda--prohibido';
    for (const celda of this.marcadas) {
      celda.classList.add(clase);
    }
  }

  private desmarcar(): void {
    for (const celda of this.marcadas) {
      celda.classList.remove('matriz__celda--destino', 'matriz__celda--prohibido');
    }
    this.marcadas = [];
  }

  private limpiar(): void {
    this.desmarcar();
    for (const origen of this.origenes) {
      origen.classList.remove('matriz-ruta--arrastrada');
    }
    this.host.nativeElement.classList.remove('matriz--arrastrando');
    this.rutaId = null;
    this.rutaIds = [];
    this.origenes = [];
    this.clave = null;
    this.motivo = null;
  }
}
