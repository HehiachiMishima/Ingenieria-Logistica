import { Directive, ElementRef, OnDestroy, OnInit, inject, input, output } from '@angular/core';

/** Celda de la matriz donde se soltó una ruta. */
export interface DestinoRuta {
  rutaId: number;
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

  /** Motivo por el que la ruta no puede ir a esa celda, o null si puede. */
  readonly validar = input.required<(rutaId: number, clusterId: number, chuteId: number) => string | null>();
  readonly soltar = output<DestinoRuta>();

  private rutaId: number | null = null;
  private origen: HTMLElement | null = null;
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
    this.rutaId = id;
    this.origen = asa.closest<HTMLElement>('.matriz-ruta');
    // Chrome cancela el arrastre si el origen cambia de estilo dentro de
    // `dragstart` (p. ej. pointer-events): las clases van en el cuadro siguiente.
    const origen = this.origen;
    requestAnimationFrame(() => {
      if (this.rutaId === id) {
        origen?.classList.add('matriz-ruta--arrastrada');
        this.host.nativeElement.classList.add('matriz--arrastrando');
      }
    });
    if (evento.dataTransfer) {
      evento.dataTransfer.effectAllowed = 'move';
      // Firefox no arrastra sin datos.
      evento.dataTransfer.setData('text/plain', String(id));
    }
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
      this.motivo = this.validar()(this.rutaId, clusterId, chuteId);
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
    this.limpiar();
    if (!celda || rutaId === null) {
      return;
    }
    evento.preventDefault();
    const clusterId = Number(celda.dataset['cluster']);
    const chuteId = Number(celda.dataset['chute']);
    this.soltar.emit({ rutaId, clusterId, chuteId, motivo: this.validar()(rutaId, clusterId, chuteId) });
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
    this.origen?.classList.remove('matriz-ruta--arrastrada');
    this.host.nativeElement.classList.remove('matriz--arrastrando');
    this.rutaId = null;
    this.origen = null;
    this.clave = null;
    this.motivo = null;
  }
}
