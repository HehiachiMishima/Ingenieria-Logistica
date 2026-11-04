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
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { mensajeDeError } from '../../../core/errors/error.util';
import { Auth } from '../../../core/services/auth';
import { Carga } from '../../../core/services/carga';
import { Notificaciones } from '../../../core/services/notificaciones';
import { Icon } from '../../../shared/ui/icon/icon';
import { CAPAS, ENLACES, LIMA, NODOS, PERU, RUTAS } from './login-escena';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, Icon],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly notificaciones = inject(Notificaciones);
  private readonly carga = inject(Carga);
  private readonly hero = viewChild.required<ElementRef<HTMLElement>>('hero');

  readonly cargando = signal(false);
  readonly error = signal<string | null>(null);
  readonly verContrasena = signal(false);

  readonly titulo = ['A', 'T', 'L', 'A', 'S'] as const;

  /** Concepto de la plataforma: acróstico ATLAS. */
  readonly concepto = ['Análisis', 'Trazabilidad', 'Logística', 'Automatización', 'Sincronización'] as const;

  /** Escena del hero: mapa del Perú, red de rutas y pines (ver login-escena.ts). */
  readonly peru = PERU;
  readonly hub = LIMA;
  readonly nodos = NODOS;
  readonly rutas = RUTAS;
  readonly capas = CAPAS;
  readonly enlaces = ENLACES;

  /** Capas de la plataforma mostradas en la barra inferior del hero. */
  readonly pie: ReadonlyArray<{ texto: string; icono: string }> = [
    { texto: 'Cadena de suministro', icono: 'almacen' },
    { texto: 'Integración de sistemas', icono: 'engranaje' },
    { texto: 'Datos y analítica', icono: 'base_datos' },
    { texto: 'Procesos logísticos', icono: 'nodos' },
    { texto: 'Slotting', icono: 'area' },
    { texto: 'Farmacias', icono: 'farmacia' },
    { texto: 'Indicadores de operación', icono: 'dashboard' },
  ];

  readonly formulario = this.fb.nonNullable.group({
    usuario: ['', [Validators.required]],
    contrasena: ['', [Validators.required]],
  });

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => destroyRef.onDestroy(this.activarParallax(this.hero().nativeElement)));
  }

  campoInvalido(campo: 'usuario' | 'contrasena'): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  toggleContrasena(): void {
    this.verContrasena.update((valor) => !valor);
  }

  enviar(): void {
    if (this.cargando()) {
      return;
    }
    if (this.formulario.invalid) {
      this.formulario.markAllAsTouched();
      return;
    }

    this.cargando.set(true);
    this.error.set(null);
    this.carga.mostrar('Ingresando a Atlas', 'Preparando tus paneles de operación');

    const { usuario, contrasena } = this.formulario.getRawValue();

    this.auth.iniciarSesion({ usuario, contrasena }).subscribe({
      next: (datos) => {
        this.cargando.set(false);
        this.notificaciones.exito('Acceso concedido', `Hola, ${datos.usuario?.nombre ?? usuario}.`);
        const destino = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/';
        void this.router.navigateByUrl(destino).then(() => this.carga.ocultar());
      },
      error: (err: unknown) => {
        this.cargando.set(false);
        this.carga.ocultar(true);
        this.error.set(mensajeDeError(err));
      },
    });
  }

  /**
   * Parallax sutil de la escena siguiendo el puntero: publica --mx / --my
   * (-1…1) en el hero, una vez por frame. Solo con puntero fino y sin
   * preferencia de movimiento reducido. Devuelve la función de limpieza.
   */
  private activarParallax(hero: HTMLElement): () => void {
    const apto = window.matchMedia('(pointer: fine) and (prefers-reduced-motion: no-preference)');
    if (!apto.matches) {
      return () => undefined;
    }
    let frame = 0;
    const mover = (evento: PointerEvent): void => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const caja = hero.getBoundingClientRect();
        const x = ((evento.clientX - caja.left) / caja.width) * 2 - 1;
        const y = ((evento.clientY - caja.top) / caja.height) * 2 - 1;
        hero.style.setProperty('--mx', x.toFixed(3));
        hero.style.setProperty('--my', y.toFixed(3));
      });
    };
    const salir = (): void => {
      cancelAnimationFrame(frame);
      hero.style.setProperty('--mx', '0');
      hero.style.setProperty('--my', '0');
    };
    hero.addEventListener('pointermove', mover, { passive: true });
    hero.addEventListener('pointerleave', salir);
    return () => {
      cancelAnimationFrame(frame);
      hero.removeEventListener('pointermove', mover);
      hero.removeEventListener('pointerleave', salir);
    };
  }
}
