import { ChangeDetectionStrategy, Component, HostListener, input, output } from '@angular/core';
import { Icon } from '../icon/icon';

/**
 * Panel de filtros: drawer derecho en escritorio y hoja inferior en móvil.
 *
 * Los campos los proyecta cada módulo (`ng-content`), agrupados con
 * `.filtro-seccion`. El pie fijo ofrece «Limpiar» (emite `limpiar`) y «Ver
 * resultados» (cierra): los filtros se aplican en vivo, igual que antes.
 */
@Component({
  selector: 'ui-panel-filtros',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="ui-panel-filtros" [class.ui-panel-filtros--abierto]="abierto()">
      <div class="ui-panel-filtros__overlay" (click)="cerrar.emit()"></div>

      <aside
        class="ui-panel-filtros__panel"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="titulo()"
        [attr.inert]="abierto() ? null : ''"
      >
        <span class="ui-panel-filtros__asa" aria-hidden="true"></span>
        <header class="ui-panel-filtros__header">
          <span class="ui-panel-filtros__icono"><ui-icon name="filtro" [size]="18" /></span>
          <span class="ui-panel-filtros__textos">
            <span class="ui-panel-filtros__title">{{ titulo() }}</span>
            <span class="ui-panel-filtros__sub">
              @if (activos() > 0) {
                {{ activos() }} {{ activos() === 1 ? 'filtro activo' : 'filtros activos' }}
              } @else {
                Sin filtros: se muestra todo
              }
            </span>
          </span>
          <button type="button" class="ui-icon-btn" aria-label="Cerrar filtros" (click)="cerrar.emit()">
            <ui-icon name="close" [size]="18" />
          </button>
        </header>

        <div class="ui-panel-filtros__body">
          <ng-content />
        </div>

        <footer class="ui-panel-filtros__pie">
          <button
            type="button"
            class="ui-btn ui-btn--ghost"
            [disabled]="activos() === 0"
            (click)="limpiar.emit()"
          >
            <ui-icon name="refresh" [size]="16" />
            Limpiar
          </button>
          <button type="button" class="ui-btn ui-btn--accent" (click)="cerrar.emit()">
            <ui-icon name="check" [size]="16" />
            Ver resultados
          </button>
        </footer>
      </aside>
    </div>
  `,
})
export class PanelFiltros {
  readonly abierto = input(false);
  readonly titulo = input('Filtros');
  /** Nº de filtros aplicados (cabecera y estado del botón «Limpiar»). */
  readonly activos = input(0);
  readonly cerrar = output<void>();
  readonly limpiar = output<void>();

  @HostListener('document:keydown.escape')
  alEscape(): void {
    if (this.abierto()) {
      this.cerrar.emit();
    }
  }
}
