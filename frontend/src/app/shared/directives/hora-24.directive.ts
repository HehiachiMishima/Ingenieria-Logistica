import { Directive, inject } from '@angular/core';
import { NgControl } from '@angular/forms';

/**
 * Campo de hora en formato de 24 h ("HH:mm") como texto: el input time del
 * navegador sigue el idioma del equipo y puede mostrar a. m./p. m. Al salir
 * del campo completa lo escrito: "730" → "07:30", "7" → "07:00", "19.30" → "19:30".
 * Lo que no se entiende se deja tal cual para que el validador lo marque.
 */
@Directive({
  selector: 'input[appHora24]',
  host: {
    inputmode: 'numeric',
    maxlength: '5',
    placeholder: 'HH:mm',
    autocomplete: 'off',
    '(blur)': 'normalizar()',
  },
})
export class Hora24 {
  private readonly control = inject(NgControl, { self: true });

  normalizar(): void {
    const actual = this.control.value;
    if (typeof actual !== 'string') {
      return;
    }
    const normalizada = normalizarHora(actual);
    if (normalizada !== actual) {
      this.control.control?.setValue(normalizada);
    }
  }
}

export function normalizarHora(texto: string): string {
  const limpio = texto.trim();
  // Solo dígitos: los dos últimos son los minutos ("730", "1930"); si no, "h:m" o "h".
  const partes = /^\d{3,4}$/.test(limpio)
    ? [limpio, limpio.slice(0, -2), limpio.slice(-2)]
    : /^(\d{1,2})(?:[:.,h ](\d{1,2}))?$/.exec(limpio);
  if (!partes) {
    return limpio;
  }
  const horas = Number(partes[1]);
  const minutos = Number(partes[2] ?? 0);
  if (horas > 23 || minutos > 59) {
    return limpio;
  }
  return `${String(horas).padStart(2, '0')}:${String(minutos).padStart(2, '0')}`;
}
