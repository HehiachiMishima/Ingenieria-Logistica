import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { Router } from '@angular/router';
import { Auth } from '../../core/services/auth';
import { Icon } from '../../shared/ui/icon/icon';

@Component({
  selector: 'app-sin-acceso',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="denied">
      <div class="denied__card">
        <span class="denied__icon"><ui-icon name="lock" [size]="30" /></span>
        <h1 class="denied__title">Acceso denegado</h1>
        <p class="denied__text">
          No tienes el permiso requerido para ver este contenido.
          @if (permiso()) {
            <span class="denied__permiso">{{ permiso() }}</span>
          }
        </p>
        <div class="denied__actions">
          <button type="button" class="ui-btn ui-btn--primary" (click)="irInicio()">
            <ui-icon name="panel" [size]="18" />
            Volver al inicio
          </button>
          <button type="button" class="ui-btn ui-btn--ghost" (click)="cerrarSesion()">
            Cerrar sesión
          </button>
        </div>
      </div>
    </div>
  `,
  styles: `
    .denied {
      min-height: 100dvh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: var(--space-6);
      background: var(--bg);
    }
    .denied__card {
      max-width: 440px;
      width: 100%;
      text-align: center;
      padding: var(--space-10);
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius-xl);
      box-shadow: var(--shadow-lg);
      animation: ui-rise var(--transition-slow) both;
    }
    .denied__icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 64px;
      height: 64px;
      border-radius: var(--radius-lg);
      background: var(--danger-soft);
      color: var(--danger);
      margin-bottom: var(--space-5);
    }
    .denied__title {
      font-size: var(--fs-2xl);
    }
    .denied__text {
      margin-top: var(--space-3);
      color: var(--text-2);
    }
    .denied__permiso {
      display: inline-block;
      margin-top: var(--space-3);
      padding: 2px 10px;
      border-radius: var(--radius-pill);
      background: var(--surface-3);
      color: var(--text-2);
      font-family: var(--font-mono);
      font-size: var(--fs-sm);
    }
    .denied__actions {
      margin-top: var(--space-6);
      display: flex;
      gap: var(--space-3);
      justify-content: center;
    }
  `,
})
export class SinAcceso {
  private readonly router = inject(Router);
  private readonly auth = inject(Auth);

  readonly permiso = input<string>('');

  irInicio(): void {
    void this.router.navigateByUrl('/');
  }

  cerrarSesion(): void {
    this.auth.cerrarSesion();
  }
}
