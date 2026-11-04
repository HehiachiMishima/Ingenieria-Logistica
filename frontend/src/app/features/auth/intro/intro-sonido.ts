import { INTRO, SONIDO } from './intro-diseno';

/**
 * Banda sonora de la intro sintetizada con Web Audio (sin archivos): rumor
 * grave y barrido de ruido que crecen con los haces, un impacto al aparecer
 * ATLAS y un acorde de marca que se apaga con el fundido. Se programa sobre la
 * línea de tiempo, así que puede arrancar a mitad de ella (movimiento reducido).
 */
export class IntroSonido {
  private readonly ctx: AudioContext | null = IntroSonido.crearContexto();
  private salida: GainNode | null = null;
  private eco: ConvolverNode | null = null;
  private programado = false;
  private detenido = false;

  private get activo(): boolean {
    return this.ctx?.state === 'running';
  }

  /** Intenta sonar sin interacción; el navegador puede exigir un gesto (autoplay). */
  async intentar(): Promise<boolean> {
    if (!this.ctx) {
      return false;
    }
    await Promise.race([this.ctx.resume(), new Promise((listo) => setTimeout(listo, 250))]);
    return this.activo;
  }

  /** Programa la banda alineada con el segundo actual de la intro. */
  reproducir(segundo: number): void {
    const ctx = this.ctx;
    if (!ctx || this.programado || this.detenido || !this.activo) {
      return;
    }
    this.programado = true;
    this.montarCadena(ctx);
    // Instante del contexto que corresponde al segundo 0 de la intro.
    const cero = ctx.currentTime + 0.05 - segundo;
    const ya = (inicio: number): boolean => cero + inicio >= ctx.currentTime - 0.08;

    if (segundo < INTRO.marca - 0.3) {
      this.rumor(ctx, cero);
      this.subida(ctx, cero);
    }
    for (const [momento, paneo] of [
      [INTRO.abanico, -0.6],
      [INTRO.tunel, 0.6],
      [INTRO.cortina, 0],
    ] as const) {
      if (ya(momento)) {
        this.barrido(ctx, cero + momento, paneo);
      }
    }
    if (ya(INTRO.marca)) {
      this.impacto(ctx, cero + INTRO.marca);
    }
    if (segundo < INTRO.salida) {
      this.acorde(ctx, cero);
    }
  }

  /** Funde a silencio y libera el contexto de audio. */
  detener(fundido = 0.25): void {
    const ctx = this.ctx;
    if (!ctx || this.detenido) {
      return;
    }
    this.detenido = true;
    this.salida?.gain.setTargetAtTime(0, ctx.currentTime, fundido / 4);
    setTimeout(() => void ctx.close().catch(() => undefined), fundido * 1000 + 60);
  }

  private static crearContexto(): AudioContext | null {
    try {
      return typeof AudioContext === 'function' ? new AudioContext() : null;
    } catch {
      return null;
    }
  }

  /** Salida general con compresor y una reverberación generada (sala grande). */
  private montarCadena(ctx: AudioContext): void {
    const compresor = ctx.createDynamicsCompressor();
    compresor.threshold.value = -14;
    compresor.ratio.value = 4;
    compresor.connect(ctx.destination);

    this.salida = ctx.createGain();
    this.salida.gain.value = SONIDO.volumen;
    this.salida.connect(compresor);

    const impulso = ctx.createBuffer(
      2,
      Math.round(ctx.sampleRate * SONIDO.colaReverberacion),
      ctx.sampleRate,
    );
    for (let canal = 0; canal < 2; canal++) {
      const datos = impulso.getChannelData(canal);
      for (let i = 0; i < datos.length; i++) {
        datos[i] = (Math.random() * 2 - 1) * (1 - i / datos.length) ** 3;
      }
    }
    this.eco = ctx.createConvolver();
    this.eco.buffer = impulso;
    const retorno = ctx.createGain();
    retorno.gain.value = SONIDO.reverberacion;
    this.eco.connect(retorno).connect(this.salida);
  }

