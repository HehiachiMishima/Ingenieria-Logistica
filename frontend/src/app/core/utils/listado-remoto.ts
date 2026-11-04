import { DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, catchError, map, of, switchMap, tap } from 'rxjs';
import { mensajeDeError } from '../errors/error.util';

/** Forma mínima de una página devuelta por el backend (PaginaResponse). */
export interface PaginaRemota<T> {
  contenido: T[];
  total: number;
  totalPaginas: number;
}

type Resultado<T> = { ok: true; pagina: PaginaRemota<T> } | { ok: false; error: string };

/**
 * Listado paginado contra el servidor, pensado para búsquedas y filtros en vivo.
 *
 * - Un único flujo con `switchMap`: cada recarga cancela la petición anterior,
 *   así una respuesta lenta nunca pisa a una más reciente.
 * - Los errores se capturan DENTRO del switchMap: un fallo no mata el flujo y
 *   la búsqueda sigue funcionando en el siguiente intento.
 * - Mientras recarga conserva las filas actuales (`refrescando`), de modo que
 *   la tabla no parpadea ni se reemplaza por un spinner al escribir.
 *
 * Debe crearse en un contexto de inyección (campo o constructor de componente).
 */
export class ListadoRemoto<T> {
  private readonly disparador = new Subject<void>();

  readonly items = signal<T[]>([]);
  readonly total = signal(0);
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  /** true tras la primera respuesta (con o sin datos). */
  readonly listo = signal(false);
  /** Recarga en curso con datos ya visibles: la UI atenúa la tabla en vez de vaciarla. */
  readonly refrescando = computed(() => this.cargando() && this.listo());
  /** Primera carga: la UI muestra el esqueleto/spinner. */
  readonly cargaInicial = computed(() => this.cargando() && !this.listo());

  constructor(
    fuente: () => Observable<PaginaRemota<T>>,
    /** Si la página pedida quedó fuera de rango (p. ej. tras borrar), se invoca con la última válida. */
    alDesbordar?: (ultimaPagina: number) => void,
  ) {
    this.disparador
      .pipe(
        tap(() => {
          this.cargando.set(true);
          this.error.set(null);
        }),
        switchMap(() =>
          fuente().pipe(
            map((pagina): Resultado<T> => ({ ok: true, pagina })),
            catchError((err: unknown) =>
              of<Resultado<T>>({ ok: false, error: mensajeDeError(err) }),
            ),
          ),
        ),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((resultado) => {
        this.cargando.set(false);
        this.listo.set(true);
        if (!resultado.ok) {
          this.error.set(resultado.error);
          return;
        }
        const { pagina } = resultado;
        if (pagina.contenido.length === 0 && pagina.total > 0 && alDesbordar) {
          alDesbordar(Math.max(1, pagina.totalPaginas));
          this.recargar();
          return;
        }
        this.items.set(pagina.contenido);
        this.total.set(pagina.total);
      });
  }

  recargar(): void {
    this.disparador.next();
  }

  /**
   * Olvida las filas actuales (p. ej. al cambiar de registro): así un fallo
   * de la siguiente carga no deja a la vista datos de otro contexto.
   */
  vaciar(): void {
    this.items.set([]);
    this.total.set(0);
    this.error.set(null);
    this.listo.set(false);
  }
}

/**
 * Recurso remoto de un solo valor (p. ej. estadísticas de un dashboard) con
 * las mismas garantías que ListadoRemoto: cancela la petición anterior al
 * cambiar filtros, no muere ante un error y conserva el último valor mientras
 * recarga.
 */
export class RecursoRemoto<T> {
  private readonly disparador = new Subject<void>();

  readonly valor = signal<T | null>(null);
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);

  constructor(fuente: () => Observable<T>) {
    this.disparador
      .pipe(
        tap(() => {
          this.cargando.set(true);
          this.error.set(null);
        }),
        switchMap(() =>
          fuente().pipe(
            map((valor) => ({ ok: true as const, valor })),
            catchError((err: unknown) => of({ ok: false as const, error: mensajeDeError(err) })),
          ),
        ),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((resultado) => {
        this.cargando.set(false);
        if (resultado.ok) {
          this.valor.set(resultado.valor);
        } else {
          this.error.set(resultado.error);
        }
      });
  }

  recargar(): void {
    this.disparador.next();
  }
}
