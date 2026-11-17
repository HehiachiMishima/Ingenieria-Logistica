import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PERMISOS } from '../../../../../core/config/permisos';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { DashboardSantaStore } from '../../data/dashboard.store';
import { duracion, porciento } from '../../santa-calculo';
import { colorCluster, entero, etiquetaEstadoRegistro } from '../../santa-formato';
import { AvanceClusters } from '../../ui/avance-clusters/avance-clusters';
import { EnviosTiempos } from '../../ui/envios-tiempos/envios-tiempos';

/**
 * Dashboard de Santa Anita: cuánto trae cada envío (% del total), cuánto tarda
 * con los operarios (que se pueden simular), cuántos operarios hacen falta para
 * la ventana y cómo avanza cada cluster en cada envío. Los parámetros viven en
 * la URL (?registro=&ventana=) para poder compartir la vista.
 */
@Component({
  selector: 'app-santa-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon, EmptyState, HasPermiso, EnviosTiempos, AvanceClusters],
  providers: [DashboardSantaStore],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class SantaDashboard {
  readonly store = inject(DashboardSantaStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly permisos = PERMISOS;
  readonly entero = entero;
  readonly duracion = duracion;
  readonly porciento = porciento;
  readonly colorCluster = colorCluster;
  readonly etiquetaEstadoRegistro = etiquetaEstadoRegistro;

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    const ventana = Number(params.get('ventana'));
    if (Number.isFinite(ventana) && ventana > 0) {
      this.store.fijarVentana(ventana);
    }
    const pedido = Number(params.get('registro'));
    this.store.iniciar(Number.isFinite(pedido) && pedido > 0 ? pedido : null);
  }

  elegirRegistro(valor: string): void {
    this.store.seleccionar(Number(valor));
    this.actualizarUrl();
  }

  fijarVentana(valor: string): void {
    this.store.fijarVentana(Number(valor));
    this.actualizarUrl();
  }

  fijarOperarios(valor: string): void {
    this.store.fijarOperarios(Number(valor));
  }

  private actualizarUrl(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { registro: this.store.registroId(), ventana: this.store.ventanaMin() },
      replaceUrl: true,
    });
  }
}
