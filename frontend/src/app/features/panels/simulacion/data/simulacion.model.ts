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
 * Los dos días de trabajo: L-X-V (lunes, miércoles y viernes) y M-J-S (martes,
 * jueves y sábado). Tienen operarios, dashboard y exportación.
 */
export type DiaBalanceo = 'LXV' | 'MJS';

/**
 * Los balanceos de cada cluster, en orden: DIA (las rutas diarias; aquí se elige
 * el rango de chutes y los tramos de los grupos) y luego L-X-V y M-J-S, que solo
 * mueven sus rutas dentro de ese rango con las diarias como carga fija.
 */
export type Balanceo = DiaBalanceo | 'DIA';

export const DIAS_BALANCEO: readonly DiaBalanceo[] = ['LXV', 'MJS'];

/** Frecuencia de una ruta (columna FRECUENCIA del Excel): DIARIA aparece en los dos días. */
export type FrecuenciaRuta = 'DIARIA' | DiaBalanceo;

export const ETIQUETA_BALANCEO: Record<Balanceo, string> = {
  LXV: 'L-X-V',
  MJS: 'M-J-S',
  DIA: 'Diarias',
};

/** La ruta de esa frecuencia se ve en el balanceo: las diarias, en los tres; las de un día, en ese. */
export function rutaEnBalanceo(frecuencia: string | null | undefined, balanceo: Balanceo): boolean {
  const diaria = !frecuencia || frecuencia === 'DIARIA';
  return balanceo === 'DIA' ? diaria : diaria || frecuencia === balanceo;
}

/**
 * Avance de un cluster en un balanceo. Se trabajan de a uno en el orden de las
 * olas: rutas balanceadas (RUTAS), operarios asignados (LISTO, solo los días) y
 * cerrado (CERRADO, los tres a la vez).
 */
export type EstadoCluster = 'PENDIENTE' | 'RUTAS' | 'LISTO' | 'CERRADO' | string;

export interface ClusterParametroSimulacion {
  id: number;
  nombre: string;
  limiteHoras: number;
  estado: EstadoCluster;
  /** Diferencia de bultos entre sus chutes al cargar el Excel (%); null con un solo chute. */
  diferenciaOriginal: number | null;
  /** Último rango de chutes con que se balancearon sus rutas; null si nunca se eligió. */
  chuteDesdeId: number | null;
  chuteHastaId: number | null;
  /** Operarios con que se calculó su ola la última vez; null si nunca se eligió. */
  operariosOla: number | null;
  /** Grupos del cluster en orden natural, con su carga en este balanceo. */
  grupos: GrupoCluster[];
  /** Balanceo de esta fila. */
  balanceo?: Balanceo;
  /** Avance del cluster en los tres balanceos (viene en cualquier configuración). */
  avance?: AvanceCluster;
  /** Sus diarias ya están balanceadas: el automático de los días no las mueve (a mano sí). */
  diariasFijas?: boolean;
}

/** Avance de un cluster en Diarias, L-X-V y M-J-S. */
export interface AvanceCluster {
  diarias: EstadoCluster;
  lxv: EstadoCluster;
  mjs: EstadoCluster;
  /**
   * Rutas del cluster por frecuencia (las suyas, sin contar las diarias en los días). Un día sin
   * rutas propias no se balancea; uno sin rutas propias ni diarias no participa.
   */
  rutasDiarias: number;
  rutasLxv: number;
  rutasMjs: number;
}

/**
 * Grupo de rutas de un cluster (columna GRUPO del Excel): el balanceo automático
 * lo reparte solo en su tramo de chutes; a mano, sus rutas pueden ir a cualquiera.
 */
export interface GrupoCluster {
  nombre: string;
  /** Posición del grupo en orden natural (1, 2, 3…). */
  prioridad: number | null;
  rutas: number;
  bultos: number;
  /** Tramo que le dieron las diarias; null sin balancear o tras restablecer. */
  chuteDesdeId: number | null;
  chuteDesde: string | null;
  chuteHastaId: number | null;
  chuteHasta: string | null;
}

