import { HttpContext } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PETICION_SILENCIOSA } from '../../../../core/interceptors/error.interceptor';
import { Api } from '../../../../core/services/api';
import {
  CambioRutas,
  DetalleRutaDiccionario,
  Diccionario,
  FrecuenciaDiccionario,
  RutaDiccionario,
  RutaDiccionarioRequest,
  TipoRutaDiccionario,
  UbicacionRuta,
} from './diccionario.model';

const BASE = '/simulacion/diccionario';

/** Registro v2: diccionario de rutas (módulo independiente del registro por Excel). */
@Service()
export class DiccionarioApi {
  private readonly api = inject(Api);

  ver(): Observable<Diccionario> {
    return this.api.get<Diccionario>(BASE);
  }

  /** Ruta con sus locales. Silenciosa: la ficha muestra su propio error con «Reintentar». */
  detalle(id: number): Observable<DetalleRutaDiccionario> {
    return this.api.get<DetalleRutaDiccionario>(`${BASE}/rutas/${id}`, undefined, new HttpContext().set(PETICION_SILENCIOSA, true));
  }

  crear(peticion: RutaDiccionarioRequest): Observable<RutaDiccionario> {
    return this.api.post<RutaDiccionario>(`${BASE}/rutas`, peticion);
  }

  actualizar(id: number, peticion: RutaDiccionarioRequest): Observable<RutaDiccionario> {
    return this.api.put<RutaDiccionario>(`${BASE}/rutas/${id}`, peticion);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`${BASE}/rutas/${id}`);
  }

  /** A un cluster (conservan frecuencia y grupo) o a Datos (`clusterId` null: los pierden). */
  mover(rutaIds: readonly number[], clusterId: number | null): Observable<CambioRutas> {
    return this.api.put<CambioRutas>(`${BASE}/rutas/mover`, { rutaIds, clusterId });
  }

  asignarFrecuencia(rutaIds: readonly number[], frecuencia: FrecuenciaDiccionario): Observable<CambioRutas> {
    return this.api.put<CambioRutas>(`${BASE}/rutas/frecuencia`, { rutaIds, frecuencia });
  }

  asignarGrupo(rutaIds: readonly number[], grupo: string): Observable<CambioRutas> {
    return this.api.put<CambioRutas>(`${BASE}/rutas/grupo`, { rutaIds, grupo });
  }

  cambiarTipo(rutaIds: readonly number[], tipo: TipoRutaDiccionario): Observable<CambioRutas> {
    return this.api.put<CambioRutas>(`${BASE}/rutas/tipo`, { rutaIds, tipo });
  }

  /** Deshacer el último movimiento. */
  restaurar(rutas: readonly UbicacionRuta[]): Observable<CambioRutas> {
    return this.api.put<CambioRutas>(`${BASE}/rutas/restaurar`, { rutas });
  }

  cambiarClusters(cantidad: number): Observable<Diccionario> {
    return this.api.put<Diccionario>(`${BASE}/clusters`, { cantidad });
  }
}
