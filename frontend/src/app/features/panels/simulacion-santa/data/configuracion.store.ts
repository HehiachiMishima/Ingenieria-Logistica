import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, debounceTime, finalize, forkJoin, of, switchMap, tap } from 'rxjs';
import { Notificaciones } from '../../../../core/services/notificaciones';
import { mensajeDeError } from '../../../../core/errors/error.util';
import {
  Cluster,
  ConfiguracionSanta,
  ConfiguracionSantaRequest,
  OrdenLlenado,
  PaletaSimulacion,
  RegistroSanta,
  SimulacionSanta,
  TipoUbicacion,
} from '../models/santa.model';
import { SantaApi } from './santa.api';
import { clavePosicion } from '../plano/plano-santa';

/** Configuración guardada → cuerpo editable (lo que se envía al guardar o previsualizar). */
export function aPeticion(config: ConfiguracionSanta): ConfiguracionSantaRequest {
  return {
    operarios: config.operarios,
    bultosHora: config.bultosHora,
    ordenClusters: [...config.ordenClusters],
    rampas: config.rampas.map((r) => ({ numero: r.numero, cluster: r.cluster, posiciones: r.posiciones })),
    pasillos: config.pasillos.map((p) => ({ numero: p.numero, cluster: p.cluster })),
  };
}

function iguales(a: ConfiguracionSantaRequest | null, b: ConfiguracionSantaRequest | null): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/**
 * Estado del módulo Configuración: registro elegido, su configuración guardada,
 * el borrador que se edita (con vista previa en el servidor, sin guardar) y los
 * envíos. Se provee en la página.
 */
@Injectable()
export class ConfiguracionSantaStore {
  private readonly api = inject(SantaApi);
  private readonly notificaciones = inject(Notificaciones);
  private readonly destroyRef = inject(DestroyRef);

  readonly registros = signal<RegistroSanta[]>([]);
  readonly cargandoRegistros = signal(true);
  readonly registroId = signal<number | null>(null);
  readonly registro = computed(
    () => this.registros().find((r) => r.id === this.registroId()) ?? null,
  );

  readonly configuracion = signal<ConfiguracionSanta | null>(null);
  readonly borrador = signal<ConfiguracionSantaRequest | null>(null);
  readonly simulacion = signal<SimulacionSanta | null>(null);
  readonly vistaPrevia = signal<SimulacionSanta | null>(null);

  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly previsualizando = signal(false);
  readonly guardando = signal(false);
  readonly cambiandoEnvio = signal(false);
  /** Bloqueando o desbloqueando el candado. */
  readonly bloqueando = signal(false);

  /** Candado global: ninguna parte de la configuración se cambia en ningún registro. */
  readonly bloqueado = computed(() => this.configuracion()?.bloqueado ?? false);

  readonly hayCambios = computed(() => {
    const config = this.configuracion();
    return config !== null && !iguales(this.borrador(), aPeticion(config));
  });

  /** Lo que muestra el plano: la vista previa si hay cambios sin guardar. */
  readonly simulacionVisible = computed(() =>
    this.hayCambios() ? (this.vistaPrevia() ?? this.simulacion()) : this.simulacion(),
  );

  /** Paletas por posición, para que el plano las encuentre en O(1). */
  readonly paletasPorPosicion = computed(() => {
    const mapa = new Map<string, PaletaSimulacion>();
    for (const p of this.simulacionVisible()?.paletas ?? []) {
      if (p.ubicacion) {
        const u = p.ubicacion;
        mapa.set(clavePosicion(u.tipo, u.numero, u.lado, u.fila), p);
      }
    }
    return mapa;
  });

  /** Paletas que el borrador deja sin ubicación de más respecto de lo guardado. */
  readonly perdidaPorGuardar = computed(() => {
    const guardada = this.simulacion()?.kpis.sinUbicar ?? 0;
    const previa = this.vistaPrevia()?.kpis.sinUbicar ?? guardada;
    return Math.max(0, previa - guardada);
  });

  private readonly carga$ = new Subject<number>();
  private readonly previa$ = new Subject<void>();

