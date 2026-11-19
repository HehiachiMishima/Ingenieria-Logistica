import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { compacto, entero, porcentaje } from '../../../../../../../shared/ui/charts/formato';
import { Icon } from '../../../../../../../shared/ui/icon/icon';
import { ResumenActividad, ResumenMonitoreo } from '../../../../data/observabilidad.model';
import { META_DISPONIBILIDAD, META_P95_MS, Nivel } from '../../../../ui/observabilidad.util';
import {
  CRITICO_ERROR,
  Delta,
  ICONO_NIVEL,
  LIMITE_ERROR,
  TEXTO_NIVEL,
  deltaPuntos,
  deltaRelativo,
  etiquetaEje,
  maximoRedondo,
  nivelDisponibilidad,
  nivelError,
  nivelP95,
} from './grafico.util';

interface Punto {
  /** Posición en % del ancho y del alto del trazo. */
  x: number;
  y: number;
  texto: string;
}

interface Tarjeta {
  clave: string;
  etiqueta: string;
  valor: string;
  nivel: Nivel | null;
  delta: Delta | null;
  nota: string;
  tipo: 'medidor' | 'bala' | 'tendencia';
  /** Medidor y bala: valor y meta en % de la escala, y sus rótulos. */
  pct?: number;
  metaPct?: number;
  desde?: string;
  hasta?: string;
  metaTexto?: string;
  bandas?: { desde: number; ancho: number; tono: 'bien' | 'atencion' | 'critico' }[];
  aria?: string;
  /** Tendencia: 12 semanas. */
  trazo?: string;
  puntos?: Punto[];
}

const escala = (v: number, desde: number, hasta: number) => Math.min(100, Math.max(0, ((v - desde) / (hasta - desde)) * 100));

/**
 * Tablero de metas: cada indicador contra su meta, con un estado en palabras (nunca solo
 * color). Disponibilidad y tasa de error en medidor; el tiempo p95 en una gráfica de bala con
 * sus tramos (en meta, atención, crítico); usuarios y acciones con su tendencia de 12 semanas.
 */
@Component({
  selector: 'app-tablero-metas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  templateUrl: './tablero-metas.html',
  styleUrl: './tablero-metas.scss',
})
export class TableroMetas {
  readonly monitoreo = input<ResumenMonitoreo | null>(null);
  readonly actividad = input<ResumenActividad | null>(null);
  readonly cuentasActivas = input<number | null>(null);

  readonly textoNivel = TEXTO_NIVEL;
  readonly iconoNivel = ICONO_NIVEL;
  /** Semana bajo el puntero en una tendencia (si no, se lee la última). */
  readonly activo = signal<{ clave: string; i: number } | null>(null);

