# 1. Informe técnico — EJEAS V6 Pro

> Estado: **investigación documental completa a nivel público; caracterización física pendiente.**
> Nada de lo que aparece aquí como "documentado" está confirmado en *tu* unidad. La columna
> "confianza" distingue lo que dice el fabricante, lo que dicen terceros y lo que se infiere.

## 1.1 Alcance y límites de esta investigación

- Fuentes consultadas: búsqueda web sobre ejeas.com, tiendas, manuales indexados y fichas de App Store.
- **No se pudo descargar** `www.ejeas.com` (páginas de producto, soporte y firmware) ni `device.report`
  (manuales en PDF): la política de red de esta sesión no permite esos dominios. Para la siguiente
  iteración, agrega `ejeas.com` y `device.report` a los dominios permitidos del entorno, o descarga
  manualmente el manual y el paquete de firmware de tu modelo y súbelos al repo (`docs/research/`).
- No hubo hardware conectado: ningún experimento físico se ha ejecutado.

## 1.2 La familia V6 (lo que se pudo establecer)

| Dato | V6 Pro | V6 Pro+ | Confianza |
|---|---|---|---|
| Bluetooth | 5.1 | 5.1 (anunciado) | Fabricante (ficha del V6 Pro) / tiendas |
| Alcance intercom | 800 m | 800 m | Fabricante / tiendas |
| Grupo | hasta 6 cascos, 2 en full-duplex ("1 a 5", el anfitrión elige) | igual | Tiendas |
| Batería | 850 mAh (tienda) | — | Tienda |
| Autonomía | 18 h conversación / 25 h música (ficha oficial); 30 h música (blog oficial) | — | **Contradictorio** entre fuentes del propio fabricante |
| Protección | IP67 | IP67 | Tiendas |
| Funciones declaradas | contestar llamadas automáticamente, navegación GPS, **asistente de voz**, soporte de app | "versión mejorada"; escuchar navegación GPS; app SafeRiding; mando de manillar EUC | Fabricante / tiendas |
| BLE | — | "incorpora BLE para extender batería" | Marketing (sin detalle técnico) |
| Puerto | USB-C (carga; firmware según tiendas) | USB-C "mejorado" | Tiendas |
| Firmware | actualizable desde el sitio oficial | actualizable vía app y sitio oficial | Fabricante / marketing |
| FCC ID | **no encontrado** | **no encontrado** | — |
| SoC / chipset | **no identificado** | **no identificado** | — |

Variantes relacionadas: **V6C** (versión "universal" derivada del V6 PRO+), **V4 Plus** (comparte manual con el
V6 Pro Plus). No hay evidencia pública de que V6 Pro y V6 Pro+ compartan hardware o firmware;
**se tratan como dispositivos distintos** hasta tener evidencia física.

### Botones (según el manual del V6 Pro Plus / V4 Plus)

- `MOTOR/A` (encendido), `B`, `C`, `D`, `E` (canales de intercom), **botón Teléfono**, volumen +/−.
- Teléfono, pulsación larga ~5 s: modo de emparejamiento (LED rojo/azul alterno).
- Teléfono, pulsación larga ~3 s con teléfono conectado: rellamada al último número.
- **No se localizó en el extracto disponible una función explícita de "asistente de voz"** por botón,
  aunque la ficha oficial del V6 Pro declara "voice assistant". Es la incógnita central de EXP-05.
- Reinicio de fábrica: combinación de botones (el extracto estaba truncado; consultar el manual completo).

## 1.3 Aplicación oficial

- **SafeRiding** (iOS): ficha archivada que la describe como "companion for EJEAS intercom device";
  última vez vista en mayo de 2025. Existe además una app "Ejeas" en App Store con funciones de
  intercom y rastreo.
- Funciones declaradas: grupos de intercom, control de música, control de FM, apagado, verificación de
  autenticidad; para el V6 Pro+: niveles de audio, grupos, **actualización de firmware**, ajustes.
- Emparejamiento con la app: mantener el botón Teléfono hasta LED rojo/azul, abrir la app, icono de
  Bluetooth, elegir el dispositivo. **En iOS hay que emparejar además en Ajustes del sistema.**
  - *Inferencia (no confirmada)*: que iOS requiera un emparejamiento aparte sugiere que la app usa un
    canal distinto del audio clásico, probablemente **BLE GATT**. EXP-02 lo verifica con la sonda BLE.
- No se encontró el paquete Android ni documentación de protocolo. **No hay comandos documentados.**

