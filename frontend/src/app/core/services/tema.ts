import { Service, signal } from '@angular/core';
import { CONFIG } from '../config/configuracion';

export type Tema = 'light' | 'dark';

const CLAVE = CONFIG.almacenamiento.tema;

@Service()
export class TemaService {
  private readonly _tema = signal<Tema>(this.inicial());

  readonly tema = this._tema.asReadonly();

  constructor() {
    this.aplicar(this._tema());
  }

  alternar(origen?: Event): void {
    this.cambiar(this._tema() === 'light' ? 'dark' : 'light', origen);
  }

  establecer(tema: Tema): void {
    this.cambiar(tema);
  }

  private cambiar(tema: Tema, origen?: Event): void {
    const actualizar = (): void => {
      this._tema.set(tema);
      this.aplicar(tema);
      try {
        localStorage.setItem(CLAVE, tema);
      } catch {
        /* almacenamiento no disponible */
      }
    };

    if (typeof document.startViewTransition !== 'function' || this.reducido()) {
      actualizar();
      return;
    }

    this.anclar(origen);
    document.startViewTransition(actualizar);
  }

  private anclar(origen?: Event): void {
    const ancla = origen?.currentTarget;
    const raiz = document.documentElement;
    if (!(ancla instanceof Element)) {
      raiz.style.removeProperty('--vt-x');
      raiz.style.removeProperty('--vt-y');
      return;
    }
    const caja = ancla.getBoundingClientRect();
    raiz.style.setProperty('--vt-x', `${caja.left + caja.width / 2}px`);
    raiz.style.setProperty('--vt-y', `${caja.top + caja.height / 2}px`);
  }

  private reducido(): boolean {
    return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
  }

  private aplicar(tema: Tema): void {
    document.documentElement.dataset['theme'] = tema;
  }

  private inicial(): Tema {
    try {
      const guardado = localStorage.getItem(CLAVE);
      if (guardado === 'dark' || guardado === 'light') {
        return guardado;
      }
    } catch {
      /* almacenamiento no disponible */
    }
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
}
