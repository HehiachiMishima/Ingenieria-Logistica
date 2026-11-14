import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { finalize } from 'rxjs';
import { mensajeDeError } from '../../../../../../core/errors/error.util';
import { Pagina } from '../../../../../../core/models';
import { ActorLabelPipe } from '../../../../../../shared/pipes/actor-label.pipe';
import { EmptyState } from '../../../../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { Paginador } from '../../../../../../shared/ui/paginador/paginador';
import { SimulacionApi } from '../../../data/simulacion.api';
import {
  Balanceo,
  ComparativoLanzamiento,
  ETIQUETA_BALANCEO,
  FaseBalanceo,
  HistorialRegistroSimulacion,
  HistorialSimulacion,
} from '../../../data/simulacion.model';
import {
  EstadoCara,
  IconoSim,
  NombreIconoSim,
  diferenciaCarga,
  estadosCarga,
} from '../../../icono-sim/icono-sim';
import { NumeroPipe } from '../../../numero.pipe';
import { ChuteCortina, CortinaComparativa } from './cortina-comparativa/cortina-comparativa';
import { formatearEntero } from '../../../simulacion-formato';

/** Un lanzamiento (o los cambios sin lanzamiento) tal como se lista. */
interface GrupoHistorial {
  clave: string;
  /** null = cambios sin lanzamiento (a mano o de versiones anteriores). */
  lanzamientoId: number | null;
  titulo: string;
  /** Para el chip: el cluster (o «A mano») y la fase (null en los cambios a mano). */
  cluster: string;
  fase: FaseBalanceo | null;
  mensaje: string | null;
  fecha: string | null;
  creadoPor: number | null;
  rutas: number;
  chutesOperarios: number;
}

/** Una ficha comparativa: el antes (tachado) y el después. */
interface FichaComparada {
  icono: NombreIconoSim;
  estado: EstadoCara;
  etiqueta: string;
  antes: string | null;
  despues: string;
}

/** Escala de las torres: una caja vale 1, 2 o 5 × 10ⁿ bultos, así la más alta tiene unas 8. */
function unidadCaja(maximo: number): number {
  const bruto = Math.max(1, maximo / 8);
  const base = 10 ** Math.floor(Math.log10(bruto));
  return [1, 2, 5, 10].map((m) => m * base).find((u) => u >= bruto) ?? 10 * base;
}

/** «CHUTE 15» → «15»: la columna es angosta; el nombre completo va en el title. */
function chuteCorto(nombre: string): string {
  return nombre.replace(/^chute\s*/i, '') || nombre;
}

/**
 * Modal con los cambios del registro. Arriba, un chip por lanzamiento (el más reciente
 * elegido); el elegido se compara con una cortina: los chutes antes (a la izquierda) y después
 * (a la derecha) como torres de cajas con su carita, o de operarios si fue la asignación de la
 * ola. Debajo, las fichas antes → después y la lista de cambios, plegada y paginada: un
 * balanceo grande mueve miles de rutas.
 */
@Component({
  selector: 'app-simulacion-historial-cambios',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, ActorLabelPipe, CortinaComparativa, EmptyState, Icon, IconoSim, Modal, Paginador, NumeroPipe],
  templateUrl: './historial-cambios.html',
  styleUrl: './historial-cambios.scss',
})
export class SimulacionHistorialCambios {
  private readonly api = inject(SimulacionApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly registroId = input<number | null>(null);
  readonly abierto = input(false);
  /**
   * Pestaña con que se abre el modal (Diarias, L-X-V o M-J-S): la que mira
   * Configuración. Se toma cada vez que el modal se abre.
   */
  readonly balanceoInicial = input<Balanceo>('LXV');
  readonly cerrar = output<void>();

  private readonly historial = signal<HistorialRegistroSimulacion | null>(null);
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);

  /** Grupo abierto y la página de sus cambios. */
  readonly abiertoGrupo = signal<string | null>(null);
  readonly cambios = signal<Pagina<HistorialSimulacion> | null>(null);
  readonly cargandoCambios = signal(false);
  readonly pagina = signal(1);
  /**
   * Historial que se mira: Diarias solo lo suyo; cada día, lo suyo y además los
   * lanzamientos y cambios de Diarias (el backend antepone «Diarias · »).
   */
  readonly balanceo = signal<Balanceo>('LXV');
  readonly balanceos: readonly Balanceo[] = ['DIA', 'LXV', 'MJS'];
  readonly etiquetaBalanceo = ETIQUETA_BALANCEO;
  readonly porPagina = signal(25);
  /** Descarta respuestas viejas si se cambia de grupo o de página rápido. */
  private solicitud = 0;
  /** Descarta historiales viejos si se cambia de pestaña (o de registro) rápido. */
  private solicitudHistorial = 0;
  /** El modal ya estaba abierto en la corrida anterior del efecto. */
  private estabaAbierto = false;

