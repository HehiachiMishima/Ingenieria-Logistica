import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PERMISOS } from '../../../../../../core/config/permisos';
import { Auth } from '../../../../../../core/services/auth';
import { RecursoRemoto } from '../../../../../../core/utils/listado-remoto';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { ObservabilidadApi } from '../../../data/observabilidad.api';
import { Periodo } from '../../../data/observabilidad.model';
import { OpcionPeriodo, SelectorPeriodo } from '../../../ui/selector-periodo';
import { ApisVigilar } from './graficos/apis-vigilar';
import { DemandaRendimiento } from './graficos/demanda-rendimiento';
import { PulsoPlataforma } from './graficos/pulso-plataforma';
import { TableroMetas } from './graficos/tablero-metas';

const PERIODOS: OpcionPeriodo<Periodo>[] = [
  { valor: 7, etiqueta: '7 días' },
  { valor: 30, etiqueta: '30 días' },
];

/**
 * Monitoreo y actividad en el Dashboard: cuatro vistas ejecutivas sobre las mismas fuentes
 * (resumen de Monitoreo, resumen de Actividad y APIs) y el mismo período:
 *  1. Pulso de la plataforma: disponibilidad y tres franjas alineadas por día.
 *  2. ¿Cumplimos las metas?: cada indicador contra su meta, con su tendencia.
 *  3. ¿El sistema aguanta cuando más se usa?: demanda por día y franja con las franjas lentas.
 *  4. APIs a vigilar: las más lentas contra la meta, con su semáforo.
 * Cada fuente se pide solo con su permiso de consulta.
 */
@Component({
  selector: 'app-tablero-operacion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ApisVigilar, DemandaRendimiento, Icon, PulsoPlataforma, RouterLink, SelectorPeriodo, TableroMetas],
  templateUrl: './tablero-operacion.html',
  styleUrl: './tablero-operacion.scss',
})
export class TableroOperacion {
  private readonly api = inject(ObservabilidadApi);
  private readonly auth = inject(Auth);

  /** Cuentas activas (del dashboard), para leer la adopción. */
  readonly cuentasActivas = input<number | null>(null);

  readonly periodos = PERIODOS;
  readonly dias = signal<Periodo>(30);

  readonly verMonitoreo = this.auth.tienePermiso(PERMISOS.VER_MONITOREO);
  readonly verActividad = this.auth.tienePermiso(PERMISOS.VER_ACTIVIDAD);
  readonly hayAlgo = this.verMonitoreo || this.verActividad;

  private readonly monitoreo = this.verMonitoreo ? new RecursoRemoto(() => this.api.resumenMonitoreo(this.dias())) : null;
  private readonly apis = this.verMonitoreo ? new RecursoRemoto(() => this.api.endpoints(this.dias())) : null;
  private readonly actividad = this.verActividad ? new RecursoRemoto(() => this.api.resumenActividad(this.dias(), '')) : null;

  readonly m = computed(() => this.monitoreo?.valor() ?? null);
  readonly a = computed(() => this.actividad?.valor() ?? null);
  readonly endpoints = computed(() => this.apis?.valor() ?? null);

  readonly cargandoM = computed(() => !!this.monitoreo?.cargando());
  readonly cargandoA = computed(() => !!this.actividad?.cargando());
  readonly cargandoApis = computed(() => !!this.apis?.cargando());
  readonly errorM = computed(() => this.monitoreo?.error() ?? null);
  readonly errorA = computed(() => this.actividad?.error() ?? null);
  readonly errorApis = computed(() => this.apis?.error() ?? null);

  /** Primera carga de las fuentes del pulso y las metas: esqueleto del mismo alto (sin saltos). */
  readonly esperandoResumen = computed(
    () => (this.verMonitoreo && !this.m() && !this.errorM()) || (this.verActividad && !this.a() && !this.errorA()),
  );

  /** Mensaje si falló alguna fuente (la otra se sigue mostrando). */
  readonly errores = computed(() =>
    [
      this.errorM() ? `Monitoreo: ${this.errorM()}` : null,
      this.errorA() ? `Actividad: ${this.errorA()}` : null,
    ].filter((e): e is string => e !== null),
  );

  constructor() {
    this.cargar();
  }

  cargar(): void {
    this.monitoreo?.recargar();
    this.apis?.recargar();
    this.actividad?.recargar();
  }

  cambiarPeriodo(dias: Periodo): void {
    if (dias !== this.dias()) {
      this.dias.set(dias);
      this.cargar();
    }
  }
}
