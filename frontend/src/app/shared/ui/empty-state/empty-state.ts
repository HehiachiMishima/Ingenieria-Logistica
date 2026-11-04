import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icon } from '../icon/icon';

@Component({
  selector: 'ui-empty',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="ui-empty">
      <span class="ui-empty__icon">
        <ui-icon [name]="icono()" [size]="28" />
      </span>
      <div class="ui-empty__title">{{ titulo() }}</div>
      @if (mensaje()) {
        <p class="ui-empty__text">{{ mensaje() }}</p>
      }
      <ng-content />
    </div>
  `,
})
export class EmptyState {
  readonly icono = input<string>('layers');
  readonly titulo = input<string>('Sin datos');
  readonly mensaje = input<string>('');
}
