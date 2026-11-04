import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize, forkJoin, interval } from 'rxjs';
import { PERMISOS } from '../../../../../core/config/permisos';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import { descargarArchivo } from '../../../../../core/utils/archivo.util';
import { HasPermiso } from '../../../../../shared/directives/has-permiso.directive';
import { Kpi } from '../../../../../shared/ui/charts/kpi';
import { EmptyState } from '../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { MallaApi } from '../../data/malla.api';
import { CentroMalla, CentroMallaResumen, Proceso } from '../../data/malla.model';
import {
  MINUTOS_REFRIGERIO,
  PROCESOS,
  ahoraEnMinutos,
  duracion,
  faltan,
  formatearDuracion,
  formatearNumero,
  hora,
  minutos,
  proceso as datosProceso,
} from '../../malla-formato';
import { MallaLineaDia } from './linea-dia/linea-dia';
import {
  Ventana,
  activosEnVentana,
  dotacionComparada,
  enCurso,
  hitos,
  lineaDelDia,
  pasos,
  pendientes,
  personasDeProceso,
  personasDeTurno,
  proximo,
  textoActivos,
  turnosDeVentana,
  turnosEn,
  ventanaDeHora,
  ventanaDeProceso,
  ventanaDeTurno,
} from './malla.logica';

type Vista = { modo: 'ahora' } | { modo: 'turno'; codigo: string } | { modo: 'hora'; hora: number };

/** Capacidades del día: cuáles muestra cada proceso en su detalle. */
const CAPACIDADES = [
  {
    clave: 'unidadesDia',
    etiqueta: 'Unidades',
    icono: 'paquete',
    color: 'var(--accent)',
    proceso: 'OLA',
  },
  {
    clave: 'lineasDia',
    etiqueta: 'Líneas',
    icono: 'analisis',
    color: 'var(--danger)',
    proceso: 'OLA',
  },
  {
    clave: 'bultosRecepcionDia',
    etiqueta: 'Bultos en recepción',
    icono: 'truck',
    color: 'var(--success)',
    proceso: 'REC',
  },
  {
    clave: 'proveedoresDia',
    etiqueta: 'Proveedores en recepción',
    icono: 'centro',
    color: 'var(--cyan)',
    proceso: 'REC',
  },
] as const;

/**
 * Malla operativa de un centro: lo que pasa ahora (o en un turno u hora),
 * el flujo de procesos, la línea del día y el detalle de cada proceso. Con
 * «Comparar», todos los centros lado a lado. Los datos se editan en
 * Configuración.
 */
