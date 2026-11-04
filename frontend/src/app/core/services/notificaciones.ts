import { Service, signal } from '@angular/core';
import { CONFIG } from '../config/configuracion';

export type TipoNotificacion = 'exito' | 'error' | 'info' | 'advertencia';

export interface Notificacion {
  id: number;
  tipo: TipoNotificacion;
  titulo: string;
  mensaje?: string;
}

@Service()
export class Notificaciones {
  private readonly _items = signal<Notificacion[]>([]);
  private contador = 0;

  readonly items = this._items.asReadonly();

  exito(titulo: string, mensaje?: string): void {
    this.mostrar('exito', titulo, mensaje);
  }

  error(titulo: string, mensaje?: string): void {
    this.mostrar('error', titulo, mensaje, CONFIG.notificaciones.duracionError);
  }

  info(titulo: string, mensaje?: string): void {
    this.mostrar('info', titulo, mensaje);
  }

  advertencia(titulo: string, mensaje?: string): void {
    this.mostrar('advertencia', titulo, mensaje);
  }

  cerrar(id: number): void {
    this._items.update((items) => items.filter((item) => item.id !== id));
  }

  private mostrar(
    tipo: TipoNotificacion,
    titulo: string,
    mensaje?: string,
    duracion: number = CONFIG.notificaciones.duracion,
  ): void {
    const id = ++this.contador;
    this._items.update((items) => [...items, { id, tipo, titulo, mensaje }]);
    setTimeout(() => this.cerrar(id), duracion);
  }
}
