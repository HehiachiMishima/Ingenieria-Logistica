import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CONFIG } from '../../../core/config/configuracion';
import { marcarIntroVista } from '../../../core/guards/intro.guard';
import { INTRO, MARCA, TIEMPOS, franjasCss } from './intro-diseno';
import { IntroEscena } from './intro-escena';
import { IntroSonido } from './intro-sonido';

/**
 * Intro cinematográfica de Atlas (máx. 8 s) previa al login: escena de luz en
 * canvas, logotipo y banda sonora sintetizada. Sin controles: un clic en
 * cualquier parte (o Esc) la salta; al terminar va al login conservando el
 * returnUrl. Todo el diseño (tiempos, colores, textos) está en intro-diseno.ts.
 */
@Component({
  selector: 'app-intro',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './intro.html',
  styleUrl: './intro.scss',
  host: {
    '[class.intro--reproduciendo]': 'reproduciendo()',
    '[class.intro--saliendo]': 'saliendo()',
    '[style]': 'estilos',
    '(click)': 'omitir()',
    '(document:keydown.escape)': 'omitir()',
  },
})
export class Intro {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly lienzo = viewChild.required<ElementRef<HTMLCanvasElement>>('lienzo');
  private readonly titulo = viewChild.required<ElementRef<HTMLElement>>('titulo');

  readonly marca = MARCA;
  readonly letras = [...MARCA.palabra].map((letra, i) => ({
  letra,
  tono:
    i === MARCA.indiceAmarilla
      ? 'amarilla'
      : i === MARCA.indiceMixta
        ? 'mixta'
        : 'roja',
}));
  readonly reproduciendo = signal(false);
  readonly saliendo = signal(false);

  /** Con movimiento reducido se omite la escena de luz y se empieza en el logotipo. */
  private readonly reducido =
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
  private readonly desfase = this.reducido ? INTRO.marca - TIEMPOS.previoReducido : 0;

  /** Diseño compartido con el SCSS: línea de tiempo y franjas de las letras. */
  readonly estilos = {
    '--intro-franjas': franjasCss(),
    '--intro-marca': `${INTRO.marca}s`,
    '--intro-salida': `${INTRO.salida}s`,
    '--intro-duracion': `${INTRO.duracion}s`,
    '--intro-desfase': `${this.desfase}s`,
  };

  private readonly audio = new IntroSonido();
  private escena: IntroEscena | null = null;
  private observador: ResizeObserver | null = null;
  private inicio = 0;
  private cuadro = 0;
  private temporizador: ReturnType<typeof setTimeout> | undefined;
  private terminado = false;

  constructor() {
    // Precarga el login para que el corte final sea inmediato.
    void import('../login/login');
    afterNextRender(() => void this.iniciar());
    inject(DestroyRef).onDestroy(() => this.liberar());
  }

  omitir(): void {
    if (this.terminado || this.saliendo()) {
      return;
    }
    this.saliendo.set(true);
    this.audio.detener();
    clearTimeout(this.temporizador);
    this.temporizador = setTimeout(() => this.terminar(), TIEMPOS.corteMs);
  }

  private async iniciar(): Promise<void> {
    const ctx = this.lienzo().nativeElement.getContext('2d', { alpha: false });
    if (ctx) {
      this.escena = new IntroEscena(ctx, this.reducido);
      this.observador = new ResizeObserver(() => this.medir());
      this.observador.observe(this.lienzo().nativeElement);
    }

    const [, sonora] = await Promise.all([
      Promise.race([
        document.fonts.load(MARCA.fuente).catch(() => undefined),
        new Promise((listo) => setTimeout(listo, TIEMPOS.esperaFuenteMs)),
      ]),
      this.audio.intentar(),
    ]);
    if (this.terminado) {
      return;
    }

    this.medir();
    this.inicio = performance.now() - this.desfase * 1000;
    this.reproduciendo.set(true);
    this.cuadro = requestAnimationFrame(this.animar);
    this.temporizador = setTimeout(() => this.terminar(), (INTRO.duracion - this.desfase) * 1000);
    // Si el navegador bloquea el audio automático, la intro sigue en silencio.
    if (sonora) {
      this.audio.reproducir(this.desfase);
    }
  }

  private segundo(): number {
    return (performance.now() - this.inicio) / 1000;
  }

  private readonly animar = (): void => {
    this.escena?.dibujar(this.segundo());
    if (!this.terminado) {
      this.cuadro = requestAnimationFrame(this.animar);
    }
  };

  /** Ajusta el lienzo y ubica el logotipo, hacia el que se cierra la cortina de luz. */
  private medir(): void {
    if (!this.escena) {
      return;
    }
    this.escena.redimensionar();
    const caja = this.titulo().nativeElement.getBoundingClientRect();
    this.escena.marco = { x: caja.left, y: caja.top, ancho: caja.width, alto: caja.height };
    this.escena.dibujar(this.reproduciendo() ? this.segundo() : this.desfase);
  }

  private terminar(): void {
    if (this.terminado) {
      return;
    }
    marcarIntroVista();
    this.liberar();
    void this.router.navigate([CONFIG.rutas.login], {
      queryParams: this.route.snapshot.queryParams,
      replaceUrl: true,
    });
  }

  private liberar(): void {
    this.terminado = true;
    cancelAnimationFrame(this.cuadro);
    clearTimeout(this.temporizador);
    this.observador?.disconnect();
    this.audio.detener();
  }
}
