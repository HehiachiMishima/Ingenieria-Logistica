/** Texto comparable: minúsculas y sin tildes («José» → «jose»). */
export function normalizarTexto(texto: string | null | undefined): string {
  return (texto ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/** true si el término está vacío o aparece en algún campo, sin distinguir mayúsculas ni tildes. */
export function contieneTexto(
  campos: ReadonlyArray<string | null | undefined>,
  termino: string,
): boolean {
  const buscado = normalizarTexto(termino.trim());
  return !buscado || campos.some((campo) => normalizarTexto(campo).includes(buscado));
}

/** Iniciales de nombre y apellido para los avatares («Juan Pérez» → «JP»; «?» si no hay). */
export function iniciales(
  persona: { nombre?: string | null; apellido?: string | null } | null | undefined,
): string {
  if (!persona) {
    return '?';
  }
  return `${persona.nombre?.[0] ?? ''}${persona.apellido?.[0] ?? ''}`.toUpperCase() || '?';
}

/** Escapa un texto (p. ej. nombres de la BD) antes de insertarlo como HTML. */
export function escaparHtml(texto: string): string {
  return texto.replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!,
  );
}
