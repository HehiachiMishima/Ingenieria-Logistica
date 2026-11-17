import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { FilaEnvio, duracion, porciento } from '../../santa-calculo';
import { entero } from '../../santa-formato';

/**
 * Envíos de un registro: cuánto trae cada uno (% del total), cuánto tarda con
 * los operarios simulados frente a la ventana, y cuántos operarios pide para
 * caber en ella. Presentación pura.
 */
@Component({
  selector: 'app-envios-tiempos',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './envios-tiempos.html',
  styleUrl: './envios-tiempos.scss',
})
export class EnviosTiempos {
  readonly filas = input.required<FilaEnvio[]>();
  readonly ventanaMin = input.required<number>();
  readonly operarios = input.required<number>();
  /** Envío en curso del registro (0 = ninguno recibido). */
  readonly envioActual = input(0);

  readonly entero = entero;
  readonly duracion = duracion;
  readonly porciento = porciento;

  /** Escala común de la barra de tiempo: la ventana o el envío más largo. */
  readonly escalaMin = computed(() =>
    Math.max(this.ventanaMin(), ...this.filas().map((f) => f.minutos ?? 0), 1),
  );

  anchoTiempo(f: FilaEnvio): number {
    return Math.min(100, ((f.minutos ?? 0) / this.escalaMin()) * 100);
  }

  marcaVentana(): number {
    return Math.min(100, (this.ventanaMin() / this.escalaMin()) * 100);
  }

  excede(f: FilaEnvio): boolean {
    return (f.minutos ?? 0) > this.ventanaMin();
  }
}
