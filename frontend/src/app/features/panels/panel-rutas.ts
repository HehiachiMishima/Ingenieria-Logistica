import { inject } from '@angular/core';
import { RedirectFunction } from '@angular/router';
import { PanelDef, primerModuloAccesible } from '../../core/config/paneles';
import { SesionStore } from '../../core/services/sesion';

/**
 * Redirección de la ruta raíz de un panel (`/bitacora` → `/bitacora/incidencias`)
 * al primer módulo que el usuario puede abrir, o a /sin-acceso si no tiene ninguno.
 */
export function aPrimerModulo(panel: PanelDef): RedirectFunction {
  return () => {
    const modulo = primerModuloAccesible(panel, inject(SesionStore).permisos());
    return modulo ? `${panel.ruta}/${modulo.ruta}` : '/sin-acceso';
  };
}
