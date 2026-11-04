import { HttpClient, HttpContext } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { environment } from '../../../environments/environment';
import { PETICION_SILENCIOSA } from '../interceptors/error.interceptor';
import { ApiResponse, SesionVigente } from '../models';
import { SesionStore } from './sesion';

/** Intervalo mínimo entre dos sincronizaciones (evita ráfagas al cambiar de pestaña). */
const INTERVALO_MINIMO_MS = 60_000;

/**
 * Mantiene los permisos de la sesión al día con el servidor.
 *
 * El JWT solo lleva la identidad; los permisos se resuelven en el backend por
 * petición. Si un administrador cambia roles o permisos, el menú y los guards
 * del navegador quedarían desfasados hasta el siguiente login. Este servicio
 * consulta GET /auth/sesion al arrancar y cada vez que la pestaña vuelve a
 * estar visible (como mucho una vez por minuto).
 */
@Service()
export class SesionSincronizador {
  private readonly http = inject(HttpClient);
  private readonly sesionStore = inject(SesionStore);
  private readonly url = `${environment.apiUrl.replace(/\/+$/, '')}/auth/sesion`;
  private ultima = 0;
  private enCurso = false;

  constructor() {
    this.sincronizar(true);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        this.sincronizar();
      }
    });
  }

  sincronizar(forzar = false): void {
    if (!this.sesionStore.autenticado() || this.enCurso) {
      return;
    }
    const ahora = Date.now();
    if (!forzar && ahora - this.ultima < INTERVALO_MINIMO_MS) {
      return;
    }
    this.ultima = ahora;
    this.enCurso = true;
    this.http
      .get<ApiResponse<SesionVigente>>(this.url, { context: new HttpContext().set(PETICION_SILENCIOSA, true) })
      .subscribe({
      next: ({ datos }) => {
        this.enCurso = false;
        this.sesionStore.actualizar(datos.usuario, datos.permisos);
      },
      // 401 lo gestiona el interceptor de errores (cierra la sesión).
      error: () => {
        this.enCurso = false;
      },
    });
  }
}
