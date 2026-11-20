import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PERMISOS } from '../../../../../../core/config/permisos';
import { Auth } from '../../../../../../core/services/auth';
import { entero } from '../../../../../../shared/ui/charts/formato';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { AnaliticaAdmin } from '../../../data/analitica.model';
import { Nivel, abreviar } from '../../../ui/observabilidad.util';
import { Delta, ICONO_NIVEL, deltaRelativo } from '../tablero-operacion/graficos/grafico.util';

type Tono = 'bien' | 'atencion' | 'critico' | 'marca' | 'neutro';

interface Segmento {
  etiqueta: string;
  valor: number;
  texto: string;
  tono: Tono;
}

interface Tarjeta {
  clave: 'usuarios' | 'roles' | 'permisos';
  titulo: string;
  icono: string;
  ruta: string;
  /** Texto del enlace al módulo («Ver usuarios»). */
  enlace: string;
  verModulo: boolean;
  valor: string;
  deTotal: string;
  detalle: string;
  delta: Delta | null;
  segmentos: Segmento[];
  /** Todas las partes en cero: se dibuja solo la pista. */
  vacia: boolean;
  aria: string;
  aviso: { nivel: Nivel; texto: string };
}

const plural = (n: number, uno: string, varios: string) => `${entero(n)} ${n === 1 ? uno : varios}`;

/**
 * Usuarios, roles y permisos en tres tarjetas gemelas: una cifra grande (activos de su total),
 * cómo se reparten en una barra segmentada con su leyenda (cada parte con su número y su nombre:
 * el color nunca va solo) y una sola línea con lo que hay que revisar, o que todo está en orden.
 * Cada tarjeta lleva a su módulo si hay permiso para verlo.
 */
@Component({
  selector: 'app-resumen-accesos',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, RouterLink],
  templateUrl: './resumen-accesos.html',
  styleUrl: './resumen-accesos.scss',
})
export class ResumenAccesos {
  private readonly auth = inject(Auth);

  readonly datos = input<AnaliticaAdmin | null>(null);
  readonly cargando = input(false);

  readonly iconoNivel = ICONO_NIVEL;

  private readonly ver = {
    usuarios: this.auth.tienePermiso(PERMISOS.VER_USUARIOS),
    roles: this.auth.tienePermiso(PERMISOS.VER_ROLES),
    permisos: this.auth.tienePermiso(PERMISOS.VER_PERMISOS),
  };

