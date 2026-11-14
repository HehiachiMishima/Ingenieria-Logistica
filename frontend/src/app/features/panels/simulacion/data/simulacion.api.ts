import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ArchivoSimulacion,
  Balanceo,
  DiaBalanceo,
  BalanceoCuerpo,
  BalanceoSimulacion,
  ComparativoLanzamiento,
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
  RutaSimulacion,
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

  /** Configuración de un balanceo (Diarias, L-X-V o M-J-S): sus clusters, su carga y los chutes. */
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

  /** Todas las rutas del registro de una vez (reporte): una sola lectura en el servidor. */
  todasLasRutas(id: number): Observable<RutaSimulacion[]> {
    return this.api.get<RutaSimulacion[]>(`/simulacion/registros/${id}/rutas/todas`);
  }

  /** Velocidad, máximo por chute, buff y límites de horas (valen para los tres balanceos). */
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
   * Trabaja el cluster en curso. RUTAS en DIA reparte las diarias con el rango del
   * cuerpo; RUTAS en un día usa el rango de las diarias; OPERARIOS asigna los dos
   * días con la cantidad del cuerpo.
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
   * Vista previa del paso de rutas (no cambia nada): los grupos del cluster en
   * orden natural y el tramo de chutes de cada uno. En DIA con ese rango; en un
   * día, el rango y los tramos de las diarias.
   */
  vistaBalanceo(
    id: number,
    clusterId: number,
    desde: number | null,
    hasta: number | null,
    balanceo: Balanceo,
  ): Observable<VistaBalanceo> {
    return this.api.get<VistaBalanceo>(
      `/simulacion/registros/${id}/clusters/${clusterId}/balanceo/vista`,
      { desde, hasta, balanceo },
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
   * Varias rutas a una celda (cluster y chute) en un solo paso: el servidor las mueve todas o
   * ninguna y devuelve la configuración del balanceo indicado.
   */
  moverRutas(
    id: number,
    rutaIds: readonly number[],
    clusterId: number,
    chuteId: number,
    balanceo: Balanceo = 'LXV',
  ): Observable<ConfiguracionSimulacion> {
    return this.api.put<ConfiguracionSimulacion>(
      `/simulacion/registros/${id}/rutas/mover?balanceo=${balanceo}`,
      { rutaIds, clusterId, chuteId },
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

  /** Todo el registro vuelve a la proyección (los tres balanceos, en una sola operación). */
  restablecer(id: number): Observable<void> {
    return this.api.post<void>(`/simulacion/registros/${id}/restablecer`);
  }

  /** Cierra el cluster en curso en los tres balanceos: el equipo pasa al siguiente. */
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

  /** Antes y después de un lanzamiento en los chutes de su cluster. */
  comparativoLanzamiento(
    id: number,
    lanzamientoId: number,
    balanceo: Balanceo = 'LXV',
  ): Observable<ComparativoLanzamiento> {
    return this.api.get<ComparativoLanzamiento>(
      `/simulacion/registros/${id}/historial/${lanzamientoId}/comparativo`,
      { balanceo },
    );
  }

  /** Sin clusterId, el resumen del registro; con él, la vista de ese cluster. */
  dashboard(
    id: number,
    clusterId: number | null = null,
    balanceo: DiaBalanceo = 'LXV',
  ): Observable<DashboardSimulacion> {
    return this.api.get<DashboardSimulacion>(
      `/simulacion/registros/${id}/dashboard`,
      clusterId === null ? { balanceo } : { clusterId, balanceo },
    );
  }

  /**
   * Excel de la simulación: un solo archivo con los dos días (lo mismo que el PDF) y un selector
   * de frecuencia dentro del libro.
   */
  exportarExcel(id: number): Observable<Blob> {
    return this.api.getArchivo(`/simulacion/registros/${id}/exportar`);
  }

  /** Rutas de un lado del chute (antes o después), paginadas y con totales del filtro. */
  rutasDashboard(
    id: number,
    filtros: RutasDashboardFiltros,
    balanceo: DiaBalanceo = 'LXV',
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
