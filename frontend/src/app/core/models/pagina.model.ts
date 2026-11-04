/** Página de resultados del backend (PaginaResponse), común a todos los paneles. */
export interface Pagina<T> {
  contenido: T[];
  total: number;
  pagina: number;
  tamano: number;
  totalPaginas: number;
}
