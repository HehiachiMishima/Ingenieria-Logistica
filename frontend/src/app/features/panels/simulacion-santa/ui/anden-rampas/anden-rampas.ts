import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  input,
  output,
  viewChild,
} from '@angular/core';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { Cluster, Lado, PaletaSimulacion } from '../../models/santa.model';
import { celdaRampaHabilitada, clavePosicion, tamanoPaleta } from '../../plano/plano-santa';
import { colorCluster } from '../../santa-formato';
import { anchoDelHost } from '../ancho-host';
import { Paleta } from '../paleta/paleta';

/** Rampa tal como se dibuja: cluster y posiciones del borrador, capacidad del plano. */
export interface RampaVista {
  numero: number;
  cluster: Cluster | null;
  capacidad: number;
  posiciones: number;
}

interface CeldaVista {
  clave: string;
  lado: Lado;
  fila: number;
  x: number;
  y: number;
  habilitada: boolean;
  paleta: PaletaSimulacion | null;
}

interface RampaDibujo {
  rampa: RampaVista;
  linea: { x: number; alto: number };
  puerta: { x: number; y: number; ancho: number; alto: number };
  letrero: { x: number; y: number; ancho: number; alto: number };
  celdas: CeldaVista[];
}

/** Filas del rack más alto (rampas de 14 posiciones). */
const FILAS = 7;
const SEP = 6;
const ARRIBA = 8;

/**
 * Ventana «Rampas» en modo compacto: las calles entre racks se reducen y la
 * paleta tiene el tamaño M ({@link tamanoPaleta}), el mismo que en los
 * pasillos. Al abrir se ven las rampas 15–20; el resto se recorre con scroll
 * horizontal (y vertical si la pantalla es baja). Cada puerta tiene su rampa
 * niveladora y su letrero.
 */
@Component({
  selector: 'app-anden-rampas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Paleta],
  templateUrl: './anden-rampas.html',
  styleUrl: './anden-rampas.scss',
})
export class AndenRampas {
  readonly rampas = input.required<RampaVista[]>();
  readonly paletas = input.required<Map<string, PaletaSimulacion>>();
  readonly seleccionada = input<number | null>(null);
  readonly seleccionar = output<PaletaSimulacion>();

  private readonly scroll = viewChild.required<ElementRef<HTMLElement>>('scroll');
  private readonly ancho = anchoDelHost();

  readonly colorCluster = colorCluster;

  readonly g = computed(() => {
    const { ancho: pw, alto: ph } = tamanoPaleta(this.ancho());
    const calle = Math.max(24, Math.round(pw / 3));
    const unidad = 2 * pw + SEP + calle;
    const puertaAncho = Math.round(pw * 1.5);
    const inicio = Math.max(calle, Math.round(puertaAncho / 2) + 8);
    const altoRacks = FILAS * (ph + SEP);
    const puertaY = ARRIBA + altoRacks + 6;
    const puertaAlto = Math.max(44, Math.round(ph * 0.8));
    const letreroAlto = Math.max(28, Math.round(ph * 0.45));
    const letreroAncho = Math.max(48, Math.round(pw * 0.62));
    // El letrero va montado sobre la rampa (como en la puerta), no debajo: ahorra alto.
    const letreroY = puertaY + Math.round((puertaAlto - letreroAlto) / 2);
    const paletas = this.paletas();

    const dibujo: RampaDibujo[] = this.rampas().map((rampa, k) => {
      const xIzq = inicio + k * unidad;
      const xDer = xIzq + pw + SEP;
      const centroPuerta = xIzq - calle / 2;
      const celdas: CeldaVista[] = [];
      const filas = Math.ceil(rampa.capacidad / 2);
      for (const lado of ['IZQ', 'DER'] as const) {
        for (let fila = 1; fila <= filas; fila++) {
          const clave = clavePosicion('RAMPA', rampa.numero, lado, fila);
          celdas.push({
            clave,
            lado,
            fila,
            x: lado === 'IZQ' ? xIzq : xDer,
            y: ARRIBA + (fila - 1) * (ph + SEP),
            habilitada: celdaRampaHabilitada(lado, fila, rampa.posiciones),
            paleta: paletas.get(clave) ?? null,
          });
        }
      }
      return {
        rampa,
        linea: { x: xIzq + pw + SEP / 2 - 1, alto: ARRIBA + altoRacks },
        puerta: { x: centroPuerta - puertaAncho / 2, y: puertaY, ancho: puertaAncho, alto: puertaAlto },
        letrero: { x: centroPuerta - letreroAncho / 2, y: letreroY, ancho: letreroAncho, alto: letreroAlto },
        celdas,
      };
    });

    return {
      pw,
      ph,
      unidad,
      anchoTotal: inicio + this.rampas().length * unidad,
      altoTotal: puertaY + puertaAlto + 10,
      fuenteLetrero: Math.max(16, Math.round(letreroAlto * 0.6)),
      dibujo,
    };
  });

  lugar(rampa: number, c: CeldaVista): string {
    return `Rampa ${rampa} · columna ${c.lado === 'IZQ' ? 'izquierda' : 'derecha'} · fila ${c.fila}`;
  }

  /** Avanza o retrocede lo que se ve de rampas en pantalla. */
  mover(direccion: 1 | -1): void {
    const el = this.scroll().nativeElement;
    const u = this.g().unidad;
    const paso = Math.max(u, Math.floor(el.clientWidth / u) * u);
    el.scrollBy({ left: direccion * paso, behavior: 'smooth' });
  }

  alTeclado(evento: KeyboardEvent): void {
    const el = this.scroll().nativeElement;
    const acciones: Record<string, () => void> = {
      Home: () => el.scrollTo({ left: 0, behavior: 'smooth' }),
      End: () => el.scrollTo({ left: el.scrollWidth, behavior: 'smooth' }),
      PageDown: () => this.mover(1),
      PageUp: () => this.mover(-1),
    };
    const accion = acciones[evento.key];
    if (accion) {
      evento.preventDefault();
      accion();
    }
  }
}
