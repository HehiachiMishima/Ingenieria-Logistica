import { ConfiguracionSimulacion } from '../../data/simulacion.model';
import {
  LIMITES,
  colorCluster,
  formatearHoras,
  formatearNumero,
  horas,
} from '../../simulacion-formato';

/**
 * Lógica pura de la configuración: resúmenes y normalización de lo que se
 * escribe. Sin estado ni servicios, para que la vista solo pinte.
 */

export type Paso = 'RUTAS' | 'OPERARIOS' | 'CERRAR';

export type EstadoPaso = 'hecho' | 'actual' | 'espera';

export interface PasoCluster {
  numero: number;
  titulo: string;
  estado: EstadoPaso;
  dato: string;
}

/** Paso que toca según el estado del cluster; sin entrada (CERRADO) = 4. */
const PASO_POR_ESTADO: Record<string, number> = { PENDIENTE: 1, RUTAS: 2, LISTO: 3 };

/** Un cluster tal como lo muestra su tarjeta. */
export interface ResumenCluster {
  id: number;
  nombre: string;
  color: string;
  /** Paso que toca: 1 rutas, 2 operarios, 3 cerrar (4 = cerrado). */
  paso: number;
  pasos: PasoCluster[];
  enCurso: boolean;
  cerrado: boolean;
  balanceado: boolean;
  conOperarios: boolean;
  rutas: number;
  bultos: number;
  chutes: number;
  /** Diferencia de bultos entre sus chutes (%): hoy y al cargar el Excel; null con un solo chute. */
  dispersion: number | null;
  diferenciaOriginal: number | null;
  /** Chutes donde comparte con su incompatible: al balancearlo, sus rutas salen de ahí. */
  mezclas: string[];
  operarios: number;
  tiempo: number;
  limite: number;
  cumple: boolean;
  /** % del límite que usa la ola (la barra se corta en 100 %). */
  uso: number | null;
  /** Cluster anterior y cuántos operarios siguen en su chute desde él. */
  previo: string | null;
  siguen: number;
  siguiente: string | null;
}

/**
 * Cada cluster en el orden de las olas: su carga, qué tan parejos están sus
 * chutes en bultos ((más cargado − menos cargado) / promedio) y, si ya tiene
 * operarios, cuántos, su tiempo frente al límite y cuántos siguen en su
 * chute desde la ola anterior.
 */
