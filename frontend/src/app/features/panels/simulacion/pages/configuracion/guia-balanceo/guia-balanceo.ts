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
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { IconoSim, NombreIconoSim, estadosCarga } from '../../../icono-sim/icono-sim';
import { DETALLES } from './guia-balanceo.textos';

/** Cajas por chute del ejemplo (cada caja, 40 bultos): desparejo a propósito. */
const CAJAS_INICIO: readonly number[] = [9, 2, 7, 1, 8, 3];
const BULTOS_POR_CAJA = 40;
/** Alto de una caja con su separación (px): la silueta del antes usa la misma escala. */
const ALTO_CAJA = 21;

/** Los tres pasos, con una frase corta cada uno. */
const PASOS: readonly { icono: NombreIconoSim; titulo: string; texto: string }[] = [
  { icono: 'caja', titulo: 'Reparte', texto: 'Cajas al chute vacío' },
  { icono: 'operario', titulo: 'Asigna', texto: 'Operarios al más lento' },
  { icono: 'reloj', titulo: 'Cierra', texto: 'Antes del límite' },
];

/**
 * Guía visual del balanceo: seis chutes de ejemplo como torres de cajas con su carita. Al abrir,
 * las cajas pasan del chute lleno al vacío (queda la silueta de cómo estaba), llegan los
 * operarios y se ve el cierre; abajo, los tres pasos se completan. Con «reducir movimiento» se
 * muestra el final directamente. Las reglas detalladas quedan plegadas al final.
 */
@Component({
  selector: 'app-simulacion-guia-balanceo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Modal, IconoSim],
  templateUrl: './guia-balanceo.html',
  styleUrl: './guia-balanceo.scss',
})
export class SimulacionGuiaBalanceo {
  readonly abierto = input(false);
  readonly cerrar = output<void>();

  /** Lo que la animación no explica: parámetros, arrastre manual y reglas. */
  readonly detalles = DETALLES;
  readonly pasos = PASOS;
  readonly bultosPorCaja = BULTOS_POR_CAJA;
  readonly altoCaja = ALTO_CAJA;

  readonly cajas = signal<number[]>([...CAJAS_INICIO]);
  readonly operarios = signal<number[]>(CAJAS_INICIO.map(() => 0));
  /** -1 = aún no empieza; 0, 1, 2 = paso en curso; 3 = terminado. */
  readonly paso = signal(-1);
  readonly movidas = signal(0);

  readonly chutes = computed(() => {
    const cajas = this.cajas();
    const estados = estadosCarga(cajas);
    const operarios = this.operarios();
    return cajas.map((n, i) => ({
      numero: i + 1,
      cajas: Array.from({ length: n }, (_, k) => k),
      operarios: Array.from({ length: operarios[i] }, (_, k) => k),
      estado: estados[i] ?? 'medio',
      inicio: CAJAS_INICIO[i],
      diferencia: (n - CAJAS_INICIO[i]) * BULTOS_POR_CAJA,
      bultos: n * BULTOS_POR_CAJA,
    }));
  });

  /** Avance de la línea de pasos (0 a 100 % entre el primero y el último). */
  readonly avance = computed(() => (Math.max(0, Math.min(this.paso(), 2)) / 2) * 100);

  private temporizadores: ReturnType<typeof setTimeout>[] = [];
  private readonly sinMovimiento =
    typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

  constructor() {
    // Al abrir corre sola; al cerrar se detiene y vuelve al inicio.
    effect(() => {
      const abierto = this.abierto();
      untracked(() => (abierto ? this.balancear() : this.reiniciar()));
    });
    inject(DestroyRef).onDestroy(() => this.detener());
  }

  /** Reproduce el ejemplo desde el principio. */
  balancear(): void {
    this.reiniciar();
    if (this.sinMovimiento) {
      this.irAlFinal();
      return;
    }
    this.paso.set(0);
    this.esperar(400, () => this.moverCaja());
  }

  private moverCaja(): void {
    const cajas = [...this.cajas()];
    const mayor = cajas.indexOf(Math.max(...cajas));
    const menor = cajas.indexOf(Math.min(...cajas));
    if (cajas[mayor] - cajas[menor] <= 1) {
      this.esperar(600, () => this.asignar(0));
      return;
    }
    cajas[mayor]--;
    cajas[menor]++;
    this.cajas.set(cajas);
    this.movidas.update((n) => n + 1);
    this.esperar(420, () => this.moverCaja());
  }

  private asignar(chute: number): void {
    this.paso.set(1);
    if (chute >= CAJAS_INICIO.length) {
      this.esperar(600, () => {
        this.paso.set(2);
        this.esperar(900, () => this.paso.set(3));
      });
      return;
    }
    this.operarios.update((o) => o.map((n, i) => (i === chute ? 1 : n)));
    this.esperar(200, () => this.asignar(chute + 1));
  }

  /** El resultado sin animación: cajas parejas, un operario por chute y los pasos completos. */
  private irAlFinal(): void {
    const cajas = [...CAJAS_INICIO];
    let movidas = 0;
    for (;;) {
      const mayor = cajas.indexOf(Math.max(...cajas));
      const menor = cajas.indexOf(Math.min(...cajas));
      if (cajas[mayor] - cajas[menor] <= 1) {
        break;
      }
      cajas[mayor]--;
      cajas[menor]++;
      movidas++;
    }
    this.cajas.set(cajas);
    this.movidas.set(movidas);
    this.operarios.set(cajas.map(() => 1));
    this.paso.set(3);
  }

  private reiniciar(): void {
    this.detener();
    this.cajas.set([...CAJAS_INICIO]);
    this.operarios.set(CAJAS_INICIO.map(() => 0));
    this.movidas.set(0);
    this.paso.set(-1);
  }

  private esperar(ms: number, accion: () => void): void {
    this.temporizadores.push(setTimeout(accion, ms));
  }

  private detener(): void {
    this.temporizadores.forEach(clearTimeout);
    this.temporizadores = [];
  }
}