@Component({
  selector: 'app-malla-operativa',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HasPermiso, Kpi, EmptyState, Icon, MallaLineaDia],
  templateUrl: './operativa.html',
  styleUrl: './operativa.scss',
})
export class MallaOperativa {
  private readonly api = inject(MallaApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly permisos = PERMISOS;
  readonly procesos = PROCESOS;
  readonly formatearNumero = formatearNumero;
  readonly formatearDuracion = formatearDuracion;
  readonly hora = hora;

  readonly centros = signal<CentroMallaResumen[]>([]);
  /** Centro que se mira o «comparar» (todos). */
  readonly seleccion = signal<number | 'comparar' | null>(null);
  /** Malla completa de cada centro ya pedido. */
  private readonly mallas = signal<Record<number, CentroMalla>>({});
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly vista = signal<Vista>({ modo: 'ahora' });
  readonly procesoAbierto = signal<Proceso | null>(null);
  /** Minuto del día; se actualiza cada 30 s para que «ahora» avance solo. */
  readonly reloj = signal(ahoraEnMinutos());

  readonly centro = computed(() => {
    const sel = this.seleccion();
    return typeof sel === 'number' ? (this.mallas()[sel] ?? null) : null;
  });

  readonly todos = computed(() =>
    this.centros()
      .map((c) => this.mallas()[c.id])
      .filter((c): c is CentroMalla => !!c),
  );

  /** Ventana de la vista elegida (turno u hora); null = ahora. */
  readonly ventana = computed<Ventana | null>(() => this.ventanaDe(this.centro()));

  /** Minuto de referencia: inicio de la ventana o la hora actual. */
  readonly minuto = computed(() => this.ventana()?.desde ?? this.reloj());

  readonly turnosDelCentro = computed(() => this.centro()?.turnos ?? this.todos()[0]?.turnos ?? []);

  /** Horas que se pueden elegir, desde el inicio del primer turno. */
  readonly horas = computed(() => {
    const t0 = minutos(this.turnosDelCentro()[0]?.inicio) ?? 7 * 60;
    return Array.from({ length: 24 }, (_, i) => Math.floor(((t0 + i * 60) % 1440) / 60));
  });

  readonly kpis = computed(() => {
    const c = this.centro();
    if (!c) {
      return [];
    }
    return CAPACIDADES.map((k) => this.kpi(c, k));
  });

  /** Barra «ahora» (o de la ventana elegida): turno, personas, en curso y próximo. */
  readonly resumen = computed(() => {
    const c = this.centro();
    if (!c) {
      return null;
    }
    const v = this.ventana();
    const t = this.minuto();
    const turnos = v ? turnosDeVentana(c, v) : turnosEn(c, t);
    const activos = PROCESOS.flatMap((p) => {
      const items = v ? activosEnVentana(c, p.id, v) : enCurso(c, p.id, t);
      return items.length
        ? [{ proceso: p.id, color: p.color, texto: textoActivos(p.id, items) }]
        : [];
    });
    // En refrigerio: desde su hora y durante MINUTOS_REFRIGERIO.
    const refrigerio = turnos.find((x) => {
      const m = minutos(x.refrigerio);
      return m !== null && faltan(m, t) < MINUTOS_REFRIGERIO;
    });
    return {
      turno: turnos.map((x) => x.codigo).join(' + ') || '—',
      sub: v
        ? v.sub
        : turnos.length > 1
          ? 'Traslape de turnos'
          : turnos[0]
            ? `${turnos[0].inicio}–${turnos[0].fin}`
            : 'Fuera de turno',
      refrigerio: refrigerio
        ? `${(refrigerio.tipoRefrigerio ?? 'Refrigerio').toLowerCase()} ${refrigerio.refrigerio}`
        : null,
      personas: turnos.reduce((total, x) => total + personasDeTurno(c, x.codigo), 0),
      activos,
      proximo: v ? null : proximo(c, t),
      hitos: v ? hitos(c, t, v) : [],
    };
  });

  /** Una tarjeta por proceso, en el orden del flujo. */
  readonly tarjetas = computed(() => {
    const c = this.centro();
    if (!c) {
      return [];
    }
    const v = this.ventana();
    const t = this.minuto();
    const siguientes = hitos(c, t);
    return PROCESOS.map((p) => {
      const ventana = ventanaDeProceso(c, p.id);
      const activo = v ? activosEnVentana(c, p.id, v).length > 0 : enCurso(c, p.id, t).length > 0;
      const siguiente = siguientes.find((h) => h.proceso === p.id);
      const lista = pasos(c, p.id);
      return {
        ...p,
        ventana: ventana ? `${ventana.inicio}–${ventana.fin}` : '—',
        activo,
        estado: activo
          ? v
            ? `Activo en ${v.etiqueta}`
            : 'En curso'
          : v
            ? 'Sin actividad'
            : siguiente
              ? `Próximo ${hora(siguiente.minuto)}`
              : '—',
        il: lista.filter((f) => f.responsable === 'IL').length,
        ops: lista.filter((f) => f.responsable === 'OPS').length,
        personas: personasDeProceso(c, p.id),
        pendientes: pendientes(c, p.id),
      };
    });
  });

  /** Personas planificadas (suma de turnos) frente a la dotación real declarada. */
  readonly dotacion = computed(() => {
    const c = this.centro();
    return c ? dotacionComparada(c) : null;
  });

  readonly linea = computed(() => {
    const c = this.centro();
    return c ? lineaDelDia(c, this.minuto(), this.ventana(), false) : null;
  });

  /** Detalle del proceso abierto: sus pasos en dos carriles (IL y OPS). */
  readonly detalle = computed(() => {
    const c = this.centro();
    const p = this.procesoAbierto();
    if (!c || !p) {
      return null;
    }
    const info = datosProceso(p);
    const tarjeta = this.tarjetas().find((x) => x.id === p)!;
    const v = this.ventana();
    const t = this.minuto();
    const indice = PROCESOS.findIndex((x) => x.id === p);
    const zonasMax = Math.max(1, ...c.zonas.map((z) => Math.max(z.lphReal ?? 0, z.lphDiseno ?? 0)));
    return {
      ...tarjeta,
      anterior: PROCESOS[indice - 1] ?? null,
      siguiente: PROCESOS[indice + 1] ?? null,
      capacidades: CAPACIDADES.filter((k) => k.proceso === p).map((k) => this.kpi(c, k)),
      pasos: pasos(c, p).map((f, i) => ({
        ...f,
        columna: i + 2,
        activo: v ? activosEnVentana(c, p, v).includes(f) : !!f.fin && enCurso(c, p, t).includes(f),
      })),
      olas:
        p === 'OLA'
          ? [...c.olas]
              .sort(
                (a, b) =>
                  (Number(a.cluster) || 0) - (Number(b.cluster) || 0) ||
                  (minutos(a.lanzamiento) ?? 9e9) - (minutos(b.lanzamiento) ?? 9e9),
              )
              .map((o, i, todas) => ({
                ...o,
                primeraDelCluster: i === 0 || todas[i - 1].cluster !== o.cluster,
                filasCluster: todas.filter((x) => x.cluster === o.cluster).length,
                duracion: formatearDuracion(duracion(o.lanzamiento, o.cierrePicking)),
              }))
          : [],
      zonas:
        p === 'OLA'
          ? c.zonas.map((z) => {
              const uso = z.lphReal && z.lphDiseno ? z.lphReal / z.lphDiseno : null;
              return {
                ...z,
                barra: ((z.lphReal ?? 0) / zonasMax) * 100,
                diseno: z.lphDiseno ? (z.lphDiseno / zonasMax) * 100 : null,
                uso: uso === null ? null : Math.round(uso * 100),
                nivel: uso === null ? '' : uso > 0.95 ? 'alto' : uso > 0.85 ? 'medio' : 'bajo',
              };
            })
          : [],
      semana: c.dotacionReal.semana,
      personasPorTurno: c.turnos.map((x) => ({
        turno: x,
        personas:
          c.dotacion.find(
            (d) => d.proceso === p && d.turno.toUpperCase() === x.codigo.toUpperCase(),
          )?.personas ?? null,
      })),
      color: info.color,
    };
  });

  /** Vista «Comparar»: cada centro con sus cifras y su línea del día compacta. */
  readonly comparativo = computed(() =>
    this.todos().map((c) => {
      const v = this.ventanaDe(c);
      const t = v?.desde ?? this.reloj();
      const sig = v ? null : proximo(c, t);
      const lista = v ? hitos(c, t, v) : [];
      return {
        centro: c,
        cifras: CAPACIDADES.map((k) => ({
          etiqueta: `${k.etiqueta}/día`,
          valor: formatearNumero(c.capacidad[k.clave]),
        })),
        turno: (v ? turnosDeVentana(c, v) : turnosEn(c, t)).map((x) => x.codigo).join(' + ') || '—',
        siguiente: v
          ? {
              titulo: 'Hitos',
              valor: String(lista.length),
              detalle: lista[0] ? `${hora(lista[0].minuto)} ${lista[0].texto}` : '',
            }
          : {
              titulo: 'Próximo',
              valor: sig ? hora(sig.minuto) : '—',
              detalle: sig?.textos[0] ?? '',
            },
        linea: lineaDelDia(c, t, v, true),
      };
    }),
  );

  constructor() {
    interval(30_000)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.reloj.set(ahoraEnMinutos()));
    this.cargarCentros();
  }

