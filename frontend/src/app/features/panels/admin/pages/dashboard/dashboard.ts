import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AnaliticaAdmin } from '../../data/analitica.model';
import { AdminAnaliticaApi } from '../../data/admin-analitica.api';
import { Auth } from '../../../../../core/services/auth';
import { saludoSegunHora } from '../../../../../core/utils/fecha.util';
import { RecursoRemoto } from '../../../../../core/utils/listado-remoto';
import { BarChart, BarraDato } from '../../../../../shared/ui/charts/bar-chart';
import { SERIES, entero, porcentaje, variacion } from '../../../../../shared/ui/charts/formato';
import { GaugeChart } from '../../../../../shared/ui/charts/gauge-chart';
import { Kpi } from '../../../../../shared/ui/charts/kpi';
import { LineChart, SerieLinea } from '../../../../../shared/ui/charts/line-chart';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';

const NOMBRE_PANEL: Record<string, string> = {
  PANEL_ADMIN: 'Administración',
  PANEL_BITACORA: 'Bitácora',
  PANEL_SIMULACION: 'Simulación',
  PANEL_MALLA: 'Malla',
  PANEL_PICKING: 'Picking',
};

const COLOR_PANEL: Record<string, string> = {
  PANEL_ADMIN: 'var(--violet)',
  PANEL_BITACORA: 'var(--brand)',
  PANEL_SIMULACION: 'var(--cyan)',
  PANEL_MALLA: 'var(--success)',
  PANEL_PICKING: 'var(--warning)',
};

/** Meta del índice de gobierno: desde aquí el acceso se considera sano. */
const META_GOBIERNO = 0.9;

type Nivel = 'ok' | 'aviso' | 'riesgo';

const COLOR_NIVEL: Record<Nivel, string> = {
  ok: 'var(--success)',
  aviso: 'var(--warning)',
  riesgo: 'var(--danger)',
};

interface Alerta {
  titulo: string;
  valor: number;
  texto: string;
  nivel: Nivel;
  ruta: string;
}

/**
 * Dashboard ejecutivo de Administración: si el acceso está sano (índice de
 * gobierno por rol), qué riesgos hay que atender (alertas de control), cómo
 * crece la base de usuarios y hasta dónde llega cada panel y cada rol.
 */
