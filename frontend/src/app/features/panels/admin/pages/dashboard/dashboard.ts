import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, viewChild } from '@angular/core';
import { AnaliticaAdmin } from '../../data/analitica.model';
import { AdminAnaliticaApi } from '../../data/admin-analitica.api';
import { Auth } from '../../../../../core/services/auth';
import { saludoSegunHora } from '../../../../../core/utils/fecha.util';
import { RecursoRemoto } from '../../../../../core/utils/listado-remoto';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { ResumenAccesos } from './resumen-accesos/resumen-accesos';
import { TableroOperacion } from './tablero-operacion/tablero-operacion';

/**
 * Dashboard de Control de accesos: arriba Usuarios, Roles y Permisos (una tarjeta por módulo)
 * y debajo Monitoreo y actividad (pulso de la plataforma, metas, demanda y APIs a vigilar).
 */
@Component({
  selector: 'app-admin-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, EmptyState, Icon, ResumenAccesos, TableroOperacion],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {
  private readonly auth = inject(Auth);
  private readonly api = inject(AdminAnaliticaApi);
  private readonly tablero = viewChild(TableroOperacion);

  readonly usuario = this.auth.usuario;

  private readonly recurso = new RecursoRemoto<AnaliticaAdmin>(() => this.api.analitica());
  readonly cargando = this.recurso.cargando;
  readonly error = this.recurso.error;
  readonly datos = this.recurso.valor;

  readonly saludo = saludoSegunHora();
  /** Hora en que llegaron los datos mostrados. */
  readonly corte = computed(() => (this.datos() ? new Date() : null));

  readonly kpis = computed(() => this.datos()?.kpis ?? null);

  constructor() {
    this.cargar();
  }

  /** Recarga los accesos y, si ya está en pantalla, también Monitoreo y actividad. */
  cargar(): void {
    this.recurso.recargar();
    this.tablero()?.cargar();
  }
}
