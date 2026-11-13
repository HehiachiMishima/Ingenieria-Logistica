/**
 * Reporte interactivo en un solo archivo HTML: el DOM del reporte ya dibujado (los dos días, cada
 * bloque con su data-vistas), los estilos de la página y un script mínimo en JavaScript plano para
 * el filtro y la impresión. Sin JavaScript (vista previa del correo o del Mac) se ve la vista
 * guardada en data-vista: con los dos días, uno debajo del otro.
 */

/** Filtro y botón de imprimir del archivo descargado (sin Angular). */
const SCRIPT = `(function () {
  var raiz = document.querySelector('.rep');
  if (!raiz) { return; }
  document.documentElement.classList.remove('sin-js');
  function marcar(vista) {
    raiz.setAttribute('data-vista', vista);
    document.querySelectorAll('[data-vista-boton]').forEach(function (b) {
      b.setAttribute('aria-pressed', String(b.getAttribute('data-vista-boton') === vista));
    });
  }
  document.addEventListener('click', function (e) {
    var boton = e.target.closest('[data-vista-boton]');
    if (boton) {
      if (!boton.disabled) { marcar(boton.getAttribute('data-vista-boton')); }
      return;
    }
    if (e.target.closest('[data-accion="imprimir"]')) { window.print(); }
  });
  marcar(raiz.getAttribute('data-vista') || 'AMBOS');
})();`;

/** Sin JavaScript el filtro no funciona: no se muestra. */
const ESTILO_SIN_JS = `.sin-js .rep-filtros { display: none !important; }`;

export function documentoHtml(raiz: HTMLElement, opciones: { titulo: string; vista: string }): string {
  const copia = raiz.cloneNode(true) as HTMLElement;
  // Lo que solo sirve en la app (cerrar la pestaña, volver a descargar, avisos).
  copia.querySelectorAll('[data-html-quitar]').forEach((nodo) => nodo.remove());
  copia.setAttribute('data-vista', opciones.vista);
  // Fuera de la app no hay base: las anclas del índice vuelven a ser «#id».
  copia.querySelectorAll<HTMLAnchorElement>('a[data-ancla]').forEach((a) => {
    a.setAttribute('href', `#${a.getAttribute('data-ancla')}`);
  });
  const { css, enlaces } = estilos();
  return [
    '<!doctype html>',
    '<html lang="es" data-theme="light" class="sin-js">',
    '<head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    `<title>${escapar(opciones.titulo)}</title>`,
    ...enlaces.map((href) => `<link rel="stylesheet" href="${escapar(href)}">`),
    `<style>${css}\n${ESTILO_SIN_JS}</style>`,
    '</head>',
    '<body>',
    copia.outerHTML,
    `<script>${SCRIPT}</script>`,
    '</body>',
    '</html>',
  ].join('\n');
}

/** «reporte-proyeccion-enero.html». */
export function nombreReporteHtml(nombre: string): string {
  const base = nombre
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-|-$)/g, '');
  return `reporte-${base || 'registro'}.html`;
}

/**
 * Las reglas de todas las hojas de estilo de la página. Las de otro origen (Google Fonts) no se
 * pueden leer: quedan como enlace, y sin internet se usa la fuente del sistema del font-family.
 */
function estilos(): { css: string; enlaces: string[] } {
  const partes: string[] = [];
  const enlaces: string[] = [];
  for (const hoja of Array.from(document.styleSheets)) {
    try {
      partes.push(Array.from(hoja.cssRules, (regla) => regla.cssText).join('\n'));
    } catch {
      if (hoja.href) {
        enlaces.push(hoja.href);
      }
    }
  }
  // Un «</style>» dentro de una regla cerraría la etiqueta antes de tiempo.
  return { css: partes.join('\n').replace(/<\/style/gi, '<\\/style'), enlaces };
}

function escapar(texto: string): string {
  return texto.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
}
