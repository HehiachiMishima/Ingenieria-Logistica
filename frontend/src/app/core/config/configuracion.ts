/**
 * Configuración transversal de la aplicación en un único punto:
 * claves de almacenamiento, rutas de navegación, duraciones de UI y
 * márgenes de sesión. Evita "números y cadenas mágicas" repartidos por el
 * código y concentra aquí lo que cambia entre entornos o por diseño.
 */
export const CONFIG = {
  almacenamiento: {
    sesion: 'oracle.sesion',
    tema: 'oracle.tema',
    intro: 'oracle.intro',
    sidebar: 'oracle.sidebar',
  },
  rutas: {
    intro: '/intro',
    login: '/login',
    sinAcceso: '/sin-acceso',
  },
  notificaciones: {
    duracion: 4200,
    duracionError: 6000,
  },
  sesion: {
    /** El access token se renueva este tiempo antes de vencer (si hubo actividad). */
    anticipoRenovacionMs: 60_000,
    /** Sin conexión al renovar: se reintenta pasado este tiempo. */
    reintentoMs: 30_000,
    /** Al abrir la app, cuánto se espera la renovación antes de seguir sin sesión. */
    esperaInicioMs: 8_000,
  },
  /**
   * Zona horaria del negocio (Lima, sin horario de verano). La API envía la
   * auditoría en UTC y aquí se convierte para mostrarla; las fechas y horas de
   * negocio llegan ya en esta zona y se muestran tal cual.
   */
  zonaHoraria: '-0500',
} as const;
