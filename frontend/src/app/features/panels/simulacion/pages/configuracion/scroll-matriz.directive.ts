import { AfterViewInit, Directive, ElementRef, OnDestroy, inject } from '@angular/core';

/**
 * Desplazamiento horizontal de la planilla: cada cluster (`.mz-bloque`) se
 * mueve por su cuenta y arrastra con él solo a sus propias tablas (la de
 * rutas y la de sus totales), para que esas columnas sigan alineadas. El
 * total general se desplaza solo.
 *
 * Un único listener en captura sobre el contenedor (el evento `scroll` no
 * burbujea) y un `requestAnimationFrame` para no copiar el desplazamiento en
 * cada evento. La app no usa zone.js: el listener nativo no dispara detección
 * de cambios.
 *
 * - Los `scroll` que provoca la propia sincronización se ignoran: si no,
 *   con inercia (trackpad) el destino podía "devolver" al origen hacia atrás.
 * - Una tabla de totales que aparece (al pulsar «Ver totales») se alinea al
 *   instante con la de rutas de su cluster.
 */
@Directive({ selector: '[appScrollMatriz]' })
export class ScrollMatriz implements AfterViewInit, OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private retirar: (() => void) | null = null;
  private marco: number | null = null;
  private pendiente: HTMLElement | null = null;
  /** Elementos movidos por la directiva: su próximo `scroll` es eco, no del usuario. */
  private readonly sincronizados = new WeakSet<HTMLElement>();
  private observador: MutationObserver | null = null;

  ngAfterViewInit(): void {
    const alDesplazar = (evento: Event) => {
      const objetivo = evento.target;
      if (!(objetivo instanceof HTMLElement) || !objetivo.hasAttribute('data-scroll-matriz')) {
        return;
      }
      if (this.sincronizados.has(objetivo)) {
        this.sincronizados.delete(objetivo);
        return;
      }
      this.pendiente = objetivo;
      if (this.marco === null) {
        this.marco = requestAnimationFrame(() => this.sincronizar());
      }
    };

    const host = this.host.nativeElement;
    host.addEventListener('scroll', alDesplazar, true);
    this.retirar = () => host.removeEventListener('scroll', alDesplazar, true);

    // Tablas nuevas dentro de un cluster (totales al abrirse): toman su desplazamiento.
    this.observador = new MutationObserver((cambios) => {
      for (const cambio of cambios) {
        for (const nodo of cambio.addedNodes) {
          if (nodo instanceof HTMLElement) {
            this.alinearNuevas(nodo);
          }
        }
      }
    });
    this.observador.observe(host, { childList: true, subtree: true });
  }

  ngOnDestroy(): void {
    this.retirar?.();
    this.observador?.disconnect();
    if (this.marco !== null) {
      cancelAnimationFrame(this.marco);
    }
  }

  /** Copia el desplazamiento a las demás tablas del mismo cluster. */
  private sincronizar(): void {
    this.marco = null;
    const origen = this.pendiente;
    this.pendiente = null;
    const bloque = origen?.closest('.mz-bloque');
    if (!origen || !bloque) {
      return;
    }
    this.copiar(bloque, origen.scrollLeft, origen);
  }

  /** Alinea las tablas que acaban de aparecer con la de rutas de su cluster. */
  private alinearNuevas(nodo: HTMLElement): void {
    const bloque = nodo.closest('.mz-bloque');
    const referencia = bloque?.querySelector<HTMLElement>('.mz-scroll[data-scroll-matriz]');
    if (bloque && referencia && referencia.scrollLeft > 0) {
      this.copiar(bloque, referencia.scrollLeft, referencia);
    }
  }

  private copiar(bloque: Element, izquierda: number, origen: HTMLElement): void {
    for (const destino of bloque.querySelectorAll<HTMLElement>('[data-scroll-matriz]')) {
      if (destino !== origen && destino.scrollLeft !== izquierda) {
        this.sincronizados.add(destino);
        destino.scrollLeft = izquierda;
      }
    }
  }
}
