import { Routes } from '@angular/router';
import { PANEL_SIMULACION } from '../../../core/config/paneles';
import { PERMISOS } from '../../../core/config/permisos';
import { permisoGuard } from '../../../core/guards/permiso.guard';
import { PanelShell } from '../panel-shell';
import { aPrimerModulo } from '../panel-rutas';

export const SIMULACION_ROUTES: Routes = [
  // Reporte ejecutivo para imprimir / guardar como PDF: sin el menú del panel.
  {
    path: 'reporte',
    canActivate: [permisoGuard(PERMISOS.VER_DASHBOARD_SIMULACION)],
    loadComponent: () =>
      import('./pages/reporte/reporte').then((m) => m.SimulacionReporteEjecutivo),
  },
  {
    path: '',
    component: PanelShell,
    data: { panel: PANEL_SIMULACION },
    children: [
      { path: '', pathMatch: 'full', redirectTo: aPrimerModulo(PANEL_SIMULACION) },
      {
        path: 'registro',
        canActivate: [permisoGuard(PERMISOS.VER_REGISTRO_SIMULACION)],
        loadComponent: () => import('./pages/registro/registro').then((m) => m.SimulacionRegistro),
      },
      {
        path: 'configuracion',
        canActivate: [permisoGuard(PERMISOS.VER_CONFIGURACION_SIMULACION)],
        // Salir con el equipo o un límite sin guardar pregunta antes de descartarlos.
        canDeactivate: [
          (pagina: { dirty: () => boolean }) =>
            !pagina.dirty() ||
            window.confirm(
              'Tienes cambios sin guardar en los parámetros o en los límites. ¿Salir y descartarlos?',
            ),
        ],
        loadComponent: () =>
          import('./pages/configuracion/configuracion').then((m) => m.SimulacionConfiguracion),
      },
      {
        path: 'dashboard',
        canActivate: [permisoGuard(PERMISOS.VER_DASHBOARD_SIMULACION)],
        loadComponent: () =>
          import('./pages/dashboard/dashboard').then((m) => m.SimulacionDashboard),
      },
    ],
  },
];
