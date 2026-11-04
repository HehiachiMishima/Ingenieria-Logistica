import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Icon } from '../icon/icon';
import { normalizarTexto } from '../../../core/utils/texto.util';

/** Opción de un filtro múltiple: el id puede ser numérico o una clave de texto. */
export interface OpcionMultiple {
  id: number | string;
  etiqueta: string;
}

/**
 * Select múltiple con búsqueda por escritura. El usuario escribe y la lista se
 * filtra al instante; cada opción se marca con un clic (o Enter) sin cerrar el
 * desplegable, así se pueden elegir varias. Esc o un clic fuera lo cierran.
 */
@Component({
  selector: 'ui-select-multiple',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div
      class="ui-selbusc ui-selbusc--multiple"
      [class.ui-selbusc--abierto]="abierto()"
      [class.ui-selbusc--deshabilitado]="deshabilitado()"
    >
      <div class="ui-selbusc__control" (click)="alHacerClic()">
        <input
          #campo
          class="ui-selbusc__input"
          type="text"
          autocomplete="off"
          spellcheck="false"
          [disabled]="deshabilitado()"
          [placeholder]="placeholder()"
          [attr.id]="campoId()"
          [attr.aria-label]="campoId() ? null : placeholder()"
          [value]="abierto() ? consulta() : resumen()"
          (focus)="abrir()"
          (beforeinput)="antesDeEscribir($event)"
          (input)="escribir($any($event.target).value)"
          (keydown)="tecla($event)"
          (click)="$event.stopPropagation(); alHacerClic()"
        />
        @if (!abierto() && seleccionadas().length > 1) {
          <span class="ui-selbusc__cuenta">{{ seleccionadas().length }}</span>
        }
        <ui-icon class="ui-selbusc__chevron" name="chevronRight" [size]="16" />
      </div>

      @if (abierto()) {
        <ul
          class="ui-selbusc__lista"
          role="listbox"
          aria-multiselectable="true"
          [attr.aria-label]="placeholder()"
        >
          @for (op of filtradas(); track op.id; let i = $index) {
            <li
              class="ui-selbusc__op"
              [class.ui-selbusc__op--activo]="i === resaltado()"
              [class.ui-selbusc__op--sel]="estaSeleccionada(op.id)"
              (click)="$event.stopPropagation(); alternar(op)"
              (mouseenter)="resaltado.set(i)"
            >
              <span class="ui-selbusc__op-texto">{{ op.etiqueta }}</span>
              <ui-icon
                [name]="estaSeleccionada(op.id) ? 'check' : 'plus'"
                [size]="14"
                [class.ui-selbusc__op-marca]="!estaSeleccionada(op.id)"
              />
            </li>
          } @empty {
            <li class="ui-selbusc__vacio">Sin coincidencias</li>
          }
        </ul>
      }
    </div>
  `,
})
export class SelectMultiple {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly campo = viewChild<ElementRef<HTMLInputElement>>('campo');

  readonly opciones = input.required<readonly OpcionMultiple[]>();
  readonly valores = input<readonly (number | string)[]>([]);
  readonly placeholder = input('Todos');
  readonly campoId = input<string | null>(null);
  readonly deshabilitado = input(false);

  readonly valoresCambia = output<(number | string)[]>();

  readonly abierto = signal(false);
  readonly consulta = signal('');
  readonly resaltado = signal(0);

  readonly seleccionadas = computed(() => {
    const elegidas = this.valores();
    return this.opciones().filter((op) => elegidas.includes(op.id));
  });

  /** Texto que se ve con el desplegable cerrado. */
  readonly resumen = computed(() => {
    const elegidas = this.seleccionadas();
    if (elegidas.length === 0) {
      return '';
    }
    return elegidas.length === 1 ? elegidas[0].etiqueta : `${elegidas.length} seleccionados`;
  });

  readonly filtradas = computed(() => {
    const q = normalizarTexto(this.consulta());
    if (!q) {
      return this.opciones();
    }
    return this.opciones().filter((op) => normalizarTexto(op.etiqueta).includes(q));
  });

  abrir(): void {
    if (this.deshabilitado() || this.abierto()) {
      return;
    }
    this.abierto.set(true);
    this.consulta.set('');
    this.resaltado.set(0);
    queueMicrotask(() => this.campo()?.nativeElement?.focus());
  }

  cerrar(): void {
    this.abierto.set(false);
    this.consulta.set('');
  }

  /** Escribir con el select cerrado reabre la lista y filtra desde cero. */
  antesDeEscribir(evento: InputEvent): void {
    if (this.abierto() || this.deshabilitado()) {
      return;
    }
    evento.preventDefault();
    this.abrir();
    this.consulta.set(evento.data ?? '');
    this.resaltado.set(0);
  }

  alHacerClic(): void {
    if (!this.abierto()) {
      this.abrir();
    }
  }

  escribir(valor: string): void {
    if (!this.abierto()) {
      this.abrir();
    }
    this.consulta.set(valor);
    this.resaltado.set(0);
  }

  estaSeleccionada(id: number | string): boolean {
    return this.valores().includes(id);
  }

  /** Marca o desmarca sin cerrar: se pueden elegir varias opciones seguidas. */
  alternar(op: OpcionMultiple): void {
    const actuales = [...this.valores()];
    const indice = actuales.indexOf(op.id);
    if (indice >= 0) {
      actuales.splice(indice, 1);
    } else {
      actuales.push(op.id);
    }
    this.valoresCambia.emit(actuales);
    // Sigue escribiendo o marcando más opciones sin perder el foco.
    this.campo()?.nativeElement?.focus();
  }

  tecla(evento: KeyboardEvent): void {
    const opciones = this.filtradas();
    switch (evento.key) {
      case 'Escape':
        this.cerrar();
        break;
      case 'ArrowDown': {
        evento.preventDefault();
        const abierto = this.abierto();
        this.abrir();
        if (abierto) {
          this.resaltado.update((i) => Math.min(i + 1, Math.max(opciones.length - 1, 0)));
        }
        break;
      }
      case 'ArrowUp':
        evento.preventDefault();
        this.resaltado.update((i) => Math.max(i - 1, 0));
        break;
      case 'Enter': {
        evento.preventDefault();
        if (!this.abierto()) {
          this.abrir();
          break;
        }
        const op = opciones[this.resaltado()];
        if (op) {
          this.alternar(op);
        }
        break;
      }
      case 'Tab':
        this.cerrar();
        break;
    }
  }

  @HostListener('document:click', ['$event'])
  clicFuera(evento: MouseEvent): void {
    if (!this.host.nativeElement.contains(evento.target as Node)) {
      this.cerrar();
    }
  }

}