@Component({
  selector: 'app-admin-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BarChart, DatePipe, EmptyState, GaugeChart, Icon, Kpi, LineChart, RouterLink],
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
  /** Hora en que llegaron los datos mostrados. */
  readonly corte = computed(() => (this.datos() ? new Date() : null));

  readonly kpis = computed(() => this.datos()?.kpis ?? null);

  /** Activos gobernados solo por su rol (sin excepciones), en fracción. */
  readonly gobierno = computed(() => {
    const k = this.kpis();
    const activos = k?.usuariosActivos ?? 0;
    const fraccion = activos ? k!.usuariosSoloRol / activos : 0;
    const nivel: Nivel = fraccion >= META_GOBIERNO ? 'ok' : fraccion >= 0.7 ? 'aviso' : 'riesgo';
    return {
      fraccion,
      pct: Math.round(fraccion * 100),
      nivel,
      color: COLOR_NIVEL[nivel],
      hay: activos > 0,
      // Partición de los activos: solo rol / rol + excepción / sin rol.
      desglose: k
        ? [
            { etiqueta: 'Solo por rol', valor: k.usuariosSoloRol, color: 'var(--success)' },
            {
              etiqueta: 'Rol + excepción',
              valor: Math.max(0, activos - k.usuariosSoloRol - k.usuariosSinRol),
              color: 'var(--warning)',
            },
            { etiqueta: 'Sin rol', valor: k.usuariosSinRol, color: 'var(--danger)' },
          ]
        : [],
    };
  });

  /** Lectura en una línea de lo más importante (lo primero que lee un gerente). */
  readonly lecturas = computed(() => {
    const k = this.kpis();
    if (!k || !k.usuarios) {
      return [];
    }
    const g = this.gobierno();
    const riesgos = this.alertas().filter((a) => a.nivel !== 'ok');
    return [
      {
        kicker: 'Gobierno de accesos',
        icono: 'shieldCheck',
        color: g.color,
        texto: `<strong>${porcentaje(g.fraccion)}</strong> de las cuentas activas se rige solo por su rol (meta ${porcentaje(META_GOBIERNO)}).`,
      },
      {
        kicker: 'Riesgos',
        icono: riesgos.length ? 'alertTriangle' : 'check',
        color: riesgos.length ? COLOR_NIVEL[riesgos.some((r) => r.nivel === 'riesgo') ? 'riesgo' : 'aviso'] : COLOR_NIVEL.ok,
        texto: riesgos.length
          ? `<strong>${riesgos.length}</strong> ${riesgos.length === 1 ? 'alerta de control abierta' : 'alertas de control abiertas'}: ${riesgos
              .slice(0, 2)
              .map((r) => r.titulo.toLowerCase())
              .join(' y ')}.`
          : 'Sin alertas de control: roles, permisos y cuentas están en orden.',
      },
      {
        kicker: 'Crecimiento',
        icono: 'tendencia',
        color: 'var(--accent)',
        texto: `<strong>${entero(k.altas30)}</strong> ${k.altas30 === 1 ? 'cuenta nueva' : 'cuentas nuevas'} en 30 días (${entero(k.altasPrevias30)} en los 30 anteriores).`,
      },
    ];
  });

  readonly tarjetas = computed(() => {
    const k = this.kpis();
    const g = this.gobierno();
    const excepciones = k ? k.overridesGrant + k.overridesDeny : 0;
    return [
      {
        etiqueta: 'Cuentas activas',
        valor: k ? `${entero(k.usuariosActivos)} de ${entero(k.usuarios)}` : '—',
        icono: 'usuarios',
        color: 'var(--accent)',
        detalle: k ? `${porcentaje(k.usuarios ? k.usuariosActivos / k.usuarios : 0)} habilitadas` : '',
        variacion: null,
        subirEsMalo: false,
      },
      {
        etiqueta: 'Conectados ahora',
        valor: k ? entero(k.conectados) : '—',
        icono: 'activity',
        color: 'var(--cyan)',
        detalle: k
          ? `${porcentaje(k.usuariosActivos ? k.conectados / k.usuariosActivos : 0)} de las activas con sesión`
          : '',
        variacion: null,
        subirEsMalo: false,
      },
      {
        etiqueta: 'Altas en 30 días',
        valor: k ? entero(k.altas30) : '—',
        icono: 'tendencia',
        color: 'var(--violet)',
        detalle: 'cuentas nuevas',
        variacion: k ? variacion(k.altas30, k.altasPrevias30) : null,
        subirEsMalo: false,
      },
      {
        etiqueta: 'Gobierno por rol',
        valor: k ? porcentaje(g.fraccion) : '—',
        icono: 'shieldCheck',
        color: g.color,
        detalle: k ? `${entero(k.usuariosSoloRol)} activas sin excepciones` : '',
        variacion: null,
        subirEsMalo: false,
      },
      {
        etiqueta: 'Usuarios sin rol',
        valor: k ? entero(k.usuariosSinRol) : '—',
        icono: 'alertTriangle',
        color: k && k.usuariosSinRol > 0 ? 'var(--warning)' : 'var(--success)',
        detalle: k && k.usuariosSinRol > 0 ? 'Acceso solo por excepción' : 'Todos tienen un rol',
        variacion: null,
        subirEsMalo: true,
      },
      {
        etiqueta: 'Excepciones al RBAC',
        valor: k ? entero(excepciones) : '—',
        icono: 'key',
        color: excepciones > 0 ? 'var(--brand)' : 'var(--success)',
        detalle: k ? `${entero(k.overridesGrant)} concedidas · ${entero(k.overridesDeny)} denegadas` : '',
        variacion: null,
        subirEsMalo: true,
      },
    ];
  });

  /** Controles de higiene del acceso: en verde los que están en orden. */
  readonly alertas = computed<Alerta[]>(() => {
    const k = this.kpis();
    if (!k) {
      return [];
    }
    const inactivas = k.usuarios - k.usuariosActivos;
    const alerta = (
      titulo: string,
      valor: number,
      nivel: Nivel,
      texto: string,
      ok: string,
      ruta: string,
    ): Alerta => ({ titulo, valor, nivel: valor > 0 ? nivel : 'ok', texto: valor > 0 ? texto : ok, ruta });
    return [
      alerta('Cuentas sin rol', k.usuariosSinRol, 'riesgo',
        'Activas que entran solo por permisos individuales o no pueden hacer nada.',
        'Toda cuenta activa tiene al menos un rol.', 'usuarios'),
      alerta('Cuentas con excepciones', k.usuariosConExcepcion, 'aviso',
        'Activas con permisos concedidos o denegados fuera de su rol: revisar si siguen vigentes.',
        'Nadie tiene permisos fuera de su rol.', 'usuarios'),
      alerta('Roles sin usuarios', k.rolesSinUsuarios, 'aviso',
        'Roles activos que nadie usa: candidatos a desactivar.',
        'Todos los roles activos están en uso.', 'roles'),
      alerta('Roles sin permisos', k.rolesSinPermisos, 'riesgo',
        'Roles activos que no conceden nada: quien solo tiene ese rol no puede trabajar.',
        'Todos los roles conceden permisos.', 'roles'),
      alerta('Cuentas inactivas', inactivas, 'aviso',
        'Cuentas bloqueadas que siguen registradas: eliminar las que ya no volverán.',
        'No hay cuentas bloqueadas.', 'usuarios'),
      alerta('Permisos inactivos', k.permisos - k.permisosActivos, 'aviso',
        'Permisos apagados en el catálogo: no los concede ningún rol.',
        'Todo el catálogo de permisos está activo.', 'permisos'),
    ];
  });

  readonly alertasAbiertas = computed(() => this.alertas().filter((a) => a.nivel !== 'ok').length);

  /** Crecimiento de los últimos 12 meses: total registrado y altas del mes. */
  readonly crecimiento = computed(() => {
    const meses = this.datos()?.crecimiento ?? [];
    const series: SerieLinea[] = [
      { nombre: 'Cuentas registradas', color: 'var(--accent)', valores: meses.map((m) => m.total) },
      { nombre: 'Altas del mes', color: 'var(--chart-2)', valores: meses.map((m) => m.altas) },
    ];
    return { etiquetas: meses.map((m) => m.periodo), series, hay: meses.some((m) => m.total > 0) };
  });

  /** Usuarios activos con acceso a cada panel, en % del total de activos. */
  readonly barrasCobertura = computed<BarraDato[]>(() => {
    const activos = this.kpis()?.usuariosActivos ?? 0;
    const porPanel = new Map((this.datos()?.coberturaPaneles ?? []).map((c) => [c.etiqueta, c.valor]));
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

  /** Usuarios activos por rol (los 8 con más usuarios). */
  readonly barrasRoles = computed<BarraDato[]>(() => {
    const activos = this.kpis()?.usuariosActivos ?? 0;
    return (this.datos()?.usuariosPorRol ?? []).slice(0, 8).map((r, i) => ({
      etiqueta: r.etiqueta,
      valor: r.valor,
      color: SERIES[i % SERIES.length],
      detalle: r.valor ? `${porcentaje(activos ? r.valor / activos : 0)} de los activos` : 'Sin usuarios',
    }));
  });

  readonly colorNivel = COLOR_NIVEL;

  constructor() {
    this.cargar();
  }

  cargar(): void {
    this.recurso.recargar();
  }
}