/** Tramo que recibiría un grupo con el rango elegido (vista previa). */
export interface TramoGrupo {
  grupo: string;
  prioridad: number;
  rutas: number;
  bultos: number;
  /** Chutes del tramo; 0 sin rango o si el rango no alcanza. */
  chutes: number;
  chuteDesdeId: number | null;
  chuteDesde: string | null;
  chuteHastaId: number | null;
  chuteHasta: string | null;
  /** Rutas del grupo hoy fuera de su tramo (también las movidas a mano): el balanceo las traerá. */
  rutasFuera: number;
}

/** Vista previa del paso de rutas de un cluster: no cambia nada. */
export interface VistaBalanceo {
  clusterId: number;
  cluster: string;
  /** Chutes útiles del rango (sin bloqueados); null sin rango. */
  chutesRango: number | null;
  /** Chutes del rango que no recibe ningún grupo (tienen menos rutas que chutes). */
  chutesSinUsar: number;
  /** Por qué así no se podría balancear; null si se puede. */
  aviso: string | null;
  grupos: TramoGrupo[];
  /** 'DIA' en un día: el rango y los tramos son los de las diarias; null si se eligen aquí. */
  heredadoDe?: 'DIA' | null;
}

/**
 * Parámetros de un paso de balanceo. Paso 1: chutes entre los que se reparten
 * las rutas (ambos incluidos). Paso 2: operarios con que se calcula la ola.
 */
export interface BalanceoCuerpo {
  chuteDesdeId?: number;
  chuteHastaId?: number;
  operarios?: number;
  /** Paso 1 con varios grupos: sus nombres del de mayor prioridad al de menor. */
  prioridad?: string[];
}

export interface ChuteParametroSimulacion {
  id: number;
  nombre: string;
  /** Base que fija el usuario: los operarios de la proyección (el «antes»). */
  operarios: number;
  /** Clusters con rutas hoy en el chute. */
  clusterIds: number[];
  /**
   * Chute bloqueado del plano (19, 20, 21): no recibe rutas. Solo llega si un
   * registro anterior al bloqueo aún tiene rutas en él (se pueden sacar).
   */
  bloqueado: boolean;
  /** Chute con más capacidad: al balancear recibe primero las rutas más pesadas. */
  buff?: boolean;
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
  /** Cluster que tenía la ruta al cargar el Excel (distinto si se cambió a mano). */
  clusterOriginalId: number;
  clusterOriginal: string;
  /** Su cluster y algún otro siguen sin cerrar: se puede pasar a otro cluster (antes o después de balancear). */
  clusterEditable: boolean;
  codigoRuta: string;
  /** Tipo de operación de la ruta: B2C o B2B. */
  tipo: 'B2C' | 'B2B' | string;
  totalBultos: number;
  totalUnidades: number;
  totalLineas: number;
  conteoCod: number;
  /** Grupo de la ruta dentro de su cluster (columna GRUPO del Excel). */
  grupo: string;
  /** DIARIA, LXV o MJS. */
  frecuencia?: FrecuenciaRuta;
  /** Ruta diaria ya balanceada (paso Diarias): el automático de los días no la vuelve a mover. */
  diariaFija?: boolean;
  /** Días tal como vienen en el Excel («L-X», «V», «L-X-V»); null en rutas antiguas. */
  dias?: string | null;
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
  /** Operarios de referencia (la ola más grande ya elegida). Ya no hay equipo por defecto editable. */
  operariosMaximos: number;
  /** Máximo de operarios en un chute definido por el usuario; null = reparto parejo. */
  operariosPorChute: number | null;
  /** Tope por chute que aplica el balanceo: el definido o, si no hay, el equipo completo. */
  topePorChute: number;
  clusters: ClusterParametroSimulacion[];
  chutes: ChuteParametroSimulacion[];
  carga: CargaSimulacion[];
  /** Nombres de los chutes bloqueados («19», «20», «21»): no se muestran ni se usan. */
  chutesBloqueados: string[];
  /** Balanceo de esta configuración. */
  balanceo?: Balanceo;
  /** Algún cluster ya se balanceó (en cualquier balanceo): el buff ya no cambia. */
  buffBloqueado?: boolean;
}

