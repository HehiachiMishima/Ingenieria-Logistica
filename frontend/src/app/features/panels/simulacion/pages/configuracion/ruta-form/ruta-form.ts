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
import { SimulacionApi } from '../../../data/simulacion.api';
import { LocalRuta, RutaDataRequest } from '../../../data/simulacion.model';
import { NumeroPipe } from '../../../numero.pipe';

/** La ruta de DATA que se edita: su número y su tipo (si ya lo tiene). */
export interface RutaFormRuta {
  id: number;
  codigoRuta: string;
  tipo: string | null;
}

/** Lo que abre el formulario: una ruta de DATA para editar (con sus locales) o ninguna para crear. */
export interface RutaFormInicial {
  ruta: RutaFormRuta | null;
  locales: LocalRuta[];
}

type Campo = 'cod' | 'bultos' | 'unidades' | 'lineas';

interface LineaForm {
  clave: number;
  /** Veces que ya se había ingresado ese código (al editar se conserva). */
  filas: number;
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
 * Crear o editar una ruta de DATA. Al crear, primero se elige si tiene un local o varios
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
  private readonly api = inject(SimulacionApi);

  /** Escenario donde se crea o edita la ruta. */
  readonly registroId = input<number | null>(null);
  readonly inicial = input<RutaFormInicial | null>(null);
  readonly cerrar = output<void>();
  readonly guardado = output<{ mensaje: string; creada: boolean }>();

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
        ? ini.locales.map((l) =>
            this.linea(l.cod ?? '', texto(l.bultos), texto(l.unidades), texto(l.lineas), Math.max(1, l.filas || 1)),
          )
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
    return r ? `Editar ruta ${r.codigoRuta}${r.tipo ? ' ' + r.tipo : ''}` : 'Nueva ruta';
  });

  readonly subtitulo = computed(() => {
    if (this.editando()) {
      return 'El tipo, la frecuencia y el grupo de la ruta no cambian.';
    }
    return this.modo() === null
      ? 'Primero indica si la ruta tiene un local o varios.'
      : 'Se crea en DATA, sin tipo, frecuencia ni grupo: se los asignas ahí antes de pasarla a un cluster.';
  });

  private linea(cod = '', bultos = '', unidades = '', lineas = '', filas = 1): LineaForm {
    return { clave: ++this.claves, filas, cod, bultos, unidades, lineas };
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
   * Pegar varias celdas copiadas del Excel: se escriben desde la línea y la columna donde se pegó,
   * como en una planilla (en «Bultos», la primera celda es bultos y el código no cambia). Un pegado
   * normal (un solo valor) no se toca.
   */
  pegar(evento: ClipboardEvent, indice: number, campo: Campo = 'cod'): void {
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
    const desde = CAMPOS.indexOf(campo);
    // Encabezado («COD BULTOS…»): su primera celda numérica no es un número.
    const numerica = desde === 0 ? 1 : 0;
    if (filas.length > 0 && filas[0].length > numerica && Number.isNaN(parsearNumero(filas[0][numerica]) ?? 0)) {
      filas.shift();
    }
    if (filas.length === 0) {
      return;
    }
    this.lineas.update((lista) => {
      const copia = [...lista];
      filas.forEach((celdas, n) => {
        const actual = copia[indice + n] ?? this.linea();
        const nueva: LineaForm = { ...actual };
        celdas.forEach((valor, c) => {
          const destino = CAMPOS[desde + c];
          if (destino) {
            nueva[destino] = valor;
          }
        });
        copia[indice + n] = nueva;
      });
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
    const registro = this.registroId();
    if (registro === null) {
      return;
    }
    const peticion: RutaDataRequest = {
      codigoRuta: this.codigo().trim(),
      locales: this.lineas().map((l) => ({
        cod: l.cod.trim(),
        bultos: parsearNumero(l.bultos) ?? 0,
        unidades: parsearNumero(l.unidades) ?? 0,
        lineas: parsearNumero(l.lineas) ?? 0,
        ...(l.filas > 1 ? { filas: l.filas } : {}),
      })),
    };
    const editando = this.editando();
    this.guardando.set(true);
    (editando
      ? this.api.actualizarRutaData(registro, editando.id, peticion)
      : this.api.crearRutaData(registro, peticion)
    )
      .pipe(finalize(() => this.guardando.set(false)))
      .subscribe((cambio) => this.guardado.emit({ mensaje: cambio.mensaje, creada: !editando }));
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
