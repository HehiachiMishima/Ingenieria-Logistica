/** Procesos de la operación, en el orden en que fluye el producto. */
export type Proceso = 'REC' | 'ALM' | 'REA' | 'OLA' | 'DES';

/** IL: Ingeniería Logística corre la tarea; OPS: Operaciones la ejecuta. */
export type Responsable = 'IL' | 'OPS';

export interface TurnoMalla {
  codigo: string;
  nombre: string;
  /** Horas "HH:mm"; un turno puede cruzar la medianoche (22:00–06:00). */
  inicio: string;
  fin: string;
  refrigerio: string | null;
  tipoRefrigerio: string | null;
  nota: string | null;
  confirmado: boolean;
}

/** Un paso de un proceso; sin fin, es un hito puntual. */
export interface FlujoMalla {
  proceso: Proceso;
  responsable: Responsable;
  inicio: string;
  fin: string | null;
  actividad: string;
  confirmado: boolean;
}

export interface OlaMalla {
  cluster: string;
  nombre: string;
  caidaPedidos: string | null;
  lanzamiento: string | null;
  cierrePicking: string | null;
  despacho: string | null;
  confirmado: boolean;
  nota: string | null;
}

/** Productividad de una zona en líneas por hora (real y de diseño). */
export interface ZonaMalla {
  nombre: string;
  lphReal: number | null;
  lphDiseno: number | null;
}

/** Capacidad diaria declarada; null = por definir. */
export interface CapacidadMalla {
  unidadesDia: number | null;
  lineasDia: number | null;
  bultosRecepcionDia: number | null;
  proveedoresDia: number | null;
}

export interface DotacionMalla {
  turno: string;
  proceso: Proceso;
  personas: number | null;
}

export interface DotacionRealMalla {
  inbound: number | null;
  outbound: number | null;
  despacho: number | null;
  total: number | null;
  semana: string | null;
}

/** Todo lo que necesita la malla de un centro: se guarda completo. */
export interface CentroMallaDatos {
  codigo: string;
  nombre: string;
  turnos: TurnoMalla[];
  flujos: FlujoMalla[];
  olas: OlaMalla[];
  zonas: ZonaMalla[];
  capacidad: CapacidadMalla;
  dotacion: DotacionMalla[];
  dotacionReal: DotacionRealMalla;
}

export interface CentroMalla extends CentroMallaDatos {
  id: number;
  actualizadoEn: string;
}

export interface CentroMallaResumen {
  id: number;
  codigo: string;
  nombre: string;
  turnos: number;
  flujos: number;
  olas: number;
  zonas: number;
  actualizadoEn: string;
}

/** Un cambio guardado en la malla de un centro. */
export interface CambioMalla {
  accion: 'CREAR' | 'GUARDAR' | 'ELIMINAR';
  resumen: string;
  creadoPor: number;
  creadoEn: string;
}

// ---------------- Validación (reglas del servidor) ----------------

export interface ParametroRegla {
  clave: string;
  etiqueta: string;
  /** ENTERO, BOOLEANO o LISTA_HORAS. */
  tipo: string;
  valor: string;
  /** Rango permitido de un ENTERO (null en los demás tipos). */
  min: number | null;
  max: number | null;
}

export interface HallazgoRegla {
  centroCodigo: string;
  centroNombre: string;
  entidad: string;
  detalle: string;
  esperado: string;
  obtenido: string;
}

export interface ReglaEvaluada {
  id: number;
  codigo: string;
  nombre: string;
  descripcion: string;
  ambito: string;
  severidad: 'ALTA' | 'MEDIA' | 'BAJA' | string;
  activa: boolean;
  orden: number;
  /** CUMPLE, NO_CUMPLE, INACTIVA o NO_EVALUABLE. */
  estado: string;
  hallazgos: number;
  parametros: ParametroRegla[];
  detalles: HallazgoRegla[];
}

export interface EvaluacionMalla {
  evaluadoEn: string;
  resumen: {
    reglas: number;
    activas: number;
    cumplen: number;
    noCumplen: number;
    inactivas: number;
    noEvaluables: number;
    hallazgos: number;
  };
  reglas: ReglaEvaluada[];
}
