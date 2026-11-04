import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { mensajeDeError } from '../../../core/errors/error.util';
import { Auth } from '../../../core/services/auth';
import { SesionStore } from '../../../core/services/sesion';
import { iniciales } from '../../../core/utils/texto.util';
import { Icon } from '../icon/icon';

/** Avisos (deben seguir visibles) y etiquetas que no se ven. */
const NO_AISLAR = new Set(['UI-TOAST-HOST', 'SCRIPT', 'STYLE', 'LINK', 'META', 'TEMPLATE']);

/**
 * Sesión vencida sin salir de la página: pide solo la contraseña de la misma
 * cuenta. Lo de atrás queda inerte (ni clics ni teclado), con su estado
 * intacto, y las peticiones en espera siguen solas al desbloquear. No se
 * cierra con Escape ni con clic fuera: la otra salida es cerrar la sesión.
 */
@Component({
  selector: 'ui-bloqueo-sesion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, Icon],
  templateUrl: './bloqueo-sesion.html',
  styleUrl: './bloqueo-sesion.scss',
})
export class BloqueoSesion {
  private readonly auth = inject(Auth);
  private readonly sesion = inject(SesionStore);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly campo = viewChild<ElementRef<HTMLInputElement>>('campo');

  readonly usuario = this.sesion.usuario;
  readonly iniciales = computed(() => iniciales(this.usuario()));
  readonly contrasena = new FormControl('', { nonNullable: true, validators: Validators.required });
  /** Con [formGroup] el envío lo maneja Angular: el navegador no recarga ni pone nada en la URL. */
  readonly formulario = new FormGroup({ contrasena: this.contrasena });
  readonly enviando = signal(false);
  readonly error = signal<string | null>(null);
  readonly visible = signal(false);

  constructor() {
    let inertes: HTMLElement[] = [];
    // Tras el primer render: antes el componente aún no está en el DOM.
    afterNextRender(() => {
      inertes = this.aislar();
      this.campo()?.nativeElement.focus();
    });
    inject(DestroyRef).onDestroy(() =>
      inertes.forEach((elemento) => elemento.removeAttribute('inert')),
    );
  }

  continuar(): void {
    if (this.enviando()) {
      return;
    }
    if (this.contrasena.invalid) {
      this.contrasena.markAsTouched();
      this.campo()?.nativeElement.focus();
      return;
    }
    this.enviando.set(true);
    this.error.set(null);
    this.auth
      .desbloquear(this.contrasena.value)
      .pipe(finalize(() => this.enviando.set(false)))
      .subscribe({
        error: (err: unknown) => {
          this.error.set(
            err instanceof HttpErrorResponse && err.status === 401
              ? 'Contraseña incorrecta.'
              : mensajeDeError(err),
          );
          this.contrasena.reset('');
          this.campo()?.nativeElement.focus();
        },
      });
  }

  cerrarSesion(): void {
    this.auth.cerrarSesion();
  }

  /** Vuelve inerte todo lo demás (la app y lo que cuelgue de body) salvo los avisos. */
  private aislar(): HTMLElement[] {
    const propio = this.host.nativeElement;
    const raiz = propio.parentElement;
    const candidatos = [
      ...Array.from(raiz?.children ?? []),
      ...Array.from(document.body.children).filter((elemento) => elemento !== raiz),
    ];
    const inertes = candidatos.filter(
      (elemento): elemento is HTMLElement =>
        elemento instanceof HTMLElement &&
        elemento !== propio &&
        !elemento.contains(propio) &&
        !NO_AISLAR.has(elemento.tagName) &&
        !elemento.hasAttribute('inert'),
    );
    inertes.forEach((elemento) => elemento.setAttribute('inert', ''));
    return inertes;
  }
}
