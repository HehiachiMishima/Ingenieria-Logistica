import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, finalize, of, switchMap, tap } from 'rxjs';
import { mensajeDeError } from '../../../../core/errors/error.util';
import { ClusterVista, DashboardSanta, RegistroSanta } from '../models/santa.model';
import { FilaEnvio, avanceAcumulado, minutosPara, operariosPara, parte, tablaEnvios } from '../santa-calculo';
import { SantaApi } from './santa.api';

/** Ventana por envío por defecto (minutos) para calcular los operarios necesarios. */
export const VENTANA_POR_DEFECTO = 120;

/**
 * Estado del Dashboard: registro elegido, sus agregados (del servidor) y los
 * parámetros de simulación (operarios y ventana por envío). Todos los cálculos
 * son locales: cambiar operarios o ventana no llama al servidor.
 */
@Injectable()
export class DashboardSantaStore {
  private readonly api = inject(SantaApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly registros = signal<RegistroSanta[]>([]);
  readonly cargandoRegistros = signal(true);
  readonly registroId = signal<number | null>(null);
  readonly datos = signal<DashboardSanta | null>(null);
  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);

  /** Operarios con que se simula (null = los de la configuración del registro). */
  readonly operariosSimulados = signal<number | null>(null);
  readonly ventanaMin = signal(VENTANA_POR_DEFECTO);

  readonly operarios = computed(() => this.operariosSimulados() ?? this.datos()?.operarios ?? 0);
  readonly bultosHora = computed(() => this.datos()?.bultosHora ?? 0);
  readonly capacidadHora = computed(() => this.operarios() * this.bultosHora());
  readonly simulando = computed(() => {
    const d = this.datos();
    return d !== null && this.operariosSimulados() !== null && this.operariosSimulados() !== d.operarios;
  });

  readonly envios = computed<FilaEnvio[]>(() => {
    const d = this.datos();
    return d ? tablaEnvios(d.envios.map((e) => e.bultos), this.operarios(), this.bultosHora(), this.ventanaMin()) : [];
  });

  readonly minutosTotales = computed(() => {
    const d = this.datos();
    return d ? minutosPara(d.bultosTotal, this.capacidadHora()) : null;
  });

  /** El envío que más operarios pide (marca la dotación si es el mismo equipo todo el día). */
  readonly envioPico = computed(() =>
    this.envios().reduce<FilaEnvio | null>((max, f) => (max === null || (f.operarios ?? 0) > (max.operarios ?? 0) ? f : max), null),
  );
  readonly operariosNecesarios = computed(() => this.envioPico()?.operarios ?? 0);
  /** Operarios que faltan (> 0) o sobran (< 0) frente a los simulados. */
  readonly diferencia = computed(() => this.operariosNecesarios() - this.operarios());

  readonly clusters = computed<ClusterVista[]>(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    const bultosHora = this.bultosHora();
    const ventana = this.ventanaMin();
    return d.clusters.map((c) => {
      const porEnvio = c.porEnvio.map((e) => e.bultos);
      const avance = avanceAcumulado(porEnvio);
      const envios = c.porEnvio.map((e, i) => ({
        envio: e.envio,
        bultos: e.bultos,
        avance: avance[i] ?? 0,
        paletasCompletas: e.paletasCompletas,
        operarios: operariosPara(e.bultos, bultosHora, ventana),
      }));
      const avisos: ClusterVista['avisos'] = [];
      if (c.sinUbicar > 0) {
        avisos.push({ codigo: 'U1', texto: `${c.sinUbicar} paletas sin espacio en C${c.cluster}.` });
      }
      if (c.bultos > 0 && c.rampas === 0) {
        avisos.push({ codigo: 'U2', texto: `C${c.cluster} no tiene rampas: los operarios trabajan solo en rampas.` });
      }
      return {
        cluster: c.cluster,
        rampas: c.rampas,
        pasillos: c.pasillos,
        paletas: c.paletas,
        bultos: c.bultos,
        sinUbicar: c.sinUbicar,
        envios,
        operariosPico: Math.max(0, ...envios.map((e) => e.operarios ?? 0)),
        avisos,
      };
    });
  });

  /** Parte de los bultos de cada cluster en el total (para la barra apilada). */
  readonly partesCluster = computed(() => {
    const d = this.datos();
    return d ? d.clusters.map((c) => ({ cluster: c.cluster, parte: parte(c.bultos, d.bultosTotal) })) : [];
  });

  private readonly carga$ = new Subject<number>();

  constructor() {
    this.carga$
      .pipe(
        tap(() => {
          this.cargando.set(true);
          this.error.set(null);
        }),
        switchMap((id) =>
          this.api.dashboard(id).pipe(catchError((err: unknown) => of({ error: mensajeDeError(err) }))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((r) => {
        this.cargando.set(false);
        if ('error' in r) {
          this.error.set(r.error);
          this.datos.set(null);
          return;
        }
        this.datos.set(r);
      });
  }

  /** Carga la lista de registros y abre el pedido (o el más reciente). */
  iniciar(pedido: number | null): void {
    this.cargandoRegistros.set(true);
    this.api
      .opciones()
      .pipe(
        finalize(() => this.cargandoRegistros.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (registros) => {
          this.registros.set(registros);
          const existe = registros.some((r) => r.id === pedido);
          this.seleccionar(existe ? pedido : (registros[0]?.id ?? null));
        },
        error: (err: unknown) => this.error.set(mensajeDeError(err)),
      });
  }

  seleccionar(id: number | null): void {
    this.registroId.set(id);
    this.datos.set(null);
    this.operariosSimulados.set(null);
    if (id !== null) {
      this.carga$.next(id);
    }
  }

  recargar(): void {
    const id = this.registroId();
    if (id !== null) {
      this.carga$.next(id);
    }
  }

  fijarOperarios(valor: number): void {
    const n = Math.round(valor);
    this.operariosSimulados.set(Number.isFinite(n) && n >= 1 ? Math.min(999, n) : null);
  }

  fijarVentana(valor: number): void {
    const n = Math.round(valor);
    this.ventanaMin.set(Number.isFinite(n) && n >= 5 ? Math.min(24 * 60, n) : VENTANA_POR_DEFECTO);
  }

  usarOperariosNecesarios(): void {
    const n = this.operariosNecesarios();
    if (n > 0) {
      this.operariosSimulados.set(n);
    }
  }
}
