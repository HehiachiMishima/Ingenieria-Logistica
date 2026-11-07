import { Pipe, PipeTransform } from '@angular/core';
import { formatearEntero, formatearHoras, formatearNumero } from './simulacion-formato';

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

/** Número sin decimales, redondeado hacia arriba (199.1 → «200»). */
@Pipe({ name: 'entero' })
export class EnteroPipe implements PipeTransform {
  transform(valor: number | null | undefined): string {
    return formatearEntero(valor);
  }
}

/** Horas como «1h 25m» (ver `formatearHoras`), memorizado por valor. */
@Pipe({ name: 'horas' })
export class HorasPipe implements PipeTransform {
  transform(valor: number | null | undefined): string {
    return formatearHoras(valor);
  }
}
