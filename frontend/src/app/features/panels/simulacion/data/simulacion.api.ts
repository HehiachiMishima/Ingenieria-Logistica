import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ArchivoSimulacion,
  Balanceo,
  BalanceoCuerpo,
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
  VistaBalanceo,
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

  /** Configuración de un balanceo (L-X-V o M-J-S): sus clusters, su carga y los chutes. */
  configuracion(id: number, balanceo: Balanceo = 'LXV'): Observable<ConfiguracionSimulacion> {
    return this.api.get<ConfiguracionSimulacion>(`/simulacion/registros/${id}/configuracion`, { balanceo });
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

  /** Velocidad, máximo por chute y límites de horas de los clusters. */
  guardarConfiguracion(
    id: number,
    peticion: ConfiguracionSimulacionRequest,
    balanceo: Balanceo = 'LXV',
  ): Observable<ConfiguracionSimulacion> {
    return this.api.put<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/configuracion?balanceo=${balanceo}`,
      peticion,
    );
  }

  /**
   * Trabaja el cluster en curso: paso 1 (RUTAS) o paso 2 (OPERARIOS) de su ola.
   * El cuerpo lleva el rango de chutes (paso 1) o la cantidad de operarios (paso 2).
   */
  balancear(
    id: number,
    clusterId: number,
    fase: FaseBalanceo,
    cuerpo: BalanceoCuerpo | null = null,
    balanceo: Balanceo = 'LXV',
  ): Observable<BalanceoSimulacion> {
    return this.api.post<BalanceoSimulacion>(
      `/simulacion/registros/${id}/clusters/${clusterId}/balancear?fase=${fase}&balanceo=${balanceo}`,
      cuerpo,
    );
  }

  /**
   * Vista previa del paso 1 (no cambia nada): los grupos del cluster en orden de
   * prioridad y el tramo de chutes de cada uno con ese rango. La prioridad viaja
   * como un parámetro por grupo, así un nombre con coma no se parte.
   */
  vistaBalanceo(
    id: number,
    clusterId: number,
    desde: number | null,
    hasta: number | null,
    prioridad: string[],
    balanceo: Balanceo = 'LXV',
  ): Observable<VistaBalanceo> {
    return this.api.get<VistaBalanceo>(
      `/simulacion/registros/${id}/clusters/${clusterId}/balanceo/vista`,
      { desde, hasta, balanceo, ...(prioridad.length > 0 ? { prioridad } : {}) },
    );
  }

  /** Mueve una ruta a otro chute de su mismo cluster (movimiento manual). */
  moverRuta(
    id: number,
    rutaId: number,
    chuteId: number,
    balanceo: Balanceo = 'LXV',
  ): Observable<ConfiguracionSimulacion> {
    return this.api.put<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/rutas/${rutaId}/mover?balanceo=${balanceo}`,
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
    balanceo: Balanceo = 'LXV',
  ): Observable<ConfiguracionSimulacion> {
    return this.api.put<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/rutas/${rutaId}/cluster?balanceo=${balanceo}`,
      chuteId === null ? { clusterId } : { clusterId, chuteId },
    );
  }

  /**
   * El cluster en curso vuelve a como estaba antes de trabajarlo: se deshace su
   * balanceo y lo cambiado a mano mientras estuvo en curso; los cerrados no cambian.
   */
  restablecerCluster(
    id: number,
    clusterId: number,
    balanceo: Balanceo = 'LXV',
  ): Observable<ConfiguracionSimulacion> {
    return this.api.post<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/clusters/${clusterId}/restablecer?balanceo=${balanceo}`,
    );
  }

  restablecer(id: number, balanceo: Balanceo = 'LXV'): Observable<void> {
    return this.api.post<void>(`/simulacion/registros/${id}/restablecer?balanceo=${balanceo}`);
  }

  /** Cierra el cluster en curso: el equipo pasa al siguiente con esta dotación. */
  cerrar(
    id: number,
    clusterId: number,
    balanceo: Balanceo = 'LXV',
  ): Observable<ConfiguracionSimulacion> {
    return this.api.post<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/clusters/${clusterId}/cerrar?balanceo=${balanceo}`,
    );
  }

  /** Reabre un cluster cerrado; los siguientes vuelven a la proyección. */
  reabrir(
    id: number,
    clusterId: number,
    balanceo: Balanceo = 'LXV',
  ): Observable<ConfiguracionSimulacion> {
    return this.api.post<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/clusters/${clusterId}/reabrir?balanceo=${balanceo}`,
    );
  }

  historial(id: number, balanceo: Balanceo = 'LXV'): Observable<HistorialRegistroSimulacion> {
    return this.api.get<HistorialRegistroSimulacion>(`/simulacion/registros/${id}/historial`, {
      balanceo,
    });
  }

  /** Cambios de un lanzamiento (null: los que no tienen), del más reciente al más antiguo. */
  historialCambios(
    id: number,
    lanzamientoId: number | null,
    pagina: number,
    tamano: number,
    balanceo: Balanceo = 'LXV',
  ): Observable<Pagina<HistorialSimulacion>> {
    return this.api.get<Pagina<HistorialSimulacion>>(
      `/simulacion/registros/${id}/historial/cambios`,
      { lanzamientoId, pagina, tamano, balanceo },
    );
  }

  /** Sin clusterId, el resumen del registro; con él, la vista de ese cluster. */
  dashboard(
    id: number,
    clusterId: number | null = null,
    balanceo: Balanceo = 'LXV',
  ): Observable<DashboardSimulacion> {
    return this.api.get<DashboardSimulacion>(
      `/simulacion/registros/${id}/dashboard`,
      clusterId === null ? { balanceo } : { clusterId, balanceo },
    );
  }

  /**
   * Excel completo de la simulación (un solo archivo): resumen, comparativos con
   * gráficos, cuadrículas de Configuración y el detalle de chutes, rutas e historial.
   */
  exportarExcel(id: number, balanceo: Balanceo = 'LXV'): Observable<Blob> {
    return this.api.getArchivo(`/simulacion/registros/${id}/exportar?balanceo=${balanceo}`);
  }

  /** Rutas de un lado del chute (antes o después), paginadas y con totales del filtro. */
  rutasDashboard(
    id: number,
    filtros: RutasDashboardFiltros,
    balanceo: Balanceo = 'LXV',
  ): Observable<RutasDashboardSimulacion> {
    return this.api.get<RutasDashboardSimulacion>(`/simulacion/registros/${id}/dashboard/rutas`, {
      pagina: filtros.pagina,
      tamano: filtros.tamano,
      texto: filtros.texto,
      tipo: filtros.tipo,
      chuteIds: filtros.chuteIds.join(','),
      lado: filtros.lado,
      clusterId: filtros.clusterId,
      soloCambios: filtros.soloCambios,
      balanceo,
    });
  }
}
