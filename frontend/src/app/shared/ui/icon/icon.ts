import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ICONOS } from './iconos';

@Component({
  selector: 'ui-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.width]="tamano()"
      [attr.height]="tamano()"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      [attr.stroke-width]="grosor()"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      @for (trazo of trazos(); track $index) {
        <path [attr.d]="trazo" />
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
    }
    svg {
      display: block;
    }
  `,
})
export class Icon {
  readonly name = input.required<string>();
  readonly tamano = input<number>(20, { alias: 'size' });
  readonly grosor = input<number>(1.8, { alias: 'stroke' });

  readonly trazos = computed<string[]>(() => ICONOS[this.name()] ?? []);
}