  readonly grupos = computed<GrupoHistorial[]>(() => {
    const historial = this.historial();
    if (!historial) {
      return [];
    }
    const grupos: GrupoHistorial[] = historial.lanzamientos.map((lanzamiento) => ({
      clave: `l${lanzamiento.id}`,
      lanzamientoId: lanzamiento.id,
      titulo: `${lanzamiento.cluster} · ${
        lanzamiento.fase === 'RUTAS' ? 'Balanceo de rutas' : 'Asignación de operarios'
      }`,
      cluster: lanzamiento.cluster,
      fase: lanzamiento.fase,
      mensaje: lanzamiento.mensaje,
      fecha: lanzamiento.creadoEn,
      creadoPor: lanzamiento.creadoPor,
      rutas: lanzamiento.rutas,
      chutesOperarios: lanzamiento.chutesOperarios,
    }));
    if (historial.sinLanzamiento > 0) {
      grupos.push({
        clave: 'sin',
        lanzamientoId: null,
        titulo: 'Movimientos sin lanzamiento',
        cluster: 'A mano',
        fase: null,
        mensaje: 'Hechos a mano o con una versión anterior del balanceo.',
        fecha: null,
        creadoPor: null,
        rutas: historial.sinLanzamiento,
        chutesOperarios: 0,
      });
    }
    return grupos;
  });

  /** Lanzamiento elegido en los chips; por defecto, el más reciente. */
  readonly seleccion = signal<string | null>(null);
  readonly grupoActual = computed<GrupoHistorial | null>(() => {
    const grupos = this.grupos();
    return grupos.find((g) => g.clave === this.seleccion()) ?? grupos[0] ?? null;
  });

  readonly comparativo = signal<ComparativoLanzamiento | null>(null);
  readonly cargandoComparativo = signal(false);
  readonly errorComparativo = signal<string | null>(null);

  /** Torres de la cortina y fichas antes → después del lanzamiento elegido. */
  readonly vista = computed(() => {
    const c = this.comparativo();
    if (!c || c.chutes.length === 0) {
      return null;
    }
    const operarios = c.fase === 'OPERARIOS';
    const antes = c.chutes.map((x) => x.antes);
    const despues = c.chutes.map((x) => x.despues);
    const unidad = operarios ? 1 : unidadCaja(Math.max(1, ...antes, ...despues));
    const estadosAntes = operarios ? antes.map(() => null) : estadosCarga(antes);
    const estadosDespues = operarios ? despues.map(() => null) : estadosCarga(despues);
    const piezas = (valor: number) =>
      Array.from({ length: valor > 0 ? Math.max(1, Math.round(valor / unidad)) : 0 }, (_, i) => i);
    const lado = (valor: number, estado: EstadoCara | null) => ({
      valor: formatearEntero(valor),
      piezas: piezas(valor),
      estado,
    });
    const suma = (v: number[]) => v.reduce((t, n) => t + n, 0);
    const saturados = (e: (EstadoCara | null)[]) => String(e.filter((x) => x === 'mal').length);
    const porcentaje = (v: number | null) => (v === null ? '—' : `${v} %`);
    const fichas: FichaComparada[] = operarios
      ? [
          { icono: 'operario', estado: 'bien', etiqueta: 'Operarios', antes: formatearEntero(suma(antes)), despues: formatearEntero(suma(despues)) },
          { icono: 'balanza', estado: 'bien', etiqueta: 'Chutes con otra dotación', antes: null, despues: formatearEntero(c.chutes.filter((x) => x.antes !== x.despues).length) },
        ]
      : [
          { icono: 'caja', estado: 'bien', etiqueta: 'Rutas movidas', antes: null, despues: formatearEntero(c.rutas) },
          { icono: 'caja', estado: 'bien', etiqueta: 'Bultos movidos', antes: null, despues: formatearEntero(c.bultosMovidos) },
          { icono: 'balanza', estado: 'bien', etiqueta: 'Diferencia', antes: porcentaje(diferenciaCarga(antes)), despues: porcentaje(diferenciaCarga(despues)) },
          { icono: 'cara', estado: 'mal', etiqueta: 'Saturados', antes: saturados(estadosAntes), despues: saturados(estadosDespues) },
        ];
    return {
      operarios,
      unidad: formatearEntero(unidad),
      chutes: c.chutes.map((x, i): ChuteCortina => ({
        id: x.chuteId,
        nombre: x.chute,
        corto: chuteCorto(x.chute),
        antes: lado(x.antes, estadosAntes[i]),
        despues: lado(x.despues, estadosDespues[i]),
      })),
      fichas,
    };
  });

  private solicitudComparativo = 0;

