import { CeldaMatriz } from '../../../../core/models';

/** Analítica del dashboard de Bitácora. */
export interface AnaliticaBitacora {
  kpis: {
    total: number;
    resueltas: number;
    segundosSolucion: number;
    mttrSegundos: number;
    sistemasAfectados: number;
    centrosAfectados: number;
    totalPeriodoAnterior: number | null;
  };
  sistemas: GrupoBitacora[];
  centros: GrupoBitacora[];
  areas: GrupoBitacora[];
  tipos: { tipo: string; sistema: string; total: number; mttrSegundos: number }[];
  centroSistema: CeldaMatriz[];
  /** Cuadro ejecutivo: una fila por sistema, ordenadas por horas. */
  ejecutivo: FilaEjecutiva[];
  reincidencia: Reincidencia;
  /** Abiertas ahora con los filtros (se calcula en cada petición, no se cachea). */
  abiertas: AbiertasBitacora;
}

/** Incidencias abiertas por antigüedad (calculada en el servidor al pedir la analítica). */
export interface AbiertasBitacora {
  total: number;
  menosDeUnaHora: number;
  deUnaACuatroHoras: number;
  masDeCuatroHoras: number;
  /** Inicio (instante UTC) de la abierta más antigua; null si no hay. */
  desdeMasAntigua: string | null;
  ticketMasAntiguo: string | null;
}

export interface FilaEjecutiva {
  /** Id del sistema (para pedir sus casos más largos). */
  id: number;
  etiqueta: string;
  total: number;
  segundos: number;
  mttrSegundos: number;
  maxSegundos: number;
}

/** Mismo tipo en el mismo centro dentro de `ventanaDias` días (dentro del filtro). */
export interface Reincidencia {
  ventanaDias: number;
  total: number;
  reincidentes: number;
  repetidos: { centro: string; tipo: string; sistema: string; casos: number; reincidencias: number }[];
}

/** Incidencia resuelta con su duración (detalle del cuadro ejecutivo). */
export interface CasoLargo {
  ticket: string;
  descripcion: string;
  tipo: string;
  centro: string;
  responsable: string;
  /** Día del negocio, YYYY-MM-DD. */
  fecha: string;
  horaInicio: string;
  horaFin: string;
  /** Día del fin (YYYY-MM-DD): puede ser posterior a `fecha`. */
  fechaFin: string;
  segundos: number;
}

export interface GrupoBitacora {
  etiqueta: string;
  total: number;
  mttrSegundos: number;
}
