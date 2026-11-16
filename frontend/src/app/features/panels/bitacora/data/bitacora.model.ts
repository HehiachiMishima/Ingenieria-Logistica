export interface Centro {
  id: number;
  centro: string;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface Sistema {
  id: number;
  sistema: string;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface Area {
  id: number;
  area: string;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface Responsable {
  id: number;
  idDataArea: number;
  responsable: string;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface TipoIncidencia {
  id: number;
  idDataSistema: number;
  incidencia: string;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface CentroCrearRequest {
  centro: string;
}

export interface CentroActualizarRequest {
  centro?: string;
}

export interface SistemaCrearRequest {
  sistema: string;
}

export interface SistemaActualizarRequest {
  sistema?: string;
}

export interface AreaCrearRequest {
  area: string;
}

export interface AreaActualizarRequest {
  area?: string;
}

export interface ResponsableCrearRequest {
  responsable: string;
}

export interface ResponsableActualizarRequest {
  responsable?: string;
}

export interface TipoIncidenciaCrearRequest {
  incidencia: string;
}

export interface TipoIncidenciaActualizarRequest {
  incidencia?: string;
}

export interface Incidencia {
  id: number;
  idDataCentro: number;
  centro: string | null;
  idDataSistema: number;
  sistema: string | null;
  idDataIncidencia: number;
  tipoIncidencia: string | null;
  ticket: string;
  idDataArea: number;
  area: string | null;
  idDataResponsable: number;
  responsable: string | null;
  descripcion: string;
  horaInicio: string;
  /** Null mientras está abierta. */
  horaFin: string | null;
  /** Duración en segundos; null mientras está abierta. */
  tiempoSolucionSeg: number | null;
  /** Fecha + hora de fin (instante UTC); null = abierta. */
  cerradoEn: string | null;
  estado: EstadoIncidencia;
  /** Fecha + hora de inicio (instante UTC). */
  creadoEn: string;
  /** Quién la registró. */
  creadoPor: number | null;
  /** Ficha de concurrencia: se devuelve al editar; si otra persona guardó antes, el servidor responde 409. */
  version: string;
}

export interface IncidenciaCrearRequest {
  idDataCentro: number;
  idDataSistema: number;
  idDataIncidencia: number;
  ticket: string;
  idDataArea: number;
  idDataResponsable: number;
  descripcion: string;
  horaInicio: string;
  /** Vacío (null) = la incidencia queda abierta. */
  horaFin: string | null;
  fecha: string;
  /** Día del fin (YYYY-MM-DD); null = abierta. */
  fechaFin: string | null;
}

/** Se deduce del fin en el servidor: sin fin = abierta. */
export type EstadoIncidencia = 'ABIERTA' | 'CERRADA';

export interface CerrarIncidenciaRequest {
  fechaFin: string;
  horaFin: string;
}

export interface IncidenciaActualizarRequest extends IncidenciaCrearRequest {
  /** `version` de la incidencia tal como se leyó. */
  version: string;
}

export interface IncidenciaFiltros {
  centro?: number | null;
  sistema?: number | null;
  area?: number | null;
  responsable?: number | null;
  tipo?: number | null;
  desde?: string;
  hasta?: string;
  texto?: string;
  estado?: EstadoIncidencia | null;
}
