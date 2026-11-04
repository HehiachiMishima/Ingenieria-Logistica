import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  HostListener,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Icon } from '../icon/icon';
import { normalizarTexto } from '../../../core/utils/texto.util';

/** Contador para ids únicos de la lista (aria-controls / aria-activedescendant). */
let siguienteId = 0;

export interface OpcionSeleccion {
  id: number;
  etiqueta: string;
}

/**
 * Select con búsqueda por escritura (combobox). Diseñado para catálogos
 * grandes: el usuario escribe para filtrar las opciones y elige con clic o
 * Enter. Al elegir vuelve a quedar listo para escribir o hacer clic y elegir
 * otro valor sin salir del campo. Teclado: ↑/↓ navegan, Enter elige, Esc
 * cierra, Tab pierde foco.
 */
@Component({
  selector: 'ui-select-buscable',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div
      class="ui-selbusc"
      [class.ui-selbusc--abierto]="abierto()"
      [class.ui-input--invalid]="invalido()"
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
          role="combobox"
          aria-autocomplete="list"
          [attr.aria-expanded]="abierto()"
          [attr.aria-controls]="listaId"
          [attr.aria-activedescendant]="
            abierto() && filtradas().length > 0 ? listaId + '-' + resaltado() : null
          "
          [attr.id]="campoId()"
          [attr.aria-label]="campoId() ? null : placeholder()"
          [value]="abierto() ? consulta() : (seleccion()?.etiqueta ?? '')"
          (focus)="abrir()"
          (beforeinput)="antesDeEscribir($event)"
          (input)="escribir($any($event.target).value)"
          (keydown)="tecla($event)"
          (click)="$event.stopPropagation(); alHacerClic()"
        />
        @if (limpiable() && valor() !== null && !deshabilitado()) {
          <button
            type="button"
            class="ui-selbusc__limpiar"
            aria-label="Quitar selección"
            (click)="$event.stopPropagation(); limpiar()"
          >
            <ui-icon name="close" [size]="14" />
          </button>
        }
        <ui-icon class="ui-selbusc__chevron" name="chevronRight" [size]="16" />
      </div>

      @if (abierto()) {
        <ul
          class="ui-selbusc__lista"
          role="listbox"
          [attr.id]="listaId"
          [attr.aria-label]="placeholder()"
        >
          @for (op of filtradas(); track op.id; let i = $index) {
            <li
              role="option"
              [attr.id]="listaId + '-' + i"
              [attr.aria-selected]="op.id === valor()"
              class="ui-selbusc__op"
              [class.ui-selbusc__op--activo]="i === resaltado()"
              [class.ui-selbusc__op--sel]="op.id === valor()"
              (click)="$event.stopPropagation(); elegir(op)"
              (mouseenter)="resaltado.set(i)"
            >
              <span class="ui-selbusc__op-texto">{{ op.etiqueta }}</span>
              @if (op.id === valor()) {
                <ui-icon name="check" [size]="14" />
              }
            </li>
          } @empty {
            <li class="ui-selbusc__vacio">Sin coincidencias</li>
          }
        </ul>
      }
    </div>
  `,
})
export class SelectBuscable {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly campo = viewChild<ElementRef<HTMLInputElement>>('campo');

  readonly opciones = input.required<readonly OpcionSeleccion[]>();
  readonly valor = input<number | null>(null);
  readonly placeholder = input('Escribir para buscar…');
  readonly invalido = input(false);
  readonly deshabilitado = input(false);
  /** id del campo, para asociarlo a un <label for>; sin él se usa el placeholder como aria-label. */
  readonly campoId = input<string | null>(null);
  /** Muestra un botón para quitar la selección (emite null), p. ej. «Todos los clusters». */
  readonly limpiable = input(false);

  readonly listaId = `ui-selbusc-${++siguienteId}`;

  readonly valorCambia = output<number | null>();

  readonly abierto = signal(false);
  readonly consulta = signal('');
  readonly resaltado = signal(0);

  readonly seleccion = computed(() => this.opciones().find((o) => o.id === this.valor()) ?? null);

  readonly filtradas = computed(() => {
    const q = normalizarTexto(this.consulta());
    if (!q) return this.opciones();
    return this.opciones().filter((o) => normalizarTexto(o.etiqueta).includes(q));
  });

  private ultimoValor: number | null = this.valor();

  constructor() {
    effect(() => {
      if (this.abierto()) {
        queueMicrotask(() => this.campo()?.nativeElement?.focus());
      }
    });

    // Si el valor cambia desde fuera (p.ej. "Limpiar filtros"), resetea el texto.
    effect(() => {
      const v = this.valor();
      if (v !== this.ultimoValor) {
        this.ultimoValor = v;
        this.consulta.set('');
        this.resaltado.set(0);
      }
    });
  }

  abrir(): void {
    if (this.deshabilitado() || this.abierto()) return;
    this.abierto.set(true);
    this.consulta.set('');
    this.resaltado.set(0);
  }

  cerrar(): void {
    this.abierto.set(false);
    this.consulta.set('');
  }

  /**
   * Escribir con el select cerrado reabre la lista y empieza a filtrar desde
   * cero: así se puede elegir un filtro, escribir de nuevo y elegir otro sin
   * salir del campo.
   */
  antesDeEscribir(evento: InputEvent): void {
    if (this.abierto() || this.deshabilitado()) {
      return;
    }
    evento.preventDefault();
    this.abrir();
    this.consulta.set(evento.data ?? '');
    this.resaltado.set(0);
  }

  /** Un clic sobre el campo cerrado vuelve a abrir la lista. */
  alHacerClic(): void {
    if (!this.abierto()) {
      this.abrir();
    }
  }

  escribir(valor: string): void {
    // Algunos navegadores no emiten `beforeinput`: abrir aquí igualmente.
    if (!this.abierto()) {
      this.abrir();
    }
    this.consulta.set(valor);
    this.resaltado.set(0);
  }

  limpiar(): void {
    this.valorCambia.emit(null);
    this.cerrar();
  }

  elegir(op: OpcionSeleccion): void {
    this.valorCambia.emit(op.id);
    // Mantiene el foco para poder escribir de nuevo sin volver a hacer clic.
    this.campo()?.nativeElement?.focus();
    this.cerrar();
  }

  tecla(evento: KeyboardEvent): void {
    const opciones = this.filtradas();
    switch (evento.key) {
      case 'Escape':
        this.cerrar();
        break;
      case 'ArrowDown': {
        evento.preventDefault();
        const estabaAbierto = this.abierto();
        this.abrir();
        if (estabaAbierto) {
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
        if (op) this.elegir(op);
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