/** Página de rutas por keyset: `siguiente` es el cursor de la próxima; `total` solo en la primera. */
export interface PaginaRutas {
  contenido: RutaSimulacion[];
  total: number | null;
  siguiente: number | null;
}

export interface RutasSimulacionFiltros {
  /** Id de la última ruta recibida (sin él, la primera página). */
  despues: number | null;
  tamano: number;
  texto: string;
  clusterIds: number[];
  chuteIds: number[];
}

/** Velocidad, máximo por chute y límites de horas de los clusters (la cantidad de operarios es de cada ola). */
export interface ConfiguracionSimulacionRequest {
  velocidadBase: number;
  /** Máximo por chute; null = sin tope propio (toda la ola). */
  operariosPorChute: number | null;
  clusters: { clusterId: number; limiteHoras: number }[];
  /** Buff de los chutes (solo antes de balancear): base de operarios y si tiene buff. */
  chutes?: { chuteId: number; operarios: number; buff: boolean }[];
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
  tipoAccion: 'MOVER_RUTA' | 'ASIGNAR_OPERARIOS' | 'CAMBIAR_CLUSTER' | string;
  /** BALANCEO: paso 1 o 2; MANUAL: hecho por el usuario; SEPARACION solo en historiales anteriores. */
  motivo: 'BALANCEO' | 'SEPARACION' | 'MANUAL' | string;
  rutaId: number | null;
  ruta: string | null;
  cluster: string | null;
  tipo: string | null;
  bultos: number | null;
  chuteOrigen: string | null;
  chuteDestino: string | null;
  chuteModificado: string | null;
  /** CAMBIAR_CLUSTER: cluster de origen y de destino. */
  clusterOrigen: string | null;
  clusterDestino: string | null;
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
  /** Rutas movidas y chutes con otra dotación. */
  rutas: number;
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
  rutasOriginal: number;
  rutasBalanceado: number;
  /** Rutas que llegaron de otro cluster y las que se fueron a otro (cambio manual). */
  rutasEntraron: number;
  rutasSalieron: number;
  unidadesOriginal: number;
  unidadesBalanceado: number;
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
}

/** Rutas que pasaron de un cluster (original) a otro (actual), con lo que se llevaron. */
export interface CambioClusterSimulacion {
  origenId: number;
  origen: string;
  destinoId: number;
  destino: string;
  rutas: number;
  bultos: number;
  unidades: number;
  lineas: number;
  cod: number;
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
  /** Movimientos de rutas entre chutes: flujo de cualquier grupo de chutes. */
  movimientos: MovimientoChuteSimulacion[];
  /** Rutas que cambiaron de cluster; en la vista de un cluster, las que entraron o salieron de él. */
  cambiosCluster: CambioClusterSimulacion[];
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

/** Totales de un lado del chute (antes o después) con los filtros del modal. */
export interface ResumenRutasSimulacion {
  rutas: number;
  rutasB2c: number;
  rutasB2b: number;
  /** Rutas que cambiaron de chute o de cluster: salieron (antes) o llegaron (después). */
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
  /** Solo las rutas que cambiaron de chute o de cluster. */
  soloCambios: boolean;
}

export type LadoChute = 'ANTES' | 'DESPUES';

/** Detalle ejecutivo del modal: totales del conjunto filtrado y la página pedida. */
export interface RutasDashboardSimulacion {
  resumen: ResumenRutasSimulacion;
  rutas: Pagina<RutaSimulacion>;
}
