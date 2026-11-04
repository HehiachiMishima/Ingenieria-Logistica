import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Notificaciones, TipoNotificacion } from '../../../core/services/notificaciones';
import { Icon } from '../icon/icon';

const ICONO_POR_TIPO: Record<TipoNotificacion, string> = {
  exito: 'checkCircleFilled',
  error: 'xCircle',
  info: 'info',
  advertencia: 'alertTriangle',
};

@Component({
  selector: 'ui-toast-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="ui-toast-host" aria-live="polite" aria-atomic="true">
      @for (aviso of notificaciones.items(); track aviso.id) {
        <div class="ui-toast" [class]="'ui-toast--' + aviso.tipo" role="status">
          <span class="ui-toast__icon">
            <ui-icon [name]="icono(aviso.tipo)" [size]="20" />
          </span>
          <div class="ui-toast__body">
            <div class="ui-toast__title">{{ aviso.titulo }}</div>
            @if (aviso.mensaje) {
              <div class="ui-toast__text">{{ aviso.mensaje }}</div>
            }
          </div>
          <button
            type="button"
            class="ui-icon-btn ui-toast__close"
            (click)="notificaciones.cerrar(aviso.id)"
            aria-label="Cerrar"
          >
            <ui-icon name="close" [size]="16" />
          </button>
        </div>
      }
    </div>
  `,
})
export class ToastHost {
  readonly notificaciones = inject(Notificaciones);

  icono(tipo: TipoNotificacion): string {
    return ICONO_POR_TIPO[tipo];
  }
}
