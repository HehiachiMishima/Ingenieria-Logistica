import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ArchivoSimulacion,
  BalanceoSimulacion,
  ConfiguracionSimulacion,
  ConfiguracionSimulacionRequest,
  DashboardSimulacion,
  FaseBalanceo,
  HistorialRegistroSimulacion,
  HistorialSimulacion,
  PaginaRutas,
  RegistroSimulacionActualizarRequest,
  RutasDashboardFiltros,
  RutasDashboardSimulacion,
  RutasSimulacionFiltros,
} from './simulacion.model';
import { Pagina } from '../../../../core/models';
import { Api } from '../../../../core/services/api';

@Service()
export class SimulacionApi {
  private readonly api = inject(Api);

  listar(
    pagina = 1,
    tamano = 10,
    texto = '',
    estados: string[] = [],
  ): Observable<Pagina<ArchivoSimulacion>> {
    return this.api.get<Pagina<ArchivoSimulacion>>('/simulacion/registros', {
      pagina,
      tamano,
      texto,
      ...(estados.length > 0 ? { estados: estados.join(',') } : {}),
    });
  }

  opciones(): Observable<ArchivoSimulacion[]> {
    return this.api.get<ArchivoSimulacion[]>('/simulacion/registros/opciones');
  }

  subir(formulario: FormData): Observable<ArchivoSimulacion> {
    return this.api.postForm<ArchivoSimulacion>('/simulacion/registros', formulario);
  }

  actualizar(
    id: number,
    peticion: RegistroSimulacionActualizarRequest,
  ): Observable<ArchivoSimulacion> {
    return this.api.put<ArchivoSimulacion>(`/simulacion/registros/${id}`, peticion);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/simulacion/registros/${id}`);
  }

  configuracion(id: number): Observable<ConfiguracionSimulacion> {
    return this.api.get<ConfiguracionSimulacion>(`/simulacion/registros/${id}/configuracion`);
  }

  /** Rutas paginadas del registro; el texto filtra por prefijo del código. */
  /** Rutas de la planilla por keyset (cursor = id de la última recibida). */
  rutas(id: number, filtros: RutasSimulacionFiltros): Observable<PaginaRutas> {
    return this.api.get<PaginaRutas>(`/simulacion/registros/${id}/rutas`, {
      ...(filtros.despues !== null ? { despues: filtros.despues } : {}),
      tamano: filtros.tamano,
      texto: filtros.texto,
      ...(filtros.clusterIds.length > 0 ? { clusterIds: filtros.clusterIds.join(',') } : {}),
      ...(filtros.chuteIds.length > 0 ? { chuteIds: filtros.chuteIds.join(',') } : {}),
    });
  }

  /** Equipo (operarios y velocidad) y límites de horas de los clusters. */
  guardarConfiguracion(
    id: number,
    peticion: ConfiguracionSimulacionRequest,
  ): Observable<ConfiguracionSimulacion> {
    return this.api.put<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/configuracion`,
      peticion,
    );
  }

  /** Trabaja el cluster en curso: paso 1 (RUTAS) o paso 2 (OPERARIOS) de su ola. */
  balancear(id: number, clusterId: number, fase: FaseBalanceo): Observable<BalanceoSimulacion> {
    return this.api.post<BalanceoSimulacion>(
      `/simulacion/registros/${id}/clusters/${clusterId}/balancear?fase=${fase}`,
    );
  }

  /** Mueve una ruta a otro chute de su mismo cluster (movimiento manual). */
  moverRuta(id: number, rutaId: number, chuteId: number): Observable<ConfiguracionSimulacion> {
    return this.api.put<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/rutas/${rutaId}/mover`,
      { chuteId },
    );
  }

  /**
   * Pasa una ruta a otro cluster (antes o después de balancear, con ninguno de
   * los dos cerrado) y, si se indica, también a otro chute en el mismo paso.
   */
  cambiarCluster(
    id: number,
    rutaId: number,
    clusterId: number,
    chuteId: number | null = null,
  ): Observable<ConfiguracionSimulacion> {
    return this.api.put<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/rutas/${rutaId}/cluster`,
      chuteId === null ? { clusterId } : { clusterId, chuteId },
    );
  }

  /**
   * El cluster en curso vuelve a como estaba antes de trabajarlo: se deshace su
   * balanceo y lo cambiado a mano mientras estuvo en curso; los cerrados no cambian.
   */
  restablecerCluster(id: number, clusterId: number): Observable<ConfiguracionSimulacion> {
    return this.api.post<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/clusters/${clusterId}/restablecer`,
    );
  }

  restablecer(id: number): Observable<void> {
    return this.api.post<void>(`/simulacion/registros/${id}/restablecer`);
  }

  /** Cierra el cluster en curso: el equipo pasa al siguiente con esta dotación. */
  cerrar(id: number, clusterId: number): Observable<ConfiguracionSimulacion> {
    return this.api.post<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/clusters/${clusterId}/cerrar`,
    );
  }

  /** Reabre un cluster cerrado; los siguientes vuelven a la proyección. */
  reabrir(id: number, clusterId: number): Observable<ConfiguracionSimulacion> {
    return this.api.post<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/clusters/${clusterId}/reabrir`,
    );
  }

  historial(id: number): Observable<HistorialRegistroSimulacion> {
    return this.api.get<HistorialRegistroSimulacion>(`/simulacion/registros/${id}/historial`);
  }

  /** Cambios de un lanzamiento (null: los que no tienen), del más reciente al más antiguo. */
  historialCambios(
    id: number,
    lanzamientoId: number | null,
    pagina: number,
    tamano: number,
  ): Observable<Pagina<HistorialSimulacion>> {
    return this.api.get<Pagina<HistorialSimulacion>>(
      `/simulacion/registros/${id}/historial/cambios`,
      { lanzamientoId, pagina, tamano },
    );
  }

  /** Sin clusterId, el resumen del registro; con él, la vista de ese cluster. */
  dashboard(id: number, clusterId: number | null = null): Observable<DashboardSimulacion> {
    return this.api.get<DashboardSimulacion>(
      `/simulacion/registros/${id}/dashboard`,
      clusterId === null ? {} : { clusterId },
    );
  }

  /** Rutas de un lado del chute (antes o después), paginadas y con totales del filtro. */
  rutasDashboard(id: number, filtros: RutasDashboardFiltros): Observable<RutasDashboardSimulacion> {
    return this.api.get<RutasDashboardSimulacion>(`/simulacion/registros/${id}/dashboard/rutas`, {
      pagina: filtros.pagina,
      tamano: filtros.tamano,
      texto: filtros.texto,
      tipo: filtros.tipo,
      chuteIds: filtros.chuteIds.join(','),
      lado: filtros.lado,
      clusterId: filtros.clusterId,
      soloCambios: filtros.soloCambios,
    });
  }
}