## 1.4 Hipótesis de hardware (a verificar)

| Hipótesis | Por qué es plausible | Cómo se verifica (no invasivo) |
|---|---|---|
| H1. Expone HFP + A2DP + AVRCP | Declara llamadas, música y asistente | EXP-02: UUIDs SDP (`fetchUuidsWithSdp`, `bluetoothctl info`) |
| H2. HFP ≥ 1.6 con mSBC (16 kHz) | BT 5.1 y SoCs recientes suelen soportarlo | `sdptool browse`: versión del perfil HFP (0x0106+); `PcmCapture` reporta 16 kHz vs 8 kHz |
| H3. El "asistente" es AT+BVRA (HFP) | Es el mecanismo estándar en HFP | EXP-05: `VoiceCommandActivity` registra el intent recibido |
| H4. SafeRiding usa BLE GATT propietario | Emparejamiento separado en iOS | EXP-02: escaneo BLE + volcado GATT de solo lectura |
| H5. SoC de audio chino de bajo costo (Actions, Jieli, Bluetrum, BES…) | Precio y categoría | OUI de la MAC, `Modalias` (Device ID), VID:PID por USB, metadatos del firmware oficial |

## 1.5 Cómo identificar *tu* revisión exacta (EXP-01)

1. **Etiqueta y caja**: modelo, revisión, número de serie, FCC ID/CE. Fotografía ambas.
2. **Nombre Bluetooth y clase**: app → Laboratorio → *Inventario Bluetooth*; o en PC
   `python3 lab/bt_inventory.py --out experiments/evidence/inventario.json`.
3. **OUI** (primeros 3 octetos de la MAC): identifica al fabricante registrado del módulo/SoC.
4. **Device ID (Modalias)**: `bluetoothctl info` muestra `bluetooth:vXXXXpYYYYdZZZZ` (fabricante, producto, versión).
5. **USB**: `python3 lab/usb_probe.py --before`, conecta el casco, `--after`. Si el puerto expone datos,
   el VID:PID orienta sobre el SoC o el puente de actualización.
6. **Firmware**: versión mostrada por SafeRiding o el actualizador oficial (captura de pantalla).

Si el FCC ID aparece en la etiqueta, la base pública de la FCC (fotos internas, manual, informe de RF)
suele identificar el SoC sin abrir el equipo.

## 1.6 Conclusión técnica provisional

La vía con mayor probabilidad de éxito **no requiere ingeniería inversa**: si el V6 Pro implementa HFP
correctamente, el teléfono puede usar su micrófono y altavoces como cualquier manos libres, y la
activación puede llegar por AT+BVRA o por botones AVRCP. La investigación de BLE/firmware queda como
tercer nivel, sin bloquear el MVP.

## Fuentes

- [EJEAS V6 Pro — página de producto](https://www.ejeas.com/products/v6-pro)
- [EJEAS V6 PRO: The Budget-Friendly Bluetooth Intercom That Delivers (blog oficial)](https://www.ejeas.com/ejeas-v6-pro-the-budget-friendly-bluetooth-intercom-that-delivers/)
- [Why the Upgraded EJEAS V6 PRO+ Is Loved by Riders (blog oficial)](https://www.ejeas.com/?p=7351)
- [EJEAS V6C (derivado del V6 PRO+)](https://buy.ejeas.com/product/v6/)
- [Manual V6 Pro Plus / V4 Plus (device.report)](https://device.report/manual/19164337)
- [Descargas EJEAS (manual con instrucciones de SafeRiding)](https://www.ejeas.com/download/4186)
- [SafeRiding en App Store (archivo)](https://appshunter.io/ios/app/saferiding/id1582917433)
- [App "Ejeas" en App Store](https://apps.apple.com/cn/app/id6737406848)
- Fichas de tiendas: [voelkner.de V6PRO+](https://www.voelkner.de/products/12629981/EJEAS-V6PRO-Motorrad-Sprechanlage.html), [skroutz.gr](https://www.skroutz.gr/s/67335428/ejeas-v6-pro-euc-einzelne-gegensprechanlage-fuer-helm-motorrad-mit-bluetooth.html), [alza.hu](https://www.alza.hu/ejeas-v6pro-d12479873.htm), [morele.net](https://www.morele.net/ejeas-ejeas-v6pro-interkom-motocyklowy-14077130/)
- Metodología de referencia: [Calif — Codex Hacked a Samsung TV](https://calif.io/research/samsung)