  constructor() {
    // Al abrir el modal (o cambiar de registro con él abierto) se recarga la lista.
    // Al abrirlo, además, parte de la pestaña que mira Configuración.
    effect(() => {
      const id = this.registroId();
      const abierto = this.abierto();
      const recienAbierto = abierto && !this.estabaAbierto;
      this.estabaAbierto = abierto;
      if (!abierto) {
        // Cerrado: cualquier respuesta en vuelo ya no se pinta.
        this.solicitudHistorial++;
        this.solicitud++;
        this.solicitudComparativo++;
        return;
      }
      if (id !== null) {
        untracked(() => {
          if (recienAbierto) {
            this.balanceo.set(this.balanceoInicial());
          }
          this.cargar(id);
        });
      }
    });
  }

  private cargar(id: number): void {
    const solicitud = ++this.solicitudHistorial;
    // Los cambios abiertos eran de otra pestaña: su respuesta en vuelo se descarta.
    this.solicitud++;
    this.cargandoCambios.set(false);
    this.historial.set(null);
    this.abiertoGrupo.set(null);
    this.cambios.set(null);
    this.error.set(null);
    this.seleccion.set(null);
    this.solicitudComparativo++;
    this.comparativo.set(null);
    this.errorComparativo.set(null);
    this.cargando.set(true);
    this.api
      .historial(id, this.balanceo())
      .pipe(
        // Solo la última solicitud apaga el spinner: una vieja que termina antes no lo corta.
        finalize(() => {
          if (solicitud === this.solicitudHistorial) {
            this.cargando.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (historial) => {
          if (solicitud === this.solicitudHistorial) {
            this.historial.set(historial);
            this.elegir(this.grupos()[0] ?? null);
          }
        },
        error: (err: unknown) => {
          if (solicitud === this.solicitudHistorial) {
            this.error.set(mensajeDeError(err));
          }
        },
      });
  }

  elegirBalanceo(balanceo: Balanceo): void {
    const id = this.registroId();
    if (balanceo === this.balanceo() || id === null) {
      return;
    }
    this.balanceo.set(balanceo);
    this.cargar(id);
  }

  /** Vuelve a pedir el historial de la pestaña actual. */
  reintentar(): void {
    const id = this.registroId();
    if (id !== null) {
      this.cargar(id);
    }
  }

  /** Elige un lanzamiento en los chips: pliega su lista y pide su antes y después. */
  elegir(grupo: GrupoHistorial | null): void {
    this.seleccion.set(grupo?.clave ?? null);
    this.abiertoGrupo.set(null);
    this.cambios.set(null);
    const id = this.registroId();
    const solicitud = ++this.solicitudComparativo;
    this.comparativo.set(null);
    this.errorComparativo.set(null);
    if (!grupo || grupo.lanzamientoId === null || id === null) {
      // Los cambios a mano no tienen un antes y después único: solo su lista.
      this.cargandoComparativo.set(false);
      return;
    }
    this.cargandoComparativo.set(true);
    this.api
      .comparativoLanzamiento(id, grupo.lanzamientoId, this.balanceo())
      .pipe(
        finalize(() => {
          if (solicitud === this.solicitudComparativo) {
            this.cargandoComparativo.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (comparativo) => {
          if (solicitud === this.solicitudComparativo) {
            this.comparativo.set(comparativo);
          }
        },
        error: (err: unknown) => {
          if (solicitud === this.solicitudComparativo) {
            this.errorComparativo.set(mensajeDeError(err));
          }
        },
      });
  }

  /** Despliega o pliega la lista de cambios del lanzamiento elegido. */
  verCambios(): void {
    const grupo = this.grupoActual();
    if (grupo) {
      this.alternar(grupo);
    }
  }

  alternar(grupo: GrupoHistorial): void {
    if (this.abiertoGrupo() === grupo.clave) {
      this.abiertoGrupo.set(null);
      return;
    }
    this.abiertoGrupo.set(grupo.clave);
    this.cambios.set(null);
    this.pagina.set(1);
    this.cargarCambios(grupo);
  }

  cambiarPagina(grupo: GrupoHistorial, pagina: number): void {
    this.pagina.set(pagina);
    this.cargarCambios(grupo);
  }

  cambiarPorPagina(grupo: GrupoHistorial, porPagina: number): void {
    this.porPagina.set(porPagina);
    this.pagina.set(1);
    this.cargarCambios(grupo);
  }

  private cargarCambios(grupo: GrupoHistorial): void {
    const id = this.registroId();
    if (id === null) {
      return;
    }
    const solicitud = ++this.solicitud;
    this.cargandoCambios.set(true);
    this.api
      .historialCambios(id, grupo.lanzamientoId, this.pagina(), this.porPagina(), this.balanceo())
      .pipe(
        finalize(() => {
          if (solicitud === this.solicitud) {
            this.cargandoCambios.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (pagina) => {
          if (solicitud === this.solicitud) {
            this.cambios.set(pagina);
          }
        },
      });
  }
}
