import {
  AbstractControl,
  FormArray,
  FormBuilder,
  FormControl,
  FormGroup,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import {
  CentroMallaDatos,
  FlujoMalla,
  OlaMalla,
  Proceso,
  Responsable,
  TurnoMalla,
  ZonaMalla,
} from '../../data/malla.model';
import { PROCESOS } from '../../malla-formato';

/** Misma regla que el servidor: "HH:mm" de 24 h. */
const HORA = /^([01]\d|2[0-3]):[0-5]\d$/;

type Numero = FormControl<number | null>;
type Texto = FormControl<string>;

/** La dotación planificada va dentro de cada turno: renombrarlo o quitarlo la arrastra. */
export type TurnoForm = FormGroup<{
  codigo: Texto;
  nombre: Texto;
  inicio: Texto;
  fin: Texto;
  refrigerio: Texto;
  tipoRefrigerio: Texto;
  nota: Texto;
  confirmado: FormControl<boolean>;
  personas: FormGroup<Record<Proceso, Numero>>;
}>;

export type FlujoForm = FormGroup<{
  proceso: FormControl<Proceso>;
  responsable: FormControl<Responsable>;
  inicio: Texto;
  fin: Texto;
  actividad: Texto;
  confirmado: FormControl<boolean>;
}>;

export type OlaForm = FormGroup<{
  cluster: Texto;
  nombre: Texto;
  caidaPedidos: Texto;
  lanzamiento: Texto;
  cierrePicking: Texto;
  despacho: Texto;
  confirmado: FormControl<boolean>;
  nota: Texto;
}>;

export type ZonaForm = FormGroup<{ nombre: Texto; lphReal: Numero; lphDiseno: Numero }>;

/**
 * Formulario de la malla de un centro: crea los grupos, agrega, quita y
 * reordena filas, y convierte entre los datos del servidor y el formulario.
 * Las horas se editan como texto "HH:mm" de 24 h (la directiva Hora24 lo
 * normaliza) y vacío equivale a null.
 */
export class FormularioMalla {
  private readonly fb = new FormBuilder().nonNullable;

  readonly form = this.fb.group({
    codigo: ['', [Validators.required, Validators.maxLength(20), Validators.pattern(/\S/)]],
    nombre: ['', [Validators.required, Validators.maxLength(80), Validators.pattern(/\S/)]],
    turnos: this.fb.array<TurnoForm>([]),
    flujos: this.fb.array<FlujoForm>([]),
    olas: this.fb.array<OlaForm>([]),
    zonas: this.fb.array<ZonaForm>([]),
    capacidad: this.fb.group({
      unidadesDia: this.numero(),
      lineasDia: this.numero(),
      bultosRecepcionDia: this.numero(),
      proveedoresDia: this.numero(),
    }),
    dotacionReal: this.fb.group({
      inbound: this.numero(),
      outbound: this.numero(),
      despacho: this.numero(),
      total: this.numero(),
      semana: ['', Validators.maxLength(10)],
    }),
  });

  get turnos(): FormArray<TurnoForm> {
    return this.form.controls.turnos;
  }

  get flujos(): FormArray<FlujoForm> {
    return this.form.controls.flujos;
  }

  get olas(): FormArray<OlaForm> {
    return this.form.controls.olas;
  }

  get zonas(): FormArray<ZonaForm> {
    return this.form.controls.zonas;
  }

  /** Carga los datos (del servidor o de un Excel) y deja el formulario sin cambios pendientes. */
  cargar(datos: CentroMallaDatos): void {
    this.turnos.clear({ emitEvent: false });
    this.flujos.clear({ emitEvent: false });
    this.olas.clear({ emitEvent: false });
    this.zonas.clear({ emitEvent: false });
    datos.turnos.forEach((turno) => {
      const personas = Object.fromEntries(
        PROCESOS.map((p) => [
          p.id,
          datos.dotacion.find(
            (d) => d.proceso === p.id && d.turno.toUpperCase() === turno.codigo.toUpperCase(),
          )?.personas ?? null,
        ]),
      ) as Record<Proceso, number | null>;
      this.turnos.push(this.turno(turno, personas), { emitEvent: false });
    });
    datos.flujos.forEach((paso) => this.flujos.push(this.paso(paso), { emitEvent: false }));
    datos.olas.forEach((ola) => this.olas.push(this.ola(ola), { emitEvent: false }));
    datos.zonas.forEach((zona) => this.zonas.push(this.zona(zona), { emitEvent: false }));
    this.form.patchValue({
      codigo: datos.codigo ?? '',
      nombre: datos.nombre ?? '',
      capacidad: datos.capacidad,
      dotacionReal: { ...datos.dotacionReal, semana: datos.dotacionReal.semana ?? '' },
    });
    this.form.markAsPristine();
    this.form.markAsUntouched();
  }

  /** Datos listos para el servidor: textos recortados, vacíos como null y dotación por turno. */
  datos(): CentroMallaDatos {
    const v = this.form.getRawValue();
    return {
      codigo: v.codigo.trim(),
      nombre: v.nombre.trim(),
      turnos: v.turnos.map((t) => ({
        codigo: t.codigo.trim(),
        nombre: t.nombre.trim(),
        inicio: t.inicio,
        fin: t.fin,
        refrigerio: nulo(t.refrigerio),
        tipoRefrigerio: nulo(t.tipoRefrigerio),
        nota: nulo(t.nota),
        confirmado: t.confirmado,
      })),
      flujos: v.flujos.map((f) => ({
        proceso: f.proceso,
        responsable: f.responsable,
        inicio: f.inicio,
        fin: nulo(f.fin),
        actividad: f.actividad.trim(),
        confirmado: f.confirmado,
      })),
      olas: v.olas.map((o) => ({
        cluster: o.cluster.trim(),
        nombre: o.nombre.trim(),
        caidaPedidos: nulo(o.caidaPedidos),
        lanzamiento: nulo(o.lanzamiento),
        cierrePicking: nulo(o.cierrePicking),
        despacho: nulo(o.despacho),
        confirmado: o.confirmado,
        nota: nulo(o.nota),
      })),
      zonas: v.zonas.map((z) => ({
        nombre: z.nombre.trim(),
        lphReal: z.lphReal,
        lphDiseno: z.lphDiseno,
      })),
      capacidad: v.capacidad,
      dotacion: v.turnos.flatMap((t) =>
        PROCESOS.map((p) => ({
          turno: t.codigo.trim(),
          proceso: p.id,
          personas: t.personas[p.id],
        })),
      ),
      dotacionReal: { ...v.dotacionReal, semana: nulo(v.dotacionReal.semana) },
    };
  }

  agregarTurno(): void {
    const n = this.turnos.length + 1;
    this.turnos.push(
      this.turno(
        {
          codigo: `T${n}`,
          nombre: `Turno ${n}`,
          inicio: '',
          fin: '',
          refrigerio: null,
          tipoRefrigerio: null,
          nota: null,
          confirmado: false,
        },
        { REC: null, ALM: null, REA: null, OLA: null, DES: null },
      ),
    );
    this.form.markAsDirty();
  }

  agregarPaso(proceso: Proceso): void {
    // Va después del último paso de su proceso para que el orden se mantenga.
    const ultimo = this.indicesDe(proceso).at(-1);
    const paso = this.paso({
      proceso,
      responsable: 'OPS',
      inicio: '',
      fin: null,
      actividad: '',
      confirmado: false,
    });
    if (ultimo === undefined) {
      this.flujos.push(paso);
    } else {
      this.flujos.insert(ultimo + 1, paso);
    }
    this.form.markAsDirty();
  }

  /** Sube o baja un paso dentro de su proceso. */
  moverPaso(indice: number, direccion: -1 | 1): void {
    const indices = this.indicesDe(this.flujos.at(indice).controls.proceso.value);
    const destino = indices[indices.indexOf(indice) + direccion];
    if (destino === undefined) {
      return;
    }
    const paso = this.flujos.at(indice);
    this.flujos.removeAt(indice);
    this.flujos.insert(destino, paso);
    this.form.markAsDirty();
  }

  agregarOla(): void {
    this.olas.push(
      this.ola({
        cluster: '',
        nombre: `Ola ${this.olas.length + 1}`,
        caidaPedidos: null,
        lanzamiento: null,
        cierrePicking: null,
        despacho: null,
        confirmado: false,
        nota: null,
      }),
    );
    this.form.markAsDirty();
  }

  agregarZona(): void {
    this.zonas.push(this.zona({ nombre: '', lphReal: null, lphDiseno: null }));
    this.form.markAsDirty();
  }

  quitar(lista: FormArray, indice: number): void {
    lista.removeAt(indice);
    this.form.markAsDirty();
  }

  /**
   * Marca en cada control el error de campo que devolvió el servidor
   * ("turnos[0].inicio" → turnos.0.inicio). Devuelve cuántos encontró.
   */
  marcarErroresServidor(campos: Record<string, string>): number {
    let marcados = 0;
    for (const [ruta, mensaje] of Object.entries(campos)) {
      const control = this.form.get(ruta.replace(/\[(\d+)\]/g, '.$1'));
      if (control) {
        control.setErrors({ ...control.errors, servidor: mensaje });
        control.markAsTouched();
        marcados++;
      }
    }
    return marcados;
  }

  /** Posiciones de los pasos de un proceso dentro de la lista de flujos. */
  indicesDe(proceso: Proceso): number[] {
    return this.flujos.controls.flatMap((paso, i) =>
      paso.controls.proceso.value === proceso ? [i] : [],
    );
  }

  private turno(t: TurnoMalla, personas: Record<Proceso, number | null>): TurnoForm {
    return this.fb.group(
      {
        codigo: [
          t.codigo,
          [Validators.required, Validators.maxLength(8), Validators.pattern(/\S/)],
        ],
        nombre: [
          t.nombre,
          [Validators.required, Validators.maxLength(40), Validators.pattern(/\S/)],
        ],
        inicio: [t.inicio, [Validators.required, Validators.pattern(HORA)]],
        fin: [t.fin, [Validators.required, Validators.pattern(HORA)]],
        refrigerio: [t.refrigerio ?? '', Validators.pattern(HORA)],
        tipoRefrigerio: [t.tipoRefrigerio ?? '', Validators.maxLength(24)],
        nota: [t.nota ?? '', Validators.maxLength(200)],
        confirmado: [t.confirmado],
        personas: this.fb.group(
          Object.fromEntries(PROCESOS.map((p) => [p.id, this.numero(personas[p.id])])) as Record<
            Proceso,
            Numero
          >,
        ),
      },
      { validators: inicioDistintoDeFin },
    );
  }

  private paso(f: FlujoMalla): FlujoForm {
    return this.fb.group(
      {
        proceso: [f.proceso],
        responsable: [f.responsable],
        inicio: [f.inicio, [Validators.required, Validators.pattern(HORA)]],
        fin: [f.fin ?? '', Validators.pattern(HORA)],
        actividad: [
          f.actividad,
          [Validators.required, Validators.maxLength(200), Validators.pattern(/\S/)],
        ],
        confirmado: [f.confirmado],
      },
      { validators: inicioDistintoDeFin },
    );
  }

  private ola(o: OlaMalla): OlaForm {
    return this.fb.group({
      cluster: [
        o.cluster,
        [Validators.required, Validators.maxLength(10), Validators.pattern(/\S/)],
      ],
      nombre: [o.nombre, [Validators.required, Validators.maxLength(20), Validators.pattern(/\S/)]],
      caidaPedidos: [o.caidaPedidos ?? '', Validators.pattern(HORA)],
      lanzamiento: [o.lanzamiento ?? '', Validators.pattern(HORA)],
      cierrePicking: [o.cierrePicking ?? '', Validators.pattern(HORA)],
      despacho: [o.despacho ?? '', Validators.pattern(HORA)],
      confirmado: [o.confirmado],
      nota: [o.nota ?? '', Validators.maxLength(320)],
    });
  }

  private zona(z: ZonaMalla): ZonaForm {
    return this.fb.group({
      nombre: [z.nombre, [Validators.required, Validators.maxLength(80), Validators.pattern(/\S/)]],
      lphReal: this.numero(z.lphReal),
      lphDiseno: this.numero(z.lphDiseno),
    });
  }

  private numero(valor: number | null = null): Numero {
    return new FormControl<number | null>(valor, Validators.min(0));
  }
}

/** Un turno o un paso que empieza y termina a la misma hora no dura nada (ni 24 h). */
function inicioDistintoDeFin(grupo: AbstractControl): ValidationErrors | null {
  const { inicio, fin } = grupo.value as { inicio?: string; fin?: string };
  return inicio && fin && inicio === fin ? { mismaHora: true } : null;
}

function nulo(valor: string | null | undefined): string | null {
  const texto = valor?.trim() ?? '';
  return texto === '' ? null : texto;
}