  readonly tarjetas = computed<Tarjeta[]>(() => {
    const m = this.monitoreo();
    const a = this.actividad();
    const lista: Tarjeta[] = [];
    if (m?.peticiones) {
      const disponibilidad = 1 - m.erroresServidor / m.peticiones;
      lista.push({
        clave: 'disponibilidad',
        etiqueta: 'Disponibilidad',
        valor: porcentaje(disponibilidad),
        nivel: nivelDisponibilidad(disponibilidad),
        delta: deltaPuntos(
          disponibilidad,
          m.peticionesPrevias ? 1 - m.erroresServidorPrevios / m.peticionesPrevias : null,
          false,
        ),
        nota: 'peticiones sin error 5xx',
        tipo: 'medidor',
        // Escala 98–100 %: en 0–100 % el valor y la meta se verían pegados al borde.
        pct: escala(disponibilidad, 0.98, 1),
        metaPct: escala(META_DISPONIBILIDAD, 0.98, 1),
        desde: '98 %',
        hasta: '100 %',
        metaTexto: `meta ${porcentaje(META_DISPONIBILIDAD)}`,
        aria: `Disponibilidad ${porcentaje(disponibilidad)} contra una meta de ${porcentaje(META_DISPONIBILIDAD)}`,
      });

      const tope = Math.max(1000, maximoRedondo(m.p95 * 1.1));
      lista.push({
        clave: 'p95',
        etiqueta: 'Respuesta p95',
        valor: `${entero(m.p95)} ms`,
        nivel: nivelP95(m.p95),
        delta: deltaRelativo(m.p95, m.p95Previo, true),
        nota: 'el 95 % de las peticiones responde en menos',
        tipo: 'bala',
        pct: escala(m.p95, 0, tope),
        metaPct: escala(META_P95_MS, 0, tope),
        desde: '0',
        hasta: `${entero(tope)} ms`,
        metaTexto: `meta ${entero(META_P95_MS)} ms`,
        bandas: [
          { desde: 0, ancho: escala(META_P95_MS, 0, tope), tono: 'bien' },
          { desde: escala(META_P95_MS, 0, tope), ancho: escala(META_P95_MS * 1.5, 0, tope) - escala(META_P95_MS, 0, tope), tono: 'atencion' },
          { desde: escala(META_P95_MS * 1.5, 0, tope), ancho: 100 - escala(META_P95_MS * 1.5, 0, tope), tono: 'critico' },
        ],
        aria: `Tiempo p95 ${entero(m.p95)} ms contra una meta de ${entero(META_P95_MS)} ms`,
      });

      const tasa = m.errores / m.peticiones;
      const topeError = tasa > CRITICO_ERROR ? 0.1 : CRITICO_ERROR;
      lista.push({
        clave: 'error',
        etiqueta: 'Tasa de error',
        valor: porcentaje(tasa),
        nivel: nivelError(tasa),
        delta: deltaPuntos(tasa, m.peticionesPrevias ? m.erroresPrevios / m.peticionesPrevias : null, true),
        nota: 'respuestas 4xx y 5xx',
        tipo: 'medidor',
        pct: escala(tasa, 0, topeError),
        metaPct: escala(LIMITE_ERROR, 0, topeError),
        desde: '0 %',
        hasta: porcentaje(topeError),
        metaTexto: `límite ${porcentaje(LIMITE_ERROR)}`,
        aria: `Tasa de error ${porcentaje(tasa)} contra un límite de ${porcentaje(LIMITE_ERROR)}`,
      });
    }
    if (a) {
      const cuentas = this.cuentasActivas();
      lista.push({
        clave: 'usuarios',
        etiqueta: 'Usuarios activos',
        valor: entero(a.usuarios),
        nivel: null,
        delta: deltaRelativo(a.usuarios, a.usuariosPrevios, false),
        nota: cuentas ? `${porcentaje(a.usuarios / cuentas)} de las cuentas activas` : 'con al menos una acción',
        tipo: 'tendencia',
        ...this.tendencia(a.semanas.map((s) => ({ periodo: s.periodo, valor: s.usuarios })), (v) => `${entero(v)} usuarios`),
      });
      lista.push({
        clave: 'acciones',
        etiqueta: 'Acciones',
        valor: compacto(a.acciones),
        nivel: null,
        delta: deltaRelativo(a.acciones, a.accionesPrevias, false),
        nota: 'clics y cambios en la plataforma',
        tipo: 'tendencia',
        ...this.tendencia(a.semanas.map((s) => ({ periodo: s.periodo, valor: s.acciones })), (v) => `${entero(v)} acciones`),
      });
    }
    return lista;
  });

  /** Cada tarjeta con su lectura: en una tendencia, la semana señalada o, si no, la última. */
  readonly vista = computed(() => {
    const a = this.activo();
    return this.tarjetas().map((t) => {
      const puntos = t.puntos ?? [];
      const marca = puntos[a?.clave === t.clave ? a.i : puntos.length - 1] ?? null;
      return { ...t, marca, lectura: marca?.texto ?? 'Sin semanas con datos' };
    });
  });

  private tendencia(semanas: { periodo: string; valor: number }[], texto: (v: number) => string) {
    if (!semanas.length) {
      return { trazo: '', puntos: [] as Punto[] };
    }
    const max = Math.max(...semanas.map((s) => s.valor));
    const min = Math.min(...semanas.map((s) => s.valor));
    const n = semanas.length;
    const puntos: Punto[] = semanas.map((s, i) => ({
      x: n === 1 ? 50 : (i / (n - 1)) * 100,
      // 10–90 % del alto: la línea no toca los bordes y el punto final no se recorta.
      y: max === min ? 50 : 90 - ((s.valor - min) / (max - min)) * 80,
      texto: `${i === n - 1 ? 'Esta semana' : `Semana del ${etiquetaEje(s.periodo)}`}: ${texto(s.valor)}`,
    }));
    return { trazo: puntos.map((p) => `${p.x.toFixed(2)},${p.y.toFixed(2)}`).join(' '), puntos };
  }
}
