import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { AnaliticaAdmin } from '../../data/analitica.model';
import { AdminAnaliticaApi } from '../../data/admin-analitica.api';
import { Auth } from '../../../../../core/services/auth';
import { saludoSegunHora } from '../../../../../core/utils/fecha.util';
import { RecursoRemoto } from '../../../../../core/utils/listado-remoto';
import { BarChart, BarraDato } from '../../../../../shared/ui/charts/bar-chart';
import { entero, porcentaje } from '../../../../../shared/ui/charts/formato';
import { GaugeChart } from '../../../../../shared/ui/charts/gauge-chart';
import { Kpi } from '../../../../../shared/ui/charts/kpi';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';

const NOMBRE_PANEL: Record<string, string> = {
  PANEL_ADMIN: 'Administración',
  PANEL_BITACORA: 'Bitácora',
  PANEL_SIMULACION: 'Simulación',
  PANEL_MALLA: 'Malla',
};

const COLOR_PANEL: Record<string, string> = {
  PANEL_ADMIN: 'var(--violet)',
  PANEL_BITACORA: 'var(--brand)',
  PANEL_SIMULACION: 'var(--cyan)',
  PANEL_MALLA: 'var(--success)',
};

/**
 * Dashboard ejecutivo de Administración: lo mínimo para saber si el acceso
 * está sano. Tres indicadores (cuentas activas, usuarios sin rol,
 * excepciones) y dos cuadros: cuentas habilitadas y acceso por panel.
 */
@Component({
  selector: 'app-admin-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BarChart, EmptyState, GaugeChart, Icon, Kpi],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {
  private readonly auth = inject(Auth);
  private readonly api = inject(AdminAnaliticaApi);

  readonly usuario = this.auth.usuario;

  private readonly recurso = new RecursoRemoto<AnaliticaAdmin>(() => this.api.analitica());
  readonly cargando = this.recurso.cargando;
  readonly error = this.recurso.error;
  readonly datos = this.recurso.valor;

  readonly saludo = saludoSegunHora();

  readonly kpis = computed(() => this.datos()?.kpis ?? null);

  readonly tarjetas = computed(() => {
    const k = this.kpis();
    const excepciones = k ? k.overridesGrant + k.overridesDeny : 0;
    return [
      {
        etiqueta: 'Cuentas activas',
        valor: k ? `${entero(k.usuariosActivos)} de ${entero(k.usuarios)}` : '—',
        icono: 'usuarios',
        color: 'var(--accent)',
        detalle: k
          ? `${porcentaje(k.usuarios ? k.usuariosActivos / k.usuarios : 0)} pueden iniciar sesión`
          : '',
      },
      {
        etiqueta: 'Usuarios sin rol',
        valor: k ? entero(k.usuariosSinRol) : '—',
        icono: 'alertTriangle',
        color: k && k.usuariosSinRol > 0 ? 'var(--warning)' : 'var(--success)',
        detalle:
          k && k.usuariosSinRol > 0 ? 'Revisar: acceso solo por excepción' : 'Todos tienen un rol',
      },
      {
        etiqueta: 'Excepciones al RBAC',
        valor: k ? entero(excepciones) : '—',
        icono: 'key',
        color: excepciones > 0 ? 'var(--brand)' : 'var(--success)',
        detalle: k
          ? `${entero(k.overridesGrant)} concedidas · ${entero(k.overridesDeny)} denegadas`
          : '',
      },
    ];
  });

  /** % de cuentas habilitadas (gauge) y su desglose. */
  readonly estadoCuentas = computed(() => {
    const k = this.kpis();
    const total = k?.usuarios ?? 0;
    const activos = k?.usuariosActivos ?? 0;
    return {
      pct: total ? Math.round((activos / total) * 100) : 0,
      activos: entero(activos),
      inactivos: entero(total - activos),
      hay: total > 0,
    };
  });

  /** Usuarios activos con acceso a cada panel, en % del total de activos. */
  readonly barrasCobertura = computed<BarraDato[]>(() => {
    const activos = this.kpis()?.usuariosActivos ?? 0;
    const porPanel = new Map(
      (this.datos()?.coberturaPaneles ?? []).map((c) => [c.etiqueta, c.valor]),
    );
    return Object.keys(NOMBRE_PANEL).map((clave) => {
      const valor = porPanel.get(clave) ?? 0;
      return {
        etiqueta: NOMBRE_PANEL[clave],
        valor,
        color: COLOR_PANEL[clave],
        detalle: `${porcentaje(activos ? valor / activos : 0)} de los activos`,
      };
    });
  });

  constructor() {
    this.cargar();
  }

  cargar(): void {
    this.recurso.recargar();
  }
}
