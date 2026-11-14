import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { EstadoCara, IconoSim } from '../../../../icono-sim/icono-sim';

/** Un lado de un chute en la cortina: su cifra, las piezas de su torre y su carita. */
export interface LadoCortina {
  valor: string;
  piezas: number[];
  estado: EstadoCara | null;
}

export interface ChuteCortina {
  id: number;
  nombre: string;
  /** «CHUTE 15» → «15»: la columna es angosta; el nombre completo va en el title. */
  corto: string;
  antes: LadoCortina;
  despues: LadoCortina;
}

/**
 * Una capa de la cortina: todos los chutes de un lado (antes o después). Componente aparte y
 * OnPush: mover la cortina solo cambia el recorte de su host, sin volver a revisar las columnas.
 */
@Component({
  selector: 'app-capa-cortina',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconoSim],
  host: { class: 'cc-capa', 'aria-hidden': 'true' },
  styleUrl: './capa-cortina.scss',
  template: `
    @for (c of chutes(); track c.id) {
      @let l = lado() === 'antes' ? c.antes : c.despues;
      <div
        class="cc-col"
        [title]="c.nombre + ': ' + l.valor + (operarios() ? ' operario(s)' : ' bultos')"
      >
        @if (l.estado) {
          <app-icono-sim nombre="cara" [estado]="l.estado" [tamano]="20" />
        } @else {
          <span class="cc-col__sin-cara"></span>
        }
        <span class="cc-col__torre">
          @for (k of l.piezas; track k) {
            <app-icono-sim
              [nombre]="operarios() ? 'operario' : 'caja'"
              [tamano]="operarios() ? 18 : 20"
            />
          }
        </span>
        <app-icono-sim nombre="rampa" />
        <span class="cc-col__pie">
          <strong>{{ l.valor }}</strong>
          <span>{{ c.corto }}</span>
        </span>
      </div>
    }
  `,
})
export class CapaCortina {
  readonly chutes = input.required<readonly ChuteCortina[]>();
  readonly lado = input.required<'antes' | 'despues'>();
  readonly operarios = input(false);
}

/**
 * Cortina antes / después: las dos capas superpuestas; a la izquierda del borde se ve el antes y
 * a la derecha el después. El borde se arrastra con la manija (mouse o dedo), se lleva con un
 * clic o toque, o con las flechas del teclado. Al llegar datos nuevos corre sola hasta la mitad
 * (sin animar con «reducir movimiento»).
 */
@Component({
  selector: 'app-cortina-comparativa',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CapaCortina, IconoSim],
  templateUrl: './cortina-comparativa.html',
  styleUrl: './cortina-comparativa.scss',
})
export class CortinaComparativa {
  readonly chutes = input.required<readonly ChuteCortina[]>();
  readonly operarios = input(false);

  /** Borde en % desde la izquierda. */
  readonly posicion = signal(50);
  readonly posicionRedonda = computed(() => Math.round(this.posicion()));
  readonly recorte = computed(() => `inset(0 ${100 - this.posicion()}% 0 0)`);

  private animacion = 0;
  private readonly sinMovimiento =
    typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

  constructor() {
    // Datos nuevos (otro lanzamiento): la cortina vuelve a correr hasta la mitad.
    effect(() => {
      this.chutes();
      untracked(() => this.barrer());
    });
    inject(DestroyRef).onDestroy(() => cancelAnimationFrame(this.animacion));
  }

  /** Clic o toque en la escena: el borde va ahí. */
  llevar(evento: MouseEvent, escena: HTMLElement): void {
    cancelAnimationFrame(this.animacion);
    const caja = escena.getBoundingClientRect();
    this.posicion.set(
      Math.max(0, Math.min(100, ((evento.clientX - caja.left) / caja.width) * 100)),
    );
  }

  /** Arrastre de la manija: captura el puntero para seguirlo fuera de ella. */
  tomar(evento: PointerEvent): void {
    evento.preventDefault();
    (evento.currentTarget as HTMLElement).setPointerCapture(evento.pointerId);
  }

  mover(evento: PointerEvent, escena: HTMLElement): void {
    if ((evento.currentTarget as HTMLElement).hasPointerCapture(evento.pointerId)) {
      this.llevar(evento, escena);
    }
  }

  /** Flechas: de a 5 %; Inicio y Fin, a los extremos. */
  tecla(evento: KeyboardEvent): void {
    const pasos: Record<string, number> = { ArrowLeft: -5, ArrowRight: 5, Home: -100, End: 100 };
    if (evento.key in pasos) {
      evento.preventDefault();
      cancelAnimationFrame(this.animacion);
      this.posicion.update((p) => Math.max(0, Math.min(100, p + pasos[evento.key])));
    }
  }

  private barrer(): void {
    cancelAnimationFrame(this.animacion);
    if (this.sinMovimiento) {
      this.posicion.set(50);
      return;
    }
    const inicio = performance.now();
    const paso = (ahora: number) => {
      const t = Math.min(1, (ahora - inicio) / 700);
      this.posicion.set(8 + 42 * (1 - (1 - t) ** 3));
      if (t < 1) {
        this.animacion = requestAnimationFrame(paso);
      }
    };
    this.animacion = requestAnimationFrame(paso);
  }
}
