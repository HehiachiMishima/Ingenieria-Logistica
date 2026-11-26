import { Directive, ElementRef, OnDestroy, OnInit, inject, input, output } from '@angular/core';

/** Ruta sobre la que se soltaron locales arrastrados desde la ficha. */
export interface SueltaLocales {
  destinoId: number;
  /** Por qué esa ruta no puede recibirlos (null = se puede). */
  motivo: string | null;
}

/**
 * Soltar locales (arrastrados desde la ficha de una ruta) sobre la tarjeta de otra ruta de la
 * matriz. Como {@link ArrastreRutas}, escucha una sola vez en el contenedor y resalta la tarjeta
 * tocando clases en el DOM; Angular solo se entera al soltar. Solo responde mientras haya locales
 * en camino: el arrastre de rutas sigue igual.
 *
 * Marcado que espera: cada tarjeta de ruta con `[data-ruta-id]`.
 */
@Directive({ selector: '[appArrastreLocales]' })
export class ArrastreLocales implements OnInit, OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Hay locales en camino desde la ficha. */
  readonly localesEnCamino = input(false);
  /** Motivo por el que esa ruta no puede recibir los locales, o null si puede. */
  readonly validarLocales = input.required<(destinoId: number) => string | null>();
  readonly soltarLocales = output<SueltaLocales>();

  private destinoId: number | null = null;
  private marcada: HTMLElement | null = null;
  private retirar: (() => void) | null = null;

  ngOnInit(): void {
    const host = this.host.nativeElement;
    const alPasar = (e: DragEvent) => this.alPasar(e);
    const alSoltar = (e: DragEvent) => this.alSoltar(e);
    // El arrastre empieza y termina en la ficha (fuera del contenedor): al terminar se limpia.
    const alTerminar = () => this.desmarcar();
    host.addEventListener('dragover', alPasar);
    host.addEventListener('drop', alSoltar);
    document.addEventListener('dragend', alTerminar, true);
    this.retirar = () => {
      host.removeEventListener('dragover', alPasar);
      host.removeEventListener('drop', alSoltar);
      document.removeEventListener('dragend', alTerminar, true);
    };
  }

  ngOnDestroy(): void {
    this.retirar?.();
    this.desmarcar();
  }

  private alPasar(evento: DragEvent): void {
    if (!this.localesEnCamino()) {
      return;
    }
    const tarjeta = this.tarjetaDe(evento);
    if (!tarjeta) {
      // Fuera de una ruta no se puede soltar (el navegador muestra el cursor de prohibido).
      this.desmarcar();
      return;
    }
    // Se acepta siempre para que `drop` llegue y pueda explicar un rechazo.
    evento.preventDefault();
    const id = Number(tarjeta.dataset['rutaId']);
    if (id !== this.destinoId) {
      this.desmarcar();
      this.destinoId = id;
      this.marcada = tarjeta;
      tarjeta.classList.add(this.validarLocales()(id) === null ? 'matriz-ruta--recibe' : 'matriz-ruta--no-recibe');
    }
    if (evento.dataTransfer) {
      evento.dataTransfer.dropEffect = 'move';
    }
  }

  private alSoltar(evento: DragEvent): void {
    if (!this.localesEnCamino()) {
      return;
    }
    const tarjeta = this.tarjetaDe(evento);
    this.desmarcar();
    if (!tarjeta) {
      return;
    }
    evento.preventDefault();
    const destinoId = Number(tarjeta.dataset['rutaId']);
    this.soltarLocales.emit({ destinoId, motivo: this.validarLocales()(destinoId) });
  }

  private tarjetaDe(evento: DragEvent): HTMLElement | null {
    return (evento.target as HTMLElement | null)?.closest<HTMLElement>('[data-ruta-id]') ?? null;
  }

  private desmarcar(): void {
    this.marcada?.classList.remove('matriz-ruta--recibe', 'matriz-ruta--no-recibe');
    this.marcada = null;
    this.destinoId = null;
  }
}
