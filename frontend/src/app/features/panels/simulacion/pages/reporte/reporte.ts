import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Injector,
  ViewEncapsulation,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgTemplateOutlet } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import { mensajeDeError } from '../../../../../core/errors/error.util';
import { ComparativoChart } from '../../../../../shared/ui/charts/comparativo-chart';
import { Kpi } from '../../../../../shared/ui/charts/kpi';
import { Icon } from '../../../../../shared/ui/icon/icon';
import { IconoSim } from '../../icono-sim/icono-sim';
import { SimulacionApi } from '../../data/simulacion.api';
import {
  DIAS_BALANCEO,
  ETIQUETA_BALANCEO,
  RutaSimulacion,
  rutaEnBalanceo,
} from '../../data/simulacion.model';
import { etiquetaEstado, formatearEntero } from '../../simulacion-formato';
import { documentoHtml, nombreReporteHtml } from './reporte-html';
import {
  VistaReporte,
  Datos,
  METRICAS,
  ClusterConfiguracion,
  ResumenTotal,
  VistaDia,
  VistaAmbos,
  SECCIONES,
  armarDia,
  armarResumenTotal,
  normalizarDia,
  normalizarVista,
  vistaValida,
} from './reporte.logica';

/**
 * Reporte ejecutivo de la simulación (A4 apaisado): indicadores, resultado por ola, los gráficos
 * del dashboard y las cuadrículas de Configuración con sus totales en semáforo. Trae los dos días
 * y un filtro (L-X-V, M-J-S o Ambos) que solo cambia lo que se ve: todo está dibujado y cada
 * bloque dice en qué vistas aparece (data-vistas). Así el mismo DOM sirve para imprimir lo que
 * se filtra y para descargarlo como HTML interactivo (con ?formato=html).
 */
@Component({
  selector: 'app-simulacion-reporte',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [NgTemplateOutlet, Icon, IconoSim, Kpi, ComparativoChart],
  templateUrl: './reporte.html',
  styleUrls: ['./reporte.scss', './reporte-configuracion.scss'],
})
export class SimulacionReporteEjecutivo {
  private readonly api = inject(SimulacionApi);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  /** Para programar afterNextRender cuando llegan los datos (fuera del constructor). */
  private readonly injector = inject(Injector);

