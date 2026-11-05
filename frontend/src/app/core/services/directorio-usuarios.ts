import { Service, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { PERMISOS } from '../config/permisos';
import { Usuario } from '../models';
import { Api } from './api';
import { SesionStore } from './sesion';

/** Ids por petición a /usuarios/actores (el backend acepta hasta 200). */
const LOTE = 200;

/**
 * Caché id → nombre de usuario para resolver las columnas creado_por / actualizado_por.
 *
 * - Con VER_USUARIOS se alimenta con la lista completa (/usuarios) cuando una
 *   página ya la cargó o con `cargar()`.
 * - Sin ese permiso, o si aparece un id que no está (p. ej. un usuario creado
 *   después de cargar la lista), `etiqueta()` lo pide a /usuarios/actores:
 *   todos los ids que falten en el mismo ciclo van en una sola petición.
 */
@Service()
export class DirectorioUsuarios {
  private readonly api = inject(Api);
  private readonly sesion = inject(SesionStore);

  private readonly nombres = signal<Map<number, string>>(new Map());
  readonly mapa = this.nombres.asReadonly();

  private cargando = false;
  private cargado = false;
  /** Cambia al cerrar sesión: descarta la respuesta de una carga anterior. */
  private ronda = 0;

  /** Ids ya consultados (existan o no): no se vuelven a pedir en la sesión. */
  private readonly consultados = new Set<number>();
  /** Ids a pedir en la próxima petición agrupada. */
  private readonly pendientes = new Set<number>();
  private programado = false;
  /** Peticiones de actores en curso (las exportaciones esperan a que terminen). */
  private readonly enVuelo = new Set<Promise<void>>();

  constructor() {
    this.sesion.alLimpiar(() => this.vaciar());
  }

  /** Alimenta el directorio con una lista ya obtenida (evita una petición extra). */
  establecer(lista: Usuario[]): void {
    this.agregar(lista);
    this.cargado = true;
  }

  /** Carga el directorio completo una sola vez (solo con VER_USUARIOS). */
  cargar(): void {
    if (this.cargado || this.cargando) {
      return;
    }
    // Sin VER_USUARIOS la lista completa daría 403: los nombres se resuelven
    // uno a uno por /usuarios/actores a medida que aparecen.
    if (!this.sesion.tienePermiso(PERMISOS.VER_USUARIOS)) {
      return;
    }
    this.cargando = true;
    const ronda = this.ronda;
    this.api.get<Usuario[]>('/usuarios').subscribe({
      next: (lista) => {
        if (ronda === this.ronda) {
          this.establecer(lista);
          this.cargando = false;
        }
      },
      error: () => {
        if (ronda === this.ronda) {
          this.cargando = false;
        }
      },
    });
  }

  /** Asegura los nombres de estos ids antes de usarlos fuera de pantalla (p. ej. un CSV). */
  async resolver(ids: Iterable<number | null | undefined>): Promise<void> {
    for (const id of ids) {
      this.solicitar(id);
    }
    if (this.programado) {
      this.enviar();
    }
    await Promise.all([...this.enVuelo]);
  }

  nombre(id: number): string | null {
    return this.nombres().get(id) ?? null;
  }

  /**
   * Etiqueta legible para las columnas creado_por / actualizado_por.
   * 0 = sistema/seeding; null = sin dato; «…» mientras se consulta;
   * «Usuario #id» solo si el usuario ya no existe.
   */
  etiqueta(valor: number | null | undefined): string {
    if (valor === null || valor === undefined) {
      return '—';
    }
    if (valor === 0) {
      return 'Sistema';
    }
    const nombre = this.nombre(valor);
    if (nombre) {
      return nombre.startsWith('@') ? nombre : `@${nombre}`;
    }
    this.solicitar(valor);
    return this.consultados.has(valor) && !this.pendientes.has(valor) && !this.enVuelo.size
      ? `Usuario #${valor}`
      : '…';
  }

  /** Anota un id desconocido y programa una sola petición para todos los del ciclo. */
  private solicitar(id: number | null | undefined): void {
    if (!id || id < 0 || this.nombres().has(id) || this.consultados.has(id) || !this.sesion.autenticado()) {
      return;
    }
    this.consultados.add(id);
    this.pendientes.add(id);
    if (!this.programado) {
      this.programado = true;
      // Fuera del ciclo de detección de cambios: etiqueta() se llama desde plantillas.
      queueMicrotask(() => this.enviar());
    }
  }

  private enviar(): void {
    if (!this.programado) {
      return;
    }
    this.programado = false;
    const ids = [...this.pendientes];
    this.pendientes.clear();
    const ronda = this.ronda;
    for (let i = 0; i < ids.length; i += LOTE) {
      const lote = ids.slice(i, i + LOTE);
      const peticion: Promise<void> = firstValueFrom(
        this.api.get<{ id: number; usuario: string }[]>('/usuarios/actores', { ids: lote.join(',') }),
      )
        .then((actores) => {
          if (ronda === this.ronda) {
            this.agregar(actores);
          }
        })
        // Sin respuesta quedan como «Usuario #id»; se reintenta en la próxima sesión.
        .catch(() => undefined)
        .finally(() => {
          this.enVuelo.delete(peticion);
          // Refresca las etiquetas «…» aunque no haya llegado ningún nombre.
          this.nombres.update((m) => new Map(m));
        });
      this.enVuelo.add(peticion);
    }
  }

  private agregar(lista: ReadonlyArray<{ id: number; usuario: string }>): void {
    this.nombres.update((actual) => {
      const nuevo = new Map(actual);
      for (const u of lista) {
        nuevo.set(u.id, u.usuario);
      }
      return nuevo;
    });
  }

  private vaciar(): void {
    this.ronda++;
    this.cargando = false;
    this.cargado = false;
    this.programado = false;
    this.consultados.clear();
    this.pendientes.clear();
    this.nombres.set(new Map());
  }
}
