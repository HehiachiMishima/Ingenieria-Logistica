import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Api } from '../../../../core/services/api';
import {
  CambioMalla,
  CentroMalla,
  CentroMallaDatos,
  CentroMallaResumen,
  EvaluacionMalla,
} from './malla.model';

@Service()
export class MallaApi {
  private readonly api = inject(Api);

  centros(): Observable<CentroMallaResumen[]> {
    return this.api.get<CentroMallaResumen[]>('/malla/centros');
  }

  centro(id: number): Observable<CentroMalla> {
    return this.api.get<CentroMalla>(`/malla/centros/${id}`);
  }

  crear(datos: CentroMallaDatos): Observable<CentroMalla> {
    return this.api.post<CentroMalla>('/malla/centros', datos);
  }

  /**
   * Con `version` (el actualizadoEn que se cargó) el servidor responde
   * 409 si otra persona guardó antes, en lugar de pisar sus cambios.
   */
  guardar(id: number, datos: CentroMallaDatos, version: string | null): Observable<CentroMalla> {
    const consulta = version ? `?version=${encodeURIComponent(version)}` : '';
    return this.api.put<CentroMalla>(`/malla/centros/${id}${consulta}`, datos);
  }

  /** Últimos cambios guardados en la malla del centro. */
  cambios(id: number): Observable<CambioMalla[]> {
    return this.api.get<CambioMalla[]>(`/malla/centros/${id}/cambios`);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/malla/centros/${id}`);
  }

  /** Lee un Excel con la plantilla y devuelve sus datos sin guardarlos. */
  importar(archivo: File): Observable<CentroMallaDatos> {
    const formulario = new FormData();
    formulario.append('archivo', archivo);
    return this.api.postForm<CentroMallaDatos>('/malla/centros/importar', formulario);
  }

  plantilla(): Observable<Blob> {
    return this.api.getArchivo('/malla/centros/plantilla');
  }

  exportar(id: number): Observable<Blob> {
    return this.api.getArchivo(`/malla/centros/${id}/excel`);
  }

  /** Reglas del servidor evaluadas sobre la malla de un centro (o de todos, sin centro). */
  evaluacion(centroId?: number): Observable<EvaluacionMalla> {
    return this.api.get<EvaluacionMalla>(
      '/malla/configuracion/evaluacion',
      centroId === undefined ? {} : { centroId },
    );
  }

  /** Activa o apaga una regla o ajusta sus parámetros; luego se vuelve a evaluar. */
  actualizarRegla(
    id: number,
    cambios: { activa?: boolean; parametros?: Record<string, string> },
  ): Observable<unknown> {
    return this.api.put<unknown>(`/malla/configuracion/reglas/${id}`, cambios);
  }
}
