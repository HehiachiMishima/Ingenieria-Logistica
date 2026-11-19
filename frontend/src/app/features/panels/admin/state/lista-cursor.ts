import { computed, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { RecursoRemoto } from '../../../../core/utils/listado-remoto';
import { PaginaCursor } from '../data/observabilidad.model';

/**
 * Lista paginada por cursor (keyset): sin total ni números de página. Guarda los
 * cursores visitados para volver atrás; cambiar un filtro vuelve a la primera
 * página y cancela la petición anterior. Crear en un contexto de inyección.
 */
export class ListaCursor<T> {
  private readonly cursor = signal<number | null>(null);
  private readonly historial = signal<(number | null)[]>([]);
  private readonly recurso: RecursoRemoto<PaginaCursor<T>>;

  readonly filas = computed(() => this.recurso.valor()?.filas ?? []);
  readonly cargando = computed(() => this.recurso.cargando());
  readonly error = computed(() => this.recurso.error());
  /** true tras la primera respuesta (con o sin filas). */
  readonly cargada = computed(() => this.recurso.valor() !== null);
  readonly pagina = computed(() => this.historial().length + 1);
  readonly hayAnterior = computed(() => this.historial().length > 0);
  readonly haySiguiente = computed(() => this.recurso.valor()?.siguiente != null);

  constructor(fuente: (antes: number | null) => Observable<PaginaCursor<T>>) {
    this.recurso = new RecursoRemoto(() => fuente(this.cursor()));
  }

  /** Primera página con los filtros actuales. */
  reiniciar(): void {
    this.cursor.set(null);
    this.historial.set([]);
    this.recurso.recargar();
  }

  siguiente(): void {
    const siguiente = this.recurso.valor()?.siguiente;
    if (siguiente == null || this.recurso.cargando()) {
      return;
    }
    this.historial.update((h) => [...h, this.cursor()]);
    this.cursor.set(siguiente);
    this.recurso.recargar();
  }

  anterior(): void {
    const historial = this.historial();
    if (!historial.length || this.recurso.cargando()) {
      return;
    }
    this.cursor.set(historial.at(-1) ?? null);
    this.historial.set(historial.slice(0, -1));
    this.recurso.recargar();
  }
}