  /** Conecta una voz a la salida y, en parte, a la reverberación. */
  private enviar(nodo: AudioNode, eco: number): void {
    if (!this.salida || !this.eco) {
      return;
    }
    nodo.connect(this.salida);
    if (eco > 0) {
      const envio = nodo.context.createGain();
      envio.gain.value = eco;
      nodo.connect(envio).connect(this.eco);
    }
  }

  private ruido(ctx: AudioContext, segundos: number): AudioBufferSourceNode {
    const buffer = ctx.createBuffer(1, Math.round(ctx.sampleRate * segundos), ctx.sampleRate);
    const datos = buffer.getChannelData(0);
    for (let i = 0; i < datos.length; i++) {
      datos[i] = Math.random() * 2 - 1;
    }
    const fuente = ctx.createBufferSource();
    fuente.buffer = buffer;
    return fuente;
  }

  /** Rumor grave que crece con los haces y se corta justo antes del impacto. */
  private rumor(ctx: AudioContext, cero: number): void {
    const filtro = ctx.createBiquadFilter();
    filtro.type = 'lowpass';
    filtro.frequency.setValueAtTime(120, cero + INTRO.haz);
    filtro.frequency.exponentialRampToValueAtTime(760, cero + INTRO.marca - 0.1);
    const volumen = ctx.createGain();
    volumen.gain.setValueAtTime(0, cero + INTRO.haz);
    volumen.gain.linearRampToValueAtTime(0.16, cero + INTRO.marca - 0.12);
    volumen.gain.linearRampToValueAtTime(0, cero + INTRO.marca - 0.04);
    filtro.connect(volumen);
    this.enviar(volumen, 0.2);

    for (const frecuencia of SONIDO.rumor) {
      const osc = ctx.createOscillator();
      osc.type = 'sawtooth';
      osc.frequency.value = frecuencia;
      osc.connect(filtro);
      osc.start(cero + INTRO.haz);
      osc.stop(cero + INTRO.marca);
    }
  }

  /** Barrido de ruido ascendente (riser) hasta el impacto. */
  private subida(ctx: AudioContext, cero: number): void {
    const fuente = this.ruido(ctx, INTRO.marca);
    const filtro = ctx.createBiquadFilter();
    filtro.type = 'bandpass';
    filtro.Q.value = 1.2;
    filtro.frequency.setValueAtTime(250, cero + INTRO.abanico);
    filtro.frequency.exponentialRampToValueAtTime(6000, cero + INTRO.marca - 0.08);
    const volumen = ctx.createGain();
    volumen.gain.setValueAtTime(0.0001, cero + INTRO.abanico);
    volumen.gain.exponentialRampToValueAtTime(0.22, cero + INTRO.marca - 0.1);
    volumen.gain.linearRampToValueAtTime(0, cero + INTRO.marca - 0.04);
    fuente.connect(filtro).connect(volumen);
    this.enviar(volumen, 0.3);
    fuente.start(cero + INTRO.abanico);
    fuente.stop(cero + INTRO.marca);
  }

  /** Soplo de aire que acompaña cada cambio de los haces. */
  private barrido(ctx: AudioContext, inicio: number, paneo: number): void {
    const fuente = this.ruido(ctx, 0.9);
    const filtro = ctx.createBiquadFilter();
    filtro.type = 'bandpass';
    filtro.Q.value = 2;
    filtro.frequency.setValueAtTime(700, inicio);
    filtro.frequency.exponentialRampToValueAtTime(3200, inicio + 0.7);
    const volumen = ctx.createGain();
    volumen.gain.setValueAtTime(0.0001, inicio);
    volumen.gain.exponentialRampToValueAtTime(0.14, inicio + 0.25);
    volumen.gain.exponentialRampToValueAtTime(0.0001, inicio + 0.85);
    const lado = ctx.createStereoPanner();
    lado.pan.value = paneo;
    fuente.connect(filtro).connect(volumen).connect(lado);
    this.enviar(lado, 0.45);
    fuente.start(inicio);
  }

