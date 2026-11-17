import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { Cluster, ClusterSimulacion, OrdenLlenado } from '../../models/santa.model';
import { CLUSTERS, ORDENES_LLENADO, colorCluster, entero, porcentaje } from '../../santa-formato';
import { ArrastreFichas, DestinoFichas, PasoFichas, TipoFicha } from './arrastre-fichas.directive';

/** Una rampa o un pasillo como ficha del tablero. */
export interface FichaAsignacion {
  /** «R15» o «P7». */
  clave: string;
  tipo: TipoFicha;
  numero: number;
  cluster: Cluster | null;
  posiciones: number;
  ocupadas: number;
  /** Su cluster difiere del guardado (se dibuja rayada). */
  cambiada: boolean;
}

export interface MovimientoFichas {
  claves: readonly string[];
  cluster: Cluster | null;
}

type Nivel = 'mal' | 'justo' | 'bien';

interface ColumnaVista {
  cluster: Cluster | null;
  color: string;
  rampas: FichaAsignacion[];
  pasillos: FichaAsignacion[];
  resumen: ClusterSimulacion | null;
  /** Paletas − posiciones: > 0 faltan, ≤ 0 sobran. */
  faltan: number;
  ocupacion: number;
  nivel: Nivel;
}

/**
 * Asignación compacta: una columna por cluster (C1, C2, C3 y «Sin cluster»),
 * cada una con su propio scroll vertical. Las fichas muestran solo el número
 * (rampa de esquinas rectas, pasillo redondeado) y una barrita de ocupación;
 * se arrastran entre columnas (una o la selección entera) o se mueven con la
 * barra «Mover a…». Presentación pura: emite los movimientos.
 */
@Component({
  selector: 'app-matriz-asignacion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ArrastreFichas],
  host: { '(keydown.escape)': 'limpiar()' },
  templateUrl: './matriz-asignacion.html',
  styleUrl: './matriz-asignacion.scss',
})
export class MatrizAsignacion {
  readonly fichas = input.required<FichaAsignacion[]>();
  readonly clusters = input<ClusterSimulacion[]>([]);
  readonly orden = input.required<readonly OrdenLlenado[]>();
  readonly editable = input(false);
  /** Texto de búsqueda: «15», «P7», «13-18», «r20, p3». */
  readonly texto = input('');
  readonly soloCambios = input(false);

  readonly mover = output<MovimientoFichas>();
  readonly cambiarOrden = output<{ cluster: Cluster; orden: OrdenLlenado }>();

  readonly ordenes = ORDENES_LLENADO;
  readonly destinos: (Cluster | null)[] = [...CLUSTERS, null];
  readonly entero = entero;
  readonly colorCluster = colorCluster;

  readonly seleccion = signal<ReadonlySet<string>>(new Set());
  private readonly ultima = signal<string | null>(null);
  readonly paso = signal<PasoFichas | null>(null);

  private readonly porClave = computed(() => new Map(this.fichas().map((f) => [f.clave, f])));

  /** Claves que coinciden con la búsqueda (null = sin búsqueda). */
  readonly buscadas = computed<ReadonlySet<string> | null>(() => {
    const texto = this.texto().trim().toUpperCase();
    if (!texto) {
      return null;
    }
    const salida = new Set<string>();
    for (const parte of texto.split(/[,\s]+/).filter(Boolean)) {
      const m = /^([RP])?(\d{1,2})(?:-(\d{1,2}))?$/.exec(parte);
      if (!m) {
        continue;
      }
      const desde = Number(m[2]);
      const hasta = m[3] ? Number(m[3]) : desde;
      for (const f of this.fichas()) {
        if ((!m[1] || f.tipo === m[1]) && f.numero >= Math.min(desde, hasta) && f.numero <= Math.max(desde, hasta)) {
          salida.add(f.clave);
        }
      }
    }
    return salida;
  });

  readonly columnas = computed<ColumnaVista[]>(() => {
    const resumen = new Map(this.clusters().map((c) => [c.cluster, c]));
    const visibles = this.fichas().filter((f) => !this.soloCambios() || f.cambiada);
    return this.destinos.map((cluster) => {
      const r = cluster ? (resumen.get(cluster) ?? null) : null;
      const ocupacion = r ? porcentaje(r.paletas, Math.max(1, r.posiciones)) : 0;
      return {
        cluster,
        color: colorCluster(cluster),
        rampas: visibles.filter((f) => f.cluster === cluster && f.tipo === 'R'),
        pasillos: visibles.filter((f) => f.cluster === cluster && f.tipo === 'P'),
        resumen: r,
        faltan: r ? r.paletas - r.posiciones : 0,
        ocupacion,
        nivel: ocupacion > 100 ? 'mal' : ocupacion > 85 ? 'justo' : 'bien',
      };
    });
  });

