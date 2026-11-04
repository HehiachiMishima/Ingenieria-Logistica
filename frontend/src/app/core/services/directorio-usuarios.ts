import { Service, inject, signal } from '@angular/core';
import { PERMISOS } from '../config/permisos';
import { Usuario } from '../models';
import { Api } from './api';
import { SesionStore } from './sesion';

/**
 * Caché id → nombre de usuario para resolver las columnas creado_por / actualizado_por.
 * Se alimenta con la lista de /usuarios cuando la página ya la cargó, o la pide una sola vez.
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

  constructor() {
    this.sesion.alLimpiar(() => this.vaciar());
  }

  /** Alimenta el directorio con una lista ya obtenida (evita una petición extra). */
  establecer(lista: Usuario[]): void {
    this.nombres.set(new Map(lista.map((usuario) => [usuario.id, usuario.usuario])));
    this.cargado = true;
  }

  /** Carga el directorio una sola vez. */
  cargar(): void {
    if (this.cargado || this.cargando) {
      return;
    }
    // El catálogo de usuarios exige VER_USUARIOS: sin él la petición daría
    // 403 y un toast de error en pantallas que solo necesitan el fallback.
    // No se marca como cargado: si en la misma pestaña entra después alguien
    // con el permiso, el directorio se carga normalmente.
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

  private vaciar(): void {
    this.ronda++;
    this.cargando = false;
    this.cargado = false;
    this.nombres.set(new Map());
  }

  nombre(id: number): string | null {
    return this.nombres().get(id) ?? null;
  }

  /**
   * Etiqueta legible para las columnas creado_por / actualizado_por.
   * 0 = sistema/seeding; null = sin dato; sin coincidencia = «Usuario #id».
   */
  etiqueta(valor: number | null | undefined): string {
    if (valor === null || valor === undefined) {
      return '—';
    }
    if (valor === 0) {
      return 'Sistema';
    }
    const nombre = this.nombre(valor);
    if (!nombre) {
      return `Usuario #${valor}`;
    }
    return nombre.startsWith('@') ? nombre : `@${nombre}`;
  }
}
