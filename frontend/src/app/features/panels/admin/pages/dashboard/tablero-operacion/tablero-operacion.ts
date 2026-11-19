import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PERMISOS } from '../../../../../../core/config/permisos';
import { Auth } from '../../../../../../core/services/auth';
import { RecursoRemoto } from '../../../../../../core/utils/listado-remoto';
import { compacto, entero, porcentaje, variacion } from '../../../../../../shared/ui/charts/formato';
import { CeldaCalor, Heatmap } from '../../../../../../shared/ui/charts/heatmap';
import { LineChart, SerieLinea } from '../../../../../../shared/ui/charts/line-chart';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { ObservabilidadApi } from '../../../data/observabilidad.api';
import { Periodo } from '../../../data/observabilidad.model';
import { MedidorMeta } from '../../../ui/medidor-meta';
import {
  BADGE_NIVEL,
  DIAS_SEMANA,
  FRANJAS,
  HORAS_POR_FRANJA,
  META_DISPONIBILIDAD,
  META_P95_MS,
  META_SIN_INCIDENTE,
  Nivel,
  TOLERANCIA_DENEGADOS_SEMANA,
  abreviar,
  enHorarioLaboral,
  etiquetaFranja,
} from '../../../ui/observabilidad.util';
import { NOMBRE_PANEL } from '../../../ui/paneles-admin';
import { OpcionPeriodo, SelectorPeriodo } from '../../../ui/selector-periodo';
import { Sparkline } from '../../../ui/sparkline';

const PERIODOS: OpcionPeriodo<Periodo>[] = [
  { valor: 7, etiqueta: '7 días' },
  { valor: 30, etiqueta: '30 días' },
];

const TEXTO_NIVEL: Record<Nivel, string> = { ok: 'En meta', aviso: 'Atención', riesgo: 'Crítico' };
const PEOR: Nivel[] = ['riesgo', 'aviso', 'ok'];

function peor(...niveles: Nivel[]): Nivel {
  return PEOR.find((n) => niveles.includes(n)) ?? 'ok';
}

/** Escala de un medidor: valor entre `desde` y `hasta` llevado a 0–1. */
function escala(valor: number, desde: number, hasta: number): number {
  return Math.min(1, Math.max(0, (valor - desde) / (hasta - desde)));
}

/** Celdas día × franja de 4 h en orden natural, con 0 donde no hubo datos. */
function matrizDiaFranja(valorDe: (dia: number, desde: number) => number): CeldaCalor[] {
  return DIAS_SEMANA.flatMap((fila, i) =>
    FRANJAS.map((desde) => ({ fila, columna: etiquetaFranja(desde), valor: valorDe(i + 1, desde) })),
  );
}

/** Suma por día y franja a partir de filas por día y hora. */
function sumarPorFranja<T>(filas: T[], dia: (f: T) => number, hora: (f: T) => number, valor: (f: T) => number) {
  const mapa = new Map<string, number>();
  for (const f of filas) {
    const clave = `${dia(f)}|${Math.floor(hora(f) / HORAS_POR_FRANJA) * HORAS_POR_FRANJA}`;
    mapa.set(clave, (mapa.get(clave) ?? 0) + valor(f));
  }
  return (d: number, desde: number) => mapa.get(`${d}|${desde}`) ?? 0;
}

const LABORALES = new Set(
  DIAS_SEMANA.flatMap((fila, i) =>
    FRANJAS.filter((desde) => enHorarioLaboral(i + 1, desde)).map((desde) => `${fila}|${etiquetaFranja(desde)}`),
  ),
);

/**
 * Operación y control en el Dashboard: un cuadro resumen por módulo (Monitoreo y
 * Auditoría con medidores contra meta, Actividad con scorecard) y dos cuadros por
 * pregunta de cada uno. Cada módulo aparece solo con su permiso de consulta.
 */
@Component({
  selector: 'app-tablero-operacion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Heatmap, Icon, LineChart, MedidorMeta, RouterLink, SelectorPeriodo, Sparkline],
  templateUrl: './tablero-operacion.html',
  styleUrl: './tablero-operacion.scss',
})
export class TableroOperacion {
  private readonly api = inject(ObservabilidadApi);
  private readonly auth = inject(Auth);

  /** Cuentas activas (del dashboard) para la adopción. */
  readonly cuentasActivas = input<number | null>(null);

