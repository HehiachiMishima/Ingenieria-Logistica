import { descargarArchivo } from './archivo.util';

/**
 * Exporta datos tabulares a un archivo CSV compatible con Excel.
 * Usa BOM UTF-8 (para que los acentos se vean correctos) y `;` como
 * separador (Excel en configuración regional en español), con escapes
 * RFC 4180 para comillas, separador y saltos de línea.
 */
export function exportarCsv(
  nombreArchivo: string,
  cabeceras: string[],
  filas: (string | number | null | undefined)[][],
): void {
  const lineas = [cabeceras, ...filas].map((fila) => fila.map(celdaCsv).join(';'));
  const contenido = `\uFEFF${lineas.join('\r\n')}`;
  const blob = new Blob([contenido], { type: 'text/csv;charset=utf-8;' });
  descargarArchivo(
    blob,
    nombreArchivo.toLowerCase().endsWith('.csv') ? nombreArchivo : `${nombreArchivo}.csv`,
  );
}

/**
 * Un texto que empieza con = + - @ (o tabulador / retorno) Excel lo ejecuta como
 * fórmula: `=HYPERLINK(...)` escrito en una incidencia correría en el equipo de
 * quien exporta. Se antepone un apóstrofo para que quede como texto. Los números
 * reales (negativos incluidos) se exportan tal cual.
 */
const INICIO_FORMULA = /^[=+\-@\t\r]/;

function celdaCsv(valor: string | number | null | undefined): string {
  let texto = valor === null || valor === undefined ? '' : String(valor);
  if (typeof valor === 'string' && INICIO_FORMULA.test(texto)) {
    texto = `'${texto}`;
  }
  return `"${texto.replace(/"/g, '""')}"`;
}
