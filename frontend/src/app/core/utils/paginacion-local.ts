import { computed, signal } from '@angular/core';

/**
 * Paginación en el navegador de una lista ya cargada (catálogos pequeños que el
 * backend devuelve completos). La página pedida se acota al rango válido, así
 * que filtrar o borrar nunca deja la tabla en una página vacía.
 */
export class PaginacionLocal<T> {
  readonly pagina = signal(1);
  readonly porPagina = signal(10);

  readonly totalPaginas = computed(() =>
    Math.max(1, Math.ceil(this.lista().length / this.porPagina())),
  );

  readonly paginaActual = computed(() => Math.min(Math.max(1, this.pagina()), this.totalPaginas()));

  readonly paginados = computed(() => {
    const inicio = (this.paginaActual() - 1) * this.porPagina();
    return this.lista().slice(inicio, inicio + this.porPagina());
  });

  constructor(private readonly lista: () => readonly T[]) {}

  cambiarPorPagina(valor: number): void {
    this.porPagina.set(valor);
    this.pagina.set(1);
  }
}
