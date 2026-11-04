import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { mensajeDeError } from '../../../core/errors/error.util';
import { Auth } from '../../../core/services/auth';
import { Notificaciones } from '../../../core/services/notificaciones';
import { iniciales } from '../../../core/utils/texto.util';
import { Icon } from '../icon/icon';
import { Modal } from '../modal/modal';

/**
 * Modal "Mi cuenta": muestra en solo lectura los datos del usuario
 * autenticado y permite cambiar su propia contraseña (exige la actual).
 */
@Component({
  selector: 'ui-mi-cuenta',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, Icon, Modal],
  templateUrl: './mi-cuenta.html',
  styleUrl: './mi-cuenta.scss',
})
export class MiCuenta {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(Auth);
  private readonly notificaciones = inject(Notificaciones);

  readonly abierto = input(false);
  readonly cerrar = output<void>();

  readonly usuario = this.auth.usuario;

  readonly iniciales = computed(() => iniciales(this.usuario()));

  readonly cambiando = signal(false);
  readonly errorContrasena = signal<string | null>(null);
  readonly verActual = signal(false);
  readonly verNueva = signal(false);
  readonly verConfirmar = signal(false);

  readonly formulario = this.fb.nonNullable.group({
    contrasenaActual: ['', [Validators.required]],
    contrasenaNueva: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(72)]],
    contrasenaConfirmar: ['', [Validators.required]],
  });

  campoInvalido(campo: 'contrasenaActual' | 'contrasenaNueva' | 'contrasenaConfirmar'): boolean {
    const control = this.formulario.controls[campo];
    return control.touched && control.invalid;
  }

  /** Valores del formulario como señal: un `computed` no ve los cambios de un FormGroup. */
  private readonly valores = toSignal(this.formulario.valueChanges, {
    initialValue: this.formulario.getRawValue(),
  });

  /** La confirmación no coincide con la nueva contraseña. */
  readonly confirmacionNoCoincide = computed(() => {
    const { contrasenaNueva = '', contrasenaConfirmar = '' } = this.valores();
    return contrasenaConfirmar.length > 0 && contrasenaNueva !== contrasenaConfirmar;
  });

  alternarVer(campo: 'actual' | 'nueva' | 'confirmar'): void {
    if (campo === 'actual') this.verActual.update((v) => !v);
    if (campo === 'nueva') this.verNueva.update((v) => !v);
    if (campo === 'confirmar') this.verConfirmar.update((v) => !v);
  }

  cambiarContrasena(): void {
    if (this.cambiando()) {
      return;
    }
    this.errorContrasena.set(null);
    if (this.formulario.invalid) {
      this.formulario.markAllAsTouched();
      return;
    }
    const { contrasenaActual, contrasenaNueva, contrasenaConfirmar } = this.formulario.getRawValue();
    if (contrasenaNueva !== contrasenaConfirmar) {
      this.errorContrasena.set('La confirmación no coincide con la nueva contraseña.');
      return;
    }
    if (contrasenaNueva === contrasenaActual) {
      this.errorContrasena.set('La contraseña nueva debe ser distinta a la actual.');
      return;
    }

    this.cambiando.set(true);
    this.auth
      .cambiarContrasena({ contrasenaActual, contrasenaNueva })
      .pipe(finalize(() => this.cambiando.set(false)))
      .subscribe({
        next: () => {
          this.notificaciones.exito('Contraseña actualizada', 'Tu contraseña se cambió correctamente.');
          this.formulario.reset({ contrasenaActual: '', contrasenaNueva: '', contrasenaConfirmar: '' });
          this.cerrar.emit();
        },
        error: (err: unknown) => this.errorContrasena.set(mensajeDeError(err)),
      });
  }
}
