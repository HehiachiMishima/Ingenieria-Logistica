import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from '@angular/core';
import { finalize } from 'rxjs';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { Modal } from '../../../../../../shared/ui/modal/modal';
import { DiccionarioApi } from '../../../data/diccionario.api';
import {
  LocalDiccionario,
  RutaDiccionario,
  RutaDiccionarioRequest,
  nombreRuta,
} from '../../../data/diccionario.model';
import { NumeroPipe } from '../../../numero.pipe';

/** Lo que abre el formulario: una ruta para editar (con sus locales) o ninguna para crear. */
export interface RutaFormInicial {
  ruta: RutaDiccionario | null;
  locales: LocalDiccionario[];
}

type Campo = 'cod' | 'bultos' | 'unidades' | 'lineas';

interface LineaForm {
  clave: number;
  cod: string;
  bultos: string;
  unidades: string;
  lineas: string;
}

type ErroresLinea = Record<Campo, string | null>;

/** Lo más que cabe en la base (NUMERIC(12,2)). */
const MAXIMO = 9_999_999_999.99;
const CAMPOS: readonly Campo[] = ['cod', 'bultos', 'unidades', 'lineas'];

/**
 * Crear o editar una ruta del diccionario. Al crear, primero se elige si tiene un local o varios
 * (con uno, los volúmenes escritos son los de la ruta; con varios, una línea por código y los
 * totales se suman solos). Se pueden pegar filas copiadas del Excel. Un código repetido se suma
 * al guardar, como en el Excel.
 */
@Component({
  selector: 'app-ruta-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Modal, Icon, NumeroPipe],
  templateUrl: './ruta-form.html',
  styleUrl: './ruta-form.scss',
})
export class RutaForm {
  private readonly api = inject(DiccionarioApi);

  readonly inicial = input<RutaFormInicial | null>(null);
  readonly cerrar = output<void>();
  readonly guardado = output<{ ruta: RutaDiccionario; creada: boolean }>();

  private claves = 0;

  readonly editando = computed(() => this.inicial()?.ruta ?? null);

  /** «uno» o «varios» locales; null mientras no se elige (solo al crear). */
  readonly modo = linkedSignal<RutaFormInicial | null, 'uno' | 'varios' | null>({
    source: this.inicial,
    computation: (ini) => (ini?.ruta ? (ini.locales.length > 1 ? 'varios' : 'uno') : null),
  });
  readonly codigo = linkedSignal({ source: this.inicial, computation: (ini) => ini?.ruta?.codigoRuta ?? '' });
  readonly lineas = linkedSignal<RutaFormInicial | null, LineaForm[]>({
    source: this.inicial,
    computation: (ini) =>
      ini && ini.locales.length > 0
        ? ini.locales.map((l) => this.linea(l.cod, texto(l.bultos), texto(l.unidades), texto(l.lineas)))
        : [this.linea()],
  });
  /** Se intentó guardar: desde ahí se muestran los errores. */
  readonly intento = linkedSignal({ source: this.inicial, computation: () => false });
  readonly guardando = signal(false);

  readonly errores = computed(() => {
    const codigo = this.codigo().trim();
    const lineas = this.lineas().map(
      (l): ErroresLinea => ({
        cod: l.cod.trim() === '' ? 'escribe el código' : l.cod.trim().length > 100 ? 'el código admite hasta 100 caracteres' : null,
        bultos: errorNumero(l.bultos, true),
        unidades: errorNumero(l.unidades, false),
        lineas: errorNumero(l.lineas, false),
      }),
    );
    return {
      codigo: codigo === '' ? 'Escribe el número de ruta.' : null,
      lineas,
      hay: codigo === '' || lineas.some((e) => CAMPOS.some((c) => e[c] !== null)),
    };
  });

  /** Totales de la ruta: la suma de sus líneas (lo inválido cuenta como 0). */
  readonly totales = computed(() => {
    let bultos = 0;
    let unidades = 0;
    let lineas = 0;
    for (const l of this.lineas()) {
      bultos += valido(l.bultos);
      unidades += valido(l.unidades);
      lineas += valido(l.lineas);
    }
    return { bultos, unidades, lineas };
  });

  /** Códigos que aparecen más de una vez (se sumarán al guardar). */
  readonly repetidos = computed(() => {
    const cuenta = new Map<string, number>();
    for (const l of this.lineas()) {
      const cod = normalizar(l.cod);
      if (cod !== '') {
        cuenta.set(cod, (cuenta.get(cod) ?? 0) + 1);
      }
    }
    return [...cuenta].filter(([, n]) => n > 1).map(([cod, n]) => `${cod} (×${n})`);
  });

  readonly titulo = computed(() => {
    const r = this.editando();
    return r ? `Editar ruta ${nombreRuta(r)}` : 'Nueva ruta';
  });

  readonly subtitulo = computed(() => {
    if (this.editando()) {
      return 'El tipo, el cluster, la frecuencia y el grupo de la ruta no cambian.';
    }
    return this.modo() === null
      ? 'Primero indica si la ruta tiene un local o varios.'
      : 'Se crea en Datos: sin tipo, cluster, frecuencia ni grupo (se asignan en un cluster).';
  });

