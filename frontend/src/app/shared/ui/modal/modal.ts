import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';
import { Icon } from '../icon/icon';

export type AnchoModal = 'sm' | 'md' | 'lg' | 'xl' | 'xxl' | 'full';

/** Diálogos abiertos a la vez: el scroll del documento se bloquea mientras haya alguno. */
let abiertos = 0;

const ENFOCABLES =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

@Component({
  selector: 'ui-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  host: {
    '(document:keydown)': 'alTecla($event)',
  },
  template: `
    @if (abierto()) {
      <div class="ui-modal-backdrop" (click)="alClicFondo($event)" tabindex="-1">
        <section
          #dialogo
          class="ui-modal"
          [class]="'ui-modal--' + ancho()"
          [class.ui-modal--compacto]="compacto()"
          role="dialog"
          aria-modal="true"
          tabindex="-1"
          [attr.aria-label]="titulo()"
        >
          <header class="ui-modal__header">
            <div class="ui-modal__heading">
              <h2 class="ui-modal__title">{{ titulo() }}</h2>
              @if (subtitulo()) {
                <p class="ui-modal__subtitle">{{ subtitulo() }}</p>
              }
            </div>
            <button type="button" class="ui-icon-btn" (click)="cerrar.emit()" aria-label="Cerrar">
              <ui-icon name="close" [size]="18" />
            </button>
          </header>

          <div class="ui-modal__body">
            <ng-content />
          </div>

          <div class="ui-modal__footer">
            <ng-content select="[modal-footer]" />
          </div>
        </section>
      </div>
    }
  `,
})
export class Modal {
  private readonly caja = viewChild<ElementRef<HTMLElement>>('dialogo');
  private focoPrevio: HTMLElement | null = null;

  readonly abierto = input(false);
  readonly titulo = input('');
  readonly subtitulo = input('');
  readonly ancho = input<AnchoModal>('md');
  readonly compacto = input(false);
  readonly cerrar = output<void>();

  constructor() {
    effect(() => {
      const caja = this.caja();
      if (caja && this.focoPrevio === null) {
        this.focoPrevio = document.activeElement as HTMLElement | null;
        this.bloquearScroll(true);
        this.enfocarPrimero(caja.nativeElement);
      } else if (!caja && this.focoPrevio !== null) {
        this.bloquearScroll(false);
        this.focoPrevio.focus();
        this.focoPrevio = null;
      }
    });
    inject(DestroyRef).onDestroy(() => {
      if (this.focoPrevio !== null) {
        this.bloquearScroll(false);
      }
    });
  }

  alClicFondo(evento: MouseEvent): void {
    if (evento.target === evento.currentTarget) {
      this.cerrar.emit();
    }
  }

  alTecla(evento: KeyboardEvent): void {
    const caja = this.caja();
    if (!caja) {
      return;
    }
    if (evento.key === 'Escape') {
      evento.preventDefault();
      this.cerrar.emit();
      return;
    }
    if (evento.key === 'Tab') {
      this.atraparFoco(evento, caja.nativeElement);
    }
  }

  private bloquearScroll(bloquear: boolean): void {
    abiertos = Math.max(0, abiertos + (bloquear ? 1 : -1));
    document.documentElement.style.overflow = abiertos > 0 ? 'hidden' : '';
  }

  private enfocarPrimero(raiz: HTMLElement): void {
    const lista = this.enfocables(raiz);
    (lista[0] ?? raiz).focus();
  }

  private atraparFoco(evento: KeyboardEvent, raiz: HTMLElement): void {
    const lista = this.enfocables(raiz);
    if (lista.length === 0) {
      evento.preventDefault();
      raiz.focus();
      return;
    }
    const primero = lista[0];
    const ultimo = lista[lista.length - 1];
    const activo = document.activeElement;
    if (evento.shiftKey && (activo === primero || activo === raiz)) {
      evento.preventDefault();
      ultimo.focus();
    } else if (!evento.shiftKey && activo === ultimo) {
      evento.preventDefault();
      primero.focus();
    }
  }

  private enfocables(raiz: HTMLElement): HTMLElement[] {
    return Array.from(raiz.querySelectorAll<HTMLElement>(ENFOCABLES)).filter(
      (el) => el.getClientRects().length > 0,
    );
  }
}