  readonly datos = signal<Datos | null>(null);
  /** Vista elegida en el filtro. */
  readonly vista = signal<VistaReporte>('AMBOS');
  readonly etiquetaBalanceo = ETIQUETA_BALANCEO;
  readonly secciones = SECCIONES;
  readonly error = signal<string | null>(null);
  /** Aviso tras descargar el HTML (en esta pestaña ya no queda nada por hacer). */
  readonly descargado = signal(false);
  readonly generado = new Date();
  readonly formatearEntero = formatearEntero;
  readonly metricas = METRICAS;
  /**
   * Horas cortas para las cifras de los gráficos («2h31», «8h», «45m»): caben
   * sobre la barra (34 px). En tablas y textos se usa el formato completo.
   */
  readonly formatoHoras = (valor: number): string => {
    const minutos = Math.round(valor * 60);
    const h = Math.floor(minutos / 60);
    const m = minutos % 60;
    if (h === 0) {
      return `${m}m`;
    }
    return m === 0 ? `${h}h` : `${h}h${String(m).padStart(2, '0')}`;
  };
  readonly formatoEntero = (valor: number): string => formatearEntero(valor);

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    const id = Number(params.get('registro'));
    const formato = params.get('formato') === 'html' ? 'html' : 'pdf';
    const imprimir = formato === 'pdf' && params.get('imprimir') !== 'no';
    // Días con rutas («LXV,MJS»); sin dato, los dos. ?balanceo=LXV (enlaces anteriores) sigue valiendo.
    const dias = DIAS_BALANCEO.filter((dia) =>
      (params.get('dias') ?? DIAS_BALANCEO.join(','))
        .split(',')
        .map(normalizarDia)
        .includes(dia),
    );
    const pedida = normalizarVista(params.get('vista') ?? params.get('balanceo'));
    if (!Number.isInteger(id) || id <= 0) {
      this.error.set('Falta el registro a reportar.');
      return;
    }
    const cargar = dias.length > 0 ? dias : [...DIAS_BALANCEO];
    forkJoin({
      todas: this.todasLasRutas(id),
      porDia: forkJoin(
        cargar.map((dia) =>
          forkJoin({ cfg: this.api.configuracion(id, dia), dash: this.api.dashboard(id, null, dia) }),
        ),
      ),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ todas, porDia }) => {
          const conRutas = porDia
            .map((d, i) => ({ dia: cargar[i], ...d, rutas: todas.filter((r) => rutaEnBalanceo(r.frecuencia, cargar[i])) }))
            .filter((d) => d.rutas.length > 0);
          // Un registro sin rutas en ningún día igual muestra el primero (vacío).
          const diasDatos = conRutas.length > 0 ? conRutas : [{ dia: cargar[0], ...porDia[0], rutas: [] }];
          this.datos.set({ dias: diasDatos, todas });
          this.vista.set(vistaValida(pedida, diasDatos.map((d) => d.dia)));
          document.title = `Reporte · ${diasDatos[0].cfg.archivo.nombreRegistro}`;
          if (formato === 'html' || imprimir) {
            // Tras dibujarse (y terminar la animación de los gráficos), imprime o descarga.
            afterNextRender(
              () => setTimeout(() => (formato === 'html' ? this.descargarHtml() : this.imprimir()), 1200),
              { injector: this.injector },
            );
          }
        },
        error: (err) => this.error.set(mensajeDeError(err)),
      });
  }

  imprimir(): void {
    window.print();
  }

  cerrar(): void {
    window.close();
  }

  cambiarVista(vista: VistaReporte): void {
    if (this.vistasDisponibles().includes(vista)) {
      this.vista.set(vista);
    }
  }

  /**
   * Enlace del índice: la URL de esta misma página con el ancla. Así Chrome lo guarda como enlace
   * interno del PDF (un «#id» suelto se resolvería contra la base de la app). En pantalla el clic
   * solo desplaza, sin pasar por el router.
   */
  enlace(id: string): string {
    return `${location.pathname}${location.search}#${id}`;
  }

  irA(evento: Event, id: string): void {
    evento.preventDefault();
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  /**
   * Descarga el reporte como un HTML autocontenido: el DOM ya dibujado (los dos días), los
   * estilos de la página y un script mínimo para el filtro y la impresión.
   */
  descargarHtml(): void {
    const raiz = document.querySelector<HTMLElement>('.rep');
    const d = this.datos();
    if (!raiz || !d) {
      return;
    }
    const nombre = d.dias[0].cfg.archivo.nombreRegistro;
    const html = documentoHtml(raiz, {
      titulo: `Reporte · ${nombre}`,
      vista: this.vistasDisponibles().includes('AMBOS') ? 'AMBOS' : this.vista(),
    });
    const url = URL.createObjectURL(new Blob([html], { type: 'text/html;charset=utf-8' }));
    const enlace = document.createElement('a');
    enlace.href = url;
    enlace.download = nombreReporteHtml(nombre);
    document.body.appendChild(enlace);
    enlace.click();
    enlace.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    this.descargado.set(true);
  }

  /** Todas las rutas del registro en un solo pedido (antes: páginas de 1 000, una tras otra). */
  private todasLasRutas(id: number): Observable<RutaSimulacion[]> {
    return this.api.todasLasRutas(id);
  }

  // ---------------- Filtro ----------------

  /** Días con rutas y, si son dos, «Ambos». */
  readonly vistasDisponibles = computed<VistaReporte[]>(() => {
    const dias = (this.datos()?.dias ?? []).map((d) => d.dia);
    return dias.length > 1 ? [...dias, 'AMBOS'] : dias;
  });

  readonly opciones = computed(() => {
    const disponibles = this.vistasDisponibles();
    return (['LXV', 'MJS', 'AMBOS'] as const).map((v) => ({
      vista: v,
      etiqueta: v === 'AMBOS' ? 'Ambos' : ETIQUETA_BALANCEO[v],
      habilitada: disponibles.includes(v),
      titulo:
        v === 'AMBOS'
          ? disponibles.includes('AMBOS')
            ? 'L-X-V y M-J-S juntos'
            : 'Hace falta que los dos días tengan rutas'
          : disponibles.includes(v)
            ? `Solo ${ETIQUETA_BALANCEO[v]} (sus rutas y las diarias)`
            : `Sin rutas ${ETIQUETA_BALANCEO[v]}`,
    }));
  });

  // ---------------- Encabezado ----------------

  readonly archivo = computed(() => this.datos()?.dias[0].cfg.archivo ?? null);
  readonly base = computed(() => this.datos()?.dias[0].cfg ?? null);

  readonly estadoRegistro = computed(() => {
    const archivo = this.archivo();
    return archivo ? etiquetaEstado(archivo.estado) : '';
  });

  readonly finalizado = computed(() => this.archivo()?.estado === 'FINALIZADO');

  // ---------------- Cada día ----------------

  readonly vistasDia = computed<VistaDia[]>(() =>
    (this.datos()?.dias ?? []).map((d) => armarDia(d, this.finalizado())),
  );

  // ---------------- Ambos ----------------

  readonly ambos = computed<VistaAmbos | null>(() => {
    const d = this.datos();
    const dias = this.vistasDia();
    if (!d || dias.length < 2) {
      return null;
    }
    const indicadores = dias[0].indicadores.map((k, i) => ({
      etiqueta: k.etiqueta,
      icono: k.icono,
      color: k.color,
      valores: dias.map((v) => ({ valor: v.indicadores[i].valor, detalle: v.indicadores[i].detalle })),
    }));
    const olas = dias[0].olas.map((o) => {
      const porDia = dias.map((v) => v.olas.find((x) => x.id === o.id) ?? null);
      const operarios = porDia.map((x) => x?.operarios ?? null).filter((n): n is number => n !== null);
      return {
        id: o.id,
        ola: o.ola,
        nombre: o.nombre,
        color: o.color,
        // La misma cantidad trabaja los dos días: la mayor que se repartió.
        operarios: operarios.length > 0 ? Math.max(...operarios) : null,
        porDia,
      };
    });
    return {
      etiquetas: dias.map((v) => v.etiqueta),
      rutas: d.todas.length,
      indicadores,
      olas,
      conclusiones: dias.map((v) => ({ etiqueta: v.etiqueta, frases: v.conclusiones })),
    };
  });

  // ---------------- Configuración: cada cluster con sus días, uno debajo del otro ----------------

  readonly configuracion = computed<ClusterConfiguracion[]>(() => {
    const d = this.datos();
    const dias = this.vistasDia();
    if (!d || dias.length === 0) {
      return [];
    }
    // Un día sin rutas del cluster no se muestra; un cluster sin rutas en ningún día, tampoco.
    return d.dias[0].cfg.clusters
      .map((cluster) => {
        const secciones = dias
          .map((v) => ({
            dia: v.dia,
            etiqueta: v.etiqueta,
            ola: v.olas.find((o) => o.id === cluster.id && o.rutas > 0) ?? null,
            tramos: v.cuadriculas.find((c) => c.id === cluster.id)?.tramos ?? [],
          }))
          .filter((s) => s.tramos.length > 0);
        return {
          id: cluster.id,
          nombre: cluster.nombre,
          color: dias[0].olas.find((o) => o.id === cluster.id)?.color ?? 'var(--accent)',
          secciones,
          vistas: [...secciones.map((s) => s.dia), 'AMBOS'].join(' '),
        };
      })
      .filter((c) => c.secciones.length > 0);
  });

  // ---------------- Resumen total ----------------

  /** «L-X-V + M-J-S» (o el único día con rutas). */
  readonly etiquetaDias = computed(() => this.vistasDia().map((v) => v.etiqueta).join(' + '));

  readonly resumenTotal = computed<ResumenTotal | null>(() => {
    const d = this.datos();
    return d ? armarResumenTotal(d) : null;
  });
}