  /** Golpe grave con caída de tono y ataque de ruido. */
  private impacto(ctx: AudioContext, inicio: number): void {
    const bombo = ctx.createOscillator();
    bombo.type = 'sine';
    bombo.frequency.setValueAtTime(120, inicio);
    bombo.frequency.exponentialRampToValueAtTime(34, inicio + 0.6);
    const cuerpo = ctx.createGain();
    cuerpo.gain.setValueAtTime(0.7, inicio);
    cuerpo.gain.exponentialRampToValueAtTime(0.001, inicio + 2.2);
    bombo.connect(cuerpo);
    this.enviar(cuerpo, 0.35);
    bombo.start(inicio);
    bombo.stop(inicio + 2.3);

    const grave = ctx.createOscillator();
    grave.type = 'triangle';
    grave.frequency.value = 55;
    const cola = ctx.createGain();
    cola.gain.setValueAtTime(0.35, inicio);
    cola.gain.exponentialRampToValueAtTime(0.001, inicio + 2.6);
    grave.connect(cola);
    this.enviar(cola, 0.2);
    grave.start(inicio);
    grave.stop(inicio + 2.7);

    const golpe = this.ruido(ctx, 0.4);
    const filtro = ctx.createBiquadFilter();
    filtro.type = 'lowpass';
    filtro.frequency.value = 1800;
    const volumen = ctx.createGain();
    volumen.gain.setValueAtTime(0.5, inicio);
    volumen.gain.exponentialRampToValueAtTime(0.001, inicio + 0.35);
    golpe.connect(filtro).connect(volumen);
    this.enviar(volumen, 0.8);
    golpe.start(inicio);
  }

  /** Acorde de marca que abre el filtro y se apaga con el fundido. */
  private acorde(ctx: AudioContext, cero: number): void {
    const inicio = cero + INTRO.marca;
    const fin = cero + INTRO.duracion;
    const filtro = ctx.createBiquadFilter();
    filtro.type = 'lowpass';
    filtro.frequency.setValueAtTime(600, inicio);
    filtro.frequency.exponentialRampToValueAtTime(2600, inicio + 1.2);
    filtro.frequency.exponentialRampToValueAtTime(900, fin);
    const volumen = ctx.createGain();
    volumen.gain.setValueAtTime(0, inicio);
    volumen.gain.linearRampToValueAtTime(0.6, inicio + 0.45);
    volumen.gain.setValueAtTime(0.6, cero + INTRO.salida);
    volumen.gain.linearRampToValueAtTime(0, fin);
    filtro.connect(volumen);
    this.enviar(volumen, 0.55);

    for (const frecuencia of SONIDO.acorde) {
      for (const desafino of [-6, 6]) {
        const osc = ctx.createOscillator();
        osc.type = 'sawtooth';
        osc.frequency.value = frecuencia;
        osc.detune.value = desafino;
        const nivel = ctx.createGain();
        nivel.gain.value = 0.05;
        osc.connect(nivel).connect(filtro);
        osc.start(inicio);
        osc.stop(fin + 0.05);
      }
    }

    for (const frecuencia of SONIDO.brillo) {
      const brillo = ctx.createOscillator();
      brillo.frequency.value = frecuencia;
      const nivel = ctx.createGain();
      nivel.gain.setValueAtTime(0, inicio + 0.3);
      nivel.gain.linearRampToValueAtTime(0.018, inicio + 1.2);
      nivel.gain.setValueAtTime(0.018, cero + INTRO.salida);
      nivel.gain.linearRampToValueAtTime(0, fin);
      brillo.connect(nivel);
      this.enviar(nivel, 0.9);
      brillo.start(inicio + 0.3);
      brillo.stop(fin + 0.05);
    }
  }
}
