import { Service, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import {
  ArchivoSimulacion,
  Balanceo,
  CambioData,
  DATA_ID,
  DiaBalanceo,
  BalanceoCuerpo,
  BalanceoSimulacion,
  ComparativoLanzamiento,
  ConfiguracionSimulacion,
  ConfiguracionSimulacionRequest,
  DashboardSimulacion,
  DistribucionSimulacion,
  FaseBalanceo,
  HistorialRegistroSimulacion,
  HistorialSimulacion,
  LocalesRuta,
  PaginaRutas,
  PredeterminadosSimulacion,
  RutasDashboardFiltros,
  RutasDashboardSimulacion,
  RutasSimulacionFiltros,
  RutaDataRequest,
  RutaSimulacion,
  VistaBalanceo,
} from './simulacion.model';
import { HttpContext } from '@angular/common/http';
import { Pagina } from '../../../../core/models';
import { PETICION_SILENCIOSA } from '../../../../core/interceptors/error.interceptor';
import { Api } from '../../../../core/services/api';

@Service()
export class SimulacionApi {
  private readonly api = inject(Api);

  /** El escenario único de Configuración y el Dashboard, en una lista. */
  opciones(): Observable<ArchivoSimulacion[]> {
    return this.api.get<ArchivoSimulacion[]>('/simulacion/registros/opciones');
  }

  /**
   * Locales de una ruta (la ficha de la ruta). Silenciosa: la ficha
   * muestra el error en su lugar, sin aviso global.
   */
  localesRuta(id: number, rutaId: number): Observable<LocalesRuta> {
    return this.api.get<LocalesRuta>(
      `/simulacion/registros/${id}/rutas/${rutaId}/locales`,
      undefined,
      new HttpContext().set(PETICION_SILENCIOSA, true),
    );
  }

  /** Configuración de un balanceo (Diarias, L-X-V o M-J-S): sus clusters, su carga y los chutes. */
  configuracion(id: number, balanceo: Balanceo = 'LXV'): Observable<ConfiguracionSimulacion> {
    return this.api.get<ConfiguracionSimulacion>(`/simulacion/registros/${id}/configuracion`, { balanceo });
  }

  /**
   * Rutas de la planilla por keyset (cursor = id de la última recibida); el texto filtra por prefijo
   * del código. Las de DATA llegan sin cluster: quedan con {@link DATA_ID}, como un bloque más.
   */
  rutas(id: number, filtros: RutasSimulacionFiltros): Observable<PaginaRutas> {
    return this.api
      .get<PaginaRutas>(`/simulacion/registros/${id}/rutas`, {
        ...(filtros.despues !== null ? { despues: filtros.despues } : {}),
        tamano: filtros.tamano,
        texto: filtros.texto,
        ...(filtros.clusterIds.length > 0 ? { clusterIds: filtros.clusterIds.join(',') } : {}),
        ...(filtros.chuteIds.length > 0 ? { chuteIds: filtros.chuteIds.join(',') } : {}),
      })
      .pipe(map((pagina) => ({ ...pagina, contenido: pagina.contenido.map(conData) })));
  }

  // ---------------- DATA: rutas que no se balancean ----------------

  /** Crea la ruta en DATA (sin tipo, frecuencia ni grupo), en un chute al azar. */
  crearRutaData(id: number, peticion: RutaDataRequest): Observable<CambioData> {
    return this.api.post<CambioData>(`/simulacion/registros/${id}/data/rutas`, peticion);
  }

  /** Cambia el número y los locales de una ruta de DATA. */
  actualizarRutaData(id: number, rutaId: number, peticion: RutaDataRequest): Observable<CambioData> {
    return this.api.put<CambioData>(`/simulacion/registros/${id}/data/rutas/${rutaId}`, peticion);
  }

  eliminarRutaData(id: number, rutaId: number): Observable<CambioData> {
    return this.api.delete<CambioData>(`/simulacion/registros/${id}/data/rutas/${rutaId}`);
  }

  /** Tipo, frecuencia o grupo para las rutas elegidas de DATA (las de un cluster se dejan igual). */
  asignarData(
    id: number,
    que: 'tipo' | 'frecuencia' | 'grupo',
    rutaIds: readonly number[],
    valor: string,
  ): Observable<CambioData> {
    return this.api.put<CambioData>(`/simulacion/registros/${id}/data/${que}`, { rutaIds, [que]: valor });
  }

  /**
   * Rutas a una celda: a un cluster (solo de DATA y completas) o a DATA ({@link DATA_ID}). Todas o
   * ninguna.
   */
  moverData(id: number, rutaIds: readonly number[], clusterId: number, chuteId: number): Observable<CambioData> {
    // La columna siempre: el chute en un cluster o el bloque en DATA (se guarda como su chute).
    return this.api.put<CambioData>(`/simulacion/registros/${id}/data/mover`, {
      rutaIds,
      clusterId: clusterId === DATA_ID ? null : clusterId,
      chuteId,
    });
  }

  /** Varias rutas de DATA a la vez: todas o ninguna (si alguna está en un cluster, ninguna). */
  eliminarRutasData(id: number, rutaIds: readonly number[]): Observable<CambioData> {
    return this.api.post<CambioData>(`/simulacion/registros/${id}/data/rutas/eliminar`, { rutaIds });
  }

  /**
   * Locales (por su orden) de una ruta a otra: DATA o clusters sin empezar. Todos o ninguno; el
   * código que la destino ya tiene se suma ahí.
   */
  moverLocales(id: number, origenId: number, destinoId: number, ordenes: readonly number[]): Observable<CambioData> {
    return this.api.put<CambioData>(`/simulacion/registros/${id}/data/locales/mover`, { origenId, destinoId, ordenes });
  }

  /** Agrega «CL{numero}» con el número elegido: puede llenar un hueco (CL2 entre CL1 y CL4). */
  agregarCluster(id: number, numero: number): Observable<CambioData> {
    return this.api.post<CambioData>(`/simulacion/registros/${id}/clusters`, { numero });
  }

  /** Quita ese cluster si no empezó; sus rutas vuelven a DATA. */
  quitarCluster(id: number, clusterId: number): Observable<CambioData> {
    return this.api.delete<CambioData>(`/simulacion/registros/${id}/clusters/${clusterId}`);
  }

  /** Distribución de rutas por cluster y chute (lo que imprime el PDF; el Excel lleva lo mismo). */
  distribucion(id: number): Observable<DistribucionSimulacion> {
    return this.api.get<DistribucionSimulacion>(`/simulacion/registros/${id}/distribucion`);
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

  /** Velocidad y chutes con buff de todos los registros, y si están bloqueados. */
  predeterminados(): Observable<PredeterminadosSimulacion> {
    return this.api.get<PredeterminadosSimulacion>('/simulacion/predeterminados');
  }

  /** Fija velocidad y buff para todos los registros (también los sin balancear) y los bloquea. */
  bloquearPredeterminados(velocidad: number, chutesBuff: readonly number[]): Observable<PredeterminadosSimulacion> {
    return this.api.put<PredeterminadosSimulacion>('/simulacion/predeterminados/bloquear', {
      velocidad,
      chutesBuff,
    });
  }

  /** Abre velocidad y buff para cambiarlos (los valores se conservan). */
  desbloquearPredeterminados(): Observable<PredeterminadosSimulacion> {
    return this.api.put<PredeterminadosSimulacion>('/simulacion/predeterminados/desbloquear', {});
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
  balanceoGuardado(id: number, accion: 'guardar' | 'cargar'): Observable<void> {
    return this.api.post<void>(`/simulacion/registros/${id}/balanceo-guardado/${accion}`);
  }

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

  /** Excel de la simulación: la distribución de rutas por cluster y chute (igual que el PDF). */
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

/** Una ruta de DATA (sin cluster) queda con {@link DATA_ID} y el nombre «DATA». */
function conData(ruta: RutaSimulacion): RutaSimulacion {
  // El servidor manda null en DATA aunque el tipo diga number (ya convertido, siempre lo es).
  return (ruta.clusterId as number | null) === null
    ? { ...ruta, clusterId: DATA_ID, cluster: 'DATA', clusterOriginalId: DATA_ID, clusterOriginal: 'DATA' }
    : ruta;
}