export function resumirClusters(
  cfg: ConfiguracionSimulacion,
  velocidad: number,
  limites: Record<number, number>,
): ResumenCluster[] {
  const enCurso = cfg.clusters.find((cluster) => cluster.estado !== 'CERRADO')?.id ?? null;
  const acumulado = new Map<
    number,
    { bultos: number; rutas: number; porChute: Map<number, number>; operarios: Map<number, number> }
  >();
  for (const fila of cfg.carga) {
    const actual = acumulado.get(fila.clusterId) ?? {
      bultos: 0,
      rutas: 0,
      porChute: new Map<number, number>(),
      operarios: new Map<number, number>(),
    };
    actual.bultos += Number(fila.bultos);
    actual.rutas += Number(fila.rutas);
    actual.porChute.set(
      fila.chuteId,
      (actual.porChute.get(fila.chuteId) ?? 0) + Number(fila.bultos),
    );
    if (fila.operarios !== null) {
      actual.operarios.set(fila.chuteId, Number(fila.operarios));
    }
    acumulado.set(fila.clusterId, actual);
  }

  return cfg.clusters.map((cluster, indice) => {
    const datos = acumulado.get(cluster.id);
    const cargas = [...(datos?.porChute.values() ?? [])].filter((bultos) => bultos > 0);
    const promedio = cargas.reduce((total, bultos) => total + bultos, 0) / (cargas.length || 1);
    const dispersion =
      cargas.length < 2
        ? null
        : Math.round(((Math.max(...cargas) - Math.min(...cargas)) / promedio) * 100);
    const incompatibles = new Set(
      cfg.clusters.filter((otro) => cluster.incompatibles.includes(otro.id)).map((o) => o.nombre),
    );
    const conOperarios = cluster.estado === 'LISTO' || cluster.estado === 'CERRADO';
    let tiempo = 0;
    let operarios = 0;
    for (const [chuteId, bultos] of datos?.porChute ?? []) {
      const enChute = datos?.operarios.get(chuteId) ?? 0;
      operarios += enChute;
      tiempo = Math.max(tiempo, horas(bultos, velocidad, enChute));
    }
    const previo = indice > 0 ? cfg.clusters[indice - 1] : null;
    const operariosPrevios = previo ? acumulado.get(previo.id)?.operarios : undefined;
    let siguen = 0;
    for (const [chuteId, enChute] of datos?.operarios ?? []) {
      siguen += Math.min(enChute, operariosPrevios?.get(chuteId) ?? 0);
    }
    const limite = limites[cluster.id] ?? 0;
    const medible = conOperarios && limite > 0 && Number.isFinite(tiempo);
    const paso = PASO_POR_ESTADO[cluster.estado] ?? 4;
    const siguiente = cfg.clusters[indice + 1]?.nombre ?? null;
    const estadoPaso = (numero: number): EstadoPaso =>
      paso > numero ? 'hecho' : paso === numero ? 'actual' : 'espera';
    return {
      id: cluster.id,
      nombre: cluster.nombre,
      color: colorCluster(indice),
      paso,
      pasos: [
        {
          numero: 1,
          titulo: 'Rutas',
          estado: estadoPaso(1),
          dato:
            paso > 1 && dispersion !== null
              ? `${cluster.diferenciaOriginal ?? dispersion} % → ${dispersion} %`
              : 'Igualar bultos',
        },
        {
          numero: 2,
          titulo: 'Operarios',
          estado: estadoPaso(2),
          dato: conOperarios
            ? `${formatearNumero(operarios)} · ${formatearHoras(tiempo)}`
            : 'Todo el equipo',
        },
        {
          numero: 3,
          titulo: 'Cerrar',
          estado: estadoPaso(3),
          dato: siguiente ? `Pasa a ${siguiente}` : 'Termina',
        },
      ],
      enCurso: cluster.id === enCurso,
      cerrado: cluster.estado === 'CERRADO',
      balanceado: cluster.estado !== 'PENDIENTE',
      conOperarios,
      rutas: datos?.rutas ?? 0,
      bultos: datos?.bultos ?? 0,
      chutes: cargas.length,
      dispersion,
      diferenciaOriginal: cluster.diferenciaOriginal,
      mezclas: cfg.mezclas
        .filter(
          (mezcla) =>
            mezcla.clusters.includes(cluster.nombre) &&
            mezcla.clusters.some((nombre) => incompatibles.has(nombre)),
        )
        .map((mezcla) => mezcla.chute),
      operarios,
      tiempo,
      limite,
      cumple: medible && tiempo <= limite + 0.01,
      uso: medible ? Math.min(100, Math.round((tiempo / limite) * 100)) : null,
      previo: previo?.nombre ?? null,
      siguen,
      siguiente,
    };
  });
}

// ---------------- Normalización de lo que se escribe ----------------

/** Velocidad positiva con dos decimales, hasta el tope; null si no es válida. */
export function normalizarVelocidad(texto: string): number | null {
  const leido = Math.round(Number(texto) * 100) / 100;
  return Number.isFinite(leido) && leido > 0 ? Math.min(LIMITES.velocidad, leido) : null;
}

/** Operarios del equipo: entero entre 1 y el tope. */
export function normalizarEquipo(texto: string): number {
  const valor = Number.parseInt(texto, 10);
  return Number.isInteger(valor) && valor >= 1 ? Math.min(LIMITES.equipo, valor) : 1;
}

/** Máximo por chute: vacío = sin tope propio; si no, entre 1 y el tope técnico. */
export function normalizarPorChute(texto: string): number | null {
  if (texto.trim() === '') {
    return null;
  }
  const valor = Number.parseInt(texto, 10);
  return Number.isInteger(valor) && valor >= 1 ? Math.min(LIMITES.operariosPorChute, valor) : 1;
}

/** Límite de horas de un cluster: no negativo, dos decimales, hasta el tope. */
export function normalizarLimite(texto: string): number {
  const valor = Math.round(Number(texto) * 100) / 100;
  return Number.isFinite(valor) && valor >= 0 ? Math.min(LIMITES.horas, valor) : 0;
}