  cargarCentros(): void {
    this.cargando.set(true);
    this.error.set(null);
    this.api
      .centros()
      .pipe(
        finalize(() => this.cargando.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (lista) => {
          this.centros.set(lista);
          const pedido = Number(this.route.snapshot.queryParamMap.get('centro'));
          const inicial = lista.find((c) => c.id === pedido)?.id ?? lista[0]?.id ?? null;
          if (inicial !== null) {
            this.elegir(inicial);
          }
        },
        error: (err: unknown) => this.error.set(mensajeDeError(err)),
      });
  }

  elegir(seleccion: number | 'comparar'): void {
    this.seleccion.set(seleccion);
    this.procesoAbierto.set(null);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { centro: typeof seleccion === 'number' ? seleccion : null },
      replaceUrl: true,
    });
    const faltan = (
      seleccion === 'comparar' ? this.centros().map((c) => c.id) : [seleccion]
    ).filter((id) => !this.mallas()[id]);
    if (faltan.length === 0) {
      return;
    }
    this.cargando.set(true);
    forkJoin(faltan.map((id) => this.api.centro(id)))
      .pipe(
        finalize(() => this.cargando.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (lista) =>
          this.mallas.update((actual) => ({
            ...actual,
            ...Object.fromEntries(lista.map((c) => [c.id, c])),
          })),
        error: (err: unknown) => this.error.set(mensajeDeError(err)),
      });
  }

  verAhora(): void {
    this.vista.set({ modo: 'ahora' });
  }

  verTurno(codigo: string): void {
    this.vista.set({ modo: 'turno', codigo });
  }

  verHora(evento: Event): void {
    const valor = (evento.target as HTMLSelectElement).value;
    this.vista.set(valor === '' ? { modo: 'ahora' } : { modo: 'hora', hora: Number(valor) });
  }

  esTurno(codigo: string): boolean {
    const v = this.vista();
    return v.modo === 'turno' && v.codigo === codigo;
  }

  horaElegida(): number | null {
    const v = this.vista();
    return v.modo === 'hora' ? v.hora : null;
  }

  exportar(): void {
    const c = this.centro();
    if (c) {
      this.api
        .exportar(c.id)
        .subscribe((archivo) => descargarArchivo(archivo, `malla-${c.codigo}.xlsx`));
    }
  }

  irAConfiguracion(): void {
    const c = this.centro();
    void this.router.navigate(['/malla/configuracion'], {
      queryParams: c ? { centro: c.id } : {},
    });
  }

  private ventanaDe(c: CentroMalla | null): Ventana | null {
    const v = this.vista();
    if (v.modo === 'hora') {
      return ventanaDeHora(v.hora);
    }
    if (v.modo === 'turno') {
      const turno = (c?.turnos ?? []).find((x) => x.codigo === v.codigo);
      return turno ? ventanaDeTurno(turno) : null;
    }
    return null;
  }

  private kpi(c: CentroMalla, k: (typeof CAPACIDADES)[number]) {
    const valor = c.capacidad[k.clave];
    return {
      etiqueta: k.etiqueta,
      valor: valor === null ? '—' : formatearNumero(valor),
      detalle: valor === null ? 'Por definir' : 'Por día',
      icono: k.icono,
      color: k.color,
    };
  }
}
