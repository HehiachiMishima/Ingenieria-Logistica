import { Directive, ElementRef, forwardRef, inject } from '@angular/core';
import { NG_VALIDATORS, ValidationErrors, Validator } from '@angular/forms';

/**
 * Fechas y horas nativas a medio escribir.
 *
 * Un `<input type="time">` en formato de 12 horas (navegador en español)
 * muestra «10:30 -----» pero su valor sigue VACÍO hasta elegir a. m./p. m.;
 * lo mismo con una fecha incompleta. Sin esta directiva el formulario lo trata
 * como «sin hora» (la incidencia quedaría abierta o la hora «obligatoria»
 * aunque se vea escrita). Marca el control con `incompleta` mientras el
 * navegador reporte `validity.badInput`, y revalida en cada tecla (el valor no
 * cambia mientras está incompleto, así que no basta con el evento `input`).
 */
@Directive({
  selector: 'input[type=time][formControlName], input[type=date][formControlName]',
  providers: [{ provide: NG_VALIDATORS, useExisting: forwardRef(() => EntradaCompleta), multi: true }],
  host: {
    '(input)': 'revalidar()',
    '(keyup)': 'revalidar()',
    '(blur)': 'revalidar()',
  },
})
export class EntradaCompleta implements Validator {
  private readonly elemento = inject<ElementRef<HTMLInputElement>>(ElementRef);
  private alCambiar?: () => void;

  validate(): ValidationErrors | null {
    return this.elemento.nativeElement.validity.badInput ? { incompleta: true } : null;
  }

  registerOnValidatorChange(fn: () => void): void {
    this.alCambiar = fn;
  }

  revalidar(): void {
    this.alCambiar?.();
  }
}