  readonly tarjetas = computed<Tarjeta[]>(() => {
    const d = this.datos();
    if (!d) {
      return [];
    }
    const k = d.kpis;
    const conExcepcion = Math.max(0, k.usuariosActivos - k.usuariosSoloRol - k.usuariosSinRol);
    const rolesEnUso = Math.max(0, k.rolesActivos - k.rolesSinUsuarios);
    const rolesInactivos = Math.max(0, k.roles - k.rolesActivos);
    const permisosApagados = Math.max(0, k.permisos - k.permisosActivos);
    const excepciones = k.overridesGrant + k.overridesDeny;
    const masUsado = d.usuariosPorRol.find((r) => r.valor > 0);

    const usuarios: Tarjeta = {
      clave: 'usuarios',
      titulo: 'Usuarios',
      icono: 'usuarios',
      ruta: '/admin/usuarios',
      enlace: 'Ver usuarios',
      verModulo: this.ver.usuarios,
      valor: entero(k.usuariosActivos),
      deTotal: `activos de ${entero(k.usuarios)}`,
      detalle: `${plural(k.conectados, 'conectado', 'conectados')} ahora · ${plural(k.altas30, 'alta', 'altas')} en 30 días`,
      delta: deltaRelativo(k.altas30, k.altasPrevias30, false),
      segmentos: [
        { etiqueta: 'solo por su rol', valor: k.usuariosSoloRol, texto: '', tono: 'bien' },
        { etiqueta: 'con excepciones', valor: conExcepcion, texto: '', tono: 'atencion' },
        { etiqueta: 'sin rol', valor: k.usuariosSinRol, texto: '', tono: 'critico' },
      ],
      vacia: false,
      aria: `${entero(k.usuariosActivos)} cuentas activas: ${entero(k.usuariosSoloRol)} solo por su rol, ${entero(conExcepcion)} con excepciones y ${entero(k.usuariosSinRol)} sin rol`,
      aviso: !k.usuariosActivos
        ? { nivel: 'aviso', texto: 'Todavía no hay cuentas activas.' }
        : k.usuariosSinRol
        ? { nivel: 'riesgo', texto: `${plural(k.usuariosSinRol, 'cuenta', 'cuentas')} sin rol: entran solo por excepción o no pueden trabajar.` }
        : conExcepcion
          ? { nivel: 'aviso', texto: `${plural(conExcepcion, 'cuenta', 'cuentas')} con permisos fuera de su rol: revisa si siguen vigentes.` }
          : { nivel: 'ok', texto: 'Todas las cuentas activas se rigen solo por su rol.' },
    };

    const roles: Tarjeta = {
      clave: 'roles',
      titulo: 'Roles',
      icono: 'roles',
      ruta: '/admin/roles',
      enlace: 'Ver roles',
      verModulo: this.ver.roles,
      valor: entero(k.rolesActivos),
      deTotal: `activos de ${entero(k.roles)}`,
      detalle: masUsado
        ? `El más usado: ${abreviar(masUsado.etiqueta, 28)} (${plural(masUsado.valor, 'usuario', 'usuarios')})`
        : 'Ningún rol tiene usuarios todavía',
      delta: null,
      segmentos: [
        { etiqueta: 'en uso', valor: rolesEnUso, texto: '', tono: 'bien' },
        { etiqueta: 'sin usuarios', valor: k.rolesSinUsuarios, texto: '', tono: 'atencion' },
        { etiqueta: 'desactivados', valor: rolesInactivos, texto: '', tono: 'neutro' },
      ],
      vacia: false,
      aria: `${entero(k.roles)} roles: ${entero(rolesEnUso)} en uso, ${entero(k.rolesSinUsuarios)} activos sin usuarios y ${entero(rolesInactivos)} desactivados`,
      aviso: !k.rolesActivos
        ? { nivel: 'aviso', texto: 'Todavía no hay roles activos: nadie recibe acceso por rol.' }
        : k.rolesSinPermisos
        ? { nivel: 'riesgo', texto: `${plural(k.rolesSinPermisos, 'rol', 'roles')} sin permisos: quien solo tiene ese rol no puede trabajar.` }
        : k.rolesSinUsuarios
          ? { nivel: 'aviso', texto: `${plural(k.rolesSinUsuarios, 'rol activo', 'roles activos')} sin usuarios: candidatos a desactivar.` }
          : { nivel: 'ok', texto: 'Todos los roles activos están en uso y conceden permisos.' },
    };

    const permisos: Tarjeta = {
      clave: 'permisos',
      titulo: 'Permisos',
      icono: 'permisos',
      ruta: '/admin/permisos',
      enlace: 'Ver permisos',
      verModulo: this.ver.permisos,
      valor: entero(k.permisosActivos),
      deTotal: `activos de ${entero(k.permisos)}`,
      detalle: excepciones
        ? `Fuera de rol: ${plural(k.overridesGrant, 'concedido', 'concedidos')} y ${plural(k.overridesDeny, 'denegado', 'denegados')}`
        : 'Ningún permiso se concede ni se niega fuera de un rol',
      delta: null,
      segmentos: [
        { etiqueta: 'activos', valor: k.permisosActivos, texto: '', tono: 'marca' },
        { etiqueta: 'apagados', valor: permisosApagados, texto: '', tono: 'neutro' },
      ],
      vacia: false,
      aria: `${entero(k.permisos)} permisos en el catálogo: ${entero(k.permisosActivos)} activos y ${entero(permisosApagados)} apagados`,
      aviso: !k.permisosActivos
        ? { nivel: 'aviso', texto: 'El catálogo no tiene permisos activos.' }
        : excepciones
        ? { nivel: 'aviso', texto: `${plural(excepciones, 'excepción', 'excepciones')} fuera de rol: conviene llevarlas a un rol.` }
        : { nivel: 'ok', texto: 'Todo el acceso se concede por roles.' },
    };

    // Cifras de la leyenda con separador de miles y marca de barra vacía.
    for (const t of [usuarios, roles, permisos]) {
      t.segmentos = t.segmentos.map((s) => ({ ...s, texto: entero(s.valor) }));
      t.vacia = t.segmentos.every((s) => !s.valor);
    }
        return [usuarios, roles, permisos];
  });
}
