import { computed, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { ListadoRemoto, PaginaRemota } from '../../../../core/utils/listado-remoto';
import { TOPE_TOTAL } from '../data/observabilidad.model';

/**
 * Página actual y tamaño para un `ListadoRemoto` con el paginador estándar (`ui-paginador`),
 * como en Incidencias: cambiar un filtro vuelve a la página 1; si la página pedida quedó
 * fuera de rango, se recarga la última. Crear en un contexto de inyección.
 */
export class ListadoPaginado<T> {
  readonly pagina = signal(1);
  readonly porPagina = signal(10);
  readonly listado: ListadoRemoto<T>;

  readonly totalPaginas = computed(() => Math.max(1, Math.ceil(this.listado.total() / this.porPagina())));
  readonly paginaActual = computed(() => Math.min(Math.max(1, this.pagina()), this.totalPaginas()));
  /** El backend dejó de contar en el tope: hay más registros que los que se pueden recorrer. */
  readonly enTope = computed(() => this.listado.total() >= TOPE_TOTAL);

  constructor(fuente: (pagina: number, tamano: number) => Observable<PaginaRemota<T>>) {
    this.listado = new ListadoRemoto(
      () => fuente(this.pagina(), this.porPagina()),
      (ultima) => this.pagina.set(ultima),
    );
  }

  /** Primera página con los filtros actuales. */
  reiniciar(): void {
    this.pagina.set(1);
    this.listado.recargar();
  }

  cambiarPagina(pagina: number): void {
    this.pagina.set(pagina);
    this.listado.recargar();
  }

  cambiarPorPagina(tamano: number): void {
    this.porPagina.set(tamano);
    this.reiniciar();
  }
}
