/**
 * Columnas de un catálogo con scroll horizontal (tableros de permisos): cuáles se ven y cómo
 * llevar una a la vista desde su atajo. Las columnas llevan `data-accion` con su clave.
 */

/**
 * Avisa qué columnas se ven al menos en un 60 % de su ancho. Usa IntersectionObserver en vez de
 * escuchar el scroll: el componente solo se entera cuando cambia el conjunto, no en cada píxel.
 * Devuelve la función que deja de observar.
 */
export function seguirColumnasVisibles(
  raiz: HTMLElement,
  columnas: readonly HTMLElement[],
  alCambiar: (visibles: ReadonlySet<string>) => void,
): () => void {
  if (typeof IntersectionObserver === 'undefined' || columnas.length === 0) {
    return () => undefined;
  }
  const vistas = new Set<string>();
  const observador = new IntersectionObserver(
    (entradas) => {
      for (const entrada of entradas) {
        const accion = (entrada.target as HTMLElement).dataset['accion'] ?? '';
        const ancho = entrada.boundingClientRect.width;
        // Solo cuenta el ancho: una columna larga nunca entra entera a lo alto.
        if (ancho > 0 && entrada.intersectionRect.width / ancho >= 0.6) {
          vistas.add(accion);
        } else {
          vistas.delete(accion);
        }
      }
      alCambiar(new Set(vistas));
    },
    { root: raiz, threshold: [0, 0.25, 0.5, 0.6, 0.75, 1] },
  );
  for (const columna of columnas) {
    observador.observe(columna);
  }
  return () => observador.disconnect();
}

/** Atajo: lleva la columna a la vista (a lo ancho en escritorio; bajo la barra al apilar). */
export function llevarAColumna(columnas: readonly HTMLElement[], accion: string): void {
  const columna = columnas.find((el) => el.dataset['accion'] === accion);
  if (!columna) {
    return;
  }
  const apilado = !matchMedia('(min-width: 1024px)').matches; // responsive:ok mismo corte lg del SCSS; solo elige cómo desplazar
  const suave = !matchMedia('(prefers-reduced-motion: reduce)').matches;
  columna.scrollIntoView({
    behavior: suave ? 'smooth' : 'auto',
    block: apilado ? 'start' : 'nearest',
    inline: 'start',
  });
}
