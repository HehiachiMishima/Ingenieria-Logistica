import { DestroyRef, ElementRef, Signal, afterNextRender, inject, signal } from '@angular/core';

/**
 * Ancho (px) del elemento del componente que la llama, siempre al día con un
 * ResizeObserver. Las vistas del plano (rampas y pasillos) lo usan para
 * calcular el mismo tamaño de paleta: las dos ocupan el ancho del plano.
 * Debe llamarse en el contexto de inyección (campo o constructor).
 */
export function anchoDelHost(): Signal<number> {
  const host = inject<ElementRef<HTMLElement>>(ElementRef);
  const destroyRef = inject(DestroyRef);
  const ancho = signal(0);
  afterNextRender(() => {
    const el = host.nativeElement;
    const medir = () => ancho.set(el.clientWidth);
    const observador = new ResizeObserver(medir);
    observador.observe(el);
    medir();
    destroyRef.onDestroy(() => observador.disconnect());
  });
  return ancho.asReadonly();
}
