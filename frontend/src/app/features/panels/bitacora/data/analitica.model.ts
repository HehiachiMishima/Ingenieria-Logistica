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
}

export interface GrupoBitacora {
  etiqueta: string;
  total: number;
  mttrSegundos: number;
}