  private linea(cod = '', bultos = '', unidades = '', lineas = ''): LineaForm {
    return { clave: ++this.claves, cod, bultos, unidades, lineas };
  }

  elegirModo(modo: 'uno' | 'varios'): void {
    this.modo.set(modo);
    if (modo === 'varios' && this.lineas().length === 1) {
      this.lineas.update((l) => [...l, this.linea()]);
    }
  }

  escribirCodigo(evento: Event): void {
    this.codigo.set((evento.target as HTMLInputElement).value);
  }

  cambiar(clave: number, campo: Campo, evento: Event): void {
    const valor = (evento.target as HTMLInputElement).value;
    this.lineas.update((lista) => lista.map((l) => (l.clave === clave ? { ...l, [campo]: valor } : l)));
  }

  agregarLinea(): void {
    this.modo.set('varios');
    this.lineas.update((l) => [...l, this.linea()]);
  }

  quitarLinea(clave: number): void {
    this.lineas.update((l) => (l.length > 1 ? l.filter((x) => x.clave !== clave) : l));
  }

  /**
   * Pegar varias filas copiadas del Excel (código, bultos, unidades, líneas): se escriben desde
   * la línea donde se pegó, como en una planilla. Un pegado normal (un solo valor) no se toca.
   */
  pegar(evento: ClipboardEvent, indice: number): void {
    const textoPegado = evento.clipboardData?.getData('text/plain') ?? '';
    if (!/[\t\n]/.test(textoPegado.trim())) {
      return;
    }
    evento.preventDefault();
    const filas = textoPegado
      .replace(/\r/g, '')
      .split('\n')
      .map((f) => f.split('\t').map((c) => c.trim()))
      .filter((f) => f.some((c) => c !== ''));
    // Encabezado («COD BULTOS…»): su segunda celda no es un número.
    if (filas.length > 0 && filas[0].length > 1 && Number.isNaN(parsearNumero(filas[0][1]) ?? 0)) {
      filas.shift();
    }
    if (filas.length === 0) {
      return;
    }
    const nuevas = filas.map((f) => this.linea(f[0] ?? '', f[1] ?? '', f[2] ?? '', f[3] ?? ''));
    this.lineas.update((lista) => {
      const copia = [...lista];
      copia.splice(indice, Math.min(nuevas.length, copia.length - indice), ...nuevas);
      return copia;
    });
    if (this.lineas().length > 1) {
      this.modo.set('varios');
    }
  }

  primerError(e: ErroresLinea): string | null {
    return e.cod ?? e.bultos ?? e.unidades ?? e.lineas;
  }

  cerrarFormulario(): void {
    if (!this.guardando()) {
      this.cerrar.emit();
    }
  }

  guardar(): void {
    this.intento.set(true);
    if (this.errores().hay || this.guardando()) {
      return;
    }
    const peticion: RutaDiccionarioRequest = {
      codigoRuta: this.codigo().trim(),
      locales: this.lineas().map((l) => ({
        cod: l.cod.trim(),
        bultos: parsearNumero(l.bultos) ?? 0,
        unidades: parsearNumero(l.unidades) ?? 0,
        lineas: parsearNumero(l.lineas) ?? 0,
      })),
    };
    const editando = this.editando();
    this.guardando.set(true);
    (editando ? this.api.actualizar(editando.id, peticion) : this.api.crear(peticion))
      .pipe(finalize(() => this.guardando.set(false)))
      .subscribe((ruta) => this.guardado.emit({ ruta, creada: !editando }));
  }
}

/**
 * Número escrito a mano o pegado del Excel: «5878.11», «5,878.11» o «12,5». Null si está vacío;
 * NaN si no es un número mayor o igual a 0 con hasta dos decimales.
 */
export function parsearNumero(valor: string): number | null {
  let t = valor.trim().replace(/\s/g, '');
  if (t === '') {
    return null;
  }
  if (t.includes(',') && t.includes('.')) {
    t = t.replace(/,/g, '');
  } else if (/^\d+,\d{1,2}$/.test(t)) {
    t = t.replace(',', '.');
  } else {
    t = t.replace(/,/g, '');
  }
  if (!/^\d+(\.\d{1,2})?$/.test(t)) {
    return Number.NaN;
  }
  const n = Number(t);
  return n > MAXIMO ? Number.NaN : n;
}

function errorNumero(valor: string, obligatorio: boolean): string | null {
  const n = parsearNumero(valor);
  if (n === null) {
    return obligatorio ? 'escribe los bultos' : null;
  }
  return Number.isNaN(n) ? 'usa números mayores o iguales a 0 con hasta 2 decimales' : null;
}

function valido(valor: string): number {
  const n = parsearNumero(valor);
  return n === null || Number.isNaN(n) ? 0 : n;
}

function normalizar(cod: string): string {
  return cod.trim().replace(/\s+/g, ' ').toUpperCase();
}

/** 100 → «100»; 122.5 → «122.5»; 5878.11 → «5878.11». */
function texto(n: number): string {
  return Number(n)
    .toFixed(2)
    .replace(/\.?0+$/, '');
}
