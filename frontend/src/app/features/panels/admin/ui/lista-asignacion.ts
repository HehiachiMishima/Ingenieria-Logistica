import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icon } from '../../../../shared/ui/icon/icon';

/** Rol o permiso tal como se lista en los modales de asignación. */
export interface ItemAsignable {
  id: number;
  nombre: string;
  descripcion: string | null;
  activo: boolean;
}

/**
 * Lista de roles o permisos de los modales de Administración. Con `asignados`
 * cada fila lleva un interruptor que emite `alternar`; sin él es de solo lectura.
 */
@Component({
  selector: 'app-lista-asignacion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  styles: `
    :host {
      display: block;
    }
  `,
  template: `
    <ul class="role-list">
      @for (item of items(); track item.id) {
        <li class="role-item">
          <span class="role-item__icon"><ui-icon [name]="icono()" [size]="18" /></span>
          <span class="role-item__text">
            <span class="role-item__name">
              {{ item.nombre }}
              @if (!item.activo) {
                <span class="ui-badge ui-badge--neutral">Inactivo</span>
              }
            </span>
            <span class="role-item__desc">{{ item.descripcion || 'Sin descripción' }}</span>
          </span>
          @if (asignados(); as asignados) {
            <button
              type="button"
              class="switch"
              [class.switch--on]="asignados.has(item.id)"
              [disabled]="cambiando() === item.id"
              (click)="alternar.emit(item)"
              [attr.aria-pressed]="asignados.has(item.id)"
            >
              <span class="switch__thumb"></span>
            </button>
          }
        </li>
      }
    </ul>
  `,
})
export class ListaAsignacion<T extends ItemAsignable> {
  readonly items = input.required<readonly T[]>();
  readonly icono = input.required<string>();
  /** Ids asignados; null = lista de solo lectura (sin interruptores). */
  readonly asignados = input<ReadonlySet<number> | null>(null);
  /** Id del elemento con un cambio en curso (su interruptor se deshabilita). */
  readonly cambiando = input<number | null>(null);
  readonly alternar = output<T>();
}