  readonly periodos = PERIODOS;
  readonly badgeNivel = BADGE_NIVEL;
  readonly textoNivel = TEXTO_NIVEL;
  readonly dias = signal<Periodo>(7);

  readonly verMonitoreo = this.auth.tienePermiso(PERMISOS.VER_MONITOREO);
  readonly verActividad = this.auth.tienePermiso(PERMISOS.VER_ACTIVIDAD);
  readonly verAuditoria = this.auth.tienePermiso(PERMISOS.VER_AUDITORIA);
  readonly hayAlgo = this.verMonitoreo || this.verActividad || this.verAuditoria;

  private readonly monitoreo = this.verMonitoreo ? new RecursoRemoto(() => this.api.resumenMonitoreo(this.dias())) : null;
  private readonly actividad = this.verActividad ? new RecursoRemoto(() => this.api.resumenActividad(this.dias(), '')) : null;
  private readonly auditoria = this.verAuditoria
    ? new RecursoRemoto(() => this.api.resumenAuditoria({ dias: this.dias(), usuario: '', registro: '', resultado: '' }))
    : null;

  readonly m = computed(() => this.monitoreo?.valor() ?? null);
  readonly a = computed(() => this.actividad?.valor() ?? null);
  readonly u = computed(() => this.auditoria?.valor() ?? null);
  readonly cargando = computed(
    () => !!(this.monitoreo?.cargando() || this.actividad?.cargando() || this.auditoria?.cargando()),
  );
  readonly errorM = computed(() => this.monitoreo?.error() ?? null);
  readonly errorA = computed(() => this.actividad?.error() ?? null);
  readonly errorU = computed(() => this.auditoria?.error() ?? null);

  // --- Monitoreo: medidores contra meta -------------------------------------------------

  readonly tarjetaMonitoreo = computed(() => {
    const r = this.m();
    if (!r || !r.peticiones) {
      return null;
    }
    const disponibilidad = 1 - r.erroresServidor / r.peticiones;
    const nivelDisp: Nivel = disponibilidad >= META_DISPONIBILIDAD ? 'ok' : disponibilidad >= 0.99 ? 'aviso' : 'riesgo';
    const nivelP95: Nivel = r.p95 <= META_P95_MS ? 'ok' : r.p95 <= META_P95_MS * 1.5 ? 'aviso' : 'riesgo';
    return {
      nivel: peor(nivelDisp, nivelP95),
      disponibilidad: {
        valor: porcentaje(disponibilidad),
        // Escala 98–100 %: en 0–100 % la meta y el valor se verían pegados al borde.
        fraccion: escala(disponibilidad, 0.98, 1),
        meta: escala(META_DISPONIBILIDAD, 0.98, 1),
        nivel: nivelDisp,
        nota: `Meta ${porcentaje(META_DISPONIBILIDAD)} sin errores 5xx · escala 98–100 %`,
      },
      p95: {
        valor: `${entero(r.p95)} ms`,
        fraccion: escala(r.p95, 0, META_P95_MS * 2),
        meta: 0.5,
        nivel: nivelP95,
        nota: `Meta ${entero(META_P95_MS)} ms · ${this.cambio(variacion(r.p95, r.p95Previo), true)}`,
      },
      tasaError: porcentaje(r.errores / r.peticiones),
      peticiones: compacto(r.peticiones),
    };
  });

  readonly calorMonitoreo = computed(() => {
    const celdas = this.m()?.calor ?? [];
    const p95 = new Map(celdas.map((c) => [`${c.dia}|${c.desde}`, c.p95]));
    return matrizDiaFranja((d, desde) => p95.get(`${d}|${desde}`) ?? 0);
  });
  readonly marcarLento = (_f: string, _c: string, valor: number) => valor > META_P95_MS;
  readonly textoLento = `Sobre la meta de ${META_P95_MS} ms`;

  readonly lecturaCalorMonitoreo = computed(() => {
    const lentas = this.calorMonitoreo().filter((c) => c.valor > META_P95_MS);
    if (!this.calorMonitoreo().some((c) => c.valor > 0)) {
      return '';
    }
    if (!lentas.length) {
      return 'Todas las franjas con tráfico respondieron dentro de la meta.';
    }
    const peor = lentas.reduce((a, b) => (b.valor > a.valor ? b : a));
    return `${lentas.length} ${lentas.length === 1 ? 'franja' : 'franjas'} sobre la meta; la peor: ${peor.fila} ${peor.columna} (${entero(peor.valor)} ms).`;
  });

