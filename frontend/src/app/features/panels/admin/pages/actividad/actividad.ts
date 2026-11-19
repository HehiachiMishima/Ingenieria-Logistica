import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { ChipFiltro, FiltrosActivos } from '../../../../../shared/ui/panel-filtros/filtros-activos';
import { PanelFiltros } from '../../../../../shared/ui/panel-filtros/panel-filtros';
import { ObservabilidadApi } from '../../data/observabilidad.api';
import { Evento, Periodo } from '../../data/observabilidad.model';
import { ListaCursor } from '../../state/lista-cursor';
import { PERIODOS_MONITOREO, badgeEstado, badgeMetodo, inicialesDe } from '../../ui/observabilidad.util';
import { PaginadorCursor } from '../../ui/paginador-cursor';
import { SelectorPeriodo } from '../../ui/selector-periodo';

/**
 * Actividad: peticiones de los usuarios, filtrables por período y usuario.
 * Los indicadores y gráficos de uso viven en el Dashboard.
 */
@Component({
  selector: 'app-admin-actividad',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, DecimalPipe, EmptyState, FiltrosActivos, Icon, PaginadorCursor, PanelFiltros, SelectorPeriodo],
  templateUrl: './actividad.html',
  styleUrl: './actividad.scss',
})
export class Actividad {
  private readonly api = inject(ObservabilidadApi);

  readonly periodos = PERIODOS_MONITOREO;
  readonly badgeEstado = badgeEstado;
  readonly badgeMetodo = badgeMetodo;
  readonly iniciales = inicialesDe;

  readonly dias = signal<Periodo>(7);
  readonly usuario = signal('');
  readonly filtrosAbierto = signal(false);

  readonly lista = new ListaCursor<Evento>((antes) => this.api.actividad(this.dias(), this.usuario(), antes));
  readonly corte = computed(() => (this.lista.cargada() ? new Date() : null));

  readonly chips = computed<ChipFiltro[]>(() =>
    this.usuario() ? [{ clave: 'usuario', etiqueta: 'Usuario', valor: this.usuario(), icono: 'user' }] : [],
  );
  readonly filtrosActivos = computed(() => this.chips().length);

  constructor() {
    this.lista.reiniciar();
  }

  cambiarPeriodo(dias: Periodo): void {
    if (dias !== this.dias()) {
      this.dias.set(dias);
      this.lista.reiniciar();
    }
  }

  aplicarUsuario(valor: string): void {
    const usuario = valor.trim();
    if (usuario !== this.usuario()) {
      this.usuario.set(usuario);
      this.lista.reiniciar();
    }
  }

  quitarFiltro(clave: string): void {
    if (clave === 'usuario') {
      this.aplicarUsuario('');
    }
  }
}
