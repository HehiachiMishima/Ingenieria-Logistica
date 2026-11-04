import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { PANELES, PanelDef, modulosAccesibles, panelAccesible } from '../../core/config/paneles';
import { Auth } from '../../core/services/auth';
import { Notificaciones } from '../../core/services/notificaciones';
import { TemaService } from '../../core/services/tema';
import { saludoSegunHora } from '../../core/utils/fecha.util';
import { iniciales } from '../../core/utils/texto.util';
import { Icon } from '../../shared/ui/icon/icon';

@Component({
  selector: 'app-launcher',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  templateUrl: './launcher.html',
  styleUrl: './launcher.scss',
})
export class Launcher {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly notificaciones = inject(Notificaciones);
  readonly tema = inject(TemaService);

  readonly paneles = PANELES;
  readonly usuario = this.auth.usuario;
  readonly permisos = this.auth.permisos;

  /**
   * Tarjeta de cada panel con su acceso ya resuelto: la plantilla no vuelve a
   * evaluar los permisos en cada detección de cambios. Los chips muestran los
   * módulos accesibles, o todos si el panel está bloqueado.
   */
  readonly tarjetas = computed(() => {
    const permisos = this.permisos();
    return this.paneles.map((panel) => {
      const accesible = panelAccesible(panel, permisos);
      return {
        panel,
        accesible,
        modulos: accesible ? modulosAccesibles(panel, permisos) : panel.modulos,
      };
    });
  });

  readonly accesibles = computed(() => this.tarjetas().filter((t) => t.accesible).length);

  readonly saludo = saludoSegunHora();

  readonly fecha = new Date().toLocaleDateString('es-PE', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });

  readonly iniciales = computed(() => iniciales(this.usuario()));

  abrir(panel: PanelDef, accesible: boolean): void {
    if (accesible) {
      void this.router.navigateByUrl(panel.ruta);
      return;
    }
    this.notificaciones.error(
      'Acceso denegado',
      `No tienes permiso para acceder a ${panel.nombre}.`,
    );
  }

  alternarTema(evento: Event): void {
    this.tema.alternar(evento);
  }

  cerrarSesion(): void {
    this.auth.cerrarSesion();
  }
}