  readonly tendenciaMonitoreo = computed(() => {
    const serie = this.m()?.serie ?? [];
    const series: SerieLinea[] = [
      { nombre: 'Tiempo p95 (ms)', color: 'var(--accent)', valores: serie.map((p) => p.p95) },
      { nombre: `Meta (${META_P95_MS} ms)`, color: 'var(--text-3)', valores: serie.map(() => META_P95_MS) },
    ];
    const conTrafico = serie.filter((p) => p.peticiones > 0);
    const sobre = conTrafico.filter((p) => p.p95 > META_P95_MS).length;
    return {
      etiquetas: serie.map((p) => p.periodo),
      series,
      hay: conTrafico.length > 0,
      lectura: !conTrafico.length
        ? ''
        : sobre
          ? `${sobre} de ${conTrafico.length} días sobre la meta.`
          : `Los ${conTrafico.length} días con tráfico cumplieron la meta.`,
    };
  });

  // --- Actividad: scorecard ---------------------------------------------------------------

  readonly tarjetaActividad = computed(() => {
    const r = this.a();
    if (!r) {
      return null;
    }
    const cambio = variacion(r.usuarios, r.usuariosPrevios);
    const porHora = new Array<number>(24).fill(0);
    for (const c of r.calor) {
      porHora[c.hora] += c.acciones;
    }
    const pico = porHora.some((v) => v > 0) ? porHora.indexOf(Math.max(...porHora)) : null;
    const lider = r.paneles.find((p) => NOMBRE_PANEL[p.panel]);
    const cuentas = this.cuentasActivas();
    return {
      usuarios: entero(r.usuarios),
      adopcion: cuentas ? `${porcentaje(r.usuarios / cuentas)} de las cuentas activas` : 'con al menos una acción',
      cambio: cambio === null ? null : { texto: `${cambio >= 0 ? '▲' : '▼'} ${porcentaje(Math.abs(cambio))}`, sube: cambio >= 0 },
      serie: r.serie.map((p) => p.usuarios),
      serieTexto: r.serie.length
        ? `Usuarios activos por día: de ${entero(r.serie[0].usuarios)} a ${entero(r.serie.at(-1)!.usuarios)}`
        : '',
      pico: pico === null ? '—' : `${pico}–${pico + 1} h`,
      lider: lider ? NOMBRE_PANEL[lider.panel] : '—',
    };
  });

  readonly calorActividad = computed(() => {
    const valor = sumarPorFranja(this.a()?.calor ?? [], (c) => c.dia, (c) => c.hora, (c) => c.acciones);
    return matrizDiaFranja(valor);
  });

  readonly lecturaCalorActividad = computed(() => {
    const celdas = this.calorActividad();
    const total = celdas.reduce((t, c) => t + c.valor, 0);
    if (!total) {
      return '';
    }
    const laboral = celdas.filter((c) => LABORALES.has(`${c.fila}|${c.columna}`)).reduce((t, c) => t + c.valor, 0);
    const pico = celdas.reduce((a, b) => (b.valor > a.valor ? b : a));
    return `${porcentaje(laboral / total)} del uso es de lunes a viernes de 8 a 20 h; la franja más intensa es ${pico.fila} ${pico.columna}.`;
  });

  readonly adopcion = computed(() => {
    const semanas = this.a()?.semanas ?? [];
    const cuentas = this.cuentasActivas();
    const series: SerieLinea[] = [
      { nombre: 'Usuarios activos por semana', color: 'var(--accent)', valores: semanas.map((s) => s.usuarios) },
    ];
    if (cuentas) {
      series.push({ nombre: 'Cuentas activas', color: 'var(--text-3)', valores: semanas.map(() => cuentas) });
    }
    const actual = semanas.at(-1)?.usuarios ?? 0;
    const anterior = semanas.at(-2)?.usuarios ?? null;
    const cambio = variacion(actual, anterior);
    return {
      etiquetas: semanas.map((s) => s.periodo),
      series,
      hay: semanas.some((s) => s.usuarios > 0),
      lectura:
        `${entero(actual)} usuarios esta semana` +
        (cuentas ? ` (${porcentaje(actual / cuentas)} de las cuentas activas)` : '') +
        (cambio === null ? '.' : `, ${cambio >= 0 ? '+' : '−'}${porcentaje(Math.abs(cambio))} frente a la anterior.`),
    };
  });