  constructor() {
    this.carga$
      .pipe(
        tap(() => {
          this.cargando.set(true);
          this.error.set(null);
        }),
        switchMap((id) =>
          forkJoin({
            config: this.api.configuracion(id),
            simulacion: this.api.simulacion(id, this.registro()?.envioActual ?? 0),
          }).pipe(catchError((err: unknown) => of({ error: mensajeDeError(err) }))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((resultado) => {
        this.cargando.set(false);
        if ('error' in resultado) {
          this.error.set(resultado.error);
          return;
        }
        this.configuracion.set(resultado.config);
        this.borrador.set(aPeticion(resultado.config));
        this.simulacion.set(resultado.simulacion);
        this.vistaPrevia.set(null);
      });

    this.previa$
      .pipe(
        debounceTime(350),
        switchMap(() => {
          const id = this.registroId();
          const borrador = this.borrador();
          if (id === null || borrador === null || !this.hayCambios()) {
            this.previsualizando.set(false);
            return of(null);
          }
          this.previsualizando.set(true);
          return this.api
            .previsualizar(id, this.simulacion()?.envio ?? 0, borrador)
            .pipe(catchError(() => of(null)));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((previa) => {
        this.previsualizando.set(false);
        this.vistaPrevia.set(previa);
      });
  }

  /** Carga la lista de registros y abre el pedido (o el más reciente). */
  iniciar(registroPedido: number | null): void {
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
          const existe = registros.some((r) => r.id === registroPedido);
          this.seleccionar(existe ? registroPedido : (registros[0]?.id ?? null));
        },
        error: (err: unknown) => this.error.set(mensajeDeError(err)),
      });
  }

  seleccionar(id: number | null): void {
    this.registroId.set(id);
    this.configuracion.set(null);
    this.borrador.set(null);
    this.simulacion.set(null);
    this.vistaPrevia.set(null);
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

  /* ---------- edición del borrador ---------- */

  /** Toda edición pasa por aquí: con candado no se cambia nada (el servidor también lo rechaza). */
  private editar(cambio: (b: ConfiguracionSantaRequest) => ConfiguracionSantaRequest): void {
    const actual = this.borrador();
    if (!actual || this.bloqueado()) {
      return;
    }
    this.borrador.set(cambio(actual));
    this.previa$.next();
  }

  mover(tipo: TipoUbicacion, numeros: number[], cluster: Cluster | null): void {
    const set = new Set(numeros);
    this.editar((b) =>
      tipo === 'RAMPA'
        ? { ...b, rampas: b.rampas.map((r) => (set.has(r.numero) ? { ...r, cluster } : r)) }
        : { ...b, pasillos: b.pasillos.map((p) => (set.has(p.numero) ? { ...p, cluster } : p)) },
    );
  }

  fijarPosiciones(numero: number, posiciones: number): void {
    const capacidad = this.configuracion()?.rampas.find((r) => r.numero === numero)?.capacidad ?? 0;
    const valor = Math.max(0, Math.min(capacidad, Math.round(posiciones)));
    this.editar((b) => ({
      ...b,
      rampas: b.rampas.map((r) => (r.numero === numero ? { ...r, posiciones: valor } : r)),
    }));
  }

  fijarOrden(cluster: Cluster, orden: OrdenLlenado): void {
    this.editar((b) => {
      const ordenClusters = [...b.ordenClusters] as ConfiguracionSantaRequest['ordenClusters'];
      ordenClusters[cluster - 1] = orden;
      return { ...b, ordenClusters };
    });
  }

  fijarOperacion(operarios: number, bultosHora: number): void {
    this.editar((b) => ({
      ...b,
      operarios: Math.max(1, Math.min(99, Math.round(operarios) || 1)),
      bultosHora: Math.max(1, Math.min(9999, Math.round(bultosHora) || 1)),
    }));
  }

  descartar(): void {
    const config = this.configuracion();
    if (config) {
      this.borrador.set(aPeticion(config));
      this.vistaPrevia.set(null);
    }
  }

  guardar(): void {
    const id = this.registroId();
    const borrador = this.borrador();
    if (id === null || borrador === null || this.guardando()) {
      return;
    }
    this.guardando.set(true);
    this.api
      .guardarConfiguracion(id, borrador)
      .pipe(
        finalize(() => this.guardando.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.notificaciones.exito('Configuración guardada', 'El plano se recalculó con los cambios.');
          this.recargar();
        },
      });
  }

  /**
   * Candado (como Simulación Supply Chain) sobre TODA la configuración de
   * Asignar. Bloquear toma lo que se ve (con los cambios sin guardar) como
   * estándar: el servidor lo aplica a los registros pendientes y los nuevos nacen
   * con él; los en curso o completos conservan la suya. Luego se recarga el
   * registro: si estaba pendiente ya tiene el estándar; si no, se ve la suya, en
   * solo lectura. Desbloquear abre la edición sin cambiar ningún registro.
   */
  alternarBloqueo(): void {
    const borrador = this.borrador();
    const config = this.configuracion();
    if (!borrador || !config || this.bloqueando()) {
      return;
    }
    this.bloqueando.set(true);
    if (config.bloqueado) {
      this.api
        .desbloquearPredeterminados()
        .pipe(
          finalize(() => this.bloqueando.set(false)),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe(() => {
          this.configuracion.update((c) => (c ? { ...c, bloqueado: false, bloqueadoPor: null, bloqueadoEn: null } : c));
          this.notificaciones.exito(
            'Configuración desbloqueada',
            'Cámbiala y vuelve a bloquearla para todos los registros.',
          );
        });
      return;
    }
    this.api
      .bloquearPredeterminados(borrador)
      .pipe(
        finalize(() => this.bloqueando.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.recargar();
        this.notificaciones.exito(
          'Configuración bloqueada',
          'Se aplicó a los registros pendientes y los nuevos nacen con ella.',
        );
      });
  }

  /* ---------- envíos ---------- */

  fijarEnvio(envio: number): void {
    const id = this.registroId();
    if (id === null || this.cambiandoEnvio() || envio < 0 || envio > 3) {
      return;
    }
    this.cambiandoEnvio.set(true);
    this.api
      .fijarEnvio(id, envio)
      .pipe(
        finalize(() => this.cambiandoEnvio.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (registro) => {
          this.registros.update((lista) => lista.map((r) => (r.id === registro.id ? registro : r)));
          this.api
            .simulacion(id, registro.envioActual)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((sim) => {
              this.simulacion.set(sim);
              if (this.hayCambios()) {
                this.previa$.next();
              }
            });
        },
      });
  }
}
