import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Paginador } from '../../../../../shared/ui/paginador/paginador';
import { ChipFiltro, FiltrosActivos } from '../../../../../shared/ui/panel-filtros/filtros-activos';
import { PanelFiltros } from '../../../../../shared/ui/panel-filtros/panel-filtros';
import { ObservabilidadApi } from '../../data/observabilidad.api';
import { Evento, FiltrosAuditoria, Periodo, ResultadoAuditoria } from '../../data/observabilidad.model';
import { ListadoPaginado } from '../../state/listado-paginado';
import { BADGE_RESULTADO, PERIODOS_AUDITORIA, badgeEstado, inicialesDe } from '../../ui/observabilidad.util';
import { SelectorPeriodo } from '../../ui/selector-periodo';

type ClaveFiltro = 'usuario' | 'registro' | 'resultado';

/**
 * Auditoría: registro inmutable de cambios, filtrable por período, usuario, registro
 * y resultado. Los indicadores y gráficos de control viven en el Dashboard.
 */
@Component({
  selector: 'app-admin-auditoria',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, DecimalPipe, EmptyState, FiltrosActivos, Icon, Paginador, PanelFiltros, SelectorPeriodo],
  templateUrl: './auditoria.html',
  styleUrl: './auditoria.scss',
})
export class Auditoria {
  private readonly api = inject(ObservabilidadApi);

  readonly periodos = PERIODOS_AUDITORIA;
  readonly resultados: (ResultadoAuditoria | '')[] = ['', 'Éxito', 'Fallo', 'Denegado'];
  readonly badgeResultado = BADGE_RESULTADO;
  readonly badgeEstado = badgeEstado;
  readonly iniciales = inicialesDe;

  readonly filtros = signal<FiltrosAuditoria>({ dias: 7, usuario: '', registro: '', resultado: '' });
  readonly filtrosAbierto = signal(false);

  readonly lista = new ListadoPaginado<Evento>((pagina, tamano) => this.api.auditoria(this.filtros(), pagina, tamano));
  readonly corte = computed(() => (this.lista.listado.listo() ? new Date() : null));

  readonly chips = computed<ChipFiltro[]>(() => {
    const f = this.filtros();
    const chips: ChipFiltro[] = [];
    if (f.usuario) chips.push({ clave: 'usuario', etiqueta: 'Usuario', valor: f.usuario, icono: 'user' });
    if (f.registro) chips.push({ clave: 'registro', etiqueta: 'Registro', valor: f.registro, icono: 'key' });
    if (f.resultado) chips.push({ clave: 'resultado', etiqueta: 'Resultado', valor: f.resultado, icono: 'shieldCheck' });
    return chips;
  });
  readonly filtrosActivos = computed(() => this.chips().length);

  constructor() {
    this.lista.reiniciar();
  }

  cambiarPeriodo(dias: Periodo): void {
    this.aplicar({ dias });
  }

  aplicarTexto(clave: 'usuario' | 'registro', valor: string): void {
    this.aplicar({ [clave]: valor.trim() });
  }

  aplicarResultado(resultado: ResultadoAuditoria | ''): void {
    this.aplicar({ resultado });
  }

  quitarFiltro(clave: string): void {
    this.aplicar({ [clave as ClaveFiltro]: '' });
  }

  limpiar(): void {
    this.aplicar({ usuario: '', registro: '', resultado: '' });
  }

  private aplicar(cambio: Partial<FiltrosAuditoria>): void {
    const actual = this.filtros();
    const nuevo = { ...actual, ...cambio };
    if (JSON.stringify(nuevo) !== JSON.stringify(actual)) {
      this.filtros.set(nuevo);
      this.lista.reiniciar();
    }
  }
}