  // --- Auditoría: medidores contra meta --------------------------------------------------

  readonly tarjetaAuditoria = computed(() => {
    const r = this.u();
    if (!r) {
      return null;
    }
    const intentos = r.cambios + r.fallidos + r.denegados;
    const limpio = intentos ? r.cambios / intentos : 1;
    const tolerancia = Math.ceil((TOLERANCIA_DENEGADOS_SEMANA * this.dias()) / 7);
    const nivelLimpio: Nivel = limpio >= META_SIN_INCIDENTE ? 'ok' : limpio >= 0.9 ? 'aviso' : 'riesgo';
    const nivelDenegados: Nivel = r.denegados === 0 ? 'ok' : r.denegados <= tolerancia ? 'aviso' : 'riesgo';
    const top = r.denegadosPorUsuario[0];
    return {
      nivel: peor(nivelLimpio, nivelDenegados),
      limpio: {
        valor: intentos ? porcentaje(limpio) : '—',
        fraccion: escala(limpio, 0.8, 1),
        meta: escala(META_SIN_INCIDENTE, 0.8, 1),
        nivel: nivelLimpio,
        nota: `Meta ${porcentaje(META_SIN_INCIDENTE)} · fallidos y denegados son incidentes · escala 80–100 %`,
      },
      denegados: {
        valor: `${entero(r.denegados)} de ${entero(tolerancia)} tolerados`,
        fraccion: escala(r.denegados, 0, tolerancia * 2),
        meta: 0.5,
        nivel: nivelDenegados,
        nota: top ? `${entero(top.total)} de ${abreviar(top.nombre, 28)}` : 'Sin intentos indebidos',
      },
      cambios: entero(r.cambios),
      personas: entero(r.personas),
    };
  });

  readonly calorAuditoria = computed(() => {
    const valor = sumarPorFranja(this.u()?.calor ?? [], (c) => c.dia, (c) => c.hora, (c) => c.total);
    return matrizDiaFranja(valor);
  });
  readonly marcarFueraDeHorario = (fila: string, columna: string) => !LABORALES.has(`${fila}|${columna}`);
  readonly textoFueraDeHorario = 'Fuera de horario (lun–vie 8–20 h)';

  readonly lecturaCalorAuditoria = computed(() => {
    const celdas = this.calorAuditoria();
    if (!celdas.some((c) => c.valor > 0)) {
      return '';
    }
    const fuera = celdas.filter((c) => c.valor > 0 && !LABORALES.has(`${c.fila}|${c.columna}`));
    const n = fuera.reduce((t, c) => t + c.valor, 0);
    return n
      ? `${entero(n)} ${n === 1 ? 'cambio' : 'cambios'} fuera de horario (en rojo): revisa quién y por qué.`
      : 'Todos los cambios se hicieron en horario laboral.';
  });

  readonly matriz = computed<CeldaCalor[]>(() =>
    (this.u()?.matriz ?? []).map((c) => ({ fila: c.usuario, columna: c.entidad, valor: c.total })),
  );

  readonly lecturaMatriz = computed(() => {
    const celdas = this.matriz();
    const total = celdas.reduce((t, c) => t + c.valor, 0);
    if (!total) {
      return '';
    }
    const porUsuario = new Map<string, number>();
    for (const c of celdas) {
      porUsuario.set(c.fila, (porUsuario.get(c.fila) ?? 0) + c.valor);
    }
    const [usuario, cambios] = [...porUsuario.entries()].reduce((a, b) => (b[1] > a[1] ? b : a));
    return `${abreviar(usuario, 28)} hizo el ${porcentaje(cambios / total)} de los cambios de este grupo.`;
  });

  constructor() {
    this.cargar();
  }

  cargar(): void {
    this.monitoreo?.recargar();
    this.actividad?.recargar();
    this.auditoria?.recargar();
  }

  cambiarPeriodo(dias: Periodo): void {
    if (dias !== this.dias()) {
      this.dias.set(dias);
      this.cargar();
    }
  }

  private cambio(v: number | null, subirEsMalo: boolean): string {
    if (v === null) {
      return 'sin período anterior';
    }
    const mejor = subirEsMalo ? v <= 0 : v >= 0;
    return `${v >= 0 ? '▲' : '▼'} ${porcentaje(Math.abs(v))} vs período anterior${mejor ? '' : ' (empeora)'}`;
  }
}
