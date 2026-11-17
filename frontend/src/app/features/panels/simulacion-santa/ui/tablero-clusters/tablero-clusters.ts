import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import {
  Cluster,
  ClusterSimulacion,
  OrdenLlenado,
  TipoUbicacion,
} from '../../models/santa.model';
import { RampaVista } from '../anden-rampas/anden-rampas';
import { PasilloVista } from '../bloque-pasillos/bloque-pasillos';
import { ORDENES_LLENADO, colorCluster, entero, porcentaje } from '../../santa-formato';

export interface MovimientoUbicaciones {
  tipo: TipoUbicacion;
  numeros: number[];
  cluster: Cluster | null;
}

interface Ficha {
  clave: string;
  tipo: TipoUbicacion;
  numero: number;
  etiqueta: string;
  detalle: string;
}

interface Columna {
  cluster: Cluster | null;
  titulo: string;
  color: string;
  rampas: Ficha[];
  pasillos: Ficha[];
  resumen: ClusterSimulacion | null;
}

/**
 * Tablero de asignación: una columna por cluster (y «Sin cluster») con sus
 * rampas y pasillos como fichas. Se eligen fichas y se mueven a otro cluster
 * con los botones de la barra; cada columna muestra si sus posiciones alcanzan
 * para sus paletas. Presentación pura: emite los cambios.
 */
@Component({
  selector: 'app-tablero-clusters',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './tablero-clusters.html',
  styleUrl: './tablero-clusters.scss',
})
export class TableroClusters {
  readonly rampas = input.required<RampaVista[]>();
  readonly pasillos = input.required<PasilloVista[]>();
  readonly clusters = input<ClusterSimulacion[]>([]);
  readonly orden = input.required<[OrdenLlenado, OrdenLlenado, OrdenLlenado]>();
  readonly editable = input(false);

  readonly mover = output<MovimientoUbicaciones>();
  readonly cambiarOrden = output<{ cluster: Cluster; orden: OrdenLlenado }>();

  readonly ordenes = ORDENES_LLENADO;
  readonly entero = entero;
  readonly porcentaje = porcentaje;
  readonly destinos: (Cluster | null)[] = [1, 2, 3, null];

  readonly seleccion = signal<ReadonlySet<string>>(new Set());

  readonly columnas = computed<Columna[]>(() => {
    const resumen = new Map(this.clusters().map((c) => [c.cluster, c]));
    const grupos: (Cluster | null)[] = [1, 2, 3, null];
    return grupos.map((cluster) => ({
      cluster,
      titulo: cluster ? `Cluster ${cluster}` : 'Sin cluster',
      color: colorCluster(cluster),
      rampas: this.rampas()
        .filter((r) => r.cluster === cluster)
        .map((r) => ({
          clave: `R${r.numero}`,
          tipo: 'RAMPA' as const,
          numero: r.numero,
          etiqueta: `R${r.numero}`,
          detalle: `Rampa ${r.numero} · ${r.posiciones} de ${r.capacidad} posiciones`,
        })),
      pasillos: this.pasillos()
        .filter((p) => p.cluster === cluster)
        .map((p) => ({
          clave: `P${p.numero}`,
          tipo: 'PASILLO' as const,
          numero: p.numero,
          etiqueta: `P${p.numero}`,
          detalle: `Pasillo ${p.numero} · 24 posiciones`,
        })),
      resumen: cluster ? (resumen.get(cluster) ?? null) : null,
    }));
  });

  readonly cuantas = computed(() => this.seleccion().size);

  elegida(clave: string): boolean {
    return this.seleccion().has(clave);
  }

  alternar(ficha: Ficha): void {
    if (!this.editable()) {
      return;
    }
    this.seleccion.update((actual) => {
      const nueva = new Set(actual);
      if (nueva.has(ficha.clave)) {
        nueva.delete(ficha.clave);
      } else {
        nueva.add(ficha.clave);
      }
      return nueva;
    });
  }

  elegirColumna(columna: Columna): void {
    if (!this.editable()) {
      return;
    }
    const claves = [...columna.rampas, ...columna.pasillos].map((f) => f.clave);
    const todas = claves.length > 0 && claves.every((c) => this.seleccion().has(c));
    this.seleccion.update((actual) => {
      const nueva = new Set(actual);
      for (const c of claves) {
        if (todas) {
          nueva.delete(c);
        } else {
          nueva.add(c);
        }
      }
      return nueva;
    });
  }

  limpiar(): void {
    this.seleccion.set(new Set());
  }

  moverA(cluster: Cluster | null): void {
    const rampas: number[] = [];
    const pasillos: number[] = [];
    for (const clave of this.seleccion()) {
      const numero = Number(clave.slice(1));
      (clave.startsWith('R') ? rampas : pasillos).push(numero);
    }
    if (rampas.length) {
      this.mover.emit({ tipo: 'RAMPA', numeros: rampas, cluster });
    }
    if (pasillos.length) {
      this.mover.emit({ tipo: 'PASILLO', numeros: pasillos, cluster });
    }
    this.limpiar();
  }

  fijarOrden(cluster: Cluster, valor: string): void {
    this.cambiarOrden.emit({ cluster, orden: valor as OrdenLlenado });
  }

  etiquetaDestino(cluster: Cluster | null): string {
    return cluster ? `C${cluster}` : 'Sin cluster';
  }

  colorDestino(cluster: Cluster | null): string {
    return colorCluster(cluster);
  }
}