  readonly mensaje = computed(() => {
    const p = this.paso();
    if (!p) {
      return this.seleccion().size > 0
        ? `${this.seleccion().size} elegidas: arrástralas a un cluster o usa «Mover a».`
        : 'Arrastra rampas y pasillos entre clusters. Clic para elegir varias (Shift para un rango).';
    }
    if (p.motivo) {
      return p.motivo;
    }
    if (p.cluster === null) {
      return 'Soltar aquí: quedan sin cluster (no se usan en la simulación).';
    }
    const suma = p.claves
      .map((c) => this.porClave().get(c))
      .filter((f): f is FichaAsignacion => !!f && f.cluster !== p.cluster)
      .reduce((s, f) => s + f.posiciones, 0);
    const actual = this.clusters().find((c) => c.cluster === p.cluster)?.posiciones ?? 0;
    return `Soltar en C${p.cluster}: +${entero(suma)} posiciones (${entero(actual + suma)} en total).`;
  });

  /** Solo se rechaza soltar donde ya están todas (no cambiaría nada). */
  readonly validar = (claves: readonly string[], cluster: Cluster | null): string | null =>
    claves.every((c) => this.porClave().get(c)?.cluster === cluster)
      ? `Ya ${claves.length === 1 ? 'está' : 'están'} en ${cluster ? 'C' + cluster : 'Sin cluster'}.`
      : null;

  readonly grupo = (clave: string): readonly string[] =>
    this.editable() && this.seleccion().has(clave) ? [...this.seleccion()] : [clave];

  coincide(clave: string): boolean {
    const b = this.buscadas();
    return b === null || b.has(clave);
  }

  elegida(clave: string): boolean {
    return this.seleccion().has(clave);
  }

  alternar(ficha: FichaAsignacion, evento: MouseEvent | KeyboardEvent): void {
    if (!this.editable()) {
      return;
    }
    const ultima = this.ultima();
    const anterior = ultima ? this.porClave().get(ultima) : undefined;
    this.seleccion.update((actual) => {
      const nueva = new Set(actual);
      if (evento.shiftKey && anterior && anterior.tipo === ficha.tipo) {
        const [a, b] = [Math.min(anterior.numero, ficha.numero), Math.max(anterior.numero, ficha.numero)];
        for (const f of this.fichas()) {
          if (f.tipo === ficha.tipo && f.numero >= a && f.numero <= b) {
            nueva.add(f.clave);
          }
        }
      } else if (nueva.has(ficha.clave)) {
        nueva.delete(ficha.clave);
      } else {
        nueva.add(ficha.clave);
      }
      return nueva;
    });
    this.ultima.set(ficha.clave);
  }

  alTeclado(ficha: FichaAsignacion, evento: KeyboardEvent): void {
    if (evento.key === 'Enter' || evento.key === ' ') {
      evento.preventDefault();
      this.alternar(ficha, evento);
    }
  }

  limpiar(): void {
    this.seleccion.set(new Set());
    this.ultima.set(null);
  }

  soltar(destino: DestinoFichas): void {
    this.mover.emit({ claves: destino.claves, cluster: destino.cluster });
    this.limpiar();
  }

  moverSeleccion(cluster: Cluster | null): void {
    const claves = [...this.seleccion()];
    if (claves.length > 0) {
      this.mover.emit({ claves, cluster });
    }
    this.limpiar();
  }

  fijarOrden(cluster: Cluster, orden: OrdenLlenado): void {
    this.cambiarOrden.emit({ cluster, orden });
  }

  /** Porcentaje ocupado de una rampa o pasillo (barrita de la ficha). */
  llenado(f: FichaAsignacion): number {
    return f.posiciones > 0 ? Math.min(100, Math.round((f.ocupadas / f.posiciones) * 100)) : 0;
  }

  etiqueta(f: FichaAsignacion): string {
    const nombre = f.tipo === 'R' ? 'Rampa' : 'Pasillo';
    const cambio = f.cambiada ? ' · cambio sin guardar' : '';
    return `${nombre} ${f.numero} · ${f.ocupadas} de ${f.posiciones} posiciones ocupadas${cambio}`;
  }
}
