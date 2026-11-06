import { Pipe, PipeTransform } from '@angular/core';
import { formatearHoras, formatearNumero } from './simulacion-formato';

/**
 * Número con separadores de es-PE. Pipe puro: Angular solo lo recalcula cuando
 * cambia el valor, a diferencia de llamar `formatearNumero()` en la plantilla.
 */
@Pipe({ name: 'numero' })
export class NumeroPipe implements PipeTransform {
  transform(valor: number | null | undefined, decimales = 0): string {
    return formatearNumero(valor, decimales);
  }
}

/** Horas como «1h 25m» (ver `formatearHoras`), memorizado por valor. */
@Pipe({ name: 'horas' })
export class HorasPipe implements PipeTransform {
  transform(valor: number | null | undefined): string {
    return formatearHoras(valor);
  }
}
