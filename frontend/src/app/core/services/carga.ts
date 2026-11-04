import { Service, computed, signal } from '@angular/core';

/** Tiempo mínimo en pantalla para que la transición se lea y no parpadee. */
const MINIMO_VISIBLE_MS = 1100;

/**
 * Pantalla de carga de marca (ingreso / salida de Atlas). Un único estado
 * global que pinta el componente `ui-loader` montado en la raíz.
 */
@Service()
export class Carga {
  private readonly estado = signal<{ visible: boolean; texto: string; detalle: string }>({
    visible: false,
    texto: '',
    detalle: '',
  });
  private desde = 0;
  private temporizador: ReturnType<typeof setTimeout> | undefined;

  readonly visible = computed(() => this.estado().visible);
  readonly texto = computed(() => this.estado().texto);
  readonly detalle = computed(() => this.estado().detalle);

  mostrar(texto = 'Cargando', detalle = ''): void {
    clearTimeout(this.temporizador);
    this.desde = Date.now();
    this.estado.set({ visible: true, texto, detalle });
  }

  /** Oculta la pantalla respetando el tiempo mínimo, salvo que sea `inmediato`. */
  ocultar(inmediato = false): void {
    clearTimeout(this.temporizador);
    const restante = inmediato ? 0 : MINIMO_VISIBLE_MS - (Date.now() - this.desde);
    if (restante <= 0) {
      this.estado.set({ visible: false, texto: '', detalle: '' });
      return;
    }
    this.temporizador = setTimeout(() => this.ocultar(true), restante);
  }
}
