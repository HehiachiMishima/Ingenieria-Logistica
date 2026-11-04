/** Valor de un `<input>` o `<select>` a partir de su evento. */
export function valorTexto(evento: Event): string {
  return (evento.target as HTMLInputElement | HTMLSelectElement).value;
}

/** Id numérico elegido en un `<select>`; la opción vacía («Todos») da null. */
export function valorNumero(evento: Event): number | null {
  const valor = valorTexto(evento);
  return valor ? Number(valor) : null;
}
