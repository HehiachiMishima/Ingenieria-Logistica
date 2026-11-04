import { Pagina } from '../../../../core/models';

export interface ArchivoSimulacion {
  id: number;
  nombreRegistro: string;
  nombreArchivo: string;
  etiqueta: string | null;
  colorHex: string | null;
  /** PROYECCION → EN_PROCESO (algún cluster empezado) → FINALIZADO (todos cerrados). */
  estado: EstadoSimulacion;
  rutas: number;
  chutes: number;
  bultos: number;
  unidades: number;
  lineas: number;
  /** Total de COD contados en las rutas del archivo. */
  cod: number;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export type EstadoSimulacion = 'PROYECCION' | 'EN_PROCESO' | 'FINALIZADO' | string;

/**
 * Avance de un cluster. Se trabajan de a uno en el orden de las olas: rutas
 * balanceadas (RUTAS), operarios asignados (LISTO) y cerrado (CERRADO).
 */
export type EstadoCluster = 'PENDIENTE' | 'RUTAS' | 'LISTO' | 'CERRADO' | string;

export interface ClusterParametroSimulacion {
  id: number;
  nombre: string;
  limiteHoras: number;
  /** Clusters con los que no puede compartir chute (CL1 con CL2). */
  incompatibles: number[];
  estado: EstadoCluster;
  /** Diferencia de bultos entre sus chutes al cargar el Excel (%); null con un solo chute. */
  diferenciaOriginal: number | null;
}

export interface ChuteParametroSimulacion {
  id: number;
  nombre: string;
  /** Base que fija el usuario: los operarios de la proyección (el «antes»). */
  operarios: number;
  /** Clusters con rutas hoy en el chute. */
  clusterIds: number[];
}

export interface RutaSimulacion {
  id: number;
  chuteId: number;
  chute: string;
  /** Chute que tenía la ruta al cargar el Excel (no lo cambia el balanceo). */
  chuteOriginalId: number;
  chuteOriginal: string;
  clusterId: number;
  cluster: string;
  codigoRuta: string;
  /** Tipo de operación de la ruta: B2C o B2B. */
  tipo: 'B2C' | 'B2B' | string;
  totalBultos: number;
  totalUnidades: number;
  totalLineas: number;
  conteoCod: number;
}

/** Rutas y totales de un cluster asignados a un chute. */
export interface CargaSimulacion {
  clusterId: number;
  chuteId: number;
  rutas: number;
  bultos: number;
  unidades: number;
  lineas: number;
  cod: number;
  /** Operarios del chute en la ola de este cluster; null = la ola aún no tiene operarios asignados. */
  operarios: number | null;
}

/** Parámetros y carga agregada; las rutas se piden aparte, paginadas. */
export interface ConfiguracionSimulacion {
  archivo: ArchivoSimulacion;
  velocidadBase: number;
  /** Tope global de operarios disponibles en cada ola; el pool se reutiliza entre olas. */
  operariosMaximos: number;
  /** Máximo de operarios en un chute definido por el usuario; null = reparto parejo. */
  operariosPorChute: number | null;
  /** Tope por chute que aplica el balanceo: el definido o, si no hay, el equipo completo. */
  topePorChute: number;
  clusters: ClusterParametroSimulacion[];
  chutes: ChuteParametroSimulacion[];
  carga: CargaSimulacion[];
  /** Chutes que hoy mezclan CL1 y CL2: el balanceo los separa. */
  mezclas: ChuteMezcladoSimulacion[];
}

export interface RutasSimulacionFiltros {
  pagina: number;
  tamano: number;
  texto: string;
  clusterIds: number[];
  chuteIds: number[];
}

/** Equipo (operarios y velocidad) y límites de horas de los clusters. */
export interface ConfiguracionSimulacionRequest {
  velocidadBase: number;
  /** Operarios del equipo: los mismos pasan de un cluster al siguiente. */
  operariosMaximos: number;
  /** Máximo por chute; null = reparto parejo del equipo. */
  operariosPorChute: number | null;
  clusters: { clusterId: number; limiteHoras: number }[];
}

export interface RegistroSimulacionActualizarRequest {
  nombreRegistro: string;
  etiqueta: string;
  colorHex: string;
}

export interface HistorialSimulacion {
  id: number;
  /** Lanzamiento que hizo el cambio; null = movimiento manual o de una versión anterior. */
  lanzamientoId: number | null;
  tipoAccion: 'MOVER_RUTA' | 'ASIGNAR_OPERARIOS' | string;
  /** BALANCEO: paso 1 o 2; SEPARACION: CL1 y CL2; MANUAL: hecho por el usuario. */
  motivo: 'BALANCEO' | 'SEPARACION' | 'MANUAL' | string;
  rutaId: number | null;
  ruta: string | null;
  cluster: string | null;
  tipo: string | null;
  bultos: number | null;
  chuteOrigen: string | null;
  chuteDestino: string | null;
  chuteModificado: string | null;
  /**
   * MOVER_RUTA: operarios del chute de origen y destino tras el cambio.
   * ASIGNAR_OPERARIOS: operarios del chute en la ola antes (origen) y después (destino).
   */
  operariosOrigen: number | null;
  operariosDestino: number | null;
  creadoEn: string;
  creadoPor: number | null;
}

/** Un lanzamiento de un cluster con el resultado que vio el usuario. */
export interface LanzamientoSimulacion {
  id: number;
  clusterId: number;
  cluster: string;
  fase: FaseBalanceo;
  mensaje: string;
  creadoEn: string;
  creadoPor: number | null;
  /** Rutas movidas, de ellas las que separaron CL1/CL2, y chutes con otra dotación. */
  rutas: number;
  separaciones: number;
  chutesOperarios: number;
}

export interface HistorialRegistroSimulacion {
  /** Del más reciente al más antiguo; sus cambios se piden paginados. */
  lanzamientos: LanzamientoSimulacion[];
  /** Cambios sin lanzamiento (a mano o de versiones anteriores). */
  sinLanzamiento: number;
}

/** RUTAS: reparte las rutas de la ola de un cluster; OPERARIOS: asigna los operarios de su ola. */
export type FaseBalanceo = 'RUTAS' | 'OPERARIOS';

export interface BalanceoSimulacion {
  clusterId: number;
  cluster: string;
  fase: FaseBalanceo;
  /** Estado del cluster tras el paso: RUTAS o LISTO. */
  estado: EstadoCluster;
  rutasMovidas: number;
  /** Operarios asignados en la ola tras el paso 2. */
  operariosAsignados: number;
  mensaje: string;
  /** Configuración ya actualizada: no hace falta pedirla de nuevo. */
  configuracion: ConfiguracionSimulacion;
}

export interface SimulacionKpis {
  /** Cierre (h, suma de las olas) antes y después de los ajustes. null = sin operarios. */
  tiempoOriginal: number | null;
  tiempoBalanceado: number | null;
  /** Rutas que hoy están en un chute distinto al original. */
  rutasMovidas: number;
}

export interface ClusterComparativoSimulacion {
  id: number;
  cluster: string;
  /** Tiempo de cierre de la ola; null = algún chute se quedó sin operarios. */
  tiempoOriginal: number | null;
  tiempoBalanceado: number | null;
  limiteHoras: number;
  excede: boolean;
  bultosOriginal: number;
  bultosBalanceado: number;
  lineasOriginal: number;
  lineasBalanceado: number;
  codOriginal: number;
  codBalanceado: number;
}

export interface ChuteComparativoSimulacion {
  id: number;
  chute: string;
  /** En la vista de un cluster, los que recibe de la ola anterior; en el resumen, 0. */
  operariosOriginal: number;
  /** En la vista de un cluster, los asignados a su ola; en el resumen, el máximo entre sus olas. */
  operariosBalanceado: number;
  /** Cantidad de rutas asignadas al chute antes y después del balanceo. */
  rutasOriginal: number;
  rutasBalanceado: number;
  /** Rutas que el balanceo sacó del chute y las que trajo de otros. */
  rutasSalieron: number;
  rutasLlegaron: number;
  /** null = el chute tiene bultos pero ningún operario. */
  tiempoOriginal: number | null;
  tiempoBalanceado: number | null;
  estado: 'MEJORO' | 'AUMENTO' | 'IGUAL' | string;
  bultosOriginal: number;
  bultosBalanceado: number;
  unidadesOriginal: number;
  unidadesBalanceado: number;
  lineasOriginal: number;
  lineasBalanceado: number;
  codOriginal: number;
  codBalanceado: number;
  /** Clusters que el chute atiende antes o después del balanceo. */
  clusters: string[];
  /** Clusters del chute al cargar el Excel y tras el balanceo, por separado. */
  clustersOriginal: string[];
  clustersBalanceado: string[];
  /** El chute junta CL1 y CL2 (no permitido) al cargar el Excel / tras el balanceo. */
  mezclaOriginal: boolean;
  mezclaBalanceada: boolean;
  /** Clusters de CL1/CL2 que salieron del chute para dejar de compartirlo. */
  clustersSeparados: string[];
}

/** Rutas que pasaron de un chute a otro (por chute original y actual). */
export interface MovimientoChuteSimulacion {
  origenId: number;
  destinoId: number;
  rutas: number;
}

export interface DashboardSimulacion {
  kpis: SimulacionKpis;
  clusters: ClusterComparativoSimulacion[];
  chutes: ChuteComparativoSimulacion[];
  /** Chutes que hoy mezclan CL1 y CL2: el balanceo los separa. */
  mezclas: ChuteMezcladoSimulacion[];
  /** Movimientos de rutas entre chutes: flujo de cualquier grupo de chutes. */
  movimientos: MovimientoChuteSimulacion[];
  /** Clusters en el orden de las olas con su avance: arman el selector de vistas. */
  olas: OlaSimulacion[];
  /** Paso de operarios entre olas: en la vista de un cluster, solo el que llega a él. */
  flujoOperarios: FlujoOperariosSimulacion[];
  /** Operarios del equipo y máximo por chute (null = sin tope propio). */
  equipo: number;
  maximoPorChute: number | null;
  /** Operarios de cada chute en cada ola, en el orden de `olas` (solo en el resumen). */
  dotacion: { chuteId: number; chute: string; operarios: number[] }[];
}

export interface OlaSimulacion {
  clusterId: number;
  cluster: string;
  estado: EstadoCluster;
  rutasMovidas: number;
  /** Cierre establecido: el límite de horas del cluster (0 si no tiene). */
  limiteHoras: number;
  /** Cierre conseguido con los operarios de su ola (null si le falta gente). */
  tiempoCierre: number | null;
  bultos: number;
  unidades: number;
  lineas: number;
  cod: number;
}

/** Operarios que siguen en su chute, salen de uno o llegan a otro al pasar de una ola a la siguiente. */
export interface FlujoOperariosSimulacion {
  origenId: number;
  origen: string;
  destinoId: number;
  destino: string;
  quedan: number;
  salen: number;
  llegan: number;
  chutes: { chuteId: number; chute: string; antes: number; despues: number }[];
}

export interface ChuteMezcladoSimulacion {
  id: number;
  chute: string;
  clusters: string[];
}

/** Totales de un lado del chute (antes o después) con los filtros del modal. */
export interface ResumenRutasSimulacion {
  rutas: number;
  rutasB2c: number;
  rutasB2b: number;
  /** Rutas que cambiaron de chute: salieron (antes) o llegaron (después). */
  cambios: number;
  bultos: number;
  unidades: number;
  lineas: number;
  cod: number;
}

export interface RutasDashboardFiltros {
  pagina: number;
  tamano: number;
  texto: string;
  tipo: '' | 'B2C' | 'B2B';
  /** Chutes elegidos (1 a 50). */
  chuteIds: number[];
  /** ANTES: rutas que los chutes tenían al cargar el Excel; DESPUES: las que tienen hoy. */
  lado: LadoChute;
  clusterId: number | null;
  /** Solo las rutas que cambiaron de chute. */
  soloCambios: boolean;
}

export type LadoChute = 'ANTES' | 'DESPUES';

/** Detalle ejecutivo del modal: totales del conjunto filtrado y la página pedida. */
export interface RutasDashboardSimulacion {
  resumen: ResumenRutasSimulacion;
  rutas: Pagina<RutaSimulacion>;
}
