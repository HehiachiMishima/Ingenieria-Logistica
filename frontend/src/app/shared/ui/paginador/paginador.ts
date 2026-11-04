import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { Icon } from '../icon/icon';

@Component({
  selector: 'ui-paginador',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    @if (total() > 0) {
      <div class="ui-paginador">
        @if (totalPaginas() > 1) {
          <nav class="ui-paginador__nav" aria-label="Paginación">
            <button
              type="button"
              class="ui-paginador__btn"
              [disabled]="pagina() <= 1"
              (click)="ir(pagina() - 1)"
              aria-label="Página anterior"
            >
              <ui-icon name="chevronLeft" [size]="16" />
            </button>

            @for (p of paginas(); track $index) {
              @if (p === 'gap') {
                <span class="ui-paginador__gap" aria-hidden="true">…</span>
              } @else {
                <button
                  type="button"
                  class="ui-paginador__btn"
                  [class.ui-paginador__btn--activa]="p === pagina()"
                  [attr.aria-current]="p === pagina() ? 'page' : null"
                  (click)="ir(p)"
                >
                  {{ p }}
                </button>
              }
            }

            <button
              type="button"
              class="ui-paginador__btn"
              [disabled]="pagina() >= totalPaginas()"
              (click)="ir(pagina() + 1)"
              aria-label="Página siguiente"
            >
              <ui-icon name="chevronRight" [size]="16" />
            </button>
          </nav>
        }
      </div>
    }
  `,
})
export class Paginador {
  readonly pagina = input<number>(1);
  readonly total = input<number>(0);
  readonly porPagina = input<number>(10);
  /**
   * Sin uso desde que se quitó el selector de tamaño de página: `opciones` y
   * `porPaginaCambia` se conservan para no romper a las páginas que los enlazan.
   */
  readonly opciones = input<number[]>([10, 25, 50]);

  readonly paginaCambia = output<number>();
  readonly porPaginaCambia = output<number>();

  readonly totalPaginas = computed(() => Math.max(1, Math.ceil(this.total() / this.porPagina())));

  readonly paginas = computed<(number | 'gap')[]>(() => {
    const total = this.totalPaginas();
    const actual = this.pagina();
    if (total <= 7) {
      return Array.from({ length: total }, (_, i) => i + 1);
    }

    const numeros = new Set<number>([1, actual - 1, actual, actual + 1, total]);
    const ordenados = [...numeros].filter((n) => n >= 1 && n <= total).sort((a, b) => a - b);

    const resultado: (number | 'gap')[] = [];
    let anterior = 0;
    for (const n of ordenados) {
      if (anterior && n - anterior > 1) {
        resultado.push('gap');
      }
      resultado.push(n);
      anterior = n;
    }
    return resultado;
  });

  ir(pagina: number): void {
    const destino = Math.min(Math.max(1, pagina), this.totalPaginas());
    if (destino !== this.pagina()) {
      this.paginaCambia.emit(destino);
    }
  }
}
